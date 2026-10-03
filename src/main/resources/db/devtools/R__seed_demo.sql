-- Demo / load-test data generator. Not part of the business schema: it lives in its own
-- Flyway location (db/devtools) so production deployments can leave it out.
--
--   SELECT * FROM seed_demo();                          -- 1,000 users, 2,000 wallets, 100,000 transfers
--   SELECT * FROM seed_demo(10000, 2, 500000);          -- ~1,000,000 ledger entries
--   SELECT * FROM seed_demo(1000, 2, 100000, 120000);   -- plus 120,000 transfers into one "hot" wallet
--
-- Everything goes through the same tables, triggers and constraints as the API: every wallet is
-- topped up from CASH_IN first, then peer transfers move small amounts between the new wallets,
-- so no wallet can go negative. p_transfers counts the top-ups too.
CREATE OR REPLACE FUNCTION seed_demo(p_users int DEFAULT 1000,
                                     p_wallets_per_user int DEFAULT 2,
                                     p_transfers int DEFAULT 100000,
                                     p_hot_wallet_transfers int DEFAULT 0)
RETURNS TABLE (users_created int, wallets_created int, transfers_created int, entries_created bigint, hot_wallet_id bigint)
LANGUAGE plpgsql AS $$
DECLARE
    v_tag      text   := to_char(clock_timestamp(), 'YYMMDDHH24MISSUS');
    v_cash_in  bigint := (SELECT id FROM accounts WHERE system_code = 'CASH_IN');
    v_wallets  bigint[];
    v_n        int;
    v_peer     int;
    v_entries  bigint;
    v_topup    bigint := 10000000;   -- ₹1,00,000 per wallet
BEGIN
    PERFORM set_config('wallet.actor', 'system:seed', true);

    CREATE TEMP TABLE _seed_users ON COMMIT DROP AS
    WITH ins AS (
        INSERT INTO users (name, email, phone, kyc_status)
        SELECT (ARRAY['Asha','Rahul','Priya','Vikram','Neha','Arjun','Kavya','Rohan','Sneha','Aditya'])[1 + g % 10]
                   || ' ' || (ARRAY['Sharma','Iyer','Patel','Reddy','Khan','Das','Nair','Gupta'])[1 + (g / 10) % 8]
                   || ' ' || g,
               'seed' || v_tag || '.' || g || '@example.com',
               '+7' || right(v_tag, 6) || lpad(g::text, 7, '0'),
               CASE WHEN g % 5 = 0 THEN 'PENDING' ELSE 'VERIFIED' END
          FROM generate_series(1, p_users) g
        RETURNING id)
    SELECT id FROM ins;

    CREATE TEMP TABLE _seed_wallets ON COMMIT DROP AS
    WITH ins AS (
        INSERT INTO accounts (user_id, kind, name)
        SELECT u.id, 'USER_WALLET', CASE w WHEN 1 THEN 'main' WHEN 2 THEN 'savings' ELSE 'wallet ' || w END
          FROM _seed_users u CROSS JOIN generate_series(1, p_wallets_per_user) w
        RETURNING id)
    SELECT id FROM ins;

    SELECT array_agg(id ORDER BY id) INTO v_wallets FROM _seed_wallets;
    v_n := coalesce(array_length(v_wallets, 1), 0);
    IF v_n < 2 THEN
        RAISE EXCEPTION 'seed_demo needs at least two wallets';
    END IF;
    v_peer := greatest(p_transfers - v_n, 0);

    -- Transfer headers: one top-up per wallet 91 days ago, then peer transfers spread over 90 days,
    -- then optional transfers into the hot wallet (v_wallets[1]).
    CREATE TEMP TABLE _seed_transfers ON COMMIT DROP AS
    WITH ins AS (
        INSERT INTO transfers (type, status, amount, from_account_id, to_account_id, note, created_at, updated_at)
        SELECT 'TOP_UP', 'POSTED', v_topup, v_cash_in, v_wallets[i], 'Seed top-up',
               now() - interval '91 days', now() - interval '91 days'
          FROM generate_series(1, v_n) i
        UNION ALL
        SELECT 'PEER_TRANSFER', 'POSTED', 100 + floor(random() * 49900)::bigint,
               v_wallets[s.a], v_wallets[1 + (s.a - 1 + s.off) % v_n],
               (ARRAY['Dinner', 'Rent share', 'Groceries', 'Movie tickets', 'Cab', NULL])[1 + s.k % 6],
               s.at, s.at
          FROM (SELECT g AS k,
                       1 + floor(random() * v_n)::int AS a,
                       1 + floor(random() * (v_n - 1))::int AS off,
                       now() - random() * interval '90 days' AS at
                  FROM generate_series(1, v_peer) g) s
        UNION ALL
        SELECT 'PEER_TRANSFER', 'POSTED', 100 + floor(random() * 4900)::bigint,
               v_wallets[2 + floor(random() * (v_n - 1))::int], v_wallets[1], 'Hot wallet payment', h.at, h.at
          FROM (SELECT now() - random() * interval '90 days' AS at FROM generate_series(1, p_hot_wallet_transfers)) h
        RETURNING id, amount, from_account_id, to_account_id, created_at)
    SELECT * FROM ins;

    -- Both legs of every transfer in ONE statement, so the balance trigger checks them all at once.
    INSERT INTO ledger_entries (transfer_id, account_id, direction, amount, created_at)
    SELECT id, from_account_id, 'D', amount, created_at FROM _seed_transfers
    UNION ALL
    SELECT id, to_account_id, 'C', amount, created_at FROM _seed_transfers;
    GET DIAGNOSTICS v_entries = ROW_COUNT;

    -- Cached balances: wallets first, then system accounts (same lock order as the API).
    UPDATE accounts a
       SET balance = a.balance + d.delta
      FROM (SELECT account_id, SUM(delta) AS delta
              FROM (SELECT to_account_id AS account_id, amount AS delta FROM _seed_transfers
                    UNION ALL
                    SELECT from_account_id, -amount FROM _seed_transfers) x
             GROUP BY account_id) d
     WHERE a.id = d.account_id AND a.kind = 'USER_WALLET';
    UPDATE accounts a
       SET balance = a.balance + d.delta
      FROM (SELECT account_id, SUM(delta) AS delta
              FROM (SELECT to_account_id AS account_id, amount AS delta FROM _seed_transfers
                    UNION ALL
                    SELECT from_account_id, -amount FROM _seed_transfers) x
             GROUP BY account_id) d
     WHERE a.id = d.account_id AND a.kind = 'SYSTEM';

    RETURN QUERY SELECT p_users, v_n, (SELECT count(*)::int FROM _seed_transfers), v_entries,
                        CASE WHEN p_hot_wallet_transfers > 0 THEN v_wallets[1] END;
END;
$$;
