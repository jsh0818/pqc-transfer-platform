# gateway

기업 시스템의 이체 요청을 받아 암호 모드, 시간, 서명, nonce, 형식을 검사한 뒤 이체 API로 넘기는 서비스다. DMZ에 있고 포트는 8080이다(`docs/infra/network.md` F1, F2, F3).
Spring Boot 4.1.3, Java 21이며 루트 Gradle 빌드에 들어 있다. 서명 검증은 `libs/crypto`를 쓴다.

## 검증 순서 (`POST /v1/transfers`)

`docs/api/message-spec.md` 5장의 1~5단계를 맡는다. 6단계(멱등성)부터는 이체 API가 맡는다.

| 단계 | 검사 | 실패 | 위협 |
| --- | --- | --- | --- |
| 0 | JSON 형식, 같은 키 중복, 정의되지 않은 항목, `security` 필수 항목, 본문 32KB 이하 | `E1000` 400 | |
| 1 | `crypto_mode`가 허용 값이고 기업의 최저 모드 이상인가 | `E1003` 403 | T2 |
| 2 | `timestamp`가 서버 시각 앞뒤 5분 안인가 | `E1002` 401 | T3 |
| 3 | 서명의 kid가 그 기업의 것이고, 모드가 요구하는 서명이 모두 유효한가 | `E1001` 401 | T5, T2 |
| 4 | nonce를 처음 보는가. 서명 확인 뒤에 Redis에 10분 등록한다 | `E1002` 401 | T3 |
| 5 | `transfer` 항목이 `openapi.yaml` 스키마의 제약을 지키는가 | `E1000` 400 | |

- 본문은 32KB까지만 읽는다. `Content-Length`가 한도를 넘으면 읽지 않고 거부하고, 길이를 모르는 본문(chunked)도 한도까지만 읽는다.
- 등록되지 않은 기업은 서명을 확인할 수 없으므로 `E1001`이다.
- 없는 경로는 404, 허용되지 않은 메서드는 405로 답한다(본문은 `E1000`). 서버 오류(ERROR) 로그로 남기지 않는다.
- `E1001`~`E1003`의 응답 메시지는 모두 "요청을 확인할 수 없습니다"이다. 원인은 로그에만 남긴다(키·서명값·본문은 남기지 않고, 원인에 섞인 줄바꿈 등 제어 문자는 `?`로 바꿔 로그 위조를 막는다).
- Redis나 이체 API에 닿지 못하면 통과시키지 않고 `E9000` 500으로 답한다. Redis는 연결·응답 2초가 지나면 끊는다(기본 60초면 Redis가 멈췄을 때 요청 스레드가 모두 묶인다).
- 서명 대상: 본문에서 `security.signatures`만 뺀 객체를 RFC 8785(JCS)로 정규화한 UTF-8 바이트(`SigningInput`).

## 이체 API로 넘기는 형식

검증을 통과하면 아래처럼 넘긴다. 이체 API의 2xx·4xx JSON 응답(`application/json`, `application/*+json`)은 상태 코드와 본문을 그대로 돌려준다. 5xx, 그 밖의 상태, JSON이 아닌 응답은 내부 오류 내용이 섞일 수 있어 기업에 넘기지 않고 `E9000`으로 답한다(로그에는 상태와 형식만 남긴다). 연결 2초, 응답 10초가 지나도 `E9000`이다.

```
POST {TRANSFER_API_URL}/v1/transfers
Content-Type: application/json
X-Pqc-Corporation-Id: 1001          # 서명으로 확인된 기업
X-Pqc-Crypto-Mode: HYBRID           # transfer_request.crypto_mode_used
X-Pqc-Request-Hash: <64자 hex>       # 서명 대상 바이트의 SHA-256, transfer_request.request_hash
본문: transfer 객체 그대로
```

## 설정 (환경변수)

| 변수 | 뜻 |
| --- | --- |
| `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD` | nonce 저장소. 비밀번호는 기본값이 없어서 없으면 시작하지 않는다(T9) |
| `TRANSFER_API_URL` | 이체 API 주소(기본 `http://localhost:8081`) |
| `GATEWAY_CORPORATIONS_<기업ID>_FLOOR` | 기업의 최저 모드 `LEGACY`, `HYBRID`, `PQC` |
| `GATEWAY_CORPORATIONS_<기업ID>_KIDS` | 기업의 서명 키 번호(쉼표로 구분) |
| `CRYPTO_KEY_<KID>_ALG`, `CRYPTO_KEY_<KID>_PUBLIC` | 기업 공개키(`libs/crypto`의 `EnvKeyProvider`. X.509 DER을 base64로) |

게이트웨이는 DB에 닿지 못하므로(DMZ) 기업 정보를 설정으로 받는다.

## 시험

```bash
./gradlew :services:gateway:test     # 루트에서. Windows: gradlew.bat :services:gateway:test
```

Redis와 이체 API 없이 돈다(시험용 저장소와 가짜 이체 API를 쓴다). 위협별 시험은 `RequestVerifierTest`의 `T2_다운그레이드`, `T3_재전송`, `T5_변조와_인증`에 있다.

## 아직 안 한 것

- 복호화와 세션(`session_id`): 키 교환 API가 아직 없다. 지금은 형식만 확인한다. 세션 키 캐시와 함께 W5에 한다.
- 이체 상태 조회(`#18`에서 방식 합의 중).
- 기업 정보를 설정 대신 이체 API에서 조회하기: 정책 변경(T13)이 바로 반영되어야 할 때 `CorporationDirectory` 구현만 바꾼다.
- 게이트웨이와 이체 API 사이 인증: 이체 API의 8081은 어드민(F7)도 쓰므로, 어드민 쪽에서 `X-Pqc-*` 헤더를 꾸며 보낼 수 있다. 이체 API가 게이트웨이 요청만 믿도록 하는 방법(전용 포트, 상호 TLS, 공유 키 서명 등)은 팀원과 정해야 한다.
- Dockerfile과 Compose 등록.
