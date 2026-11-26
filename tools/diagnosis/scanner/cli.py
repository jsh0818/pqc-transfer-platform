"""명령줄 실행: python3 tools/diagnosis/scan.py <대상 폴더 또는 파일> [옵션]

종료 코드: 0 = 기준 이상 결과 없음, 1 = 기준 이상 결과 있음(--fail-on), 2 = 실행 오류
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from . import __version__
from .core import EXPECTED_SEMGREP_VERSION, Report, ScannerError, scan

EXIT_OK, EXIT_FOUND, EXIT_ERROR = 0, 1, 2


def format_text(report: Report) -> str:
    s = report.summary()
    lines = [
        f"대상: {report.target}",
        f"파일 {s['files_scanned']}개 검사 · Semgrep {report.semgrep_version} · 규칙 {report.rules_sha256[:12]}",
        f"VULNERABLE {s['by_label'].get('VULNERABLE', 0)} · REVIEW {s['by_label'].get('REVIEW', 0)}"
        f" · 의도됨(nosemgrep) {s['suppressed']} · 오류 {s['errors']}",
    ]
    if s["by_family"]:
        lines.append("계열: " + ", ".join(f"{k} {v}" for k, v in s["by_family"].items()))
    lines.append("")
    for f in report.findings:
        tag = "의도됨" if f.suppressed else f.label
        where = f"{f.file}:{f.line}"
        api = f" ({f.api})" if f.api else ""
        if f.label == "REVIEW":
            what = f"알고리즘이 실행 중에 정해짐: {f.expression}{api}"
        else:
            what = f"{f.algorithm or '?'}{api}"
            if f.replacement:
                what += " → " + ", ".join(f.replacement)
        lines.append(f"[{tag}] {where}  {what}")
    for e in report.errors:
        lines.append(f"[오류] {e}")
    return "\n".join(lines) + "\n"


def exit_code(report: Report, fail_on: str) -> int:
    if fail_on == "none":
        return EXIT_OK
    labels = {"vulnerable": {"VULNERABLE"}, "review": {"VULNERABLE", "REVIEW"}}[fail_on]
    return EXIT_FOUND if any(f.label in labels for f in report.active()) else EXIT_OK


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="scan.py",
        description="양자 취약 암호 사용 위치를 찾는다(Java). 규칙: tools/diagnosis/rules",
    )
    parser.add_argument("target", type=Path, help="검사할 폴더 또는 파일")
    parser.add_argument("--format", choices=["text", "json"], default="text", help="출력 형식(기본 text)")
    parser.add_argument("--output", "-o", type=Path, help="결과를 파일로 저장(없으면 화면에 출력)")
    parser.add_argument(
        "--fail-on", choices=["none", "vulnerable", "review"], default="none",
        help="이 수준 이상의 결과가 있으면 종료 코드 1(nosemgrep 표시는 제외). 기본 none",
    )
    parser.add_argument("--semgrep", help="semgrep 실행 파일 경로(기본: PATH에서 찾음)")
    parser.add_argument("--version", action="version", version=f"%(prog)s {__version__}")
    args = parser.parse_args(argv)

    try:
        report = scan(args.target, semgrep=args.semgrep)
    except ScannerError as e:
        print(f"오류: {e}", file=sys.stderr)
        return EXIT_ERROR

    if report.semgrep_version != EXPECTED_SEMGREP_VERSION:
        print(
            f"주의: Semgrep {report.semgrep_version}로 실행함. 규칙은 {EXPECTED_SEMGREP_VERSION}에서 시험했으므로 결과가 다를 수 있음",
            file=sys.stderr,
        )

    out = json.dumps(report.to_dict(), ensure_ascii=False, indent=2) + "\n" if args.format == "json" else format_text(report)
    if args.output:
        args.output.write_text(out, encoding="utf-8")
    else:
        sys.stdout.write(out)

    if report.errors:
        return EXIT_ERROR
    return exit_code(report, args.fail_on)
