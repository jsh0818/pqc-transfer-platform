# 이체 전문 규격 (v0.1, W1 초안)

> 목적: 기업이 은행(우리 게이트웨이)에 보내는 이체 요청과 그 응답의 항목·형식·오류 코드를 정한다.
> 이 문서가 확정되면 OpenAPI(`docs/api/openapi.yaml`)와 DB(`docs/erd/erd.md`)가 이것에 맞춰진다.
> 상태: 초안. 형식은 JSON으로 가정한다. 팀 검토 후 W1 말 "계약 확정"에서 고정한다.

## 1. 흐름

```
기업 시스템 ──(1) 이체 요청(서명 포함)──▶ 게이트웨이(DMZ)
                                        │ 서명 검증, 재전송 검사, 모드 검사, 복호화
                                        ▼
                                    이체 API(내부망) ── 결재 필요? ── 원장 기록
기업 시스템 ◀──(2) 응답(접수 결과)─────────┘
기업 시스템 ──(3) 상태 조회──▶ 처리 결과 확인 (승인 대기 중일 수 있음)
```

- 승인이 필요한 이체는 요청 직후 `PENDING_APPROVAL`로 응답하고, 이후 상태 조회로 최종 결과를 확인한다.
- 같은 `idempotency_key`로 다시 보내면 새로 처리하지 않고 기존 결과를 돌려준다.

## 2. 요청 (Request)

### 2-1. 보안 헤더 (본문 밖, 게이트웨이가 검사)

| 항목 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `crypto_mode` | 문자열 | O | `LEGACY`, `HYBRID`, `PQC` 중 하나. 기업의 허용 최저 모드(`crypto_floor`) 미만이면 거부 |
| `session_id` | 문자열 | O | 키 교환으로 만든 세션 식별자 |
| `timestamp` | 문자열(ISO 8601, UTC) | O | 요청 시각. 서버 시각과 차이가 허용 범위(초안: 5분)를 넘으면 거부 |
| `nonce` | 문자열 | O | 요청마다 새로 만드는 일회용 값. 이미 쓴 값이면 거부(재전송 차단) |
| `signatures` | 배열 | O | `{alg, value}` 목록. `LEGACY`는 RSA 1개, `HYBRID`는 RSA와 ML-DSA **둘 다**, `PQC`는 ML-DSA |

### 2-2. 본문 (Body)

| 항목 | 타입 | 필수 | 제약 | DB 매핑 |
| --- | --- | --- | --- | --- |
| `idempotency_key` | 문자열 | O | 기업 안에서 유일, 8~64자 | `transfer_request.idempotency_key` |
| `corporation_id` | 숫자 | O | 인증된 기업과 같아야 함 | `corporation.id` |
| `from_account_no` | 문자열 | O | 해당 기업 소유 계좌, ACTIVE | `account.account_no` |
| `to_bank_code` | 문자열 | O | 3자리 은행 코드 | `to_bank_code` |
| `to_account_no` | 문자열 | O | 숫자만, 10~16자 | `to_account_no` |
| `amount` | 정수 | O | 원 단위, 1 이상, 소수점 없음 | `amount` |
| `currency` | 문자열 | O | 초안은 `KRW`만 허용 | `account.currency` |
| `memo` | 문자열 | X | 최대 20자 | (선택) |
| `requested_at` | 문자열(ISO 8601) | O | 기업 시스템이 요청을 만든 시각 | `requested_at` |

### 2-3. 예시

```json
{
  "idempotency_key": "ACME-20261123-000123",
  "corporation_id": 1001,
  "from_account_no": "1002345678901",
  "to_bank_code": "088",
  "to_account_no": "1109876543210",
  "amount": 1500000,
  "currency": "KRW",
  "memo": "11월 거래대금",
  "requested_at": "2026-11-23T05:50:12Z"
}
```

## 3. 응답 (Response)

| 항목 | 타입 | 설명 |
| --- | --- | --- |
| `result_code` | 문자열 | 4장의 코드 |
| `message` | 문자열 | 사람이 읽는 설명 |
| `transfer_id` | 숫자 | 접수된 이체 ID. 실패로 접수가 안 된 경우 없음 |
| `status` | 문자열 | `REQUESTED`, `PENDING_APPROVAL`, `APPROVED`, `POSTED`, `REJECTED`, `FAILED` (ERD의 상태 흐름과 같음) |
| `processed_at` | 문자열(ISO 8601) | 서버 처리 시각 |

```json
{
  "result_code": "0000",
  "message": "이체 요청이 접수되었습니다",
  "transfer_id": 52001,
  "status": "PENDING_APPROVAL",
  "processed_at": "2026-11-23T05:50:13Z"
}
```

## 4. 결과 코드 (초안)

| 코드 | 의미 | 어디서 막나 | 위협 모델 |
| --- | --- | --- | --- |
| `0000` | 정상 접수 | | |
| `E1001` | 서명 검증 실패(변조 의심) | 게이트웨이 | T5 |
| `E1002` | 재전송 의심(`nonce` 중복 또는 `timestamp` 범위 초과) | 게이트웨이 | T3 |
| `E1003` | 허용되지 않은 암호 모드(최저 모드 미만, 협상 불일치) | 게이트웨이 | T2 |
| `E2001` | 중복 요청. 기존 결과를 함께 반환 | 이체 API | T3, T4 |
| `E2002` | 잔액 부족 | 이체 API | |
| `E2003` | 승인 필요(`PENDING_APPROVAL`로 접수됨) | 결재 엔진 | T6 |
| `E2004` | 한도 초과 | 결재 엔진 | |
| `E2005` | 계좌 상태 이상(동결 등) 또는 소유 불일치 | 이체 API | |
| `E9000` | 서버 내부 오류. 같은 `idempotency_key`로 재시도 가능 | 전체 | |

`E2001`은 오류가 아니라 **안전한 재시도의 결과**이므로, 기업 시스템은 같은 키로 다시 보내도 이체가 한 번만 일어난다고 믿을 수 있어야 한다.

## 5. 검증 순서 (게이트웨이와 이체 API)

1. `crypto_mode`가 허용 범위인가 → 아니면 `E1003`
2. `timestamp`가 허용 범위인가, `nonce`를 처음 보는가 → 아니면 `E1002`
3. 모든 필수 서명이 유효한가(HYBRID는 둘 다) → 아니면 `E1001`
4. 복호화 후 본문 항목이 제약을 지키는가 → 아니면 400 계열 오류
5. `idempotency_key`가 이미 있는가 → 있으면 `E2001`과 기존 결과
6. 계좌·잔액·한도·승인 정책 검사 → `E2002`, `E2004`, `E2005`, `E2003`
7. 원장 기록과 `POSTED`

## 6. 팀이 정할 것

1. `timestamp` 허용 범위(초안 5분)와 `nonce` 보관 기간
2. `idempotency_key` 형식 규칙을 강제할지
3. 기존 방식의 고정 길이 전문 형식을 따로 지원할지, JSON만 쓸지(W3 "LEGACY 미니 통합" 전에 결정)
4. 타 은행 이체의 은행 코드 목록 범위
5. 결과 코드 번호 체계(초안은 임의로 붙인 값)
