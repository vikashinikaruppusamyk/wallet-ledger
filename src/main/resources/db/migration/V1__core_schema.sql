-- Core schema for the wallet ledger.
-- Money is always BIGINT paise. Every table has a system-generated BIGINT key;
-- data we don't control (email, phone) is unique but never a primary key.

-- ---------------------------------------------------------------------------
-- users: people who own wallets. role = ADMIN marks back-office operators.
-- ---------------------------------------------------------------------------
CREATE TABLE users (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        TEXT        NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 200),
    email       TEXT        NOT NULL CHECK (email ~ '^[^@[:space:]]+@[^@[:space:]]+$'),
    phone       TEXT        NOT NULL CHECK (phone ~ '^\+?[0-9]{8,15}$'),
    kyc_status  TEXT        NOT NULL DEFAULT 'PENDING'
                            CHECK (kyc_status IN ('PENDING', 'VERIFIED', 'REJECTED')),
    role        TEXT        NOT NULL DEFAULT 'CUSTOMER' CHECK (role IN ('CUSTOMER', 'ADMIN')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_users_email ON users (lower(email));
CREATE UNIQUE INDEX uq_users_phone ON users (phone);

-- ---------------------------------------------------------------------------
-- accounts: user wallets AND system accounts in one table, so a ledger entry
-- has exactly one foreign key to "the account it moves money on".
-- ---------------------------------------------------------------------------
CREATE TABLE accounts (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id      BIGINT      REFERENCES users (id),
    kind         TEXT        NOT NULL CHECK (kind IN ('USER_WALLET', 'SYSTEM')),
    system_code  TEXT        UNIQUE CHECK (system_code IN ('CASH_IN', 'CASH_OUT', 'FEE_REVENUE')),
    name         TEXT        NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 50),
    currency     CHAR(3)     NOT NULL DEFAULT 'INR' CHECK (currency = 'INR'),
    -- Cached balance, always updated in the same transaction as the entries.
    balance      BIGINT      NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_accounts_owner CHECK (
           (kind = 'USER_WALLET' AND user_id IS NOT NULL AND system_code IS NULL)
        OR (kind = 'SYSTEM'      AND user_id IS NULL     AND system_code IS NOT NULL)),
    -- Requirement 7: a user wallet can never go below zero; system accounts may.
    CONSTRAINT ck_accounts_wallet_non_negative CHECK (kind = 'SYSTEM' OR balance >= 0),
    CONSTRAINT uq_accounts_user_name UNIQUE (user_id, name)
);

-- ---------------------------------------------------------------------------
-- transfers: the business event. Every transfer is two-legged
-- (from_account debited, to_account credited); fees are their own FEE
-- transfer linked to the withdrawal by parent_transfer_id.
-- ---------------------------------------------------------------------------
CREATE TABLE transfers (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    type                 TEXT        NOT NULL
                         CHECK (type IN ('TOP_UP', 'PEER_TRANSFER', 'WITHDRAWAL', 'FEE', 'REVERSAL')),
    status               TEXT        NOT NULL CHECK (status IN ('POSTED', 'HELD', 'REJECTED', 'REVERSED')),
    amount               BIGINT      NOT NULL CHECK (amount > 0),
    from_account_id      BIGINT      NOT NULL REFERENCES accounts (id),
    to_account_id        BIGINT      NOT NULL REFERENCES accounts (id),
    initiated_by         BIGINT      REFERENCES users (id),
    reverses_transfer_id BIGINT      UNIQUE REFERENCES transfers (id),
    parent_transfer_id   BIGINT      REFERENCES transfers (id),
    note                 TEXT        CHECK (length(note) <= 280),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_transfers_distinct_accounts CHECK (from_account_id <> to_account_id),
    CONSTRAINT ck_transfers_reversal_link CHECK ((type = 'REVERSAL') = (reverses_transfer_id IS NOT NULL)),
    CONSTRAINT ck_transfers_fee_parent CHECK (type <> 'FEE' OR parent_transfer_id IS NOT NULL)
);
-- Velocity / daily-outflow fraud rules and "sent" statement questions.
CREATE INDEX ix_transfers_from_created ON transfers (from_account_id, created_at) INCLUDE (amount, status, type);
-- "received" statement questions.
CREATE INDEX ix_transfers_to_created ON transfers (to_account_id, created_at);
-- New-payee fraud rule: has this wallet ever paid this recipient?
CREATE INDEX ix_transfers_from_to ON transfers (from_account_id, to_account_id);
-- Admin review queue; tiny because it only covers held transfers.
CREATE INDEX ix_transfers_held ON transfers (created_at) WHERE status = 'HELD';

-- ---------------------------------------------------------------------------
-- ledger_entries: the debit/credit lines. Amount is always positive and the
-- direction is stored separately; insert-only (see V2 guards).
-- Convention: a debit takes money out of an account, a credit puts money in.
-- balance = SUM(credits) - SUM(debits).
-- ---------------------------------------------------------------------------
CREATE TABLE ledger_entries (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transfer_id BIGINT      NOT NULL REFERENCES transfers (id),
    account_id  BIGINT      NOT NULL REFERENCES accounts (id),
    direction   CHAR(1)     NOT NULL CHECK (direction IN ('D', 'C')),
    amount      BIGINT      NOT NULL CHECK (amount > 0),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Statement: one wallet's entries, newest first (offset and cursor pagination).
CREATE INDEX ix_ledger_entries_account_created ON ledger_entries (account_id, created_at DESC, id DESC);
-- Balance assertion trigger and per-transfer reconciliation.
CREATE INDEX ix_ledger_entries_transfer ON ledger_entries (transfer_id);

-- ---------------------------------------------------------------------------
-- transfer_status_history: audit log of every status a transfer has had.
-- Written by a trigger, so no code path can change a status without a row here.
-- ---------------------------------------------------------------------------
CREATE TABLE transfer_status_history (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transfer_id        BIGINT      NOT NULL REFERENCES transfers (id),
    from_status        TEXT,
    to_status          TEXT        NOT NULL,
    changed_by         TEXT        NOT NULL,          -- e.g. user:42, admin:1, system:seed
    changed_by_user_id BIGINT      REFERENCES users (id),
    reason             TEXT,
    changed_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_transfer_status_history_transfer ON transfer_status_history (transfer_id, id);

-- ---------------------------------------------------------------------------
-- idempotency_keys: one row per (user, client-chosen key).
-- NULLS NOT DISTINCT lets anonymous requests (POST /users) share the
-- guarantee: user_id NULL + same key is still a duplicate.
-- ---------------------------------------------------------------------------
CREATE TABLE idempotency_keys (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id        BIGINT      REFERENCES users (id),
    idem_key       UUID        NOT NULL,
    request_method TEXT        NOT NULL,
    request_path   TEXT        NOT NULL,
    request_hash   CHAR(64)    NOT NULL,              -- SHA-256 hex of method + path + canonical body
    status         TEXT        NOT NULL DEFAULT 'IN_PROGRESS' CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    response_code  INT,
    response_body  TEXT,                              -- stored verbatim so replays are byte-identical
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at   TIMESTAMPTZ,
    CONSTRAINT uq_idempotency_keys_user_key UNIQUE NULLS NOT DISTINCT (user_id, idem_key),
    CONSTRAINT ck_idempotency_keys_completed CHECK ((status = 'COMPLETED') = (response_code IS NOT NULL))
);
CREATE INDEX ix_idempotency_keys_created ON idempotency_keys (created_at);

-- ---------------------------------------------------------------------------
-- Fraud rules (thresholds live here, not in code) and the flags they raise.
-- ---------------------------------------------------------------------------
CREATE TABLE risk_rules (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code             TEXT        NOT NULL UNIQUE,
    description      TEXT        NOT NULL,
    action           TEXT        NOT NULL CHECK (action IN ('HOLD', 'FLAG')),
    applies_to       TEXT[]      NOT NULL,
    amount_threshold BIGINT      CHECK (amount_threshold > 0),
    count_threshold  INT         CHECK (count_threshold > 0),
    window_seconds   INT         CHECK (window_seconds > 0),
    enabled          BOOLEAN     NOT NULL DEFAULT true,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE risk_flags (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transfer_id     BIGINT      NOT NULL REFERENCES transfers (id),
    rule_id         BIGINT      NOT NULL REFERENCES risk_rules (id),
    action          TEXT        NOT NULL CHECK (action IN ('HOLD', 'FLAG')),
    details         JSONB       NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    llm_label       TEXT        CHECK (llm_label IN ('likely_ok', 'review', 'likely_fraud')),
    llm_explanation TEXT        CHECK (length(llm_explanation) <= 600),
    llm_model       TEXT,
    explained_at    TIMESTAMPTZ,
    CONSTRAINT uq_risk_flags_transfer_rule UNIQUE (transfer_id, rule_id)
);
CREATE INDEX ix_risk_flags_unexplained ON risk_flags (created_at) WHERE explained_at IS NULL;

-- ---------------------------------------------------------------------------
-- Background jobs: one lock row per job, one row per reconciliation run,
-- one row per problem found.
-- ---------------------------------------------------------------------------
CREATE TABLE job_locks (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_name         TEXT        NOT NULL UNIQUE,
    last_started_at  TIMESTAMPTZ,
    last_finished_at TIMESTAMPTZ
);

CREATE TABLE reconciliation_runs (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    trigger       TEXT        NOT NULL CHECK (trigger IN ('SCHEDULED', 'MANUAL')),
    -- PASSED: no problems; FAILED: problems found; ERROR: the job itself crashed.
    status        TEXT        NOT NULL CHECK (status IN ('RUNNING', 'PASSED', 'FAILED', 'ERROR')),
    started_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at   TIMESTAMPTZ,
    problem_count INT,
    stats         JSONB,
    error         TEXT,
    CONSTRAINT ck_reconciliation_runs_finished CHECK ((status = 'RUNNING') = (finished_at IS NULL))
);

CREATE TABLE reconciliation_problems (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id      BIGINT NOT NULL REFERENCES reconciliation_runs (id),
    check_name  TEXT   NOT NULL,
    entity_type TEXT,
    entity_id   BIGINT,
    expected    BIGINT,
    actual      BIGINT,
    details     JSONB  NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX ix_reconciliation_problems_run ON reconciliation_problems (run_id);
