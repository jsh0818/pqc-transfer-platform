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
    value = result.get("extra", {}).get("metavars", {}).get(name)
    return value.get("abstract_content") if value else None


def to_finding(result: dict, base: Path | None = None) -> Finding:
    """Semgrep 결과 1건을 Finding으로 바꾼다."""
    extra = result.get("extra", {})
    rule_id = result["check_id"].split(".")[-1]
    severity = extra.get("severity", "")
    label = LABEL_BY_SEVERITY.get(severity)
    if label is None:
        raise ScannerError(f"알 수 없는 severity: {severity} ({rule_id})")
    code = extra.get("lines", "").strip()

    algorithm = None
    expression = None
    api_match = _API_PATTERN.search(code)
    api = api_match.group(1) if api_match else None
    bc_class = _metavar(result, "$CLASS")
    if bc_class is not None:
        # Bouncy Castle 저수준 클래스: 클래스 이름 앞부분이 알고리즘
        api = bc_class.split(".")[-1]
        algorithm = "RSA" if api.upper().startswith("RSA") else None
    elif label == "REVIEW":
        expression = _metavar(result, "$ALG")
    else:
        # metavariable-regex가 잡은 전체 문자열($1)이 상수 접기까지 반영된 알고리즘 이름이다
        algorithm = _metavar(result, "$1") or _metavar(result, "$ALG")

    path = Path(result["path"])
    if base is not None:
        try:
            path = path.resolve().relative_to(base.resolve())
        except ValueError:
            pass

    return Finding(
        file=path.as_posix(),
        line=result["start"]["line"],
        end_line=result["end"]["line"],
        rule_id=rule_id,
        label=label,
        algorithm=algorithm,
        family=family_of(algorithm),
        api=api,
        expression=expression,
        # REVIEW는 알고리즘을 아직 모르므로 대체 알고리즘을 제안하지 않는다
        replacement=[] if label == "REVIEW" else _REPLACEMENT_BY_API.get(
            api or "", list(extra.get("metadata", {}).get("replacement", []))),
        suppressed=bool(extra.get("is_ignored", False)),
        code=code,
        message=" ".join(extra.get("message", "").split()),
    )


def parse(semgrep_json: dict, target: str, base: Path | None = None, rules_hash: str = "") -> Report:
    findings = [to_finding(r, base) for r in semgrep_json.get("results", [])]
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
    # semgrep: 0 = 정상, 1 = 결과 있음(--error 없이도 일부 버전에서), 그 외 = 실행 오류
    if proc.returncode not in (0, 1):
        raise ScannerError(f"semgrep 실행 실패(exit {proc.returncode}): {proc.stderr.strip()[:500]}")
    try:
        return json.loads(proc.stdout)
    except json.JSONDecodeError as e:
        raise ScannerError(f"semgrep 출력을 읽을 수 없음: {e}") from e


def scan(target: Path, rules_dir: Path = RULES_DIR, semgrep: str | None = None) -> Report:
    data = run_semgrep(target, rules_dir, semgrep)
    base = target if target.is_dir() else target.parent
    return parse(data, target=str(target), base=base, rules_hash=rules_sha256(rules_dir))
