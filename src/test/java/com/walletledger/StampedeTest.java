package com.walletledger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.TestInstance;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Milestone 2 acceptance test: ₹500 in one wallet, 100 concurrent ₹10 transfers out of it.
 * Exactly 50 succeed, the wallet ends at ₹0 and never goes negative, debits equal credits.
 * Repeated 20 times - a race that fails once in 20 runs is still a bug.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StampedeTest extends IntegrationTest {

    private static final int REQUESTS = 100;
    private static final long START_BALANCE = 50_000;  // ₹500
    private static final long AMOUNT = 1_000;          // ₹10

    @BeforeAll
    void disableVelocityRule() {
        // 100 transfers from one wallet in a second is exactly what VELOCITY is meant to hold;
        // this test is about locking, so the rule is switched off while it runs.
        setRuleEnabled("VELOCITY", false);
    }

    @AfterAll
    void restoreVelocityRule() {
        setRuleEnabled("VELOCITY", true);
    }

    @RepeatedTest(20)
    void hundredConcurrentTransfersFromOneWallet() throws Exception {
        long asha = verifiedUser("Asha Stampede");
        long rahul = verifiedUser("Rahul Stampede");
        long from = openWallet(asha, "main");
        long to = openWallet(rahul, "main");
        topUp(asha, from, START_BALANCE);

        ExecutorService pool = Executors.newFixedThreadPool(REQUESTS);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Resp>> futures = new ArrayList<>();
        for (int i = 0; i < REQUESTS; i++) {
            futures.add(pool.submit(() -> {
                gate.await();
                return transfer(asha, from, to, AMOUNT, "stampede");
            }));
        }
        gate.countDown(); // release all 100 at once
        int created = 0;
        int insufficient = 0;
        List<String> unexpected = new ArrayList<>();
        for (Future<Resp> f : futures) {
            Resp r = f.get();
            if (r.status() == 201) {
                created++;
            } else if (r.status() == 422 && "INSUFFICIENT_FUNDS".equals(r.body().get("code").asText())) {
                insufficient++;
            } else {
                unexpected.add(r.status() + " " + r.raw());
            }
        }
        pool.shutdown();

        assertThat(unexpected).isEmpty();
        assertThat(created).isEqualTo(50);
        assertThat(insufficient).isEqualTo(50);

        // Stored and ledger-computed balances agree: ₹0 and ₹500.
        assertThat(balance(from)).isZero();
        assertThat(ledgerBalance(from)).isZero();
        assertThat(balance(to)).isEqualTo(START_BALANCE);
        assertThat(ledgerBalance(to)).isEqualTo(START_BALANCE);

        // Never negative: replay the wallet's entries in commit order and check every running balance.
        List<Long> signed = jdbc.queryForList("""
                SELECT CASE WHEN direction = 'C' THEN amount ELSE -amount END
                  FROM ledger_entries WHERE account_id = ? ORDER BY id
                """, Long.class, from);
        long running = 0;
        for (long s : signed) {
            running += s;
            assertThat(running).isGreaterThanOrEqualTo(0);
        }

        // Exactly 50 posted transfers out of the wallet, each with balanced entries.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM transfers WHERE from_account_id = ? AND type = 'PEER_TRANSFER' AND status = 'POSTED'",
                Long.class, from)).isEqualTo(50);

        // The whole ledger still balances.
        assertThat(jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount) FILTER (WHERE direction = 'D'), 0)
                     - COALESCE(SUM(amount) FILTER (WHERE direction = 'C'), 0)
                  FROM ledger_entries
                """, Long.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM (
                    SELECT transfer_id FROM ledger_entries GROUP BY transfer_id
                    HAVING SUM(amount) FILTER (WHERE direction = 'D') <> SUM(amount) FILTER (WHERE direction = 'C')) x
                """, Long.class)).isZero();
    }
}
