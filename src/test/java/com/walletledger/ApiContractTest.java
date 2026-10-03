package com.walletledger;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApiContractTest extends IntegrationTest {

    @Test
    void errorsAlwaysHaveTheSameShape() {
        Resp r = get("/wallets/999999999/balance", adminId);
        assertThat(r.status()).isEqualTo(404);
        assertThat(r.body().fieldNames()).toIterable().containsExactlyInAnyOrder("code", "message", "request_id");
        assertThat(r.body().get("code").asText()).isEqualTo("WALLET_NOT_FOUND");
        assertThat(r.body().get("request_id").asText()).isEqualTo(r.header("X-Request-Id"));

        Resp noUser = get("/wallets/1/balance", null);
        assertThat(noUser.status()).isEqualTo(401);
        assertThat(noUser.body().get("code").asText()).isEqualTo("UNAUTHENTICATED");

        Resp malformed = post("/transfers", adminId, key(), "{not json");
        assertThat(malformed.status()).isEqualTo(400);
        assertThat(malformed.body().get("code").asText()).isEqualTo("MALFORMED_JSON");
    }

    @Test
    void usersOnlySeeTheirOwnWalletsAndTransfers() {
        long asha = verifiedUser("Asha Api");
        long rahul = verifiedUser("Rahul Api");
        long eve = verifiedUser("Eve Api");
        long a = openWallet(asha, "main");
        long r = openWallet(rahul, "main");
        topUp(asha, a, 10_000);
        long id = transfer(asha, a, r, 1_000, null).body().get("transfer_id").asLong();

        assertThat(get("/wallets/" + a + "/balance", asha).body().get("balance").asLong()).isEqualTo(9_000);
        assertThat(get("/wallets/" + a + "/balance", eve).status()).isEqualTo(404);
        assertThat(get("/wallets/" + a + "/statement", eve).status()).isEqualTo(404);
        assertThat(get("/transfers/" + id, rahul).status()).isEqualTo(200);
        assertThat(get("/transfers/" + id, eve).status()).isEqualTo(404);

        // Spending from someone else's wallet is refused.
        Resp steal = transfer(eve, a, r, 1_000, null);
        assertThat(steal.status()).isEqualTo(403);
        assertThat(balance(a)).isEqualTo(9_000);

        // Opening a wallet for another user is refused; two wallets with one name too.
        assertThat(post("/users/" + asha + "/wallets", eve, key(), Map.of("name", "x")).status()).isEqualTo(403);
        assertThat(post("/users/" + asha + "/wallets", asha, key(), Map.of("name", "main")).status()).isEqualTo(409);
    }

    @Test
    void transferDetailsShowEntriesAndStatusHistory() {
        long asha = verifiedUser("Asha Detail");
        long rahul = verifiedUser("Rahul Detail");
        long a = openWallet(asha, "main");
        long r = openWallet(rahul, "main");
        topUp(asha, a, 10_000);
        JsonNode t = transfer(asha, a, r, 2_500, "lunch").body();

        assertThat(t.get("type").asText()).isEqualTo("PEER_TRANSFER");
        assertThat(t.get("entries")).hasSize(2);
        assertThat(t.get("entries").get(0).get("direction").asText()).isEqualTo("D");
        assertThat(t.get("entries").get(0).get("account_id").asLong()).isEqualTo(a);
        assertThat(t.get("entries").get(1).get("direction").asText()).isEqualTo("C");
        assertThat(t.get("status_history").get(0).get("to_status").asText()).isEqualTo("POSTED");
    }

    @Test
    void statementIsNewestFirstAndCursorPagesMatchOffsetPages() {
        long asha = verifiedUser("Asha Statement");
        long rahul = verifiedUser("Rahul Statement");
        long a = openWallet(asha, "main");
        long r = openWallet(rahul, "main");
        long cashIn = cashInAccount();
        postedAt("TOP_UP", cashIn, a, 1_000_000, null, java.time.OffsetDateTime.now().minusDays(30));
        for (int i = 0; i < 25; i++) {
            postedAt("PEER_TRANSFER", a, r, 100 + i, "n" + i, java.time.OffsetDateTime.now().minusDays(29).plusHours(i));
        }

        List<Long> byOffset = new ArrayList<>();
        for (int offset = 0; offset < 26; offset += 10) {
            get("/wallets/" + a + "/statement?limit=10&offset=" + offset, asha).body().get("entries")
                    .forEach(e -> byOffset.add(e.get("entry_id").asLong()));
        }
        List<Long> byCursor = new ArrayList<>();
        String cursor = null;
        do {
            JsonNode page = get("/wallets/" + a + "/statement?limit=10" + (cursor == null ? "" : "&cursor=" + cursor), asha).body();
            page.get("entries").forEach(e -> byCursor.add(e.get("entry_id").asLong()));
            cursor = page.hasNonNull("next_cursor") ? page.get("next_cursor").asText() : null;
        } while (cursor != null);

        assertThat(byOffset).hasSize(26).doesNotHaveDuplicates();
        assertThat(byCursor).isEqualTo(byOffset);
        JsonNode first = get("/wallets/" + a + "/statement?limit=1", asha).body().get("entries").get(0);
        assertThat(first.get("amount").asLong()).isEqualTo(124);
        assertThat(first.get("counterparty_name").asText()).isEqualTo("Rahul Statement");
    }
}
