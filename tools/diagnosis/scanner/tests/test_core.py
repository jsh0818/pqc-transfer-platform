"""Semgrep 결과를 Finding으로 바꾸는 부분의 시험. Semgrep 없이 돈다."""

import tempfile
import unittest
from pathlib import Path

from scanner.core import Report, ScannerError, family_of, parse, rules_sha256, to_finding


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


if __name__ == "__main__":
    unittest.main()
