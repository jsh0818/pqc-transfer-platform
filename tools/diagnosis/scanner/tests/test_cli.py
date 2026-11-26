"""출력 형식과 종료 코드 판정 시험. Semgrep 없이 돈다."""

import contextlib
import io
import unittest
from unittest import mock

from scanner import cli
from scanner.cli import EXIT_ERROR, EXIT_FOUND, EXIT_OK, exit_code, format_text
from scanner.core import Finding, Report, ScannerError


def finding(label, suppressed=False, **kw):
    base = dict(file="A.java", line=1, end_line=1, rule_id="r", label=label, algorithm="RSA" if label == "VULNERABLE" else None,
                family="RSA" if label == "VULNERABLE" else None, api="Cipher",
                expression=None if label == "VULNERABLE" else "alg", replacement=["ML-KEM"] if label == "VULNERABLE" else [],
                suppressed=suppressed)
    base.update(kw)
    return Finding(**base)


def report(*findings):
    return Report(target="t", semgrep_version="1.186.0", rules_sha256="abcdef0123456789", files_scanned=2,
                  findings=list(findings), errors=[])


class ExitCodeTest(unittest.TestCase):
    def test_기준별_판정(self):
        v, r, s = finding("VULNERABLE"), finding("REVIEW"), finding("VULNERABLE", suppressed=True)
        table = [
            ((v,), "none", EXIT_OK), ((v,), "vulnerable", EXIT_FOUND), ((v,), "review", EXIT_FOUND),
            ((r,), "vulnerable", EXIT_OK), ((r,), "review", EXIT_FOUND),
            ((s,), "vulnerable", EXIT_OK), ((s,), "review", EXIT_OK),
            ((), "review", EXIT_OK),
        ]
        for findings, fail_on, expected in table:
            with self.subTest(findings=[f.label + ("*" if f.suppressed else "") for f in findings], fail_on=fail_on):
                self.assertEqual(expected, exit_code(report(*findings), fail_on))


class FormatTextTest(unittest.TestCase):
    def test_요약과_줄(self):
        text = format_text(report(finding("VULNERABLE", line=3), finding("REVIEW", line=9),
                                  finding("VULNERABLE", line=12, suppressed=True)))
        self.assertIn("파일 2개 검사 · Semgrep 1.186.0 · 규칙 abcdef012345", text)
        self.assertIn("VULNERABLE 1 · REVIEW 1 · 의도됨(nosemgrep) 1 · 오류 0", text)
        self.assertIn("[VULNERABLE] A.java:3  RSA (Cipher) → ML-KEM", text)
        self.assertIn("[REVIEW] A.java:9  알고리즘이 실행 중에 정해짐: alg (Cipher)", text)
        self.assertIn("[의도됨] A.java:12", text)


class MainTest(unittest.TestCase):
    def run_main(self, fake_report=None, error=None, args=("x",)):
        out, err = io.StringIO(), io.StringIO()
        side = ScannerError(error) if error else None
        with mock.patch.object(cli, "scan", return_value=fake_report, side_effect=side), \
                contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = cli.main(list(args))
        return code, out.getvalue(), err.getvalue()

    def test_semgrep_오류가_있으면_종료_코드_2(self):
        r = report(finding("VULNERABLE"))
        r.errors.append("SyntaxError: 깨진 파일")
        code, out, _ = self.run_main(r)
        self.assertEqual(EXIT_ERROR, code)
        self.assertIn("[오류] SyntaxError: 깨진 파일", out)

    def test_실행할_수_없으면_종료_코드_2(self):
        code, _, err = self.run_main(error="semgrep을 찾을 수 없음")
        self.assertEqual(EXIT_ERROR, code)
        self.assertIn("semgrep을 찾을 수 없음", err)

    def test_시험한_버전이_아니면_경고(self):
        r = report()
        r.semgrep_version = "9.9.9"
        code, _, err = self.run_main(r)
        self.assertEqual(EXIT_OK, code)
        self.assertIn("Semgrep 9.9.9로 실행함", err)

    def test_fail_on_전달(self):
        code, _, _ = self.run_main(report(finding("VULNERABLE")), args=("x", "--fail-on", "vulnerable"))
        self.assertEqual(EXIT_FOUND, code)


if __name__ == "__main__":
    unittest.main()
