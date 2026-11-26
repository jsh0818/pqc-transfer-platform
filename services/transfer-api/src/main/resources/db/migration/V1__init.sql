-- V1: 이체 · 결재 · 원장 · 정책 변경 · 감사 로그 (docs/erd/erd.md v0.2)
-- 스키마는 Flyway 마이그레이션으로만 바꾼다. 이미 적용된 파일은 고치지 않고 V2, V3...를 새로 만든다.
-- 규칙 번호(R1~R10)는 ERD 문서 4장과 같다.

-- ===== 기업 · 사용자 · 계좌 · 승인 정책 =====

CREATE TABLE corporation (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name         varchar(100) NOT NULL,
    biz_reg_no   varchar(20)  NOT NULL UNIQUE,          -- 사업자등록번호
    crypto_mode  varchar(10)  NOT NULL,                 -- 현재 암호 모드
    crypto_floor varchar(10)  NOT NULL,                 -- 허용 최저 모드
    created_at   timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_corporation_mode  CHECK (crypto_mode  IN ('LEGACY', 'HYBRID', 'PQC')),
    CONSTRAINT ck_corporation_floor CHECK (crypto_floor IN ('LEGACY', 'HYBRID', 'PQC')),
    -- R7: 암호 모드는 최저 모드(crypto_floor) 아래로 내릴 수 없다. LEGACY < HYBRID < PQC
    CONSTRAINT ck_corporation_mode_ge_floor CHECK (
        (CASE crypto_mode  WHEN 'LEGACY' THEN 0 WHEN 'HYBRID' THEN 1 WHEN 'PQC' THEN 2 END)
        >=
        (CASE crypto_floor WHEN 'LEGACY' THEN 0 WHEN 'HYBRID' THEN 1 WHEN 'PQC' THEN 2 END)
    )
);

CREATE TABLE app_user (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    corporation_id bigint      NOT NULL REFERENCES corporation (id),
    login_id       varchar(50) NOT NULL UNIQUE,
    role           varchar(10) NOT NULL,
    active         boolean     NOT NULL DEFAULT true,
    CONSTRAINT ck_app_user_role CHECK (role IN ('MAKER', 'CHECKER', 'ADMIN'))
);
CREATE INDEX ix_app_user_corporation ON app_user (corporation_id);

CREATE TABLE account (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    corporation_id bigint      NOT NULL REFERENCES corporation (id),
    account_no     varchar(16) NOT NULL UNIQUE,
    currency       varchar(3)  NOT NULL DEFAULT 'KRW',
    status         varchar(10) NOT NULL DEFAULT 'ACTIVE',
    balance        bigint      NOT NULL DEFAULT 0,      -- 원장 합계의 캐시. 대사 배치가 검증한다
    CONSTRAINT ck_account_currency CHECK (currency = 'KRW'),
    CONSTRAINT ck_account_status   CHECK (status IN ('ACTIVE', 'FROZEN')),
    CONSTRAINT ck_account_balance  CHECK (balance >= 0)
);
CREATE INDEX ix_account_corporation ON account (corporation_id);

CREATE TABLE approval_policy (
    id                 bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    corporation_id     bigint NOT NULL REFERENCES corporation (id),
    amount_from        bigint NOT NULL,                 -- 이 금액 이상이면
    required_approvals int    NOT NULL,                 -- 필요한 승인 수
    CONSTRAINT ck_approval_policy_amount    CHECK (amount_from >= 0),
    CONSTRAINT ck_approval_policy_approvals CHECK (required_approvals >= 0),
    CONSTRAINT ux_approval_policy UNIQUE (corporation_id, amount_from)
);

-- ===== 이체 · 결재 =====

CREATE TABLE transfer_request (
    id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    idempotency_key  varchar(64)  NOT NULL,
    from_account_id  bigint       NOT NULL REFERENCES account (id),
    to_bank_code     varchar(3)   NOT NULL,
    to_account_no    varchar(16)  NOT NULL,
    amount           bigint       NOT NULL,             -- 원 단위 정수
    status           varchar(20)  NOT NULL,
    maker_user_id    bigint       NOT NULL REFERENCES app_user (id),
    request_hash     varchar(128) NOT NULL,             -- 요청 본문 해시
    crypto_mode_used varchar(10)  NOT NULL,             -- 처리에 쓴 모드
    requested_at     timestamptz  NOT NULL DEFAULT now(),
    -- R1: 같은 idempotency_key는 한 번만 처리
    CONSTRAINT ux_transfer_idempotency UNIQUE (idempotency_key),
    -- R5: 금액은 1원 이상의 정수
    CONSTRAINT ck_transfer_amount CHECK (amount > 0),
    CONSTRAINT ck_transfer_status CHECK (status IN
        ('REQUESTED', 'PENDING_APPROVAL', 'APPROVED', 'POSTED', 'REJECTED', 'FAILED')),
    CONSTRAINT ck_transfer_mode CHECK (crypto_mode_used IN ('LEGACY', 'HYBRID', 'PQC'))
);
CREATE INDEX ix_transfer_from_account ON transfer_request (from_account_id);
CREATE INDEX ix_transfer_maker        ON transfer_request (maker_user_id);
CREATE INDEX ix_transfer_status       ON transfer_request (status);

CREATE TABLE approval (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transfer_id     bigint      NOT NULL REFERENCES transfer_request (id),
    step_no         int         NOT NULL,
    checker_user_id bigint      NOT NULL REFERENCES app_user (id),
    decision        varchar(10) NOT NULL,
    reason          varchar(200),
    decided_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_approval_step     CHECK (step_no >= 1),
    CONSTRAINT ck_approval_decision CHECK (decision IN ('APPROVED', 'REJECTED')),
    CONSTRAINT ux_approval_step     UNIQUE (transfer_id, step_no),
    -- 같은 사람이 한 이체를 두 번 결재하지 못한다 (ERD 5장 1번, 팀 확인 전 제안)
    CONSTRAINT ux_approval_checker  UNIQUE (transfer_id, checker_user_id)
);

-- R4: 만든 사람과 승인한 사람은 달라야 한다. 서로 다른 테이블이라 CHECK로 못 막아 트리거를 쓴다.
CREATE FUNCTION check_checker_not_maker() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.checker_user_id = (SELECT maker_user_id FROM transfer_request WHERE id = NEW.transfer_id) THEN
        RAISE EXCEPTION 'R4 위반: 이체를 만든 사용자는 결재할 수 없다 (transfer_id=%)', NEW.transfer_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_approval_checker_not_maker
    BEFORE INSERT OR UPDATE OF checker_user_id, transfer_id ON approval
    FOR EACH ROW EXECUTE FUNCTION check_checker_not_maker();

-- ===== 정책 변경 (T13: 관리자 한 명이 최저 모드를 낮추는 것 방지) =====

CREATE TABLE policy_change_request (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    corporation_id bigint       NOT NULL REFERENCES corporation (id),
    target_field   varchar(20)  NOT NULL,
    old_value      varchar(10)  NOT NULL,               -- 요청 시점의 현재 값
    new_value      varchar(10)  NOT NULL,               -- 바꾸려는 값
    status         varchar(10)  NOT NULL DEFAULT 'PENDING',
    requested_by   bigint       NOT NULL REFERENCES app_user (id),
    decided_by     bigint       REFERENCES app_user (id), -- 처리 전에는 비어 있음
    reason         varchar(200),
    requested_at   timestamptz  NOT NULL DEFAULT now(),
    decided_at     timestamptz,
    CONSTRAINT ck_pcr_target CHECK (target_field IN ('CRYPTO_MODE', 'CRYPTO_FLOOR')),
    CONSTRAINT ck_pcr_status CHECK (status IN ('PENDING', 'APPLIED', 'REJECTED')),
    CONSTRAINT ck_pcr_old    CHECK (old_value IN ('LEGACY', 'HYBRID', 'PQC')),
    CONSTRAINT ck_pcr_new    CHECK (new_value IN ('LEGACY', 'HYBRID', 'PQC') AND new_value <> old_value),
    -- R8: 요청자와 승인자는 달라야 한다
    CONSTRAINT ck_pcr_maker_checker CHECK (decided_by IS NULL OR decided_by <> requested_by),
    -- R9: 처리 전(PENDING)에만 승인자·처리 시각이 비어 있다
    CONSTRAINT ck_pcr_decided_by CHECK ((status = 'PENDING') = (decided_by IS NULL)),
    CONSTRAINT ck_pcr_decided_at CHECK ((status = 'PENDING') = (decided_at IS NULL))
);
-- R10: 같은 기업의 같은 항목에는 처리 대기 요청이 하나만 있을 수 있다
CREATE UNIQUE INDEX ux_pcr_one_pending
    ON policy_change_request (corporation_id, target_field) WHERE status = 'PENDING';

-- ===== 원장 (복식부기) =====

CREATE TABLE journal (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transfer_id bigint      NOT NULL REFERENCES transfer_request (id),
    kind        varchar(10) NOT NULL,
    posted_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_journal_kind CHECK (kind IN ('TRANSFER', 'REVERSAL')),
    -- 이체 1건당 전표 1건. 정정은 REVERSAL 전표를 새로 만드는데, 그 설계는 W3 원장 구현 때
    -- ERD 5장에 추가하고 이 제약을 (transfer_id, kind)로 바꾸는 V2를 만든다.
    CONSTRAINT ux_journal_transfer UNIQUE (transfer_id)
);

CREATE TABLE ledger_entry (
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    journal_id bigint      NOT NULL REFERENCES journal (id),
    account_id bigint      NOT NULL REFERENCES account (id),
    side       varchar(6)  NOT NULL,
    amount     bigint      NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_ledger_side   CHECK (side IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ck_ledger_amount CHECK (amount > 0)
);
CREATE INDEX ix_ledger_journal ON ledger_entry (journal_id);
CREATE INDEX ix_ledger_account ON ledger_entry (account_id);

-- R2: 한 전표의 차변 합계 = 대변 합계. 한 줄씩 넣는 도중에는 안 맞으므로 커밋 시점에 검사한다.
CREATE FUNCTION check_journal_balanced() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    debit_sum  bigint;
    credit_sum bigint;
BEGIN
    SELECT COALESCE(SUM(amount) FILTER (WHERE side = 'DEBIT'), 0),
           COALESCE(SUM(amount) FILTER (WHERE side = 'CREDIT'), 0)
      INTO debit_sum, credit_sum
      FROM ledger_entry
     WHERE journal_id = NEW.journal_id;
    IF debit_sum <> credit_sum THEN
        RAISE EXCEPTION 'R2 위반: 전표 % 의 차변 합계(%)와 대변 합계(%)가 다르다', NEW.journal_id, debit_sum, credit_sum
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER trg_ledger_balanced
    AFTER INSERT ON ledger_entry
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_journal_balanced();

-- ===== 감사 로그 (해시 체인) =====

CREATE TABLE audit_log (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    seq         bigint       NOT NULL UNIQUE,           -- 순번. 빈틈 검사는 서비스가 한다
    actor_id    bigint       REFERENCES app_user (id),  -- 시스템이 한 일이면 비어 있을 수 있다
    action      varchar(50)  NOT NULL,
    target_type varchar(30)  NOT NULL,
    target_id   bigint,
    prev_hash   varchar(128) NOT NULL,                  -- 직전 로그의 해시
    hash        varchar(128) NOT NULL,                  -- 이 로그의 해시
    created_at  timestamptz  NOT NULL DEFAULT now()
);

-- R3, R6: 원장과 감사 로그는 추가만 한다. 수정·삭제는 막고, 정정은 새 줄(역분개 등)로 한다.
CREATE FUNCTION forbid_update_delete() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% 는 추가만 가능하다 (% 거부)', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'integrity_constraint_violation';
END
$$;

CREATE TRIGGER trg_ledger_entry_append_only
    BEFORE UPDATE OR DELETE ON ledger_entry
    FOR EACH ROW EXECUTE FUNCTION forbid_update_delete();
CREATE TRIGGER trg_journal_append_only
    BEFORE UPDATE OR DELETE ON journal
    FOR EACH ROW EXECUTE FUNCTION forbid_update_delete();
CREATE TRIGGER trg_audit_log_append_only
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION forbid_update_delete();
