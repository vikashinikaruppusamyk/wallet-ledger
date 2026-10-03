package com.walletledger;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Milestone 5 part A (fraud rules, held transfers) and reversals, with their audit trail. */
class RiskAndReversalTest extends IntegrationTest {

    private record Setup(long sender, long receiver, long from, long to) {
    }

    private Setup setup(long topUp) {
        long sender = verifiedUser("Asha Risk");
        long receiver = verifiedUser("Rahul Risk");
        long from = openWallet(sender, "main");
        long to = openWallet(receiver, "main");
        topUp(sender, from, topUp);
        return new Setup(sender, receiver, from, to);
    }

    private static List<String> rules(JsonNode transfer) {
        List<String> rules = new ArrayList<>();
        transfer.get("risk_flags").forEach(f -> rules.add(f.get("rule").asText()));
        return rules;
    }

    @Test
    void largeTransferIsHeldAndMovesNoMoneyUntilReleased() {
        Setup s = setup(8_000_000);                         // ₹80,000
        Resp held = transfer(s.sender(), s.from(), s.to(), 6_000_000, "car"); // ₹60,000 >= ₹50,000

        assertThat(held.status()).isEqualTo(202);
        assertThat(held.body().get("status").asText()).isEqualTo("HELD");
        assertThat(rules(held.body())).contains("LARGE_AMOUNT");
        assertThat(held.body().get("entries")).isEmpty();
        assertThat(balance(s.from())).isEqualTo(8_000_000);
        assertThat(balance(s.to())).isZero();

        long id = held.body().get("transfer_id").asLong();
        Resp queue = get("/admin/risk/queue", adminId);
        assertThat(queue.body().findValues("transfer_id").stream().map(JsonNode::asLong)).contains(id);

        Resp released = post("/admin/risk/" + id + "/decision", adminId, key(),
                Map.of("decision", "RELEASE", "reason", "Called the customer"));
        assertThat(released.status()).as(released.raw()).isEqualTo(200);
        assertThat(released.body().get("status").asText()).isEqualTo("POSTED");
        assertThat(released.body().get("entries")).hasSize(2);
        assertThat(balance(s.from())).isEqualTo(2_000_000);
        assertThat(balance(s.to())).isEqualTo(6_000_000);

        // Audit log: created HELD by the user, then released by the admin with the reason.
        JsonNode history = released.body().get("status_history");
        assertThat(history).hasSize(2);
        assertThat(history.get(0).get("to_status").asText()).isEqualTo("HELD");
        assertThat(history.get(0).get("changed_by").asText()).isEqualTo("user:" + s.sender());
        assertThat(history.get(1).get("from_status").asText()).isEqualTo("HELD");
        assertThat(history.get(1).get("to_status").asText()).isEqualTo("POSTED");
        assertThat(history.get(1).get("changed_by").asText()).isEqualTo("admin:" + adminId);
        assertThat(history.get(1).get("reason").asText()).isEqualTo("Called the customer");

        // A second decision on the same transfer is refused.
        Resp again = post("/admin/risk/" + id + "/decision", adminId, key(), Map.of("decision", "REJECT", "reason", "x"));
        assertThat(again.status()).isEqualTo(409);
    }

    @Test
    void rejectedHoldNeverMovesMoney() {
        Setup s = setup(8_000_000);
        long id = transfer(s.sender(), s.from(), s.to(), 6_000_000, null).body().get("transfer_id").asLong();

        Resp rejected = post("/admin/risk/" + id + "/decision", adminId, key(), Map.of("decision", "REJECT", "reason", "Fraud"));
        assertThat(rejected.status()).isEqualTo(200);
        assertThat(rejected.body().get("status").asText()).isEqualTo("REJECTED");
        assertThat(balance(s.from())).isEqualTo(8_000_000);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE transfer_id = ?", Long.class, id)).isZero();
    }

    @Test
    void releaseRechecksTheBalance() {
        Setup s = setup(8_000_000);
        long id = transfer(s.sender(), s.from(), s.to(), 6_000_000, null).body().get("transfer_id").asLong();
        // Spend most of the money while the transfer waits for review (allowed, only flagged as a new payee).
        assertThat(transfer(s.sender(), s.from(), s.to(), 4_000_000, null).status()).isEqualTo(201);
        assertThat(balance(s.from())).isEqualTo(4_000_000);
        Resp released = post("/admin/risk/" + id + "/decision", adminId, key(), Map.of("decision", "RELEASE", "reason", "ok"));
        assertThat(released.status()).isEqualTo(422);
        assertThat(released.body().get("code").asText()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(jdbc.queryForObject("SELECT status FROM transfers WHERE id = ?", String.class, id)).isEqualTo("HELD");
    }

    @Test
    void unverifiedSenderAboveThresholdIsHeld() {
        long sender = createUser("Pending Kyc");             // KYC stays PENDING
        long receiver = verifiedUser("Receiver Kyc");
        long from = openWallet(sender, "main");
        long to = openWallet(receiver, "main");
        topUp(sender, from, 3_000_000);

        Resp r = transfer(sender, from, to, 1_500_000, null);
        assertThat(r.status()).isEqualTo(202);
        assertThat(rules(r.body())).contains("UNVERIFIED_KYC");

        Resp small = transfer(sender, from, to, 50_000, null);
        assertThat(small.status()).isEqualTo(201);
    }

    @Test
    void firstLargePaymentToANewPayeeIsAllowedButFlagged() {
        Setup s = setup(5_000_000);
        Resp r = transfer(s.sender(), s.from(), s.to(), 1_500_000, "deposit"); // ₹15,000, first time
        assertThat(r.status()).isEqualTo(201);
        assertThat(r.body().get("status").asText()).isEqualTo("POSTED");
        assertThat(rules(r.body())).containsExactly("NEW_PAYEE_LARGE");
        assertThat(r.body().get("risk_flags").get(0).get("action").asText()).isEqualTo("FLAG");

        Resp second = transfer(s.sender(), s.from(), s.to(), 1_500_000, null);
        assertThat(rules(second.body())).doesNotContain("NEW_PAYEE_LARGE");
    }

    @Test
    void velocityRuleHoldsTheEleventhTransferInAMinute() {
        Setup s = setup(1_000_000);
        for (int i = 0; i < 10; i++) {
            assertThat(transfer(s.sender(), s.from(), s.to(), 100, null).status()).isEqualTo(201);
        }
        Resp eleventh = transfer(s.sender(), s.from(), s.to(), 100, null);
        assertThat(eleventh.status()).isEqualTo(202);
        assertThat(rules(eleventh.body())).contains("VELOCITY");
    }

    @Test
    void thresholdsAreDataNotCode() {
        Setup s = setup(1_000_000);
        jdbc.update("UPDATE risk_rules SET amount_threshold = 1000 WHERE code = 'LARGE_AMOUNT'");
        try {
            assertThat(transfer(s.sender(), s.from(), s.to(), 1_000, null).status()).isEqualTo(202);
        } finally {
            jdbc.update("UPDATE risk_rules SET amount_threshold = 5000000 WHERE code = 'LARGE_AMOUNT'");
        }
    }

    @Test
    void reversalCreatesAPointingTransferAndRestoresBalances() {
        Setup s = setup(100_000);
        long id = transfer(s.sender(), s.from(), s.to(), 30_000, "oops").body().get("transfer_id").asLong();

        Resp notAdmin = post("/transfers/" + id + "/reverse", s.sender(), key(), Map.of("reason", "mine"));
        assertThat(notAdmin.status()).isEqualTo(403);

        Resp reversal = post("/transfers/" + id + "/reverse", adminId, key(), Map.of("reason", "Sent to wrong person"));
        assertThat(reversal.status()).as(reversal.raw()).isEqualTo(201);
        assertThat(reversal.body().get("type").asText()).isEqualTo("REVERSAL");
        assertThat(reversal.body().get("reverses_transfer_id").asLong()).isEqualTo(id);
        assertThat(balance(s.from())).isEqualTo(100_000);
        assertThat(balance(s.to())).isZero();

        // The original's entries are untouched; only its status moved to REVERSED.
        Resp original = get("/transfers/" + id, s.sender());
        assertThat(original.body().get("status").asText()).isEqualTo("REVERSED");
        assertThat(original.body().get("entries")).hasSize(2);

        Resp twice = post("/transfers/" + id + "/reverse", adminId, key(), Map.of("reason", "again"));
        assertThat(twice.status()).isEqualTo(409);
        assertThat(twice.body().get("code").asText()).isEqualTo("ALREADY_REVERSED");
    }

    @Test
    void withdrawalChargesAFeeAsItsOwnTransferAndReversesWithIt() {
        Setup s = setup(100_000);
        Resp w = post("/transfers/withdraw", s.sender(), key(), Map.of("wallet_id", s.from(), "amount", 20_000));
        assertThat(w.status()).as(w.raw()).isEqualTo(201);
        assertThat(w.body().get("child_transfer_ids")).hasSize(1);
        assertThat(balance(s.from())).isEqualTo(100_000 - 20_000 - 500);

        long feeId = w.body().get("child_transfer_ids").get(0).asLong();
        assertThat(jdbc.queryForObject("SELECT type FROM transfers WHERE id = ?", String.class, feeId)).isEqualTo("FEE");

        Resp tooMuch = post("/transfers/withdraw", s.sender(), key(), Map.of("wallet_id", s.from(), "amount", 79_500));
        assertThat(tooMuch.status()).isEqualTo(422);

        Resp reversal = post("/transfers/" + w.body().get("transfer_id").asLong() + "/reverse", adminId, key(),
                Map.of("reason", "Bank rejected the payout"));
        assertThat(reversal.status()).isEqualTo(201);
        assertThat(balance(s.from())).isEqualTo(100_000);
    }
}
