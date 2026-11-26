"""Semgrep 결과를 Finding으로 바꾸는 부분의 시험. Semgrep 없이 돈다."""

import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from scanner import core
from scanner.core import (Report, ScannerError, _first_argument, _string_literal_value, family_of, parse, rules_sha256,
                          run_semgrep, to_finding)


def result(rule, line, severity, code, metavars, ignored=False, path="src/A.java", replacement=("ML-DSA", "ML-KEM")):
    """Semgrep --json 결과 1건과 같은 모양(실제 출력에서 쓰는 필드만)."""
    return {
        "check_id": f"tools.diagnosis.rules.{rule}",
        "path": path,
        "start": {"line": line, "col": 9},
        "end": {"line": line, "col": 40},
        "extra": {
            "severity": severity,
            "lines": "        " + code,
            "message": "메시지\n  두 줄",
            "is_ignored": ignored,
            "metadata": {"replacement": list(replacement)} if replacement else {},
            "metavars": {k: {"abstract_content": v} for k, v in metavars.items()},
        },
    }


class FamilyTest(unittest.TestCase):
    def test_계열_분류(self):
        cases = {
            "RSA": "RSA", "rsa": "RSA", "RSA/ECB/PKCS1Padding": "RSA", "SHA256withRSA": "RSA", "RSASSA-PSS": "RSA",
            "EC": "ECC", "ECDSA": "ECC", "SHA256withECDSA": "ECC", "ECDH": "ECC", "Ed25519": "ECC", "X25519": "ECC",
            "XDH": "ECC", "EdDSA": "ECC", "SHA256withPLAIN-ECDSA": "ECC",
            "DSA": "DSA", "SHA256withDSA": "DSA",
            "DH": "DH", "DiffieHellman": "DH",
        }
        for alg, family in cases.items():
            with self.subTest(alg=alg):
                self.assertEqual(family, family_of(alg))

    def test_양자내성·대칭키는_계열_없음(self):
        for alg in ["ML-DSA", "ML-KEM", "SLH-DSA", "AES/GCM/NoPadding", "SHA-256", None, ""]:
            with self.subTest(alg=alg):
                self.assertIsNone(family_of(alg))


class ToFindingTest(unittest.TestCase):
    def test_문자열_알고리즘(self):
        f = to_finding(result("rsa-jca", 24, "ERROR", 'KeyPairGenerator.getInstance("RSA");', {"$1": "RSA", "$ALG": "RSA"}))
        self.assertEqual(("rsa-jca", "VULNERABLE", "RSA", "RSA", "KeyPairGenerator"), (f.rule_id, f.label, f.algorithm, f.family, f.api))
        self.assertEqual(["ML-DSA", "ML-KEM"], f.replacement)
        self.assertFalse(f.suppressed)
        self.assertEqual('KeyPairGenerator.getInstance("RSA");', f.code)
        self.assertEqual("메시지 두 줄", f.message)

    def test_이어붙인_문자열은_정규식이_잡은_전체_이름을_씀(self):
        f = to_finding(result("rsa-jca", 53, "ERROR", 'KeyPairGenerator.getInstance("R" + "SA");', {"$1": "RSA", "$ALG": "R"}))
        self.assertEqual("RSA", f.algorithm)

    def test_API로_대체_알고리즘을_좁힘(self):
        sig = to_finding(result("classical-pubkey-jca", 1, "ERROR", 'Signature.getInstance("SHA256withECDSA");', {"$1": "SHA256withECDSA"}))
        ka = to_finding(result("classical-pubkey-jca", 1, "ERROR", 'KeyAgreement.getInstance("ECDH");', {"$1": "ECDH"}))
        cipher = to_finding(result("rsa-jca", 1, "ERROR", 'javax.crypto.Cipher.getInstance("RSA/ECB/OAEPPadding", "BC");', {"$1": "RSA/ECB/OAEPPadding"}))
        kf = to_finding(result("rsa-jca", 1, "ERROR", 'KeyFactory.getInstance("RSA");', {"$1": "RSA"}))
        self.assertEqual((["ML-DSA"], ["ML-KEM"], ["ML-KEM"], ["ML-DSA", "ML-KEM"]),
                         (sig.replacement, ka.replacement, cipher.replacement, kf.replacement))
        self.assertEqual(("ECC", "Cipher"), (ka.family, cipher.api))

    def test_Bouncy_Castle_클래스(self):
        f = to_finding(result("rsa-bouncycastle", 110, "ERROR", "new RSAKeyPairGenerator();", {"$2": "KeyPairGenerator", "$CLASS": "RSAKeyPairGenerator"}))
        self.assertEqual(("RSA", "RSA", "RSAKeyPairGenerator"), (f.algorithm, f.family, f.api))
        g = to_finding(result("rsa-bouncycastle", 1, "ERROR", "new org.bouncycastle.crypto.engines.RSAEngine();", {"$CLASS": "org.bouncycastle.crypto.engines.RSAEngine"}))
        self.assertEqual("RSAEngine", g.api)

    def test_실행_중에_정해지는_알고리즘은_REVIEW(self):
        f = to_finding(result("dynamic-crypto-alg", 181, "WARNING", "Signature.getInstance(algFromRequest);", {"$ALG": "algFromRequest"}, replacement=None))
        self.assertEqual(("REVIEW", None, None, "algFromRequest", "Signature"), (f.label, f.algorithm, f.family, f.expression, f.api))
        self.assertEqual([], f.replacement)

    def test_nosemgrep은_숨기지_않고_의도됨으로_표시(self):
        f = to_finding(result("rsa-jca", 186, "ERROR", 'Signature.getInstance("SHA256withRSA"); // nosemgrep: rsa-jca', {"$1": "SHA256withRSA"}, ignored=True))
        self.assertTrue(f.suppressed)
        self.assertEqual("VULNERABLE", f.label)

    def test_모르는_severity는_오류(self):
        with self.assertRaises(ScannerError):
            to_finding(result("x", 1, "INFO", "x", {}))

    def test_경로를_대상_폴더_기준으로(self):
        with tempfile.TemporaryDirectory() as d:
            base = Path(d)
            (base / "src").mkdir()
            f = to_finding(result("rsa-jca", 1, "ERROR", "x", {"$1": "RSA"}, path=str(base / "src" / "A.java")), base=base)
            self.assertEqual("src/A.java", f.file)


class ParseTest(unittest.TestCase):
    def setUp(self):
        self.data = {
            "version": "1.186.0",
            "results": [
                result("rsa-jca", 30, "ERROR", 'Cipher.getInstance("RSA");', {"$1": "RSA"}, path="b/B.java"),
                result("classical-pubkey-jca", 5, "ERROR", 'KeyPairGenerator.getInstance("EC");', {"$1": "EC"}, path="a/A.java"),
                result("dynamic-crypto-alg", 9, "WARNING", "Cipher.getInstance(alg);", {"$ALG": "alg"}, path="a/A.java", replacement=None),
                result("rsa-jca", 7, "ERROR", 'Signature.getInstance("SHA256withRSA"); // nosemgrep', {"$1": "SHA256withRSA"}, ignored=True, path="a/A.java"),
            ],
            "errors": [{"type": "SyntaxError", "message": "  깨진 파일  "}],
            "paths": {"scanned": ["a/A.java", "b/B.java", "c/C.java"]},
        }

    def test_요약(self):
        report = parse(self.data, target="x", rules_hash="h")
        s = report.summary()
        self.assertEqual(3, s["files_scanned"])
        self.assertEqual(3, s["findings"])  # 의도됨 1건 제외
        self.assertEqual({"REVIEW": 1, "VULNERABLE": 2}, s["by_label"])
        self.assertEqual({"ECC": 1, "RSA": 1}, s["by_family"])
        self.assertEqual(1, s["suppressed"])
        self.assertEqual(1, s["errors"])
        self.assertEqual(["SyntaxError: 깨진 파일"], report.errors)

    def test_파일과_줄_순서로_정렬(self):
        report = parse(self.data, target="x")
        self.assertEqual([("a/A.java", 5), ("a/A.java", 7), ("a/A.java", 9), ("b/B.java", 30)],
                         [(f.file, f.line) for f in report.findings])

    def test_JSON_필드(self):
        d = parse(self.data, target="x", rules_hash="h").to_dict()
        self.assertEqual({"tool", "target", "semgrep_version", "rules_sha256", "summary", "findings", "errors"}, set(d))
        self.assertEqual({"file", "line", "end_line", "rule_id", "label", "algorithm", "family", "api", "expression",
                          "replacement", "suppressed", "code", "message"}, set(d["findings"][0]))

    def test_빈_결과(self):
        report = parse({"version": "1.186.0", "results": [], "errors": [], "paths": {"scanned": []}}, target="x")
        self.assertEqual(0, report.summary()["findings"])
        self.assertIsInstance(report, Report)


class RulesHashTest(unittest.TestCase):
    def test_규칙이_바뀌면_해시가_바뀜(self):
        with tempfile.TemporaryDirectory() as d:
            rules = Path(d)
            (rules / "a.yaml").write_text("rules: []\n", encoding="utf-8")
            h1 = rules_sha256(rules)
            self.assertEqual(h1, rules_sha256(rules))
            (rules / "a.yaml").write_text("rules: [ ]\n", encoding="utf-8")
            self.assertNotEqual(h1, rules_sha256(rules))


class SourceTest(unittest.TestCase):
    """로그인하지 않은 Semgrep은 extra.lines에 "requires login", is_ignored에 false를 준다.
    코드와 nosemgrep 여부를 원본 파일에서 직접 읽는지 확인한다."""

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.path = Path(self.dir.name, "A.java")
        self.path.write_text(
            "class A {\n"                                                          # 1
            "  void f() throws Exception {\n"                                      # 2
            "    java.security.Signature.getInstance(\"SHA256withECDSA\");\n"      # 3
            "    javax.crypto.Cipher.getInstance(\"RSA\"); // nosemgrep: rsa-jca\n"  # 4
            "    // nosemgrep\n"                                                   # 5
            "    javax.crypto.Cipher.getInstance(\"RSA\");\n"                      # 6
            "    javax.crypto.Cipher.getInstance(\"RSA\"); // nosemgrep: other-rule\n"  # 7
            "  }\n"
            "}\n",
            encoding="utf-8",
        )

    def tearDown(self):
        self.dir.cleanup()

    def logged_out(self, rule, line, message="메시지 (RSA)"):
        """로그인하지 않은 Semgrep 결과: lines·metavars·is_ignored가 비고, 위치(offset)와 메시지만 있다."""
        text = self.path.read_text(encoding="utf-8")
        line_start = sum(len(l) + 1 for l in text.split("\n")[:line - 1])
        line_text = text.split("\n")[line - 1]
        start = line_start + len(line_text) - len(line_text.lstrip())
        end = line_start + line_text.index(";")
        r = result(rule, line, "ERROR", "x", {}, path=str(self.path))
        r["start"]["offset"], r["end"]["offset"] = start, end
        r["extra"].update(lines="requires login", metavars=None, is_ignored=False, message=message)
        return r

    def test_로그인_없이도_원본에서_코드를_읽어_API와_대체_알고리즘을_정함(self):
        f = to_finding(self.logged_out("classical-pubkey-jca", 3, "발견 (SHA256withECDSA)."))
        self.assertEqual('java.security.Signature.getInstance("SHA256withECDSA");', f.code)
        self.assertEqual(("SHA256withECDSA", "Signature", "ECC", ["ML-DSA"]), (f.algorithm, f.api, f.family, f.replacement))

    def test_같은_줄의_규칙_지정_nosemgrep(self):
        self.assertTrue(to_finding(self.logged_out("rsa-jca", 4)).suppressed)

    def test_윗줄의_nosemgrep(self):
        self.assertTrue(to_finding(self.logged_out("rsa-jca", 6)).suppressed)

    def test_다른_규칙을_지정한_nosemgrep은_적용_안_됨(self):
        self.assertFalse(to_finding(self.logged_out("rsa-jca", 7)).suppressed)

    def test_표시_없는_줄(self):
        self.assertFalse(to_finding(self.logged_out("classical-pubkey-jca", 3)).suppressed)

    def test_변수로_넘긴_알고리즘은_메시지에_채워진_값을_씀(self):
        self.path.write_text('class A { void f() {\n  javax.crypto.KeyAgreement.getInstance(ALG);\n} }\n', encoding="utf-8")
        f = to_finding(self.logged_out("classical-pubkey-jca", 2, "공개키 암호 발견 (ECDH). 서명(ECDSA)은"))
        self.assertEqual(("ECDH", "ECC", "KeyAgreement", ["ML-KEM"]), (f.algorithm, f.family, f.api, f.replacement))

    def test_이어붙인_문자열은_코드에서_계산(self):
        self.path.write_text('class A { void f() {\n  java.security.KeyPairGenerator.getInstance("R" + "SA");\n} }\n', encoding="utf-8")
        f = to_finding(self.logged_out("rsa-jca", 2, "RSA 사용 발견 (R) - 취약"))
        self.assertEqual(("RSA", "RSA"), (f.algorithm, f.family))

    def test_Bouncy_Castle_클래스를_코드에서_읽음(self):
        self.path.write_text('class A { void f() {\n  new org.bouncycastle.crypto.engines.RSAEngine();\n} }\n', encoding="utf-8")
        f = to_finding(self.logged_out("rsa-bouncycastle", 2, "Bouncy Castle RSA 사용 발견 (org.bouncycastle.crypto.engines.RSAEngine)"))
        self.assertEqual(("RSAEngine", "RSA", "RSA"), (f.api, f.algorithm, f.family))

    def test_REVIEW의_식을_코드에서_읽음(self):
        self.path.write_text('class A { void f() {\n  javax.crypto.Cipher.getInstance(cfg.get("alg", "x"));\n} }\n', encoding="utf-8")
        r = self.logged_out("dynamic-crypto-alg", 2, "알고리즘(cfg.get(\"alg\", \"x\"))이 실행 중에 정해져")
        r["extra"]["severity"] = "WARNING"
        f = to_finding(r)
        self.assertEqual(("REVIEW", 'cfg.get("alg", "x")', None, "Cipher", []), (f.label, f.expression, f.algorithm, f.api, f.replacement))

    def test_원본을_못_읽으면_Semgrep_출력을_쓰고_로그인_문구는_코드로_쓰지_않음(self):
        r = result("rsa-jca", 1, "ERROR", 'Cipher.getInstance("RSA");', {"$1": "RSA"}, ignored=True, path="없는/A.java")
        f = to_finding(r)
        self.assertEqual(('Cipher.getInstance("RSA");', True, "Cipher"), (f.code, f.suppressed, f.api))
        r["extra"]["lines"] = "requires login"
        self.assertEqual("", to_finding(r).code)


class ArgumentTest(unittest.TestCase):
    def test_첫_인자(self):
        cases = {
            'KeyPairGenerator.getInstance("RSA")': '"RSA"',
            'Cipher.getInstance("RSA/ECB/OAEPPadding", "BC")': '"RSA/ECB/OAEPPadding"',
            'Signature.getInstance("R" + "SA")': '"R" + "SA"',
            'Signature.getInstance(ALG)': "ALG",
            'Cipher.getInstance(cfg.get("alg", "x,y"), p)': 'cfg.get("alg", "x,y")',
            'Cipher.getInstance("a(b,c)", p)': '"a(b,c)"',
            'Cipher.getInstance("q\\"x", p)': '"q\\"x"',
            'Cipher.getInstance(\n    alg,\n    provider)': "alg",
            'Cipher.getInstance(algs[0])': "algs[0]",
            "x": None,
            "Cipher.getInstance()": None,
        }
        for call, expected in cases.items():
            with self.subTest(call=call):
                self.assertEqual(expected, _first_argument(call))

    def test_문자열_상수_값(self):
        cases = {
            '"RSA"': "RSA", '"R" + "SA"': "RSA", '"R"+"S" + "A"': "RSA", ' "EC" ': "EC",
            '"R" + suffix': None, "ALG": None, 'cfg.get("RSA")': None, None: None,
        }
        for expr, expected in cases.items():
            with self.subTest(expr=expr):
                self.assertEqual(expected, _string_literal_value(expr))


class RunSemgrepTest(unittest.TestCase):
    def fake_run(self, returncode, stdout="", stderr=""):
        done = subprocess.CompletedProcess(args=[], returncode=returncode, stdout=stdout, stderr=stderr)
        return mock.patch.object(core.subprocess, "run", return_value=done)

    def test_semgrep이_0이_아닌_코드로_끝나면_오류(self):
        for code in (1, 2, 7):
            # 출력은 정상 JSON으로 줘서, 종료 코드만으로 오류가 나는지 본다
            with self.subTest(code=code), self.fake_run(code, stdout='{"results": []}', stderr="규칙 없음"):
                with self.assertRaises(ScannerError):
                    run_semgrep(Path("."), semgrep="semgrep")

    def test_0이고_JSON이면_정상(self):
        with self.fake_run(0, stdout='{"results": []}'):
            self.assertEqual({"results": []}, run_semgrep(Path("."), semgrep="semgrep"))

    def test_출력이_JSON이_아니면_오류(self):
        with self.fake_run(0, stdout="not json"):
            with self.assertRaises(ScannerError):
                run_semgrep(Path("."), semgrep="semgrep")


if __name__ == "__main__":
    unittest.main()
