package com.walletledger;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** Milestone 3: a client may resend a payment as often as it likes; money moves at most once. */
class IdempotencyTest extends IntegrationTest {

    private record Pair(long user, long from, long to) {
    }

    private Pair funded(long amount) {
        long asha = verifiedUser("Asha Idem");
        long rahul = verifiedUser("Rahul Idem");
        long from = openWallet(asha, "main");
        long to = openWallet(rahul, "main");
        topUp(asha, from, amount);
        return new Pair(asha, from, to);
    }

    private long transfersFrom(long walletId) {
        return jdbc.queryForObject("SELECT count(*) FROM transfers WHERE from_account_id = ?", Long.class, walletId);
    }

    private Map<String, Object> body(Pair p, long amount) {
        return Map.of("from_wallet_id", p.from(), "to_wallet_id", p.to(), "amount", amount, "note", "rent");
    }

    @Test
    void sameRequestFiveTimesCreatesOneTransferAndFiveIdenticalResponses() {
        Pair p = funded(100_000);
        String key = key();

        List<Resp> responses = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            responses.add(post("/transfers", p.user(), key, body(p, 10_000)));
        }

        assertThat(transfersFrom(p.from())).isEqualTo(1);
        assertThat(balance(p.from())).isEqualTo(90_000);
        assertThat(responses).allSatisfy(r -> {
            assertThat(r.status()).isEqualTo(201);
            assertThat(r.raw()).isEqualTo(responses.get(0).raw());
        });
        assertThat(responses.get(0).header("Idempotent-Replayed")).isEqualTo("false");
        assertThat(responses.subList(1, 5)).allSatisfy(r -> assertThat(r.header("Idempotent-Replayed")).isEqualTo("true"));
        assertThat(responses.get(0).body().get("transfer_id").asLong()).isPositive();
    }

    @Test
    void sameKeyTenTimesInParallelCreatesExactlyOneTransfer() throws Exception {
        Pair p = funded(100_000);
        String key = key();

        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Resp>> futures = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            futures.add(pool.submit(() -> {
                gate.await();
                return post("/transfers", p.user(), key, body(p, 10_000));
            }));
        }
        gate.countDown();
        List<Resp> responses = new ArrayList<>();
        for (Future<Resp> f : futures) {
            responses.add(f.get());
        }
        pool.shutdown();

        assertThat(transfersFrom(p.from())).isEqualTo(1);
        assertThat(balance(p.from())).isEqualTo(90_000);

        // Each response is either the transfer (first or replayed) or "still running, retry shortly".
        List<Resp> created = responses.stream().filter(r -> r.status() == 201).toList();
        List<Resp> inProgress = responses.stream().filter(r -> r.status() == 409).toList();
        assertThat(created.size() + inProgress.size()).isEqualTo(10);
        assertThat(created).isNotEmpty();
        assertThat(created).allSatisfy(r -> assertThat(r.raw()).isEqualTo(created.get(0).raw()));
        assertThat(inProgress).allSatisfy(r ->
                assertThat(r.body().get("code").asText()).isEqualTo("IDEMPOTENCY_KEY_IN_PROGRESS"));

        // Retrying after the first one finished always gets the original transfer back.
        Resp retry = post("/transfers", p.user(), key, body(p, 10_000));
        assertThat(retry.status()).isEqualTo(201);
        assertThat(retry.raw()).isEqualTo(created.get(0).raw());
    }

    @Test
    void sameKeyWithDifferentAmountIsRejectedAndMovesNoMoney() {
        Pair p = funded(100_000);
        String key = key();

        Resp first = post("/transfers", p.user(), key, body(p, 10_000));
        assertThat(first.status()).isEqualTo(201);

        Resp second = post("/transfers", p.user(), key, body(p, 20_000));
        assertThat(second.status()).isEqualTo(422);
        assertThat(second.body().get("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(second.body().has("request_id")).isTrue();

        assertThat(transfersFrom(p.from())).isEqualTo(1);
        assertThat(balance(p.from())).isEqualTo(90_000);
        assertThat(balance(p.to())).isEqualTo(10_000);
    }

    @Test
    void keyOrderAndWhitespaceInTheBodyDoNotMatter() {
        Pair p = funded(100_000);
        String key = key();
        String a = "{\"from_wallet_id\":" + p.from() + ",\"to_wallet_id\":" + p.to() + ",\"amount\":1000}";
        String b = "{ \"amount\" : 1000, \"to_wallet_id\" : " + p.to() + ", \"from_wallet_id\" : " + p.from() + " }";

        Resp first = post("/transfers", p.user(), key, a);
        Resp second = post("/transfers", p.user(), key, b);

        assertThat(second.status()).isEqualTo(201);
        assertThat(second.raw()).isEqualTo(first.raw());
        assertThat(transfersFrom(p.from())).isEqualTo(1);
    }

    @Test
    void aRejectedTransferIsReplayedAsTheSameRejection() {
        Pair p = funded(1_000);
        String key = key();

        Resp first = post("/transfers", p.user(), key, body(p, 5_000));
        assertThat(first.status()).isEqualTo(422);
        assertThat(first.body().get("code").asText()).isEqualTo("INSUFFICIENT_FUNDS");

        Resp again = post("/transfers", p.user(), key, body(p, 5_000));
        assertThat(again.status()).isEqualTo(422);
        assertThat(again.raw()).isEqualTo(first.raw());
        assertThat(transfersFrom(p.from())).isZero();
    }

    @Test
    void keysAreScopedPerUser() {
        Pair a = funded(100_000);
        Pair b = funded(100_000);
        String shared = key();

        assertThat(post("/transfers", a.user(), shared, body(a, 1_000)).status()).isEqualTo(201);
        assertThat(post("/transfers", b.user(), shared, body(b, 1_000)).status()).isEqualTo(201);
        assertThat(transfersFrom(a.from())).isEqualTo(1);
        assertThat(transfersFrom(b.from())).isEqualTo(1);
    }

    @Test
    void everyPostNeedsAValidKey() {
        Pair p = funded(10_000);

        Resp missing = post("/transfers", p.user(), null, body(p, 100));
        assertThat(missing.status()).isEqualTo(400);
        assertThat(missing.body().get("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");

        Resp notUuid = post("/transfers", p.user(), "retry-1", body(p, 100));
        assertThat(notUuid.status()).isEqualTo(400);
        assertThat(notUuid.body().get("code").asText()).isEqualTo("IDEMPOTENCY_KEY_INVALID");

        assertThat(post("/users", null, null, Map.of("name", "X", "email", "x@y.z", "phone", "+911234567890")).status())
                .isEqualTo(400);
        assertThat(transfersFrom(p.from())).isZero();
    }
}
