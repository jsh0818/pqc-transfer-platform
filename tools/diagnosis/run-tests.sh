#!/usr/bin/env bash
# 진단 규칙 자동 시험.
# samples/Cases.java의 ruleid 주석(기대 결과)과 실제 Semgrep 결과를 비교한다.
# 규칙 파일 3개를 한 파일에 함께 적용해야 "안전한 코드는 어떤 규칙에도 안 걸린다"까지 확인되는데,
# semgrep --test는 규칙 파일 하나와 시험 파일 하나를 짝짓기 때문에 실행할 때만 임시로 합친다.
# 이어서 스캐너(scanner/) 시험을 돌린다. 외부 패키지 없이 Python 표준 unittest만 쓴다.
# 실행: tools/diagnosis/run-tests.sh   (모두 통과하면 종료 코드 0, 실패하면 0이 아닌 값)
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

{
  echo "rules:"
  for f in "$DIR"/rules/*.yaml; do
    grep -v '^rules:' "$f"
  done
} > "$TMP/cases.yaml"
cp "$DIR/samples/Cases.java" "$TMP/cases.java"

semgrep --test --metrics=off --config "$TMP/cases.yaml" "$TMP/cases.java"

echo "== 스캐너 시험"
python3 -m unittest discover -s "$DIR/scanner/tests" -t "$DIR"
