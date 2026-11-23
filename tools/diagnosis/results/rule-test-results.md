# 진단 규칙 시험 결과

> 처음에는 `rsa.yaml` 하나의 미탐 케이스를 시험했고, 이후 규칙을 넓히며 같은 문서에 결과를 이어 적었다.

- 규칙: `tools/diagnosis/rules/rsa.yaml` (패턴: `KeyPairGenerator.getInstance("RSA")`)
- 시험 코드: `tools/diagnosis/samples/Cases.java` (메서드 하나에 케이스 하나, 처음 시험은 case01~case23, 규격 반영 때 case24~28 추가)
- 도구: Semgrep 1.186.0
- 실행: `semgrep --config tools/diagnosis/rules/rsa.yaml tools/diagnosis/samples/Cases.java`
- 탐지된 줄: 18, 34, 39, 44 (각각 case01, case04, case05, case06)

| 번호 | 시험 코드 | 실제로는 | 규칙 결과 |
|---|---|---|---|
| 1 | `KeyPairGenerator.getInstance("RSA")` | RSA 사용 | 탐지 (정답) |
| 2 | 주석에 적힌 `getInstance("RSA")` | 실제로 쓴 게 아님 | 안 잡음 (정답) |
| 3 | `System.out.println("RSA")` | 글자만 출력, 암호 아님 | 안 잡음 (정답) |
| 4 | `String a = "RSA"; getInstance(a)` | RSA 사용 (변수에 담음) | 탐지 (정답) |
| 5 | `static final String ALG = "RSA"` → `getInstance(ALG)` | RSA 사용 (상수에 담음) | 탐지 (정답) |
| 6 | `getInstance("R" + "SA")` | RSA 사용 (글자 이어붙이기) | 탐지 (정답) |
| 7 | `getInstance("RSA", "BC")` | RSA 사용 (제공자 이름 추가) | 놓침 |
| 8 | `getInstance("rsa")` | RSA 사용 (소문자) | 놓침 |
| 9 | `java.security.KeyPairGenerator.getInstance("RSA")` | RSA 사용 (패키지 이름까지 적음) | 놓침 |
| 10 | `getInstance(System.getProperty("crypto.alg"))` | 설정 값이 RSA면 RSA 사용 | 놓침 |
| 11 | `Cipher.getInstance("RSA/ECB/PKCS1Padding")` | RSA로 암호화 | 놓침 |
| 12 | `Signature.getInstance("SHA256withRSA")` | RSA로 서명 | 놓침 |
| 13 | `KeyFactory.getInstance("RSA")` | RSA 키 불러오기 | 놓침 |
| 14 | `getInstance("RSASSA-PSS")` | RSA 서명 (다른 이름) | 놓침 |
| 15 | `new RSAKeyPairGenerator()` (Bouncy Castle) | RSA 사용 (다른 라이브러리) | 놓침 |
| 16 | `KeyPairGenerator.getInstance("EC")` | 타원곡선, 양자에 취약 | 놓침 |
| 17 | `Signature.getInstance("SHA256withECDSA")` | 타원곡선 서명, 양자에 취약 | 놓침 |
| 18 | `KeyPairGenerator.getInstance("DSA")` | DSA 서명, 양자에 취약 | 놓침 |
| 19 | `KeyAgreement.getInstance("DH")` | DH 키 교환, 양자에 취약 | 놓침 |
| 20 | `KeyAgreement.getInstance("ECDH")` | 타원곡선 키 교환, 양자에 취약 | 놓침 |
| 21 | `KeyPairGenerator.getInstance("Ed25519")` | 타원곡선 서명, 양자에 취약 | 놓침 |
| 22 | `KeyPairGenerator.getInstance("ML-DSA-65")` | 양자내성암호, 안전 | 안 잡음 (정답) |
| 23 | `MessageDigest.getInstance("SHA-256")` | 해시, 비교적 안전 | 안 잡음 (정답) |

## 정리

- 23개 중 정답 9개(탐지 4 + 안 잡음 5), 놓침 14개.
- 놓친 이유는 세 갈래:
  1. 같은 RSA인데 적는 모양이 다름 (7, 8, 9, 14, 15)
  2. RSA를 쓰는 다른 기능을 규칙에 안 넣음 (11, 12, 13)
  3. RSA 말고도 양자에 취약한 암호가 있음 (16~21)
- 10번(설정 값)은 규칙만으로는 잡기 어려움. 설정 파일 추적이나 AI가 필요한 이유.

## 다음에 고칠 방향 (아래 "규칙 개선 후 재시험"에서 모두 반영함)

- 대소문자 무시, 제공자 이름이 붙은 경우까지 규칙 넓히기
- `Cipher`, `Signature`, `KeyFactory`, `KeyAgreement`까지 대상 넓히기
- `EC`, `ECDSA`, `DSA`, `DH`, `ECDH`, `Ed25519` 규칙 추가

---

# 규칙 개선 후 재시험 (2026-11-23)

- 규칙: `tools/diagnosis/rules/rsa.yaml`(RSA), `tools/diagnosis/rules/ecc-dsa-dh.yaml`(타원곡선·DSA·DH)
- 실행: `semgrep --config tools/diagnosis/rules/ --metrics=off tools/diagnosis/samples/Cases.java`

## 바꾼 내용

| 고친 점 | 방법 | 해결된 번호 |
|---|---|---|
| 제공자 이름 붙은 경우 | `getInstance("$ALG", ...)` — 뒤에 인자가 더 있어도 매칭 | 7 |
| 대소문자 | 정규식 `(?i)` | 8 |
| 패키지 이름 전체 | 패턴을 `java.security.KeyPairGenerator`처럼 전체 이름으로 적음 (import된 짧은 이름도 같이 잡힘) | 9 |
| RSA 다른 이름 | `RSA.*`, `.*withRSA.*` 정규식 | 14 |
| RSA 쓰는 다른 기능 | `Cipher`, `Signature`, `KeyFactory` 추가 | 11, 12, 13 |
| Bouncy Castle | `new RSAKeyPairGenerator()` 등 클래스 이름 규칙 (`rsa-bouncycastle`) | 15 |
| 다른 양자 취약 암호 | 새 규칙 `classical-pubkey-jca` (EC, ECDSA, ECDH, EdDSA/Ed25519, X25519, DSA, DH 등) | 16~21 |

## 결과

- 23개 중 **정답 22개**, 놓침 1개 (10번: 설정 값)
- 오탐 없음: 2, 3, 22(ML-DSA-65), 23(SHA-256)은 여전히 안 잡음

## 남은 과제

- 10번(설정 파일·시스템 속성 값)은 규칙만으로 판단 불가 → 설정 파일 추적 또는 AI 판단 필요
- 아직 시험 안 한 것: Bouncy Castle의 EC/DSA 저수준 클래스, `KeyStore`·인증서(X.509) 속 키 종류, TLS 설정의 암호 스위트

---

# 이체 전문 규격 v0.2 반영 (2026-11-23)

팀원의 API 명세 v0.2(`docs/api/message-spec.md`, `openapi.yaml`)를 기준으로, 게이트웨이에서 실제로 쓰게 될 코드 모양을 시험 케이스로 추가했다.

## 바꾼 내용

| 고친 점 | 방법 |
|---|---|
| 실행 중에 정해지는 알고리즘(10번, 요청의 `alg` 값) | 새 규칙 `dynamic-crypto-alg`(WARNING, 확인 필요). 상수·지역 변수로 정해지는 경우는 기존 규칙이 판정하므로 뺐다 |
| 경고 메시지 | 바꿀 알고리즘을 규격 용어로 적음: 서명은 HYBRID(RSA+ML-DSA)·PQC(ML-DSA), 키 교환은 ML-KEM |
| HYBRID에서 일부러 쓰는 RSA | 해당 줄에 `// nosemgrep: rsa-jca` 주석을 달면 제외 (메시지에 안내) |

## 추가 시험 케이스

| 번호 | 시험 코드 | 실제로는 | 규칙 결과 |
|---|---|---|---|
| 24 | `Cipher.getInstance("AES/GCM/NoPadding")` | 전송 구간 AES-256-GCM, 안전 | 안 잡음 (정답) |
| 25 | `KeyGenerator.getInstance("ML-KEM", "BC")` | 양자내성 키 교환, 안전 | 안 잡음 (정답) |
| 26 | `Signature.getInstance("ML-DSA", "BC")` | 양자내성 서명, 안전 | 안 잡음 (정답) |
| 27 | `Signature.getInstance(algFromRequest)` | 요청 값에 따라 다름 | 확인 필요 (WARNING) |
| 28 | `getInstance("SHA256withRSA") // nosemgrep` | HYBRID의 의도된 RSA | 안 잡음 (정답) |

## 결과

- 28개 모두 기대대로 나옴. 위험 18개는 ERROR, 판단 불가 2개(10, 27)는 WARNING, 안전 8개는 안 잡음
- 10번은 "놓침"에서 "확인 필요"로 바뀜. 값이 무엇인지는 여전히 사람이나 AI가 확인해야 함
