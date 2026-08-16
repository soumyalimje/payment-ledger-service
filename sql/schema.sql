-- ============================================================
-- Payment Ledger Service - Schema
-- ============================================================
-- Three tables, each enforcing one guarantee at the DB level:
--   1. accounts          - just balances we can lock rows on
--   2. idempotency_keys  - prevents double-processing a request
--   3. ledger_entries    - double-entry bookkeeping, DB-checked
-- ============================================================

DROP TABLE IF EXISTS ledger_entries;
DROP TABLE IF EXISTS idempotency_keys;
DROP TABLE IF EXISTS accounts;

-- 1. Accounts: each has a running balance (in paise/cents, integer to avoid float errors)
CREATE TABLE accounts (
    account_id      TEXT PRIMARY KEY,
    balance         BIGINT NOT NULL DEFAULT 0
);

-- 2. Idempotency keys: tracks in-flight and completed requests
--    status: STARTED -> COMPLETED or FAILED
--    A row is inserted the moment a request starts, BEFORE any business logic runs.
--    The UNIQUE constraint on idempotency_key is what makes "only one wins" possible:
--    concurrent inserts with the same key will have exactly one succeed.
CREATE TABLE idempotency_keys (
    idempotency_key TEXT PRIMARY KEY,
    request_hash    TEXT NOT NULL,
    status          TEXT NOT NULL CHECK (status IN ('STARTED', 'COMPLETED', 'FAILED')),
    response_body   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 3. Ledger entries: every transaction writes >= 2 rows (one debit, one credit)
--    that share the same transaction_id. amount is signed:
--    negative = debit (money leaving an account), positive = credit (money entering).
--    We do NOT rely on application code to guarantee balance -- see the trigger below,
--    which is the real DB-level enforcement the assignment asked for.
CREATE TABLE ledger_entries (
    entry_id        BIGSERIAL PRIMARY KEY,
    transaction_id  TEXT NOT NULL,
    account_id      TEXT NOT NULL REFERENCES accounts(account_id),
    amount          BIGINT NOT NULL,              -- signed: debit negative, credit positive
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_ledger_transaction ON ledger_entries(transaction_id);
CREATE INDEX idx_ledger_account ON ledger_entries(account_id);

-- ============================================================
-- DB-LEVEL ENFORCEMENT: a transaction_id's entries must sum to zero.
-- This is a deferred constraint trigger: it fires at COMMIT time (not per-row),
-- because a single transaction inserts 2 rows (debit + credit) and we need
-- both present before checking the sum. If they don't balance, COMMIT itself
-- fails and the whole transaction is rolled back automatically by Postgres.
-- ============================================================
CREATE OR REPLACE FUNCTION check_ledger_balance() RETURNS TRIGGER AS $$
DECLARE
    tx_id TEXT;
    total BIGINT;
BEGIN
    -- NEW is the row that was just inserted/updated in this trigger firing
    tx_id := NEW.transaction_id;

    SELECT COALESCE(SUM(amount), 0) INTO total
    FROM ledger_entries
    WHERE transaction_id = tx_id;

    IF total <> 0 THEN
        RAISE EXCEPTION 'Ledger imbalance for transaction %: entries sum to % (must be 0)', tx_id, total;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_check_ledger_balance
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED   -- checked at COMMIT, not immediately after each row
    FOR EACH ROW
    EXECUTE FUNCTION check_ledger_balance();

-- Seed two accounts for testing
INSERT INTO accounts (account_id, balance) VALUES ('merchant_A', 0);
INSERT INTO accounts (account_id, balance) VALUES ('customer_1', 1000000); -- starts with 10,000.00
