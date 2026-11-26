# transfer-api

이체 요청 접수, 결재(maker-checker), 복식부기 원장을 맡는 서비스. Spring Boot 4.1.3, Java 21.
스키마는 `src/main/resources/db/migration`의 Flyway 파일로만 바꾼다(ERD: `docs/erd/erd.md`).

## 로컬 실행 (PowerShell)

```powershell
cd services\transfer-api

# 1) 로컬 전용 비밀번호를 정한다(가짜 값, 이 터미널에서만 유효). 두 번째 터미널에서도 같은 값으로 다시 실행해야 한다.
$env:POSTGRES_PASSWORD = "dev-local-only"

# 2) 개발용 DB 켜기
docker compose -f dev\compose.yml up -d

# 3) 앱 실행. 시작할 때 Flyway가 테이블을 만든다.
.\gradlew.bat bootRun
```

- 확인: 브라우저에서 http://localhost:8081/actuator/health → `{"status":"UP"}`
- DB 끄기: `docker compose -f dev\compose.yml down` (데이터까지 지우려면 `down -v`)
- DB를 처음부터 다시 만들려면 `down -v` 후 다시 `up -d`

## 테스트

`.\gradlew.bat test`. DB가 필요한 테스트는 `POSTGRES_PASSWORD` 환경변수가 있을 때만 실행된다(위 1, 2번을 먼저).
