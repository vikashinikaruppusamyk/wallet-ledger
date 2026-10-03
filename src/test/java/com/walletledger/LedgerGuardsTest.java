package com.walletledger;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Milestone 1: the database itself refuses invalid money states, whatever the application does. */
class LedgerGuardsTest extends IntegrationTest {

    private long postedTransfer() {
        long u1 = verifiedUser("Guard One");
        long u2 = verifiedUser("Guard Two");
        long w1 = openWallet(u1, "main");
        long w2 = openWallet(u2, "main");
        topUp(u1, w1, 10_000);
        return transfer(u1, w1, w2, 1_000, null).body().get("transfer_id").asLong();
    }

    private void inTx(Runnable sql) {
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            jdbc.queryForList("SELECT set_config('wallet.actor', 'system:test', true)");
            sql.run();
        });
    }

    @Test
    void ledgerEntriesCannotBeUpdatedOrDeleted() {
        long t = postedTransfer();
        assertThatThrownBy(() -> jdbc.update("UPDATE ledger_entries SET amount = 1 WHERE transfer_id = ?", t))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM ledger_entries WHERE transfer_id = ?", t))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE ledger_entries CASCADE"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void unbalancedEntriesAreRejected() {
        long t = postedTransfer();
        long cashIn = cashInAccount();
        // A lone extra debit on an existing transfer breaks debits = credits = amount.
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO ledger_entries (transfer_id, account_id, direction, amount) VALUES (?, ?, 'D', 5)", t, cashIn))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("invalid");
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO ledger_entries (transfer_id, account_id, direction, amount) VALUES (?, ?, 'D', -5), (?, ?, 'C', -5)",
                t, cashIn, t, cashIn))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void walletBalanceCannotGoNegativeButSystemAccountsCan() {
        long u = verifiedUser("Guard Negative");
        long w = openWallet(u, "main");
        assertThatThrownBy(() -> jdbc.update("UPDATE accounts SET balance = -1 WHERE id = ?", w))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_accounts_wallet_non_negative");
        assertThat(jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", Long.class, cashInAccount())).isNegative();
    }

    @Test
    void transfersOnlyChangeStatusAlongAllowedPathsAndAreAudited() {
        long t = postedTransfer();
        assertThatThrownBy(() -> inTx(() -> jdbc.update("UPDATE transfers SET amount = 1 WHERE id = ?", t)))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("only status may change");
        assertThatThrownBy(() -> inTx(() -> jdbc.update("UPDATE transfers SET status = 'HELD' WHERE id = ?", t)))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("illegal status change");
        assertThatThrownBy(() -> inTx(() -> jdbc.update("DELETE FROM transfers WHERE id = ?", t)))
                .isInstanceOf(DataAccessException.class);
        // No actor, no status change.
        assertThatThrownBy(() -> jdbc.update("UPDATE transfers SET status = 'REVERSED' WHERE id = ?", t))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("wallet.actor must be set");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM transfer_status_history WHERE transfer_id = ?", Long.class, t)).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM transfer_status_history WHERE transfer_id = ?", t))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void moneyIsWholePaise() {
        long u = verifiedUser("Guard Paise");
        long w = openWallet(u, "main");
        Resp fractional = post("/transfers/topup", u, key(), "{\"wallet_id\":" + w + ",\"amount\":10.5}");
        assertThat(fractional.status()).isEqualTo(400);
        assertThat(fractional.body().get("code").asText()).isEqualTo("VALIDATION_FAILED");
        Resp asString = post("/transfers/topup", u, key(), "{\"wallet_id\":" + w + ",\"amount\":\"100\"}");
        assertThat(asString.status()).isEqualTo(400);
        Resp zero = post("/transfers/topup", u, key(), "{\"wallet_id\":" + w + ",\"amount\":0}");
        assertThat(zero.status()).isEqualTo(400);
        assertThat(balance(w)).isZero();
    }
}
