# 진단 스캐너 v1

Java 코드에서 양자컴퓨터에 취약한 공개키 암호(RSA, 타원곡선, DSA, DH)를 쓰는 위치를 찾는다.
규칙은 `tools/diagnosis/rules/`의 Semgrep 규칙을 그대로 쓰고, 결과를 데이터셋 라벨과 같은 기준으로 정리한다.

## 실행

Python 3.10 이상(Semgrep 1.186.0의 요구 사항)과 Semgrep 1.186.0이 필요하다(`pip install semgrep==1.186.0`). 그 밖의 패키지는 쓰지 않는다.

```bash
python3 tools/diagnosis/scan.py <폴더 또는 파일>                      # 화면에 요약과 목록
python3 tools/diagnosis/scan.py <폴더> --format json -o result.json   # JSON으로 저장
python3 tools/diagnosis/scan.py <폴더> --fail-on vulnerable           # 취약 결과가 있으면 종료 코드 1
```

| 옵션 | 뜻 |
| --- | --- |
| `--format text\|json` | 출력 형식(기본 `text`) |
| `-o, --output <파일>` | 결과를 파일로 저장 |
| `--fail-on none\|vulnerable\|review` | 이 수준 이상의 결과가 있으면 종료 코드 1. `review`는 VULNERABLE과 REVIEW 모두. `nosemgrep`으로 표시한 의도된 사용은 세지 않는다(기본 `none`) |
| `--semgrep <경로>` | semgrep 실행 파일 위치 |

종료 코드: `0` 기준 이상 결과 없음, `1` 기준 이상 결과 있음, `2` 실행 오류(semgrep 없음, 대상 없음, Semgrep이 실패하거나 오류를 보고함). 구문이 깨진 파일은 Semgrep이 오류로 보고하지 않을 수 있다(아래 한계).

## 출력 예 (`tools/diagnosis/samples`)

```
대상: tools/diagnosis/samples
파일 1개 검사 · Semgrep 1.186.0 · 규칙 6293bfda35bf
VULNERABLE 18 · REVIEW 2 · 의도됨(nosemgrep) 1 · 오류 0
계열: DH 1, DSA 1, ECC 4, RSA 12

[VULNERABLE] Cases.java:24  RSA (KeyPairGenerator) → ML-DSA, ML-KEM
[REVIEW] Cases.java:80  알고리즘이 실행 중에 정해짐: alg (KeyPairGenerator)
[VULNERABLE] Cases.java:92  SHA256withRSA (Signature) → ML-DSA
[VULNERABLE] Cases.java:140  ECDH (KeyAgreement) → ML-KEM
[의도됨] Cases.java:186  SHA256withRSA (Signature) → ML-DSA
...
```

## 결과 항목 (JSON `findings[]`)

결과 1건 = 암호 호출 위치 1건이다(`docs/benchmark-plan.md` M7, `tools/diagnosis/datasets/README.md`의 샘플 단위와 같다).

| 항목 | 뜻 |
| --- | --- |
| `file`, `line`, `end_line` | 위치. 경로는 검사 대상 폴더 기준 |
| `rule_id` | 걸린 규칙 |
| `label` | `VULNERABLE`(규칙 ERROR) 또는 `REVIEW`(규칙 WARNING, 알고리즘이 실행 중에 정해짐) |
| `algorithm` | 코드에 적힌 알고리즘 이름(`RSA/ECB/PKCS1Padding`, `SHA256withECDSA` 등). 상수를 이어 붙인 경우도 합친 이름이 나온다. REVIEW면 `null` |
| `family` | `RSA`, `ECC`(EC·ECDSA·ECDH·EdDSA·X25519 등), `DSA`, `DH` |
| `api` | `Signature`, `Cipher`, `KeyAgreement`, `KeyPairGenerator`, `KeyFactory`, 또는 Bouncy Castle 클래스 이름 |
| `expression` | REVIEW일 때 알고리즘을 정하는 식(예: `algFromRequest`) |
| `replacement` | 바꿀 알고리즘. 서명(`Signature`)은 ML-DSA, 키 교환·암호화(`KeyAgreement`, `Cipher`)는 ML-KEM, 용도를 알 수 없는 키 생성은 둘 다. REVIEW는 비어 있음 |
| `suppressed` | `nosemgrep`으로 의도된 사용이라고 표시됨(HYBRID의 RSA 등). 암호 자산 목록에는 남기고 `--fail-on` 판정에서는 뺀다 |
| `code`, `message` | 해당 줄, 규칙 메시지 |

맨 위에는 재현에 필요한 `semgrep_version`과 `rules_sha256`(규칙 파일 해시), 그리고 `summary`(라벨별·계열별 개수, 검사한 파일 수, 의도됨 수, 오류 수)가 있다. Semgrep 버전이 1.186.0이 아니면 경고를 낸다.

## 시험

```bash
tools/diagnosis/run-tests.sh
```

규칙 시험(`semgrep --test`)에 이어 스캐너 시험(`scanner/tests`, unittest 43개)을 돌린다. 통합 시험은 `Cases.java`의 `ruleid`·`nosemgrep` 주석을 정답으로 읽어 스캐너 결과와 비교한다. semgrep이 없으면 통합 시험만 건너뛴다.

## Semgrep 로그인과 무관하게 같은 결과

Semgrep 1.186.0은 로그인하지 않으면(CI 등) JSON 결과의 `extra.lines`에 코드 대신 `requires login`을, `extra.metavars`에 `null`을 주고, `extra.is_ignored`(nosemgrep 표시)를 항상 `false`로 준다. 그래서 스캐너는 이 값들을 쓰지 않고 다음에서 직접 얻는다.

- 코드, API, Bouncy Castle 클래스, REVIEW의 식: 결과의 위치(`start.offset`~`end.offset`, 줄 번호)로 원본 파일에서 읽는다
- 알고리즘 이름: 문자열 상수(`"RSA"`, `"R" + "SA"`)는 코드에서 계산하고, 변수·상수 이름으로 넘긴 경우는 Semgrep이 규칙 메시지의 `($ALG)` 자리에 채운 값을 쓴다. 그래서 규칙 메시지의 첫 괄호에는 `$ALG`(또는 `$CLASS`)를 둔다
- nosemgrep: Semgrep과 같은 기준(결과 첫 줄이나 바로 윗줄, 규칙 ID를 적었으면 그 규칙만)으로 원본에서 판단한다

통합 시험은 로그인 정보가 없는 설정 파일(`SEMGREP_SETTINGS_FILE`)로 돌려 CI와 같은 조건에서 확인한다.

## 범위와 한계

- v1은 Java만 검사한다. 규칙에 없는 API(KeyStore, X.509 인증서 속 키, TLS 설정 등)는 찾지 못한다.
- REVIEW는 값의 출처를 사람이 확인해야 한다. 설정 파일 추적과 AI 판정은 W4 이후 작업이다.
- 위험도 점수와 CBOM(암호 자산 목록) 출력은 W6에 추가한다.
- 구문이 깨진 Java 파일은 Semgrep이 오류 없이 넘어갈 수 있다. 이 경우 "검사했지만 결과 없음"으로 나오므로, 결과가 0건인데 이상하면 컴파일되는지 먼저 확인한다.
- 규칙이 `java.security.KeyPairGenerator`처럼 전체 이름으로 찾으므로, `import` 없이 잘린 코드 조각은 찾지 못한다.
- Semgrep 기본 제외 규칙 때문에 `test`, `tests` 폴더(`src/test` 포함)는 검사하지 않는다. 운영 코드의 암호 사용만 센다.
