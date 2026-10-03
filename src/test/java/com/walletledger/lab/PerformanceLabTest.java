package com.walletledger.lab;

import com.walletledger.IntegrationTest;
import com.walletledger.risk.RiskEngine;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Milestones 4 and 5 and the pagination bonus: query plans on a ledger with ~1.2 million entries,
 * plus the time each fraud rule adds to a transfer. Writes target/performance-lab.txt.
 *
 * <pre>./mvnw test -Dlab.exclude=none -Dgroups=lab -Dtest=PerformanceLabTest</pre>
 */
@Tag("lab")
class PerformanceLabTest extends IntegrationTest {

    @Autowired
    RiskEngine riskEngine;

    private final StringBuilder out = new StringBuilder();

    @Test
    void plansAndTimings() throws Exception {
        long t0 = System.currentTimeMillis();
        Map<String, Object> seeded = jdbc.queryForMap("SELECT * FROM seed_demo(10000, 2, 500000, 120000)");
        jdbc.execute("ANALYZE");
        long entries = jdbc.queryForObject("SELECT count(*) FROM ledger_entries", Long.class);
        long hot = ((Number) seeded.get("hot_wallet_id")).longValue();
        out.append("Seeded ").append(seeded).append(" in ").append(System.currentTimeMillis() - t0).append(" ms\n")
                .append("ledger_entries rows: ").append(entries).append("\n")
                .append("hot wallet ").append(hot).append(" has ")
                .append(jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE account_id = ?", Long.class, hot))
                .append(" entries\n");

        // ---- Milestone 4: the reconciliation checks ----
        String computed = "WITH computed AS (SELECT account_id, SUM(CASE WHEN direction = 'C' THEN amount ELSE -amount END) AS balance FROM ledger_entries GROUP BY account_id) ";
        explain("Check 1 - total debits vs credits", """
                SELECT COALESCE(SUM(amount) FILTER (WHERE direction = 'D'), 0), COALESCE(SUM(amount) FILTER (WHERE direction = 'C'), 0)
                  FROM ledger_entries""");
        explain("Check 2 - unbalanced transfers (GROUP BY ... HAVING)", """
                SELECT t.id, t.amount, SUM(e.amount) FILTER (WHERE e.direction = 'D'), SUM(e.amount) FILTER (WHERE e.direction = 'C')
                  FROM ledger_entries e JOIN transfers t ON t.id = e.transfer_id
                 GROUP BY t.id
                HAVING COALESCE(SUM(e.amount) FILTER (WHERE e.direction = 'D'), 0) <> COALESCE(SUM(e.amount) FILTER (WHERE e.direction = 'C'), 0)
                    OR COALESCE(SUM(e.amount) FILTER (WHERE e.direction = 'D'), 0) <> t.amount""");
        explain("Check 3 - stored vs computed balances", computed + """
                SELECT a.id, a.balance, COALESCE(c.balance, 0) FROM accounts a LEFT JOIN computed c ON c.account_id = a.id
                 WHERE a.balance <> COALESCE(c.balance, 0)""");
        explain("Check 4 - negative user wallets", computed + """
                SELECT c.account_id, c.balance FROM computed c JOIN accounts a ON a.id = c.account_id
                 WHERE a.kind = 'USER_WALLET' AND c.balance < 0""");
        explain("Check 5a - posted transfers without entries", """
                SELECT t.id FROM transfers t WHERE t.status IN ('POSTED', 'REVERSED')
                   AND NOT EXISTS (SELECT 1 FROM ledger_entries e WHERE e.transfer_id = t.id)""");
        explain("Check 5c - entries without a transfer", """
                SELECT e.id FROM ledger_entries e WHERE NOT EXISTS (SELECT 1 FROM transfers t WHERE t.id = e.transfer_id)""");

        // ---- Pagination: OFFSET 100000 vs cursor ----
        String select = """
                SELECT e.id, e.transfer_id, t.type, e.direction, e.amount, e.created_at
                  FROM ledger_entries e JOIN transfers t ON t.id = e.transfer_id
                 WHERE e.account_id = %d
                """.formatted(hot);
        explain("Statement page with OFFSET 100000", select + " ORDER BY e.created_at DESC, e.id DESC LIMIT 20 OFFSET 100000");
        Map<String, Object> mark = jdbc.queryForMap("""
                SELECT created_at, id FROM ledger_entries WHERE account_id = ?
                 ORDER BY created_at DESC, id DESC OFFSET 99999 LIMIT 1
                """, hot);
        explain("Same page with a cursor on (created_at, id)", select + " AND (e.created_at, e.id) < ('"
                + mark.get("created_at") + "'::timestamptz, " + mark.get("id") + ") ORDER BY e.created_at DESC, e.id DESC LIMIT 20");

        // ---- Milestone 5: fraud rule queries ----
        long wallet = jdbc.queryForObject("""
                SELECT from_account_id FROM transfers WHERE type = 'PEER_TRANSFER'
                 GROUP BY from_account_id ORDER BY count(*) DESC LIMIT 1
                """, Long.class);
        long payee = jdbc.queryForObject("SELECT to_account_id FROM transfers WHERE from_account_id = ? LIMIT 1", Long.class, wallet);
        explain("VELOCITY", "SELECT count(*) FROM transfers WHERE from_account_id = " + wallet
                + " AND created_at > now() - make_interval(secs => 60) AND type IN ('PEER_TRANSFER', 'WITHDRAWAL')");
        explain("DAILY_OUTFLOW", "SELECT COALESCE(SUM(amount), 0) FROM transfers WHERE from_account_id = " + wallet
                + " AND created_at > now() - make_interval(secs => 86400) AND status IN ('POSTED', 'HELD') AND type IN ('PEER_TRANSFER', 'WITHDRAWAL')");
        explain("NEW_PAYEE_LARGE", "SELECT EXISTS (SELECT 1 FROM transfers WHERE from_account_id = " + wallet
                + " AND to_account_id = " + payee + " AND status = 'POSTED')");
        explain("UNVERIFIED_KYC", "SELECT kyc_status FROM users WHERE id = (SELECT user_id FROM accounts WHERE id = " + wallet + ")");

        // ---- Time the rules add ----
        long owner = jdbc.queryForObject("SELECT user_id FROM accounts WHERE id = ?", Long.class, wallet);
        List<Long> wallets = jdbc.queryForList("SELECT id FROM accounts WHERE kind = 'USER_WALLET' ORDER BY random() LIMIT 500", Long.class);
        for (int i = 0; i < 200; i++) { // warm-up
            riskEngine.evaluate("PEER_TRANSFER", wallets.get(i % wallets.size()), owner, payee, 2_000_000);
        }
        long total = 0;
        for (int i = 0; i < 2000; i++) {
            long w = wallets.get(i % wallets.size());
            total += riskEngine.evaluate("PEER_TRANSFER", w, owner, payee, 2_000_000).elapsedMicros();
        }
        out.append("\n### Fraud rules: time added per transfer\n")
                .append("All five rules evaluated (amount ₹20,000 so every amount-gated rule runs its query), 2,000 calls: ")
                .append(String.format("%.0f", total / 2000.0)).append(" µs on average\n");
        for (String code : List.of("VELOCITY", "DAILY_OUTFLOW", "UNVERIFIED_KYC", "NEW_PAYEE_LARGE", "LARGE_AMOUNT")) {
            jdbc.update("UPDATE risk_rules SET enabled = (code = ?)", code);
            long sum = 0;
            for (int i = 0; i < 2000; i++) {
                sum += riskEngine.evaluate("PEER_TRANSFER", wallets.get(i % wallets.size()), owner, payee, 2_000_000).elapsedMicros();
            }
            out.append(String.format("  only %-16s %6.0f µs (includes the ~1 query that loads the enabled rules)%n", code, sum / 2000.0));
        }
        jdbc.update("UPDATE risk_rules SET enabled = false");
        long none = 0;
        for (int i = 0; i < 2000; i++) {
            none += riskEngine.evaluate("PEER_TRANSFER", wallets.get(i % wallets.size()), owner, payee, 2_000_000).elapsedMicros();
        }
        out.append(String.format("  no rules enabled       %6.0f µs (just loading the rule list)%n", none / 2000.0));
        jdbc.update("UPDATE risk_rules SET enabled = true");

        Path file = Path.of("target", "performance-lab.txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, out.toString());
        System.out.println(out);
    }

    private void explain(String title, String sql) {
        out.append("\n### ").append(title).append("\n");
        for (String line : jdbc.queryForList("EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) " + sql, String.class)) {
            out.append(line).append("\n");
        }
    }
}
