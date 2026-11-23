# API 계약 (docs/api)

| 파일 | 내용 |
| --- | --- |
| `message-spec.md` | 이체 전문 규격(항목, 결과 코드, 검증 순서). 사람이 읽는 설명서 |
| `openapi.yaml` | 같은 내용을 API 명세 형식으로 옮긴 것. 도구가 읽는 계약 |

두 파일이 어긋나면 `openapi.yaml`을 기준으로 하고 `message-spec.md`를 고친다.
API를 바꾸는 PR은 이 두 파일을 같이 고치고 상대에게 알린다.

## Mock 서버 (명세대로 가짜 응답을 주는 서버)
구현이 없어도 상대 파트가 개발할 수 있게 한다. Node.js가 필요하다.

```powershell
npx @stoplight/prism-cli mock -p 4010 docs/api/openapi.yaml
```

다른 PowerShell 창에서 `curl http://localhost:4010/v1/transfers/52001` 로 확인한다.
(명세의 예시 응답이 그대로 돌아온다.)
