package com.walletledger.recon;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletledger.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Proves the books are right. Reports problems; never fixes them.
 *
 * <p>All checks run in ONE REPEATABLE READ transaction: they all read the same snapshot, so a transfer
 * committed halfway through the run cannot make check 1 and check 3 disagree with each other.
 *
 * <p>Problems are reported by root cause. A corrupted entry makes its transfer unbalanced (check 2) AND
 * makes its account's stored balance disagree with the ledger (check 3) AND makes the global totals
 * disagree (check 1). The transfer is the cause; the other two are symptoms. Account and total findings
 * are only reported when what is left after subtracting the known-bad transfers' effect is still wrong.
 * Raw counts for every check, symptoms included, are kept in the run's stats.
 */
@Service
public class ReconciliationService {

    public static final String JOB_NAME = "nightly_reconciliation";
    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final JobLocks jobLocks;
    private final TaskExecutor executor;
    private final TransactionTemplate repeatableRead;

    public ReconciliationService(JdbcTemplate jdbc, ObjectMapper mapper, JobLocks jobLocks,
                                 @Qualifier("applicationTaskExecutor") TaskExecutor executor,
                                 PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.jobLocks = jobLocks;
        this.executor = executor;
        this.repeatableRead = new TransactionTemplate(txManager);
        this.repeatableRead.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.repeatableRead.setName("reconciliation");
    }

    public record Problem(String checkName, String entityType, Long entityId, Long expected, Long actual,
                          Map<String, Object> details) {
    }

    public record ProblemView(long problemId, String checkName, String entityType, Long entityId, Long expected,
                              Long actual, JsonNode details) {
    }

    public record RunReport(long runId, String trigger, String status, OffsetDateTime startedAt,
                            OffsetDateTime finishedAt, Integer problemCount, JsonNode stats, String error,
                            List<ProblemView> problems) {
    }

    // -----------------------------------------------------------------------------------------
    // Entry points
    // -----------------------------------------------------------------------------------------

    /** Manual run: takes the lock now (409 if busy), then runs the checks in the background. */
    public RunReport startManualRun() {
        JobLocks.Held lock = jobLocks.tryAcquire(JOB_NAME).orElseThrow(() ->
                ApiException.conflict("JOB_ALREADY_RUNNING", "A reconciliation run is already in progress"));
        long runId;
        try {
            runId = createRun("MANUAL");
        } catch (RuntimeException e) {
            lock.close();
            throw e;
        }
        executor.execute(() -> {
            try (lock) {
                runChecks(runId);
            }
        });
        return report(runId);
    }

    /** Inserts a RUNNING row (committed straight away, so it can be polled). */
    public long createRun(String trigger) {
        return jdbc.queryForObject("INSERT INTO reconciliation_runs (trigger, status) VALUES (?, 'RUNNING') RETURNING id",
                Long.class, trigger);
    }

    /** Runs every check. The caller must hold the job lock. */
    public void runChecks(long runId) {
        try {
            repeatableRead.executeWithoutResult(status -> checkAndRecord(runId));
        } catch (RuntimeException e) {
            log.error("Reconciliation run {} crashed", runId, e);
            jdbc.update("""
                    UPDATE reconciliation_runs
                       SET status = 'ERROR', finished_at = clock_timestamp(), error = ?
                     WHERE id = ?
                    """, String.valueOf(e.getMessage()), runId);
        }
    }

    public RunReport report(long runId) {
        List<RunReport> runs = jdbc.query("""
                SELECT id, trigger, status, started_at, finished_at, problem_count, stats::text AS stats, error
                  FROM reconciliation_runs WHERE id = ?
                """, (rs, n) -> new RunReport(rs.getLong("id"), rs.getString("trigger"), rs.getString("status"),
                rs.getObject("started_at", OffsetDateTime.class), rs.getObject("finished_at", OffsetDateTime.class),
                (Integer) rs.getObject("problem_count", Integer.class), readJson(rs.getString("stats")),
                rs.getString("error"), problems(runId)), runId);
        if (runs.isEmpty()) {
            throw ApiException.notFound("RUN_NOT_FOUND", "Reconciliation run " + runId + " does not exist");
        }
        return runs.get(0);
    }

    // -----------------------------------------------------------------------------------------
    // The checks (inside the REPEATABLE READ transaction)
    // -----------------------------------------------------------------------------------------

    private record Totals(long debits, long credits) {
    }

    private record UnbalancedTransfer(long transferId, String status, long amount, long debits, long credits) {
    }

    private record AccountBalance(long accountId, String kind, long stored, long computed) {
    }

    private void checkAndRecord(long runId) {
        // Check 1: everything balances.
        Totals totals = jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount) FILTER (WHERE direction = 'D'), 0) AS debits,
                       COALESCE(SUM(amount) FILTER (WHERE direction = 'C'), 0) AS credits
                  FROM ledger_entries
                """, (rs, n) -> new Totals(rs.getLong("debits"), rs.getLong("credits")));

        // Check 2: every transfer balances (and matches its own amount).
        List<UnbalancedTransfer> unbalanced = jdbc.query("""
                SELECT t.id, t.status, t.amount,
                       COALESCE(SUM(e.amount) FILTER (WHERE e.direction = 'D'), 0) AS debits,
                       COALESCE(SUM(e.amount) FILTER (WHERE e.direction = 'C'), 0) AS credits
                  FROM ledger_entries e
                  JOIN transfers t ON t.id = e.transfer_id
                 GROUP BY t.id
                HAVING COALESCE(SUM(e.amount) FILTER (WHERE e.direction = 'D'), 0)
                    <> COALESCE(SUM(e.amount) FILTER (WHERE e.direction = 'C'), 0)
                    OR COALESCE(SUM(e.amount) FILTER (WHERE e.direction = 'D'), 0) <> t.amount
                 ORDER BY t.id
                """, (rs, n) -> new UnbalancedTransfer(rs.getLong("id"), rs.getString("status"), rs.getLong("amount"),
                rs.getLong("debits"), rs.getLong("credits")));

        // Check 3: stored balances match the ledger.
        String computedCte = """
                WITH computed AS (
                    SELECT account_id, SUM(CASE WHEN direction = 'C' THEN amount ELSE -amount END) AS balance
                      FROM ledger_entries
                     GROUP BY account_id)
                """;
        List<AccountBalance> mismatched = jdbc.query(computedCte + """
                SELECT a.id, a.kind, a.balance AS stored, COALESCE(c.balance, 0) AS computed
                  FROM accounts a
                  LEFT JOIN computed c ON c.account_id = a.id
                 WHERE a.balance <> COALESCE(c.balance, 0)
                 ORDER BY a.id
                """, this::accountBalance);

        // Check 4: no user wallet's ledger balance is negative.
        List<AccountBalance> negative = jdbc.query(computedCte + """
                SELECT a.id, a.kind, a.balance AS stored, c.balance AS computed
                  FROM computed c
                  JOIN accounts a ON a.id = c.account_id
                 WHERE a.kind = 'USER_WALLET' AND c.balance < 0
                 ORDER BY a.id
                """, this::accountBalance);

        // Check 5: nothing is orphaned.
        List<Long> postedWithoutEntries = jdbc.queryForList("""
                SELECT t.id FROM transfers t
                 WHERE t.status IN ('POSTED', 'REVERSED')
                   AND NOT EXISTS (SELECT 1 FROM ledger_entries e WHERE e.transfer_id = t.id)
                 ORDER BY t.id
                """, Long.class);
        List<Long> unpostedWithEntries = jdbc.queryForList("""
                SELECT t.id FROM transfers t
                 WHERE t.status IN ('HELD', 'REJECTED')
                   AND EXISTS (SELECT 1 FROM ledger_entries e WHERE e.transfer_id = t.id)
                 ORDER BY t.id
                """, Long.class);
        List<Map<String, Object>> entriesWithoutTransfer = jdbc.queryForList("""
                SELECT e.id, e.transfer_id, e.account_id,
                       CASE WHEN e.direction = 'C' THEN e.amount ELSE -e.amount END AS signed_amount
                  FROM ledger_entries e
                 WHERE NOT EXISTS (SELECT 1 FROM transfers t WHERE t.id = e.transfer_id)
                 ORDER BY e.id
                """);

        // ---- Root causes: transfer-level and entry-level problems ----
        List<Problem> problems = new ArrayList<>();
        for (UnbalancedTransfer t : unbalanced) {
            problems.add(new Problem("TRANSFER_UNBALANCED", "transfer", t.transferId(), t.debits(), t.credits(),
                    ordered("amount", t.amount(), "debits", t.debits(), "credits", t.credits(), "status", t.status())));
        }
        for (Long id : postedWithoutEntries) {
            problems.add(new Problem("TRANSFER_WITHOUT_ENTRIES", "transfer", id, 2L, 0L, Map.of()));
        }
        for (Long id : unpostedWithEntries) {
            problems.add(new Problem("ENTRIES_ON_UNPOSTED_TRANSFER", "transfer", id, 0L, null, Map.of()));
        }
        for (Map<String, Object> e : entriesWithoutTransfer) {
            problems.add(new Problem("ENTRY_WITHOUT_TRANSFER", "ledger_entry", ((Number) e.get("id")).longValue(),
                    null, null, ordered("transfer_id", e.get("transfer_id"), "account_id", e.get("account_id"))));
        }

        // How much each bad transfer / orphan entry moved each account, relative to what its header says.
        Set<Long> badTransfers = new TreeSet<>();
        unbalanced.forEach(t -> badTransfers.add(t.transferId()));
        badTransfers.addAll(postedWithoutEntries);
        badTransfers.addAll(unpostedWithEntries);
        Map<Long, Long> explainedDelta = explainedAccountDeltas(badTransfers);
        long explainedImbalance = unbalanced.stream().mapToLong(t -> t.debits() - t.credits()).sum();
        for (Map<String, Object> e : entriesWithoutTransfer) {
            long signed = ((Number) e.get("signed_amount")).longValue();
            explainedDelta.merge(((Number) e.get("account_id")).longValue(), signed, Long::sum);
            explainedImbalance += -signed; // a credit (+) lowers debits - credits
        }

        // ---- Check 1 verdict ----
        long imbalance = totals.debits() - totals.credits();
        int symptoms = 0;
        if (imbalance != explainedImbalance) {
            problems.add(new Problem("LEDGER_TOTALS_UNBALANCED", null, null, totals.debits(), totals.credits(),
                    ordered("unexplained_difference", imbalance - explainedImbalance)));
        } else if (imbalance != 0) {
            symptoms++;
        }

        // ---- Check 3 and 4 verdicts, per account, after removing the known-bad transfers' effect ----
        Set<Long> accountsToJudge = new HashSet<>(explainedDelta.keySet());
        mismatched.forEach(a -> accountsToJudge.add(a.accountId()));
        negative.forEach(a -> accountsToJudge.add(a.accountId()));
        for (AccountBalance a : balancesOf(accountsToJudge)) {
            long delta = explainedDelta.getOrDefault(a.accountId(), 0L);
            long ledgerWithoutBadEntries = a.computed() - delta;
            if (a.stored() != ledgerWithoutBadEntries) {
                problems.add(new Problem("STORED_BALANCE_MISMATCH", "account", a.accountId(),
                        ledgerWithoutBadEntries, a.stored(),
                        ordered("computed_from_ledger", a.computed(), "explained_by_bad_transfers", delta)));
            } else if (a.stored() != a.computed()) {
                symptoms++;
            }
            if (a.kind().equals("USER_WALLET") && a.computed() < 0) {
                if (ledgerWithoutBadEntries < 0) {
                    problems.add(new Problem("NEGATIVE_WALLET_BALANCE", "account", a.accountId(), 0L, a.computed(),
                            ordered("computed_without_bad_entries", ledgerWithoutBadEntries)));
                } else {
                    symptoms++;
                }
            }
        }

        // ---- Record the run ----
        for (Problem p : problems) {
            jdbc.update("""
                    INSERT INTO reconciliation_problems (run_id, check_name, entity_type, entity_id, expected, actual, details)
                    VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)
                    """, runId, p.checkName(), p.entityType(), p.entityId(), p.expected(), p.actual(), toJson(p.details()));
        }
        Map<String, Object> stats = ordered(
                "total_debits", totals.debits(),
                "total_credits", totals.credits(),
                "check1_ledger_imbalance", imbalance,
                "check2_unbalanced_transfers", unbalanced.size(),
                "check3_stored_balance_mismatches", mismatched.size(),
                "check4_negative_wallets", negative.size(),
                "check5_posted_without_entries", postedWithoutEntries.size(),
                "check5_unposted_with_entries", unpostedWithEntries.size(),
                "check5_entries_without_transfer", entriesWithoutTransfer.size(),
                "symptoms_suppressed", symptoms);
        jdbc.update("""
                UPDATE reconciliation_runs
                   SET status = ?, finished_at = clock_timestamp(), problem_count = ?, stats = ?::jsonb
                 WHERE id = ?
                """, problems.isEmpty() ? "PASSED" : "FAILED", problems.size(), toJson(stats), runId);
        log.info("Reconciliation run {} finished: {} problem(s), stats {}", runId, problems.size(), stats);
    }

    /** For each bad transfer: (actual signed entries per account) - (what its header says they should be). */
    private Map<Long, Long> explainedAccountDeltas(Set<Long> transferIds) {
        Map<Long, Long> delta = new TreeMap<>();
        if (transferIds.isEmpty()) {
            return delta;
        }
        Long[] ids = transferIds.toArray(Long[]::new);
        jdbc.query("""
                SELECT account_id, SUM(CASE WHEN direction = 'C' THEN amount ELSE -amount END) AS actual
                  FROM ledger_entries
                 WHERE transfer_id = ANY (?)
                 GROUP BY account_id
                """, rs -> {
            delta.merge(rs.getLong("account_id"), rs.getLong("actual"), Long::sum);
        }, (Object) ids);
        jdbc.query("""
                SELECT from_account_id, to_account_id, amount
                  FROM transfers
                 WHERE id = ANY (?) AND status IN ('POSTED', 'REVERSED')
                """, rs -> {
            delta.merge(rs.getLong("from_account_id"), rs.getLong("amount"), Long::sum);   // expected -amount
            delta.merge(rs.getLong("to_account_id"), -rs.getLong("amount"), Long::sum);    // expected +amount
        }, (Object) ids);
        return delta;
    }

    private List<AccountBalance> balancesOf(Set<Long> accountIds) {
        if (accountIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                SELECT a.id, a.kind, a.balance AS stored,
                       COALESCE(SUM(CASE WHEN e.direction = 'C' THEN e.amount ELSE -e.amount END), 0) AS computed
                  FROM accounts a
                  LEFT JOIN ledger_entries e ON e.account_id = a.id
                 WHERE a.id = ANY (?)
                 GROUP BY a.id
                 ORDER BY a.id
                """, this::accountBalance, (Object) accountIds.toArray(Long[]::new));
    }

    private AccountBalance accountBalance(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new AccountBalance(rs.getLong("id"), rs.getString("kind"), rs.getLong("stored"), rs.getLong("computed"));
    }

    private List<ProblemView> problems(long runId) {
        return jdbc.query("""
                SELECT id, check_name, entity_type, entity_id, expected, actual, details::text AS details
                  FROM reconciliation_problems WHERE run_id = ? ORDER BY id
                """, (rs, n) -> new ProblemView(rs.getLong("id"), rs.getString("check_name"), rs.getString("entity_type"),
                (Long) rs.getObject("entity_id", Long.class), (Long) rs.getObject("expected", Long.class),
                (Long) rs.getObject("actual", Long.class), readJson(rs.getString("details"))), runId);
    }

    private static Map<String, Object> ordered(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode readJson(String json) {
        if (json == null) {
            return null;
        }
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
