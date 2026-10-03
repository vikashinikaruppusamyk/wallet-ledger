-- Database-level guards. Application code is not trusted to keep these rules;
-- the database refuses any statement that would break them.

-- ---------------------------------------------------------------------------
-- 1. Ledger entries are insert-only: no UPDATE, DELETE or TRUNCATE.
-- ---------------------------------------------------------------------------
CREATE FUNCTION forbid_modification() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only: % is not allowed', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER trg_ledger_entries_immutable
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION forbid_modification();

CREATE TRIGGER trg_ledger_entries_no_truncate
    BEFORE TRUNCATE ON ledger_entries
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_modification();

CREATE TRIGGER trg_transfer_status_history_immutable
    BEFORE UPDATE OR DELETE ON transfer_status_history
    FOR EACH ROW EXECUTE FUNCTION forbid_modification();

-- ---------------------------------------------------------------------------
-- 2. Every INSERT into ledger_entries must leave each touched transfer
--    balanced: debits = credits = transfer.amount, and only on a transfer
--    that is POSTED. Statement-level with a transition table, so a bulk
--    insert is checked once, not once per row. All legs of a transfer must
--    therefore be written in a single INSERT statement.
-- ---------------------------------------------------------------------------
CREATE FUNCTION ledger_entries_assert_balanced() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    bad RECORD;
BEGIN
    SELECT t.id, t.amount, t.status,
           COALESCE(SUM(e.amount) FILTER (WHERE e.direction = 'D'), 0) AS debits,
           COALESCE(SUM(e.amount) FILTER (WHERE e.direction = 'C'), 0) AS credits
      INTO bad
      FROM transfers t
      JOIN ledger_entries e ON e.transfer_id = t.id
     WHERE t.id IN (SELECT DISTINCT transfer_id FROM new_entries)
     GROUP BY t.id
    HAVING COALESCE(SUM(e.amount) FILTER (WHERE e.direction = 'D'), 0) <> t.amount
        OR COALESCE(SUM(e.amount) FILTER (WHERE e.direction = 'C'), 0) <> t.amount
        OR t.status <> 'POSTED'
     LIMIT 1;

    IF FOUND THEN
        RAISE EXCEPTION 'ledger entries for transfer % are invalid: debits %, credits %, amount %, status %',
            bad.id, bad.debits, bad.credits, bad.amount, bad.status
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER trg_ledger_entries_balanced
    AFTER INSERT ON ledger_entries
    REFERENCING NEW TABLE AS new_entries
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_entries_assert_balanced();

-- ---------------------------------------------------------------------------
-- 3. Transfers: only status may change, only along allowed transitions,
--    and never deleted.
--      HELD   -> POSTED   (admin release)
--      HELD   -> REJECTED (admin reject)
--      POSTED -> REVERSED (a REVERSAL transfer was posted against it)
-- ---------------------------------------------------------------------------
CREATE FUNCTION transfers_guard_update() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.id, NEW.type, NEW.amount, NEW.from_account_id, NEW.to_account_id, NEW.initiated_by,
        NEW.reverses_transfer_id, NEW.parent_transfer_id, NEW.note, NEW.created_at)
       IS DISTINCT FROM
       (OLD.id, OLD.type, OLD.amount, OLD.from_account_id, OLD.to_account_id, OLD.initiated_by,
        OLD.reverses_transfer_id, OLD.parent_transfer_id, OLD.note, OLD.created_at) THEN
        RAISE EXCEPTION 'transfer %: only status may change', OLD.id USING ERRCODE = 'restrict_violation';
    END IF;

    IF NEW.status IS DISTINCT FROM OLD.status AND NOT (
           (OLD.status = 'HELD'   AND NEW.status IN ('POSTED', 'REJECTED'))
        OR (OLD.status = 'POSTED' AND NEW.status = 'REVERSED')) THEN
        RAISE EXCEPTION 'transfer %: illegal status change % -> %', OLD.id, OLD.status, NEW.status
            USING ERRCODE = 'check_violation';
    END IF;

    NEW.updated_at := now();
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_transfers_guard_update
    BEFORE UPDATE ON transfers
    FOR EACH ROW EXECUTE FUNCTION transfers_guard_update();

CREATE TRIGGER trg_transfers_no_delete
    BEFORE DELETE ON transfers
    FOR EACH ROW EXECUTE FUNCTION forbid_modification();

-- ---------------------------------------------------------------------------
-- 4. Audit: every insert and status change on transfers writes a history row.
--    The actor comes from transaction-local settings that the application
--    sets with set_config(..., true). No actor, no write.
-- ---------------------------------------------------------------------------
CREATE FUNCTION transfers_audit_status() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    v_actor   TEXT   := NULLIF(current_setting('wallet.actor', true), '');
    v_user_id BIGINT := NULLIF(current_setting('wallet.actor_user_id', true), '')::BIGINT;
    v_reason  TEXT   := NULLIF(current_setting('wallet.reason', true), '');
BEGIN
    IF v_actor IS NULL THEN
        RAISE EXCEPTION 'wallet.actor must be set before changing transfer %', NEW.id
            USING ERRCODE = 'insufficient_privilege';
    END IF;

    IF TG_OP = 'INSERT' OR NEW.status IS DISTINCT FROM OLD.status THEN
        INSERT INTO transfer_status_history (transfer_id, from_status, to_status, changed_by, changed_by_user_id, reason)
        VALUES (NEW.id, CASE WHEN TG_OP = 'UPDATE' THEN OLD.status END, NEW.status, v_actor, v_user_id, v_reason);
    END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER trg_transfers_audit_status
    AFTER INSERT OR UPDATE OF status ON transfers
    FOR EACH ROW EXECUTE FUNCTION transfers_audit_status();
