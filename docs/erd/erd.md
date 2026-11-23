# ERD: 이체 · 원장 · 결재 (v0.1, W1 초안)

> 목적: 이체 한 건이 DB에 어떻게 기록되는지 정한다. 이체 API, 결재 엔진, 원장, 대사 배치가 모두 이 구조를 쓴다.
> 상태: 초안. 팀원과 검토해 W1 말 "계약 확정"에서 고정한다. 고정 후 변경은 서로 알린다(PR + ADR).
> DB: PostgreSQL. 스키마 변경은 Flyway 마이그레이션 파일로만 한다.

## 1. 한 장 그림

```mermaid
erDiagram
    CORPORATION ||--o{ APP_USER : "소속"
    CORPORATION ||--o{ ACCOUNT : "보유"
    CORPORATION ||--o{ APPROVAL_POLICY : "정책 정의"
    ACCOUNT ||--o{ TRANSFER_REQUEST : "출금 계좌"
    APP_USER ||--o{ TRANSFER_REQUEST : "작성자(maker)"
    TRANSFER_REQUEST ||--o{ APPROVAL : "결재 단계"
    APP_USER ||--o{ APPROVAL : "승인자(checker)"
    TRANSFER_REQUEST ||--o| JOURNAL : "전표 1건"
    JOURNAL ||--|{ LEDGER_ENTRY : "분개 2줄 이상"
    ACCOUNT ||--o{ LEDGER_ENTRY : "기록"
    APP_USER ||--o{ AUDIT_LOG : "행위자"

    CORPORATION {
        bigint id PK
        varchar name
        varchar biz_reg_no UK "사업자등록번호"
        varchar crypto_mode "LEGACY, HYBRID, PQC"
        varchar crypto_floor "허용 최저 모드"
        timestamp created_at
    }
    APP_USER {
        bigint id PK
        bigint corporation_id FK
        varchar login_id UK
        varchar role "MAKER, CHECKER, ADMIN"
        boolean active
    }
    ACCOUNT {
        bigint id PK
        bigint corporation_id FK
        varchar account_no UK
        varchar currency "KRW"
        varchar status "ACTIVE, FROZEN"
        bigint balance "원장 합계의 캐시, 검증 대상"
    }
    APPROVAL_POLICY {
        bigint id PK
        bigint corporation_id FK
        bigint amount_from "이 금액 이상이면"
        int required_approvals "필요한 승인 수"
    }
    TRANSFER_REQUEST {
        bigint id PK
        varchar idempotency_key UK "중복 이체 방지"
        bigint from_account_id FK
        varchar to_bank_code
        varchar to_account_no
        bigint amount "원 단위 정수"
        varchar status "상태 흐름은 3장"
        bigint maker_user_id FK
        varchar request_hash "요청 본문 해시"
        varchar crypto_mode_used "처리에 쓴 모드"
        timestamp requested_at
    }
    APPROVAL {
        bigint id PK
        bigint transfer_id FK
        int step_no
        bigint checker_user_id FK
        varchar decision "APPROVED, REJECTED"
        varchar reason
        timestamp decided_at
    }
    JOURNAL {
        bigint id PK
        bigint transfer_id FK, UK "이체 1건당 전표 1건"
        varchar kind "TRANSFER, REVERSAL"
        timestamp posted_at
    }
    LEDGER_ENTRY {
        bigint id PK
        bigint journal_id FK
        bigint account_id FK
        varchar side "DEBIT, CREDIT"
        bigint amount "항상 양수"
        timestamp created_at
    }
    AUDIT_LOG {
        bigint id PK
        bigint seq UK "순번, 빈틈 없음"
        bigint actor_id FK
        varchar action
        varchar target_type
        bigint target_id
        varchar prev_hash "직전 로그의 해시"
        varchar hash "이 로그의 해시"
        timestamp created_at
    }
```

## 2. 테이블이 하는 일 (한 줄 설명)

| 테이블 | 한 줄 설명 | 이 테이블이 막는 문제 |
| --- | --- | --- |
| `corporation` | 기업 고객. 암호 모드(LEGACY, HYBRID, PQC)와 허용 최저 모드를 기업별로 가진다 | 다운그레이드: 최저 모드 밑으로 못 내리게 함 |
| `app_user` | 이체를 만들거나 승인하는 사용자와 역할 | 권한 없는 사람의 이체 |
| `account` | 계좌. `balance`는 편의용 합계이고 원장과 맞는지 매일 검증한다 | 잔액 불일치 |
| `approval_policy` | "이 금액 이상이면 승인 N명" 규칙 | 고액을 혼자 보내는 것 |
| `transfer_request` | 이체 요청 한 건. `idempotency_key`가 유니크 | 같은 요청이 두 번 처리되는 것 |
| `approval` | 결재 한 단계의 기록 | 만든 사람이 직접 승인 |
| `journal` | 이체가 확정될 때 만드는 전표(거래 묶음) | 한 이체가 반쯤만 기록되는 것 |
| `ledger_entry` | 전표 안의 분개 줄(차변/대변) | 돈이 생기거나 사라지는 것 |
| `audit_log` | 누가 무엇을 했는지 이어 붙인 기록. 각 줄이 앞 줄 해시를 포함 | 기록 삭제·위조 |

## 3. 이체 상태 흐름

```mermaid
stateDiagram-v2
    [*] --> REQUESTED : 요청 수신, 서명 검증 통과
    REQUESTED --> PENDING_APPROVAL : 정책상 승인 필요
    REQUESTED --> APPROVED : 승인 불필요(소액)
    PENDING_APPROVAL --> APPROVED : 필요한 승인 수 충족
    PENDING_APPROVAL --> REJECTED : 승인자가 거절
    APPROVED --> POSTED : 원장 기록 성공(전표 생성)
    APPROVED --> FAILED : 잔액 부족 등
    POSTED --> [*]
    REJECTED --> [*]
    FAILED --> [*]
```

상태는 앞으로만 간다. 되돌릴 때는 상태를 고치지 않고 **역분개 전표**(`journal.kind = REVERSAL`)를 새로 만든다.

## 4. 반드시 지킬 규칙 (DB와 코드에서 같이 막는다)

| 번호 | 규칙 | 막는 방법 |
| --- | --- | --- |
| R1 | 같은 `idempotency_key`는 한 번만 처리 | `transfer_request.idempotency_key` UNIQUE. 중복 요청은 기존 결과를 돌려줌 |
| R2 | 한 전표의 차변 합계 = 대변 합계 | 전표 확정 시 서비스에서 검증 + 대사 배치에서 재검증 |
| R3 | 원장은 수정·삭제하지 않는다 | `ledger_entry`에 UPDATE·DELETE 권한을 주지 않음. 정정은 역분개 |
| R4 | 만든 사람과 승인한 사람은 달라야 한다 | `approval.checker_user_id <> transfer_request.maker_user_id`를 서비스 검증 + 가능하면 DB 제약 |
| R5 | 금액은 원 단위 정수 | `amount` BIGINT, 소수점 사용 안 함, 0 이하 금지(CHECK) |
| R6 | 감사 로그는 추가만 한다 | `audit_log`는 INSERT만 허용, `seq`로 빈틈 검사, 해시 체인 검증 |
| R7 | 기업의 암호 모드는 `crypto_floor` 아래로 내릴 수 없다 | 모드 변경 시 서비스 검증 + 감사 로그 기록 |

## 5. 아직 정하지 않은 것 (팀이 정할 것)

1. 결재 승인 수가 2명 이상일 때 같은 사람이 두 번 승인하지 못하게 할지(권장: `UNIQUE(transfer_id, checker_user_id)`)
2. 입금 계좌가 우리 DB에 없는 타 은행 계좌일 때의 처리(현재는 `to_bank_code` + `to_account_no`만 기록)
3. `account.balance` 캐시를 둘지, 항상 원장 합계로 계산할지(성능 vs 단순함, W9 성능 측정 때 판단)
4. 일 마감 대사 결과 테이블(`daily_reconciliation`)은 W8에 추가한다. 지금은 ERD에 넣지 않는다.
5. 컬럼 이름은 영문 snake_case를 쓰고, 한글 설명은 이 문서의 표에만 둔다.
