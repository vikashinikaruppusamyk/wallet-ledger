package com.walletledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.walletledger.recon.JobLocks;
import com.walletledger.recon.ReconciliationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Milestone 4 acceptance test: seed 10,000 transfers, corrupt the data directly in the database
 * (change one entry's amount, delete one credit, change one stored balance). The job must report
 * exactly those three problems and nothing else.
 */
class ReconciliationTest extends IntegrationTest {

    @Autowired
    JobLocks jobLocks;

    @Test
    void reportsExactlyTheThreeCorruptionsAndNothingElse() {
        Map<String, Object> seeded = jdbc.queryForMap("SELECT * FROM seed_demo(1000, 2, 10000)");
        assertThat(((Number) seeded.get("transfers_created")).intValue()).isEqualTo(10_000);

        JsonNode clean = runToCompletion();
        assertThat(clean.get("status").asText()).as(clean.toString()).isEqualTo("PASSED");
        assertThat(clean.get("problems")).isEmpty();

        // Pick victims among the seeded peer transfers: two transfers and a third, unrelated wallet.
        Map<String, Object> t1 = jdbc.queryForMap("""
                SELECT t.id, t.from_account_id, t.to_account_id, t.amount, e.id AS debit_entry_id
                  FROM transfers t JOIN ledger_entries e ON e.transfer_id = t.id AND e.direction = 'D'
                 WHERE t.type = 'PEER_TRANSFER' AND t.note IS DISTINCT FROM 'Hot wallet payment'
                 ORDER BY t.id DESC LIMIT 1
                """);
        Map<String, Object> t2 = jdbc.queryForMap("""
                SELECT t.id, t.from_account_id, t.to_account_id, t.amount, e.id AS credit_entry_id,
                       e.account_id, e.created_at
                  FROM transfers t JOIN ledger_entries e ON e.transfer_id = t.id AND e.direction = 'C'
                 WHERE t.type = 'PEER_TRANSFER'
                   AND t.from_account_id NOT IN (?, ?) AND t.to_account_id NOT IN (?, ?)
                 ORDER BY t.id DESC LIMIT 1
                """, t1.get("from_account_id"), t1.get("to_account_id"), t1.get("from_account_id"), t1.get("to_account_id"));
        List<Long> touched = List.of(id(t1, "from_account_id"), id(t1, "to_account_id"),
                id(t2, "from_account_id"), id(t2, "to_account_id"));
        long walletZ = jdbc.queryForObject("""
                SELECT id FROM accounts
                 WHERE kind = 'USER_WALLET' AND balance > 0 AND id <> ALL (?)
                 ORDER BY id DESC LIMIT 1
                """, Long.class, (Object) touched.toArray(Long[]::new));
        long originalBalanceZ = balance(walletZ);

        // ---- Corrupt the data, bypassing the append-only trigger the way a DBA could ----
        jdbc.execute("ALTER TABLE ledger_entries DISABLE TRIGGER trg_ledger_entries_immutable");
        try {
            jdbc.update("UPDATE ledger_entries SET amount = amount + 777 WHERE id = ?", t1.get("debit_entry_id"));
            jdbc.update("DELETE FROM ledger_entries WHERE id = ?", t2.get("credit_entry_id"));
        } finally {
            jdbc.execute("ALTER TABLE ledger_entries ENABLE TRIGGER trg_ledger_entries_immutable");
        }
        jdbc.update("UPDATE accounts SET balance = balance + 12345 WHERE id = ?", walletZ);

        JsonNode report = runToCompletion();
        try {
            assertThat(report.get("status").asText()).isEqualTo("FAILED");
            assertThat(report.get("problem_count").asInt()).as(report.toString()).isEqualTo(3);

            List<String> found = new ArrayList<>();
            report.get("problems").forEach(p -> found.add(p.get("check_name").asText() + ":" + p.get("entity_id").asLong()));
            assertThat(found).containsExactlyInAnyOrder(
                    "TRANSFER_UNBALANCED:" + id(t1, "id"),
                    "TRANSFER_UNBALANCED:" + id(t2, "id"),
                    "STORED_BALANCE_MISMATCH:" + walletZ);

            JsonNode p1 = problem(report, id(t1, "id"));
            assertThat(p1.get("expected").asLong()).isEqualTo(id(t1, "amount") + 777); // debits
            assertThat(p1.get("actual").asLong()).isEqualTo(id(t1, "amount"));        // credits
            JsonNode p2 = problem(report, id(t2, "id"));
            assertThat(p2.get("expected").asLong()).isEqualTo(id(t2, "amount"));
            assertThat(p2.get("actual").asLong()).isZero();
            JsonNode pz = problem(report, walletZ);
            assertThat(pz.get("expected").asLong()).isEqualTo(originalBalanceZ);
            assertThat(pz.get("actual").asLong()).isEqualTo(originalBalanceZ + 12345);

            // The symptoms were seen and counted, not reported as separate problems.
            assertThat(report.get("stats").get("check3_stored_balance_mismatches").asInt()).isEqualTo(3);
            assertThat(report.get("stats").get("symptoms_suppressed").asInt()).isGreaterThanOrEqualTo(3);
        } finally {
            // ---- Repair, so the rest of the suite sees clean books ----
            jdbc.execute("ALTER TABLE ledger_entries DISABLE TRIGGER trg_ledger_entries_immutable");
            try {
                jdbc.update("UPDATE ledger_entries SET amount = amount - 777 WHERE id = ?", t1.get("debit_entry_id"));
            } finally {
                jdbc.execute("ALTER TABLE ledger_entries ENABLE TRIGGER trg_ledger_entries_immutable");
            }
            jdbc.update("INSERT INTO ledger_entries (transfer_id, account_id, direction, amount, created_at) VALUES (?, ?, 'C', ?, ?)",
                    t2.get("id"), t2.get("account_id"), t2.get("amount"), t2.get("created_at"));
            jdbc.update("UPDATE accounts SET balance = ? WHERE id = ?", originalBalanceZ, walletZ);
        }

        JsonNode repaired = runToCompletion();
        assertThat(repaired.get("status").asText()).as(repaired.toString()).isEqualTo("PASSED");
    }

    @Test
    void aRunIsRefusedWhileAnotherHoldsTheLock() {
        try (JobLocks.Held held = jobLocks.tryAcquire(ReconciliationService.JOB_NAME).orElseThrow()) {
            assertThat(jobLocks.tryAcquire(ReconciliationService.JOB_NAME)).isEmpty();
            Resp r = post("/admin/reconciliation/run", adminId, key(), Map.of());
            assertThat(r.status()).isEqualTo(409);
            assertThat(r.body().get("code").asText()).isEqualTo("JOB_ALREADY_RUNNING");
        }
        JsonNode after = runToCompletion();
        assertThat(after.get("status").asText()).isIn("PASSED", "FAILED");
    }

    @Test
    void onlyAdminsCanRunIt() {
        long user = createUser("Not Admin");
        assertThat(post("/admin/reconciliation/run", user, key(), Map.of()).status()).isEqualTo(403);
    }

    /** POST /admin/reconciliation/run (202) and poll GET /admin/reconciliation/runs/{id} until it finishes. */
    private JsonNode runToCompletion() {
        Resp started = post("/admin/reconciliation/run", adminId, key(), Map.of());
        assertThat(started.status()).as(started.raw()).isEqualTo(202);
        long runId = started.body().get("run_id").asLong();
        Instant deadline = Instant.now().plus(Duration.ofMinutes(2));
        while (Instant.now().isBefore(deadline)) {
            Resp r = get("/admin/reconciliation/runs/" + runId, adminId);
            if (!"RUNNING".equals(r.body().get("status").asText())) {
                return r.body();
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("Reconciliation run " + runId + " did not finish");
    }

    private static JsonNode problem(JsonNode report, long entityId) {
        for (JsonNode p : report.get("problems")) {
            if (p.get("entity_id").asLong() == entityId) {
                return p;
            }
        }
        throw new AssertionError("No problem for " + entityId);
    }

    private static long id(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).longValue();
    }
}
