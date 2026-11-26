# pqc-transfer-platform
## 빌드와 테스트

Java 21이 필요하다. Gradle은 설치하지 않아도 된다(wrapper가 내려받는다).

```bash
./gradlew test          # Windows: gradlew.bat test
```

| 경로 | 내용 |
| --- | --- |
| `libs/crypto` | 암호 모듈: LEGACY / HYBRID / PQC 서명, 키 교환, 본문 암호화 ([ADR-0001](docs/adr/0001-crypto-module-modes.md)) |
| `tools/diagnosis` | 암호 자산 진단 규칙과 시험 (`tools/diagnosis/run-tests.sh`) |
| `docs` | 위협 모델, API 명세, ERD, ADR |
