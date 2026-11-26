#!/usr/bin/env python3
"""진단 스캐너 실행 파일. 사용법은 tools/diagnosis/scanner/README.md 참고.

예: python3 tools/diagnosis/scan.py libs/crypto --format json -o result.json
"""

import sys

from scanner.cli import main

if __name__ == "__main__":
    sys.exit(main())
