package com.walletledger.lab;

import com.walletledger.IntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Milestone 2, step 2: "break it first". The textbook transfer - read the balance, check it in code,
 * write the new balance - with no row locks, fired 100 times in parallel at a ₹500 wallet.
 * Compare with StampedeTest, which runs the same load through the real (locked) transfer.
 *
 * <pre>./mvnw test -Dlab.exclude=none -Dgroups=lab -Dtest=NaiveTransferLabTest</pre>
 */
@Tag("lab")
class NaiveTransferLabTest extends IntegrationTest {

    private static final long START = 50_000;   // ₹500
    private static final long AMOUNT = 1_000;   // ₹10

    private final StringBuilder out = new StringBuilder();

    @Test
    void naiveTransfersOverdrawAndLoseUpdates() throws Exception {
        out.append("Naive transfer lab: 100 concurrent ₹10 transfers from a ₹500 wallet, no row locks\n");
        variant("A. read balance, check in code, SET balance = <value computed in Java>",
                "UPDATE accounts SET balance = ? WHERE id = ?", true);
        variant("B. read balance, check in code, SET balance = balance - amount (atomic decrement)",
                "UPDATE accounts SET balance = balance - ? WHERE id = ?", false);

        Path file = Path.of("target", "naive-lab.txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, out.toString());
        System.out.println(out);
    }

    private void variant(String title, String debitSql, boolean absolute) throws Exception {
        long sender = verifiedUser("Naive Sender");
        long receiver = verifiedUser("Naive Receiver");
        long from = openWallet(sender, "main");
        long to = openWallet(receiver, "main");
        topUp(sender, from, START);

        TransactionTemplate tx = new TransactionTemplate(txManager);
        ExecutorService pool = Executors.newFixedThreadPool(100);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            results.add(pool.submit(() -> {
                gate.await();
                try {
                    return tx.execute(s -> {
                        jdbc.queryForList("SELECT set_config('wallet.actor', 'system:naive-lab', true)");
                        // 1. Read the balance - no FOR UPDATE.
                        long balance = jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", Long.class, from);
                        // 2. Check it in application code.
                        if (balance < AMOUNT) {
                            return "rejected";
                        }
                        sleepQuietly(); // a little application work between the read and the write
                        // 3. Write.
                        long id = jdbc.queryForObject("""
                                INSERT INTO transfers (type, status, amount, from_account_id, to_account_id, initiated_by)
                                VALUES ('PEER_TRANSFER', 'POSTED', ?, ?, ?, ?) RETURNING id
                                """, Long.class, AMOUNT, from, to, sender);
                        jdbc.update("INSERT INTO ledger_entries (transfer_id, account_id, direction, amount) VALUES (?, ?, 'D', ?), (?, ?, 'C', ?)",
                                id, from, AMOUNT, id, to, AMOUNT);
                        jdbc.update(debitSql, absolute ? balance - AMOUNT : AMOUNT, from);
                        jdbc.update("UPDATE accounts SET balance = balance + ? WHERE id = ?", AMOUNT, to);
                        return "succeeded";
                    });
                } catch (RuntimeException e) {
                    return "failed: " + e.getClass().getSimpleName();
                }
            }));
        }
        gate.countDown();
        long ok = 0;
        long rejected = 0;
        long failed = 0;
        String failure = "";
        for (Future<String> f : results) {
            String r = f.get();
            if (r.equals("succeeded")) {
                ok++;
            } else if (r.equals("rejected")) {
                rejected++;
            } else {
                failed++;
                failure = r;
            }
        }
        pool.shutdown();

        long stored = balance(from);
        long ledger = ledgerBalance(from);
        long receiverStored = balance(to);
        out.append("\n").append(title).append("\n")
                .append("  transfers that succeeded:          ").append(ok).append("   (correct answer: 50)\n")
                .append("  rejected by the code's check:      ").append(rejected).append("\n")
                .append("  failed in the database:            ").append(failed).append(failed > 0 ? "   (" + failure + ")" : "").append("\n")
                .append("  sender stored balance:             ").append(stored).append(" paise\n")
                .append("  sender balance from the ledger:    ").append(ledger).append(" paise   (correct answer: 0)\n")
                .append("  receiver stored balance:           ").append(receiverStored).append(" paise\n")
                .append("  money created out of nothing:      ").append(stored + receiverStored - START).append(" paise\n");
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(2);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
