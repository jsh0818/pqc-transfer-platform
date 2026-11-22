# PoC: Bouncy Castle ML-KEM / ML-DSA 지원 확인

목적: 프로젝트 핵심 기술(ML-KEM 키 교환, ML-DSA 서명)이 Java 21 + Bouncy Castle에서 실제로 동작하는지 W1에 확인한다.

## 실행
```powershell
cd C:\dev\pqc-transfer-platform\poc-bc-check
.\run.ps1
```
(`bcprov-jdk18on-*.jar`는 스크립트가 자동 다운로드한다. 저장소에는 올리지 않는다.)

## 통과 기준
- ML-KEM-512/768/1024: 캡슐화한 공유 비밀과 디캡슐화한 공유 비밀이 같다.
- ML-DSA-44/65/87: 정상 메시지는 검증 성공, 변조한 메시지는 검증 실패.
- 6개 모두 `[PASS]`

## 기록할 결과 (ADR-0001 재료)
| 항목 | 값 |
| --- | --- |
| BC 버전 | |
| ML-KEM-768 공개키/캡슐 크기 | |
| ML-KEM-768 keygen/encap/decap 시간 | |
| ML-DSA-65 공개키/서명 크기 | |
| ML-DSA-65 sign/verify 시간 | |

이 크기와 시간이 이후 HYBRID 모드 성능 비교(RSA 대비)의 기준값이 된다.
