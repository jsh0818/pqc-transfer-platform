"""Semgrep 실행과 결과 정리.

- 한 결과 = 암호 호출 위치 1건(docs/benchmark-plan.md M7, datasets/README.md의 샘플 단위와 같음)
- 라벨: 규칙 severity ERROR → VULNERABLE, WARNING → REVIEW
- nosemgrep으로 표시한 의도된 사용(HYBRID의 RSA 등)도 숨기지 않고 suppressed=True로 남긴다.
  암호 자산 목록에는 들어가야 하기 때문이다. 실패 판정(--fail-on)에서는 뺀다.
"""

from __future__ import annotations

import hashlib
import json
import re
import shutil
import subprocess
from dataclasses import asdict, dataclass, field
from pathlib import Path

RULES_DIR = Path(__file__).resolve().parent.parent / "rules"
EXPECTED_SEMGREP_VERSION = "1.186.0"

LABEL_BY_SEVERITY = {"ERROR": "VULNERABLE", "WARNING": "REVIEW"}

# 알고리즘 이름 → 계열. 위에서부터 먼저 맞는 것을 쓴다.
_FAMILY_PATTERNS = [
    ("RSA", re.compile(r"(?i)^(RSA.*|.*WITHRSA.*)$")),
    ("ECC", re.compile(r"(?i)^(EC|ECDSA|ECDH|ECDHC|ECMQV|ECIES|EdDSA|Ed25519|Ed448|XDH|X25519|X448|.*with(ECDSA|PLAIN-ECDSA))$")),
    ("DSA", re.compile(r"(?i)^(DSA|.*withDSA)$")),
    ("DH", re.compile(r"(?i)^(DH|DiffieHellman)$")),
]

# API로 용도가 정해지면 대체 알고리즘을 좁힌다. 키 생성·키 불러오기는 용도를 알 수 없어 규칙 값을 그대로 쓴다.
_REPLACEMENT_BY_API = {
    "Signature": ["ML-DSA"],
    "KeyAgreement": ["ML-KEM"],
    "Cipher": ["ML-KEM"],
}

_API_PATTERN = re.compile(r"\b(KeyPairGenerator|KeyFactory|Signature|KeyAgreement|Cipher)\s*\.\s*getInstance\b")


@dataclass
class Finding:
    file: str
    line: int
    end_line: int
    rule_id: str
    label: str  # VULNERABLE | REVIEW
    algorithm: str | None  # 코드에 적힌 알고리즘 이름. 실행 중에 정해지면 None
    family: str | None  # RSA | ECC | DSA | DH | None
    api: str | None  # Cipher, Signature, KeyPairGenerator, ... 또는 Bouncy Castle 클래스 이름
    expression: str | None  # 알고리즘이 실행 중에 정해질 때 그 식(REVIEW)
    replacement: list[str] = field(default_factory=list)
    suppressed: bool = False  # nosemgrep으로 의도된 사용이라고 표시됨
    code: str = ""
    message: str = ""


@dataclass
class Report:
    target: str
    semgrep_version: str
    rules_sha256: str
    files_scanned: int
    findings: list[Finding]
    errors: list[str]

    def active(self) -> list[Finding]:
        return [f for f in self.findings if not f.suppressed]

    def summary(self) -> dict:
        active = self.active()
        by_label: dict[str, int] = {}
        by_family: dict[str, int] = {}
        for f in active:
            by_label[f.label] = by_label.get(f.label, 0) + 1
            if f.family:
                by_family[f.family] = by_family.get(f.family, 0) + 1
        return {
            "files_scanned": self.files_scanned,
            "findings": len(active),
            "by_label": dict(sorted(by_label.items())),
            "by_family": dict(sorted(by_family.items())),
            "suppressed": len(self.findings) - len(active),
            "errors": len(self.errors),
        }

    def to_dict(self) -> dict:
        return {
            "tool": "pqc-diagnosis-scanner",
            "target": self.target,
            "semgrep_version": self.semgrep_version,
            "rules_sha256": self.rules_sha256,
            "summary": self.summary(),
            "findings": [asdict(f) for f in self.findings],
            "errors": self.errors,
        }


class ScannerError(Exception):
    """Semgrep을 실행할 수 없거나 결과를 읽을 수 없을 때."""


def family_of(algorithm: str | None) -> str | None:
    if not algorithm:
        return None
    for family, pattern in _FAMILY_PATTERNS:
        if pattern.match(algorithm):
            return family
    return None


def rules_sha256(rules_dir: Path = RULES_DIR) -> str:
    """규칙 파일 내용의 해시. 결과가 어떤 규칙으로 나왔는지 남긴다."""
    h = hashlib.sha256()
    for path in sorted(rules_dir.glob("*.yaml")):
        h.update(path.name.encode())
        h.update(b"\0")
        h.update(path.read_bytes())
        h.update(b"\0")
    return h.hexdigest()


def _metavar(result: dict, name: str) -> str | None:
    metavars = result.get("extra", {}).get("metavars") or {}
    value = metavars.get(name)
    return value.get("abstract_content") if value else None


# Semgrep의 nosemgrep 표시: `nosemgrep`(모든 규칙) 또는 `nosemgrep: 규칙1, 규칙2`(적힌 규칙만)
_NOSEM_PATTERN = re.compile(r"\bnosem(?:grep)?\b(?:\s*:\s*(?P<ids>[\w.\-]+(?:\s*,\s*[\w.\-]+)*))?")

# Semgrep에 로그인하지 않으면 extra.lines에 코드 대신 이 문구가 온다(1.186.0에서 확인)
_LOGIN_PLACEHOLDER = "requires login"

_NEW_CLASS_PATTERN = re.compile(r"^\s*new\s+([\w.]+)\s*\(")
_STRING_CONCAT_PATTERN = re.compile(r'^\s*"((?:[^"\\]|\\.)*)"(?:\s*\+\s*"((?:[^"\\]|\\.)*)")*\s*$')
_STRING_PIECE_PATTERN = re.compile(r'"((?:[^"\\]|\\.)*)"')
# 규칙 메시지는 "... ($ALG) ..." 형태로 Semgrep이 상수 값을 채워 넣는다(로그인 여부와 관계없이)
_MESSAGE_VALUE_PATTERN = re.compile(r"\(([^()]+)\)")


def _read_source(path: Path, cache: dict[Path, bytes | None]) -> bytes | None:
    """원본 파일을 읽는다. 읽을 수 없으면 None."""
    if path not in cache:
        try:
            cache[path] = path.read_bytes()
        except OSError:
            cache[path] = None
    return cache[path]


def _is_nosem(lines: list[str], start_line: int, check_id: str) -> bool:
    """Semgrep과 같은 기준: 결과의 첫 줄이나 바로 윗줄에 nosemgrep이 있으면 제외한다."""
    rule_id = check_id.split(".")[-1]
    for line_no in (start_line, start_line - 1):
        if not 1 <= line_no <= len(lines):
            continue
        m = _NOSEM_PATTERN.search(lines[line_no - 1])
        if m is None:
            continue
        ids = m.group("ids")
        if ids is None:
            return True
        if any(i.strip() in (rule_id, check_id) for i in ids.split(",")):
            return True
    return False


def _first_argument(call: str) -> str | None:
    """`X.getInstance(a, b)`에서 첫 인자 `a`의 코드를 돌려준다. 문자열 안의 쉼표·괄호는 무시한다."""
    open_at = call.find("(")
    if open_at < 0:
        return None
    depth, i, start = 0, open_at + 1, open_at + 1
    while i < len(call):
        ch = call[i]
        if ch == '"':
            i += 1
            while i < len(call) and call[i] != '"':
                i += 2 if call[i] == "\\" else 1
        elif ch in "([{":
            depth += 1
        elif ch in ")]}":
            if depth == 0:
                return call[start:i].strip() or None
            depth -= 1
        elif ch == "," and depth == 0:
            return call[start:i].strip() or None
        i += 1
    return None


def _string_literal_value(expr: str | None) -> str | None:
    """`"RSA"` 또는 `"R" + "SA"`처럼 문자열 상수만으로 된 식이면 그 값을 돌려준다."""
    if expr is None or not _STRING_CONCAT_PATTERN.match(expr):
        return None
    return "".join(_STRING_PIECE_PATTERN.findall(expr))


def to_finding(result: dict, base: Path | None = None, source_cache: dict | None = None) -> Finding:
    """Semgrep 결과 1건을 Finding으로 바꾼다.

    Semgrep에 로그인하지 않으면(CI 등) 출력의 extra.lines는 "requires login", extra.metavars는 null,
    extra.is_ignored는 항상 false다(1.186.0에서 확인). 그래서 로그인 여부와 관계없이 같은 결과가 나오도록
    코드·알고리즘·nosemgrep 여부를 원본 파일(결과의 위치 offset)과 메시지에서 직접 얻는다.
    원본 파일을 읽을 수 없을 때만 Semgrep 출력(metavars, lines, is_ignored)을 쓴다.
    """
    extra = result.get("extra", {})
    rule_id = result["check_id"].split(".")[-1]
    severity = extra.get("severity", "")
    label = LABEL_BY_SEVERITY.get(severity)
    if label is None:
        raise ScannerError(f"알 수 없는 severity: {severity} ({rule_id})")
    message = " ".join(extra.get("message", "").split())

    start, end = result["start"], result["end"]
    start_line, end_line = start["line"], end["line"]
    raw = _read_source(Path(result["path"]), source_cache if source_cache is not None else {})
    lines = raw.decode("utf-8", errors="replace").splitlines() if raw is not None else None
    have_source = (
        lines is not None and 1 <= start_line <= end_line <= len(lines)
        and "offset" in start and "offset" in end and 0 <= start["offset"] <= end["offset"] <= len(raw)
    )

    if have_source:
        code = "\n".join(lines[start_line - 1:end_line]).strip()
        matched = raw[start["offset"]:end["offset"]].decode("utf-8", errors="replace")
        suppressed = _is_nosem(lines, start_line, result["check_id"])
        message_value_match = _MESSAGE_VALUE_PATTERN.search(message)
        message_value = message_value_match.group(1).strip() if message_value_match else None
        new_class = _NEW_CLASS_PATTERN.match(matched)
        argument = None if new_class else _first_argument(matched)
        bc_class = new_class.group(1) if new_class else None
        review_expression = argument
        # 문자열 상수면 코드에서 바로, 변수·상수 이름이면 Semgrep이 메시지에 채운 값을 쓴다
        algorithm_value = _string_literal_value(argument) or message_value
    else:
        lines_text = extra.get("lines", "").strip()
        code = "" if lines_text == _LOGIN_PLACEHOLDER else lines_text
        matched = code
        suppressed = bool(extra.get("is_ignored", False))
        bc_class = _metavar(result, "$CLASS")
        review_expression = _metavar(result, "$ALG")
        # metavariable-regex가 잡은 전체 문자열($1)이 상수 접기까지 반영된 알고리즘 이름이다
        algorithm_value = _metavar(result, "$1") or _metavar(result, "$ALG")

    algorithm = None
    expression = None
    api_match = _API_PATTERN.search(matched) or _API_PATTERN.search(code)
    api = api_match.group(1) if api_match else None
    if bc_class is not None:
        # Bouncy Castle 저수준 클래스: 클래스 이름 앞부분이 알고리즘
        api = bc_class.split(".")[-1]
        algorithm = "RSA" if api.upper().startswith("RSA") else None
    elif label == "REVIEW":
        expression = review_expression
    else:
        algorithm = algorithm_value

    path = Path(result["path"])
    if base is not None:
        try:
            path = path.resolve().relative_to(base.resolve())
        except ValueError:
            pass

    return Finding(
        file=path.as_posix(),
        line=start_line,
        end_line=end_line,
        rule_id=rule_id,
        label=label,
        algorithm=algorithm,
        family=family_of(algorithm),
        api=api,
        expression=expression,
        # REVIEW는 알고리즘을 아직 모르므로 대체 알고리즘을 제안하지 않는다
        replacement=[] if label == "REVIEW" else _REPLACEMENT_BY_API.get(
            api or "", list(extra.get("metadata", {}).get("replacement", []))),
        suppressed=suppressed,
        code=code,
        message=message,
    )


def parse(semgrep_json: dict, target: str, base: Path | None = None, rules_hash: str = "") -> Report:
    cache: dict[Path, bytes | None] = {}  # 같은 파일을 여러 번 읽지 않도록
    findings = [to_finding(r, base, cache) for r in semgrep_json.get("results", [])]
    findings.sort(key=lambda f: (f.file, f.line, f.rule_id))
    errors = []
    for e in semgrep_json.get("errors", []):
        errors.append(f"{e.get('type', 'error')}: {e.get('message', '').strip()}"[:500])
    scanned = semgrep_json.get("paths", {}).get("scanned", [])
    return Report(
        target=target,
        semgrep_version=semgrep_json.get("version", "unknown"),
        rules_sha256=rules_hash,
        files_scanned=len(scanned) if isinstance(scanned, list) else int(scanned or 0),
        findings=findings,
        errors=errors,
    )


def run_semgrep(target: Path, rules_dir: Path = RULES_DIR, semgrep: str | None = None) -> dict:
    exe = semgrep or shutil.which("semgrep")
    if not exe:
        raise ScannerError("semgrep을 찾을 수 없음. `pip install semgrep==1.186.0`으로 설치할 것")
    if not target.exists():
        raise ScannerError(f"대상이 없음: {target}")
    cmd = [
        exe, "scan",
        "--config", str(rules_dir),
        "--metrics=off",
        "--disable-version-check",
        "--disable-nosem",  # 의도된 사용도 받아서 suppressed로 표시한다(is_ignored)
        "--json", "--quiet",
        str(target),
    ]
    proc = subprocess.run(cmd, capture_output=True, text=True)
    # --error를 쓰지 않으므로 결과가 있어도 0으로 끝난다(1.186.0에서 확인). 0이 아니면 실행 오류다(예: 7 = 규칙 없음)
    if proc.returncode != 0:
        raise ScannerError(f"semgrep 실행 실패(exit {proc.returncode}): {proc.stderr.strip()[:500]}")
    try:
        return json.loads(proc.stdout)
    except json.JSONDecodeError as e:
        raise ScannerError(f"semgrep 출력을 읽을 수 없음: {e}") from e


def scan(target: Path, rules_dir: Path = RULES_DIR, semgrep: str | None = None) -> Report:
    data = run_semgrep(target, rules_dir, semgrep)
    base = target if target.is_dir() else target.parent
    return parse(data, target=str(target), base=base, rules_hash=rules_sha256(rules_dir))
