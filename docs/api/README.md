# API 계약 (docs/api)

| 파일 | 내용 |
| --- | --- |
| `message-spec.md` | 이체 전문 규격(항목, 결과 코드, 검증 순서). 사람이 읽는 설명서 |
| `openapi.yaml` | 같은 내용을 API 명세 형식으로 옮긴 것. 도구가 읽는 계약 |

두 파일이 어긋나면 `openapi.yaml`을 기준으로 하고 `message-spec.md`를 고친다.
API를 바꾸는 PR은 이 두 파일을 같이 고치고 상대에게 알린다.

## Mock 서버 (명세대로 가짜 응답을 주는 서버)
구현이 없어도 상대 파트가 개발할 수 있게 한다. Node.js가 필요하다(Docker는 필요 없다).

```powershell
npx @stoplight/prism-cli mock -p 4010 docs/api/openapi.yaml
```

처음 실행하면 내려받느라 20초쯤 걸린다. 끄려면 `Ctrl+C`.
다른 PowerShell 창에서 확인한다. 명세의 예시 응답이 그대로 돌아온다.

```powershell
# 상태 조회 (서명 인증 POST). 본문은 openapi.yaml의 InquiryEnvelope 예시
curl.exe -X POST http://localhost:4010/v1/transfers/52001/status -H "Content-Type: application/json" -d '{\"security\":{\"crypto_mode\":\"LEGACY\",\"session_id\":\"s-1\",\"timestamp\":\"2026-11-23T06:10:40Z\",\"nonce\":\"4b8d0e2f6a1c7359\",\"signatures\":[{\"alg\":\"RSA\",\"kid\":\"k\",\"value\":\"AAAA\"}]},\"inquiry\":{\"transfer_id\":52001,\"corporation_id\":1001}}'
```

### 오류 응답 받아 보기
요청에 `Prefer: code=<상태 코드>` 헤더를 붙이면 명세에 있는 그 상태의 예시 응답을 준다.

```powershell
curl.exe -X POST http://localhost:4010/v1/transfers/52001/approvals -H "Content-Type: application/json" -H "Authorization: Bearer x" -H "Prefer: code=409" -d '{\"decision\":\"APPROVED\"}'
```

### 알아 둘 것
- Mock은 서버 주소를 구분하지 않는다. 명세에서 결재 API는 이체 API 내부 포트(8081)에 있지만 Mock에서는 4010 하나로 모두 받는다.
- 요청 검증은 형식만 본다(예: `decision`이 `APPROVED`, `REJECTED`가 아니면 400). 서명이 맞는지, 기업이 같은지 같은 업무 규칙은 확인하지 않는다.
- 오류 예시 문구는 명세에 적힌 대로 나온다. 같은 상태 코드는 API가 달라도 같은 예시를 쓰는 경우가 있어 문구가 어색할 수 있다(예: 결재 API의 400에 "amount는 ..."). 결과 코드(`result_code`)와 상태 코드만 믿는다.
