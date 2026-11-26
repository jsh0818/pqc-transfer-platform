"""실제 Semgrep으로 samples/Cases.java를 검사하는 시험. semgrep이 없으면 건너뛴다.

CI와 같은 조건이 되도록 Semgrep에 로그인하지 않은 상태로 돌린다(빈 설정 파일을 SEMGREP_SETTINGS_FILE로 지정).
로그인하면 Semgrep 출력의 코드·nosemgrep 정보가 채워지므로, 로그인 여부에 따라 결과가 달라지지 않는지 이 조건에서 본다.

정답은 Cases.java에 사람이 적어 둔 주석에서 읽는다(스캐너와 독립된 기준).
- `// ruleid: <규칙>` 바로 아랫줄 = 그 규칙에 걸려야 함
- `// nosemgrep: <규칙>`이 붙은 줄 = 의도된 사용(suppressed)
"""

import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from scanner.core import EXPECTED_SEMGREP_VERSION, rules_sha256, scan

DIAG = Path(__file__).resolve().parents[2]
CASES = DIAG / "samples" / "Cases.java"
SCAN_PY = DIAG / "scan.py"


def logged_out_env():
    """로그인 정보가 없는 Semgrep 설정 파일을 쓰는 환경변수. (임시 폴더, 환경변수) 를 돌려준다."""
    tmp = tempfile.TemporaryDirectory()
    settings = Path(tmp.name, "settings.yml")
    settings.write_text("{}\n", encoding="utf-8")
    env = dict(os.environ, SEMGREP_SETTINGS_FILE=str(settings))
    env.pop("SEMGREP_APP_TOKEN", None)
    return tmp, env


def expected_from_annotations():
    lines = CASES.read_text(encoding="utf-8").splitlines()
    expected, intended = set(), set()
    for i, text in enumerate(lines, start=1):
        m = re.match(r"\s*// ruleid: ([\w-]+)\s*$", text)
        if m:
            expected.add((i + 1, m.group(1)))
        m = re.search(r"// nosemgrep: ([\w-]+)", text)
        if m:
            intended.add((i, m.group(1)))
    return expected, intended


@unittest.skipUnless(shutil.which("semgrep"), "semgrep 없음")
class CasesTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        tmp, env = logged_out_env()
        with tmp, mock.patch.dict(os.environ, env, clear=True):
            cls.report = scan(CASES.parent)

    def test_주석의_정답과_같음(self):
        expected, intended = expected_from_annotations()
        self.assertEqual(20, len(expected))
        self.assertEqual(1, len(intended))
        active = {(f.line, f.rule_id) for f in self.report.findings if not f.suppressed}
        suppressed = {(f.line, f.rule_id) for f in self.report.findings if f.suppressed}
        self.assertEqual(expected, active)
        self.assertEqual(intended, suppressed)

    def test_요약_수치(self):
        s = self.report.summary()
        self.assertEqual({"REVIEW": 2, "VULNERABLE": 18}, s["by_label"])
        self.assertEqual({"DH": 1, "DSA": 1, "ECC": 4, "RSA": 12}, s["by_family"])
        self.assertEqual(1, s["suppressed"])
        self.assertEqual(0, s["errors"])
        self.assertEqual(1, s["files_scanned"])

    def test_취약_결과는_모두_알고리즘과_계열이_있음(self):
        for f in self.report.findings:
            if f.label == "VULNERABLE":
                self.assertTrue(f.algorithm and f.family, f)
            else:
                self.assertTrue(f.expression, f)

    def test_재현_정보(self):
        self.assertEqual(EXPECTED_SEMGREP_VERSION, self.report.semgrep_version)
        self.assertEqual(rules_sha256(), self.report.rules_sha256)


@unittest.skipUnless(shutil.which("semgrep"), "semgrep 없음")
class CliTest(unittest.TestCase):
    def setUp(self):
        self.tmp, self.env = logged_out_env()
        self.addCleanup(self.tmp.cleanup)

    def run_cli(self, *args):
        return subprocess.run([sys.executable, str(SCAN_PY), *args], capture_output=True, text=True, env=self.env)

    def test_종료_코드(self):
        self.assertEqual(0, self.run_cli(str(CASES.parent)).returncode)
        self.assertEqual(1, self.run_cli(str(CASES.parent), "--fail-on", "vulnerable").returncode)
        self.assertEqual(2, self.run_cli(str(DIAG / "없는폴더")).returncode)

    def test_안전한_코드만_있으면_fail_on_review도_통과(self):
        with tempfile.TemporaryDirectory() as d:
            Path(d, "Safe.java").write_text(
                "import javax.crypto.Cipher;\nclass Safe { void f() throws Exception {\n"
                "  Cipher.getInstance(\"AES/GCM/NoPadding\");\n"
                "  java.security.Signature.getInstance(\"ML-DSA\", \"BC\");\n"
                "  System.out.println(\"RSA\");\n} }\n",
                encoding="utf-8",
            )
            r = self.run_cli(d, "--fail-on", "review", "--format", "json")
            self.assertEqual(0, r.returncode, r.stderr)
            self.assertEqual(0, json.loads(r.stdout)["summary"]["findings"])

    def test_JSON_파일로_저장(self):
        with tempfile.TemporaryDirectory() as d:
            out = Path(d, "r.json")
            r = self.run_cli(str(CASES.parent), "--format", "json", "-o", str(out))
            self.assertEqual(0, r.returncode, r.stderr)
            data = json.loads(out.read_text(encoding="utf-8"))
            self.assertEqual(18, data["summary"]["by_label"]["VULNERABLE"])
            self.assertEqual("Cases.java", data["findings"][0]["file"])


if __name__ == "__main__":
    unittest.main()
