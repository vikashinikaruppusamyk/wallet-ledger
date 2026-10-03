# Performance: query plans on ~1.2 million ledger entries

Produced by `PerformanceLabTest` (`./mvnw test -Dlab.exclude=none -Dgroups=lab -Dtest=PerformanceLabTest`)
on PostgreSQL 18, a Windows 11 laptop, default Postgres settings. The lab seeds
`seed_demo(10000, 2, 500000, 120000)`: 10,000 users, 20,000 wallets, 620,000 transfers and
**1,240,304 ledger entries**, of which 120,056 sit on one "hot" wallet (used for the pagination test).
Then `ANALYZE` and `EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)` on every query. Raw output:
`target/performance-lab.txt`.

(The naive-transfer lab ran first in the same database and left one corrupted wallet behind - which is
why checks 3 and 4 below each find one row. The nightly job caught the damage, as it should.)

## Summary

| Query | Plan | Time | Verdict |
|---|---|---|---|
| Check 1 - total debits vs credits | parallel seq scan + aggregate | 150 ms | Full scan by nature (it sums everything). Fine nightly. |
| Check 2 - unbalanced transfers | merge join of two full index scans + group | 2,549 ms | Full scan by nature. Slowest check; fine nightly. Grows linearly - see "next" below. |
| Check 3 - stored vs computed balance | parallel seq scan + hash aggregate per account, hash join to accounts | 348 ms | Full scan, fine nightly. An index wouldn't help: every row is needed. |
| Check 4 - negative user wallets | same aggregate as check 3 | 287 ms | Full scan, fine nightly. |
| Check 5a - posted transfers without entries | parallel hash anti-join | 337 ms | Full scan, fine nightly. |
| Check 5c - entries without a transfer | parallel hash anti-join | 324 ms | Full scan; the FK already guarantees zero. |
| **Whole nightly reconciliation** | | **≈ 4 s** | one REPEATABLE READ snapshot |
| Statement page, `OFFSET 100000` | bitmap scan of all 120k entries, sort, discard 100,000 | **180.7 ms** | Gets slower with every page. |
| Statement page, cursor `(created_at, id) <` | index scan of exactly 20 index entries | **0.27 ms** | Constant time on any page - **~670× faster**. |
| VELOCITY | index-only scan, `ix_transfers_from_created` | 0.08 ms | Needs the index - runs inside every transfer. |
| DAILY_OUTFLOW | index-only scan, `ix_transfers_from_created INCLUDE (amount, status, type)` | 0.24 ms | Needs the index. |
| NEW_PAYEE_LARGE | index scan, `ix_transfers_from_to`, stops at first row | 0.02 ms | Needs the index. |
| UNVERIFIED_KYC | two primary-key lookups | 0.14 ms | Primary keys suffice. |

**Which checks can afford a full table scan?** All five reconciliation checks. They are nightly, they
must look at every row anyway (a sum, or "is there any row that ..."), and Postgres runs them as parallel
sequential scans and hash joins, which beat index lookups when you need everything. Adding indexes for
them would only slow down every transfer. The one index they do use, `ix_ledger_entries_transfer`
(check 2), exists anyway for the balance trigger.

**Which need an index?** Everything on the request path: the fraud rules (they run inside every
transfer, while the sender's wallet is locked, so every millisecond is lock time), the statement, and
the per-transfer balance trigger.

**Why `OFFSET 100000` is slow.** `OFFSET` doesn't skip; it produces and throws away 100,000 rows. With
that many rows to produce, the planner even gives up on walking the index in order and instead reads all
120,056 of the wallet's entries, joins them to transfers, and sorts them (spilling to disk in two
workers) - just to return 20. Page 5,001 costs 5,001 pages of work. The cursor version starts the index
scan at `(created_at, id) < (last row seen)` and reads exactly 20 index entries: 103 buffers instead of
11,291, and the same cost on page 1 and page 5,001. Ties on `created_at` are broken by `id`, so the
cursor never skips or repeats a row (`ApiContractTest` checks that the cursor walk returns exactly the
offset walk).

## Fraud rules: time added per transfer

Measured by calling `RiskEngine.evaluate` 2,000 times on random wallets of the 1.2M-entry database, with
an amount (₹20,000) high enough that every amount-gated rule runs its query:

| Rules enabled | Time per transfer |
|---|---|
| none (only loading the enabled rules from `risk_rules`) | 67 µs |
| only LARGE_AMOUNT (no query) | 74 µs |
| only UNVERIFIED_KYC | 164 µs |
| only NEW_PAYEE_LARGE | 166 µs |
| only VELOCITY | 310 µs |
| only DAILY_OUTFLOW | 338 µs |
| **all five** | **777 µs** |

So the whole rule set adds well under a millisecond, most of which is JDBC round trips rather than
query execution (the plans above execute in 0.02-0.24 ms). The cheapest next step would be to cache
the rule list for a few seconds (saving 67 µs) and to fold the per-rule queries into one statement.

## Full plans

### Check 1 - total debits vs credits

```
Finalize Aggregate (actual time=141.996..149.981 rows=1.00 loops=1)
  Buffers: shared hit=10611 read=981
  ->  Gather (actual time=141.861..149.968 rows=3.00 loops=1)
        Workers Planned: 2
        Workers Launched: 2
        Buffers: shared hit=10611 read=981
        ->  Partial Aggregate (actual time=108.410..108.410 rows=1.00 loops=3)
              Buffers: shared hit=10611 read=981
              ->  Parallel Seq Scan on ledger_entries (actual time=0.057..35.972 rows=413434.67 loops=3)
                    Buffers: shared hit=10611 read=981
Planning:
  Buffers: shared hit=31 read=1
Planning Time: 0.320 ms
Execution Time: 150.020 ms

```

### Check 2 - unbalanced transfers (GROUP BY ... HAVING)

```
GroupAggregate (actual time=2541.535..2541.537 rows=0.00 loops=1)
  Group Key: t.id
  Filter: ((COALESCE(sum(e.amount) FILTER (WHERE (e.direction = 'D'::bpchar)), '0'::numeric) <> COALESCE(sum(e.amount) FILTER (WHERE (e.direction = 'C'::bpchar)), '0'::numeric)) OR (COALESCE(sum(e.amount) FILTER (WHERE (e.direction = 'D'::bpchar)), '0'::numeric) <> (t.amount)::numeric))
  Rows Removed by Filter: 620152
  Buffers: shared hit=1233130 read=21458 written=7135
  ->  Merge Join (actual time=0.117..1607.892 rows=1240304.00 loops=1)
        Merge Cond: (e.transfer_id = t.id)
        Buffers: shared hit=1233130 read=21458 written=7135
        ->  Index Scan using ix_ledger_entries_transfer on ledger_entries e (actual time=0.095..787.551 rows=1240304.00 loops=1)
              Index Searches: 1
              Buffers: shared hit=1233049 read=10446 written=2939
        ->  Index Scan using transfers_pkey on transfers t (actual time=0.016..373.118 rows=620152.00 loops=1)
              Index Searches: 1
              Buffers: shared hit=81 read=11012 written=4196
Planning:
  Buffers: shared hit=69 read=13
Planning Time: 0.935 ms
Execution Time: 2548.637 ms

```

### Check 3 - stored vs computed balances

```
Hash Left Join (actual time=333.595..346.593 rows=1.00 loops=1)
  Hash Cond: (a.id = c.account_id)
  Filter: ((a.balance)::numeric <> COALESCE(c.balance, '0'::numeric))
  Rows Removed by Filter: 20006
  Buffers: shared hit=11591 read=415 dirtied=207, temp read=3 written=19
  ->  Seq Scan on accounts a (actual time=0.171..5.463 rows=20007.00 loops=1)
        Buffers: shared read=414 dirtied=207
  ->  Hash (actual time=333.278..333.371 rows=20005.00 loops=1)
        Buckets: 32768  Batches: 1  Memory Usage: 1194kB
        Buffers: shared hit=11591 read=1, temp read=3 written=19
        ->  Subquery Scan on c (actual time=320.351..330.320 rows=20005.00 loops=1)
              Buffers: shared hit=11591 read=1, temp read=3 written=19
              ->  Finalize HashAggregate (actual time=320.349..328.174 rows=20005.00 loops=1)
                    Group Key: ledger_entries.account_id
                    Batches: 4  Memory Usage: 9009kB  Disk Usage: 136kB
                    Buffers: shared hit=11591 read=1, temp read=3 written=19
                    ->  Gather (actual time=258.299..275.559 rows=60005.00 loops=1)
                          Workers Planned: 2
                          Workers Launched: 2
                          Buffers: shared hit=11591 read=1
                          ->  Partial HashAggregate (actual time=223.344..232.745 rows=20001.67 loops=3)
                                Group Key: ledger_entries.account_id
                                Batches: 1  Memory Usage: 7953kB
                                Buffers: shared hit=11591 read=1
                                Worker 0:  Batches: 1  Memory Usage: 7953kB
                                Worker 1:  Batches: 1  Memory Usage: 7953kB
                                ->  Parallel Seq Scan on ledger_entries (actual time=0.056..35.711 rows=413434.67 loops=3)
                                      Buffers: shared hit=11591 read=1
Planning:
  Buffers: shared hit=83 read=26 dirtied=3
Planning Time: 1.058 ms
Execution Time: 348.353 ms

```

### Check 4 - negative user wallets

```
Hash Join (actual time=281.316..285.410 rows=1.00 loops=1)
  Hash Cond: (a.id = ledger_entries.account_id)
  Buffers: shared hit=12006, temp read=3 written=19
  ->  Seq Scan on accounts a (actual time=0.013..2.638 rows=20004.00 loops=1)
        Filter: (kind = 'USER_WALLET'::text)
        Rows Removed by Filter: 3
        Buffers: shared hit=414
  ->  Hash (actual time=281.268..281.341 rows=2.00 loops=1)
        Buckets: 8192  Batches: 1  Memory Usage: 65kB
        Buffers: shared hit=11592, temp read=3 written=19
        ->  Finalize HashAggregate (actual time=281.179..281.335 rows=2.00 loops=1)
              Group Key: ledger_entries.account_id
              Filter: (sum(CASE WHEN (ledger_entries.direction = 'C'::bpchar) THEN ledger_entries.amount ELSE (- ledger_entries.amount) END) < '0'::numeric)
              Batches: 4  Memory Usage: 9009kB  Disk Usage: 136kB
              Rows Removed by Filter: 20003
              Buffers: shared hit=11592, temp read=3 written=19
              ->  Gather (actual time=216.350..233.090 rows=60005.00 loops=1)
                    Workers Planned: 2
                    Workers Launched: 2
                    Buffers: shared hit=11592
                    ->  Partial HashAggregate (actual time=189.340..198.748 rows=20001.67 loops=3)
                          Group Key: ledger_entries.account_id
                          Batches: 1  Memory Usage: 7953kB
                          Buffers: shared hit=11592
                          Worker 0:  Batches: 1  Memory Usage: 7953kB
                          Worker 1:  Batches: 1  Memory Usage: 7953kB
                          ->  Parallel Seq Scan on ledger_entries (actual time=0.009..32.519 rows=413434.67 loops=3)
                                Buffers: shared hit=11592
Planning:
  Buffers: shared hit=8 read=1
Planning Time: 0.309 ms
Execution Time: 287.282 ms

```

### Check 5a - posted transfers without entries

```
Gather (actual time=293.097..336.831 rows=0.00 loops=1)
  Workers Planned: 2
  Workers Launched: 2
  Buffers: shared hit=14187 read=6730, temp read=6140 written=6280
  ->  Parallel Hash Anti Join (actual time=266.067..266.069 rows=0.00 loops=3)
        Hash Cond: (t.id = e.transfer_id)
        Buffers: shared hit=14187 read=6730, temp read=6140 written=6280
        ->  Parallel Seq Scan on transfers t (actual time=0.079..33.048 rows=206717.33 loops=3)
              Filter: (status = ANY ('{POSTED,REVERSED}'::text[]))
              Buffers: shared hit=2595 read=6730
        ->  Parallel Hash (actual time=107.828..107.829 rows=413434.67 loops=3)
              Buckets: 262144  Batches: 16  Memory Usage: 5152kB
              Buffers: shared hit=11592, temp written=4096
              ->  Parallel Seq Scan on ledger_entries e (actual time=0.010..42.070 rows=413434.67 loops=3)
                    Buffers: shared hit=11592
Planning:
  Buffers: shared hit=11 read=10
Planning Time: 0.574 ms
Execution Time: 336.869 ms

```

### Check 5c - entries without a transfer

```
Gather (actual time=300.127..324.361 rows=0.00 loops=1)
  Workers Planned: 2
  Workers Launched: 2
  Buffers: shared hit=14463 read=6448, temp read=7327 written=7400
  ->  Parallel Hash Anti Join (actual time=270.846..270.849 rows=0.00 loops=3)
        Hash Cond: (e.transfer_id = t.id)
        Buffers: shared hit=14463 read=6448, temp read=7327 written=7400
        ->  Parallel Seq Scan on ledger_entries e (actual time=0.019..44.432 rows=413434.67 loops=3)
              Buffers: shared hit=11592
        ->  Parallel Hash (actual time=63.544..63.545 rows=206717.33 loops=3)
              Buckets: 262144  Batches: 8  Memory Usage: 5152kB
              Buffers: shared hit=2871 read=6448, temp written=1900
              ->  Parallel Seq Scan on transfers t (actual time=0.120..24.027 rows=206717.33 loops=3)
                    Buffers: shared hit=2871 read=6448
Planning:
  Buffers: shared hit=18
Planning Time: 0.226 ms
Execution Time: 324.394 ms

```

### Statement page with OFFSET 100000

```
Limit (actual time=171.829..180.174 rows=20.00 loops=1)
  Buffers: shared hit=4110 read=7181, temp read=622 written=622
  ->  Gather Merge (actual time=137.398..175.289 rows=100020.00 loops=1)
        Workers Planned: 2
        Workers Launched: 2
        Buffers: shared hit=4110 read=7181, temp read=622 written=622
        ->  Sort (actual time=106.654..111.196 rows=33844.67 loops=3)
              Sort Key: e.created_at DESC, e.id DESC
              Sort Method: quicksort  Memory: 3056kB
              Buffers: shared hit=4110 read=7181, temp read=622 written=622
              Worker 0:  Sort Method: external merge  Disk: 2488kB
              Worker 1:  Sort Method: external merge  Disk: 2488kB
              ->  Parallel Hash Join (actual time=24.808..91.788 rows=40018.67 loops=3)
                    Hash Cond: (t.id = e.transfer_id)
                    Buffers: shared hit=4080 read=7181
                    ->  Parallel Seq Scan on transfers t (actual time=0.059..20.366 rows=206717.33 loops=3)
                          Buffers: shared hit=2899 read=6420
                    ->  Parallel Hash (actual time=20.752..20.753 rows=40018.67 loops=3)
                          Buckets: 131072  Batches: 1  Memory Usage: 9536kB
                          Buffers: shared hit=1181 read=761
                          ->  Parallel Bitmap Heap Scan on ledger_entries e (actual time=6.028..12.778 rows=40018.67 loops=3)
                                Recheck Cond: (account_id = 8)
                                Heap Blocks: exact=749
                                Buffers: shared hit=1181 read=761
                                Worker 0:  Heap Blocks: exact=124
                                Worker 1:  Heap Blocks: exact=305
                                ->  Bitmap Index Scan on ix_ledger_entries_account_created (actual time=17.725..17.725 rows=120056.00 loops=1)
                                      Index Cond: (account_id = 8)
                                      Index Searches: 1
                                      Buffers: shared hit=3 read=761
Planning:
  Buffers: shared hit=38 read=2 dirtied=1
Planning Time: 0.448 ms
Execution Time: 180.685 ms

```

### Same page with a cursor on (created_at, id)

```
Limit (actual time=0.067..0.234 rows=20.00 loops=1)
  Buffers: shared hit=94 read=9
  ->  Nested Loop (actual time=0.067..0.231 rows=20.00 loops=1)
        Buffers: shared hit=94 read=9
        ->  Index Scan using ix_ledger_entries_account_created on ledger_entries e (actual time=0.040..0.060 rows=20.00 loops=1)
              Index Cond: ((account_id = 8) AND (ROW(created_at, id) < ROW('2026-07-20 10:14:03.499952+05:30'::timestamp with time zone, 1139237)))
              Index Searches: 1
              Buffers: shared hit=22 read=1
        ->  Index Scan using transfers_pkey on transfers t (actual time=0.008..0.008 rows=1.00 loops=20)
              Index Cond: (id = e.transfer_id)
              Index Searches: 20
              Buffers: shared hit=72 read=8
Planning:
  Buffers: shared hit=18
Planning Time: 0.322 ms
Execution Time: 0.267 ms

```

### VELOCITY

```
Aggregate (actual time=0.058..0.058 rows=1.00 loops=1)
  Buffers: shared read=3
  ->  Index Only Scan using ix_transfers_from_created on transfers (actual time=0.056..0.056 rows=0.00 loops=1)
        Index Cond: ((from_account_id = 4) AND (created_at > (now() - '00:01:00'::interval)))
        Filter: (type = ANY ('{PEER_TRANSFER,WITHDRAWAL}'::text[]))
        Heap Fetches: 0
        Index Searches: 1
        Buffers: shared read=3
Planning:
  Buffers: shared hit=6
Planning Time: 0.193 ms
Execution Time: 0.076 ms

```

### DAILY_OUTFLOW

```
Aggregate (actual time=0.228..0.229 rows=1.00 loops=1)
  Buffers: shared hit=21 read=16
  ->  Index Only Scan using ix_transfers_from_created on transfers (actual time=0.032..0.216 rows=100.00 loops=1)
        Index Cond: ((from_account_id = 4) AND (created_at > (now() - '24:00:00'::interval)))
        Filter: ((status = ANY ('{POSTED,HELD}'::text[])) AND (type = ANY ('{PEER_TRANSFER,WITHDRAWAL}'::text[])))
        Heap Fetches: 100
        Index Searches: 1
        Buffers: shared hit=21 read=16
Planning:
  Buffers: shared hit=1
Planning Time: 0.096 ms
Execution Time: 0.243 ms

```

### NEW_PAYEE_LARGE

```
Result (actual time=0.012..0.012 rows=1.00 loops=1)
  Buffers: shared hit=4
  InitPlan 1
    ->  Index Scan using ix_transfers_from_to on transfers (actual time=0.011..0.011 rows=1.00 loops=1)
          Index Cond: ((from_account_id = 4) AND (to_account_id = 5))
          Filter: (status = 'POSTED'::text)
          Index Searches: 1
          Buffers: shared hit=4
Planning:
  Buffers: shared hit=1
Planning Time: 0.088 ms
Execution Time: 0.021 ms

```

### UNVERIFIED_KYC

```
Index Scan using users_pkey on users (actual time=0.118..0.119 rows=1.00 loops=1)
  Index Cond: (id = (InitPlan 1).col1)
  Index Searches: 1
  Buffers: shared hit=1 read=5
  InitPlan 1
    ->  Index Scan using accounts_pkey on accounts (actual time=0.047..0.048 rows=1.00 loops=1)
          Index Cond: (id = 4)
          Index Searches: 1
          Buffers: shared hit=1 read=2
Planning:
  Buffers: shared hit=31 read=5 dirtied=1
Planning Time: 0.833 ms
Execution Time: 0.141 ms

```
