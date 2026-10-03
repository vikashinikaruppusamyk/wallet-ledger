package com.walletledger.transfer;

import com.walletledger.common.ApiException;
import com.walletledger.common.ApiResult;
import com.walletledger.common.Caller;
import com.walletledger.risk.RiskEngine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.LongStream;

/**
 * The money path. Every public method runs inside the READ COMMITTED transaction
 * opened by {@link com.walletledger.idempotency.IdempotencyService#executeInTransaction},
 * right after the idempotency key row was inserted; the transaction commits when
 * that method returns.
 *
 * <p>Locking protocol (the same in every method, so two transfers can never wait on each other in a cycle):
 * <ol>
 *   <li>existing transfer rows, by id ({@code SELECT ... FOR UPDATE}) - only for release / reject / reverse</li>
 *   <li>user wallets, in ascending account id ({@code SELECT ... FOR UPDATE})</li>
 *   <li>system accounts, in ascending account id, implicitly by the final balance {@code UPDATE}</li>
 * </ol>
 * System accounts take part in almost every transfer, so they are locked last and held for the
 * shortest possible time. Their balances are never checked, so they need no read lock.
 */
@Service
public class TransferService {

    private final JdbcTemplate jdbc;
    private final RiskEngine riskEngine;
    private final SystemAccounts system;
    private final TransferQueries queries;
    private final long withdrawalFeePaise;

    public TransferService(JdbcTemplate jdbc, RiskEngine riskEngine, SystemAccounts system, TransferQueries queries,
                           @Value("${wallet.withdrawal-fee-paise}") long withdrawalFeePaise) {
        this.jdbc = jdbc;
        this.riskEngine = riskEngine;
        this.system = system;
        this.queries = queries;
        this.withdrawalFeePaise = withdrawalFeePaise;
    }

    /** A user wallet row read under FOR UPDATE: the balance cannot change until we commit. */
    record LockedWallet(long id, long userId, long balance) {
    }

    /** An existing transfer row read under FOR UPDATE. */
    record LockedTransfer(long id, String type, String status, long amount, long fromAccountId, long toAccountId,
                          Long parentTransferId) {
    }

    // =========================================================================================
    // Top-up: CASH_IN -> wallet
    // =========================================================================================

    public ApiResult topUp(Caller caller, long walletId, long amount, String note) {
        requireTransaction();
        LockedWallet wallet = lockWallets(walletId).get(walletId);
        requireOwner(caller, wallet);

        long transferId = insertTransfer("TOP_UP", "POSTED", amount, system.cashIn(), walletId, caller, null, null, note);
        Map<Long, Long> deltas = new TreeMap<>();
        writeLegs(transferId, system.cashIn(), walletId, amount, deltas);
        applyBalances(deltas);
        return ApiResult.of(HttpStatus.CREATED.value(), queries.find(transferId));
    }

    // =========================================================================================
    // Peer transfer: wallet -> wallet
    // =========================================================================================

    public ApiResult peerTransfer(Caller caller, long fromWalletId, long toWalletId, long amount, String note) {
        requireTransaction();
        if (fromWalletId == toWalletId) {
            throw ApiException.unprocessable("SAME_WALLET", "Cannot transfer a wallet to itself");
        }

        // 1. Lock both wallets, lowest id first.
        Map<Long, LockedWallet> wallets = lockWallets(fromWalletId, toWalletId);
        LockedWallet from = wallets.get(fromWalletId);
        requireOwner(caller, from);

        // 2. Re-check the balance while holding the lock.
        if (from.balance() < amount) {
            throw insufficientFunds(from.balance(), amount);
        }

        // 3. Fraud rules, before anything is written.
        RiskEngine.Decision risk = riskEngine.evaluate("PEER_TRANSFER", fromWalletId, from.userId(), toWalletId, amount);
        String status = risk.hold() ? "HELD" : "POSTED";

        // 4. The transfer, its flags, and - unless held - its balanced entries and new balances.
        long transferId = insertTransfer("PEER_TRANSFER", status, amount, fromWalletId, toWalletId, caller, null, null, note);
        riskEngine.recordFlags(transferId, risk);
        if (status.equals("POSTED")) {
            Map<Long, Long> deltas = new TreeMap<>();
            writeLegs(transferId, fromWalletId, toWalletId, amount, deltas);
            applyBalances(deltas);
        }
        int http = status.equals("HELD") ? HttpStatus.ACCEPTED.value() : HttpStatus.CREATED.value();
        return ApiResult.of(http, queries.find(transferId));
    }

    // =========================================================================================
    // Withdrawal: wallet -> CASH_OUT, plus a FEE transfer wallet -> FEE_REVENUE
    // =========================================================================================

    public ApiResult withdraw(Caller caller, long walletId, long amount) {
        requireTransaction();
        LockedWallet wallet = lockWallets(walletId).get(walletId);
        requireOwner(caller, wallet);

        long fee = withdrawalFeePaise;
        if (wallet.balance() < amount + fee) {
            throw insufficientFunds(wallet.balance(), amount + fee);
        }

        RiskEngine.Decision risk = riskEngine.evaluate("WITHDRAWAL", walletId, wallet.userId(), system.cashOut(), amount);
        String status = risk.hold() ? "HELD" : "POSTED";

        long transferId = insertTransfer("WITHDRAWAL", status, amount, walletId, system.cashOut(), caller, null, null, null);
        Long feeTransferId = fee > 0
                ? insertTransfer("FEE", status, fee, walletId, system.feeRevenue(), caller, null, transferId, "Withdrawal fee")
                : null;
        riskEngine.recordFlags(transferId, risk);

        if (status.equals("POSTED")) {
            Map<Long, Long> deltas = new TreeMap<>();
            writeLegs(transferId, walletId, system.cashOut(), amount, deltas);
            if (feeTransferId != null) {
                writeLegs(feeTransferId, walletId, system.feeRevenue(), fee, deltas);
            }
            applyBalances(deltas);
        }
        int http = status.equals("HELD") ? HttpStatus.ACCEPTED.value() : HttpStatus.CREATED.value();
        return ApiResult.of(http, queries.find(transferId));
    }

    // =========================================================================================
    // Reversal (admin): a new REVERSAL transfer per posted transfer in the group
    // =========================================================================================

    public ApiResult reverse(Caller admin, long transferId, String reason) {
        requireTransaction();
        admin.requireAdmin();

        List<LockedTransfer> group = lockTransferGroup(transferId);
        LockedTransfer main = group.get(0);
        if (main.status().equals("REVERSED")) {
            throw ApiException.conflict("ALREADY_REVERSED", "Transfer " + transferId + " has already been reversed");
        }
        if (!main.status().equals("POSTED") || main.type().equals("REVERSAL")) {
            throw ApiException.conflict("NOT_REVERSIBLE",
                    "Only a posted, non-reversal transfer can be reversed (this one is " + main.type() + "/" + main.status() + ")");
        }
        List<LockedTransfer> toReverse = group.stream().filter(t -> t.status().equals("POSTED")).toList();

        // Money flows back: each original recipient is debited. Lock the user wallets involved, then
        // make sure every wallet that gets debited can cover it.
        long[] walletIds = toReverse.stream()
                .flatMapToLong(t -> LongStream.of(t.fromAccountId(), t.toAccountId()))
                .filter(id -> !system.isSystem(id)).toArray();
        Map<Long, LockedWallet> wallets = lockWallets(walletIds);
        Map<Long, Long> needed = new TreeMap<>();
        for (LockedTransfer t : toReverse) {
            if (wallets.containsKey(t.toAccountId())) {
                needed.merge(t.toAccountId(), t.amount(), Long::sum);
            }
        }
        for (var need : needed.entrySet()) {
            long balance = wallets.get(need.getKey()).balance();
            if (balance < need.getValue()) {
                throw ApiException.unprocessable("INSUFFICIENT_FUNDS",
                        "Wallet " + need.getKey() + " holds " + balance + " paise but the reversal needs " + need.getValue());
            }
        }

        setReason(reason);
        Map<Long, Long> deltas = new TreeMap<>();
        Long mainReversalId = null;
        for (LockedTransfer t : toReverse) {
            jdbc.update("UPDATE transfers SET status = 'REVERSED' WHERE id = ?", t.id());
            long reversalId = insertTransfer("REVERSAL", "POSTED", t.amount(), t.toAccountId(), t.fromAccountId(),
                    admin, t.id(), mainReversalId, reason);
            if (mainReversalId == null) {
                mainReversalId = reversalId;
            }
            writeLegs(reversalId, t.toAccountId(), t.fromAccountId(), t.amount(), deltas);
        }
        applyBalances(deltas);
        return ApiResult.of(HttpStatus.CREATED.value(), queries.find(mainReversalId));
    }

    // =========================================================================================
    // Risk decision (admin): RELEASE posts a held transfer, REJECT closes it. No money moved while held.
    // =========================================================================================

    public ApiResult decide(Caller admin, long transferId, String decision, String reason) {
        requireTransaction();
        admin.requireAdmin();

        List<LockedTransfer> group = lockTransferGroup(transferId);
        LockedTransfer main = group.get(0);
        if (!main.status().equals("HELD")) {
            throw ApiException.conflict("NOT_HELD", "Transfer " + transferId + " is " + main.status() + ", not HELD");
        }
        setReason(reason);

        if (decision.equals("REJECT")) {
            for (LockedTransfer t : group) {
                jdbc.update("UPDATE transfers SET status = 'REJECTED' WHERE id = ?", t.id());
            }
            return ApiResult.of(HttpStatus.OK.value(), queries.find(transferId));
        }

        if (!main.type().equals("PEER_TRANSFER") && !main.type().equals("WITHDRAWAL")) {
            throw ApiException.conflict("NOT_RELEASABLE", "Only held peer transfers and withdrawals can be released");
        }
        // RELEASE: lock every user wallet involved (ascending), then re-check the sender's balance,
        // which may have changed while the transfer was held.
        long[] walletIds = main.type().equals("PEER_TRANSFER")
                ? new long[]{main.fromAccountId(), main.toAccountId()}
                : new long[]{main.fromAccountId()};
        LockedWallet sender = lockWallets(walletIds).get(main.fromAccountId());
        long total = group.stream().mapToLong(LockedTransfer::amount).sum();
        if (sender.balance() < total) {
            throw insufficientFunds(sender.balance(), total);
        }

        Map<Long, Long> deltas = new TreeMap<>();
        for (LockedTransfer t : group) {
            // Status first: the ledger trigger only accepts entries for POSTED transfers.
            jdbc.update("UPDATE transfers SET status = 'POSTED' WHERE id = ?", t.id());
            writeLegs(t.id(), t.fromAccountId(), t.toAccountId(), t.amount(), deltas);
        }
        applyBalances(deltas);
        return ApiResult.of(HttpStatus.OK.value(), queries.find(transferId));
    }

    // =========================================================================================
    // Building blocks - every statement that touches money is in this section.
    // =========================================================================================

    /** SELECT ... FOR UPDATE on each user wallet, one row at a time, in ascending id order. */
    private Map<Long, LockedWallet> lockWallets(long... walletIds) {
        Map<Long, LockedWallet> locked = new LinkedHashMap<>();
        for (long id : LongStream.of(walletIds).distinct().sorted().toArray()) {
            List<LockedWallet> rows = jdbc.query("""
                    SELECT id, user_id, balance
                      FROM accounts
                     WHERE id = ? AND kind = 'USER_WALLET'
                       FOR UPDATE
                    """, (rs, n) -> new LockedWallet(rs.getLong("id"), rs.getLong("user_id"), rs.getLong("balance")), id);
            if (rows.isEmpty()) {
                throw ApiException.notFound("WALLET_NOT_FOUND", "Wallet " + id + " does not exist");
            }
            locked.put(id, rows.get(0));
        }
        return locked;
    }

    /** Locks a transfer and its child transfers (a withdrawal's fee), parent first. */
    private List<LockedTransfer> lockTransferGroup(long transferId) {
        List<LockedTransfer> group = jdbc.query("""
                SELECT id, type, status, amount, from_account_id, to_account_id, parent_transfer_id
                  FROM transfers
                 WHERE id = ? OR parent_transfer_id = ?
                 ORDER BY id
                   FOR UPDATE
                """, (rs, n) -> new LockedTransfer(rs.getLong("id"), rs.getString("type"), rs.getString("status"),
                rs.getLong("amount"), rs.getLong("from_account_id"), rs.getLong("to_account_id"),
                (Long) rs.getObject("parent_transfer_id", Long.class)), transferId, transferId);
        if (group.isEmpty() || group.get(0).id() != transferId) {
            throw ApiException.notFound("TRANSFER_NOT_FOUND", "Transfer " + transferId + " does not exist");
        }
        if (group.get(0).parentTransferId() != null) {
            throw ApiException.conflict("CHILD_TRANSFER",
                    "Transfer " + transferId + " belongs to transfer " + group.get(0).parentTransferId() + "; act on that one");
        }
        return group;
    }

    private long insertTransfer(String type, String status, long amount, long fromAccountId, long toAccountId,
                                Caller caller, Long reversesTransferId, Long parentTransferId, String note) {
        return jdbc.queryForObject("""
                INSERT INTO transfers (type, status, amount, from_account_id, to_account_id, initiated_by,
                                       reverses_transfer_id, parent_transfer_id, note)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, type, status, amount, fromAccountId, toAccountId, caller.userId(),
                reversesTransferId, parentTransferId, note);
    }

    /**
     * Both legs in ONE insert statement: the ledger trigger checks, at the end of the statement,
     * that debits = credits = transfer amount. The balance change is only accumulated here.
     */
    private void writeLegs(long transferId, long debitAccountId, long creditAccountId, long amount, Map<Long, Long> deltas) {
        jdbc.update("""
                INSERT INTO ledger_entries (transfer_id, account_id, direction, amount)
                VALUES (?, ?, 'D', ?),
                       (?, ?, 'C', ?)
                """, transferId, debitAccountId, amount, transferId, creditAccountId, amount);
        deltas.merge(debitAccountId, -amount, Long::sum);
        deltas.merge(creditAccountId, amount, Long::sum);
    }

    /**
     * Updates cached balances: user wallets first (already locked), then system accounts in ascending
     * id order (this UPDATE is what locks them). The CHECK constraint on accounts is the last line of
     * defence against a negative wallet balance.
     */
    private void applyBalances(Map<Long, Long> deltas) {
        List<Map.Entry<Long, Long>> ordered = new ArrayList<>(deltas.entrySet());
        ordered.sort((a, b) -> {
            boolean aSystem = system.isSystem(a.getKey());
            boolean bSystem = system.isSystem(b.getKey());
            return aSystem != bSystem ? Boolean.compare(aSystem, bSystem) : Long.compare(a.getKey(), b.getKey());
        });
        for (Map.Entry<Long, Long> delta : ordered) {
            if (delta.getValue() != 0) {
                jdbc.update("UPDATE accounts SET balance = balance + ? WHERE id = ?", delta.getValue(), delta.getKey());
            }
        }
    }

    private void setReason(String reason) {
        jdbc.queryForList("SELECT set_config('wallet.reason', ?, true)", reason == null ? "" : reason);
    }

    private static void requireOwner(Caller caller, LockedWallet wallet) {
        if (wallet.userId() != caller.userId()) {
            throw ApiException.forbidden("Wallet " + wallet.id() + " does not belong to you");
        }
    }

    private static ApiException insufficientFunds(long balance, long needed) {
        return ApiException.unprocessable("INSUFFICIENT_FUNDS",
                "Balance is " + balance + " paise; this needs " + needed + " paise");
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Money-path methods must run inside the idempotency transaction");
        }
    }
}
