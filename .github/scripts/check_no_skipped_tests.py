"""JUnit 결과에 건너뛴 시험이 있거나 시험이 하나도 없으면 실패한다(CI용).

환경변수가 없을 때 건너뛰는 시험(@EnabledIfEnvironmentVariable 등)은 CI에서 조용히 건너뛰어도
초록 체크가 뜬다. 그러면 체크가 아무것도 검증하지 않으므로 여기서 막는다.

사용: python3 check_no_skipped_tests.py <결과 파일 glob> [...]
"""

import glob
import sys
import xml.etree.ElementTree as ET  # CI가 방금 만든 시험 결과 파일만 읽는다(외부 입력 아님)


def main(patterns: list[str]) -> int:
    total = skipped = 0
    for pattern in patterns:
        for path in sorted(glob.glob(pattern, recursive=True)):
            suite = ET.parse(path).getroot()
            total += int(suite.get("tests", 0))
            count = int(suite.get("skipped", 0))
            skipped += count
            if count:
                print(f"건너뜀: {suite.get('name')} {count}개")
    print(f"시험 {total}개, 건너뜀 {skipped}개")
    return 1 if total == 0 or skipped else 0


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit("사용: check_no_skipped_tests.py <결과 파일 glob> [...]")
    sys.exit(main(sys.argv[1:]))
