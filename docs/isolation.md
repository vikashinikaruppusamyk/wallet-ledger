# Isolation levels, by hand

Two sessions, **A** and **B**, side by side on the same database. Each case below shows every statement
(`A=>` / `B=>`) and exactly what Postgres answered.

**How to reproduce.** Open two `psql` windows (`psql -U wallet -d wallet_test`), create the tables
below, and type the statements in the order shown. The same script is automated in
`src/test/java/com/walletledger/lab/IsolationLabTest.java` - it drives two real JDBC sessions and writes
this transcript to `target/isolation-lab.txt`:

```bash
./mvnw test -Dlab.exclude=none -Dgroups=lab -Dtest=IsolationLabTest
```

(The transcript below was produced that way, on PostgreSQL 18, because the development machine's
application-control policy blocks `psql.exe`. The statements are identical to what you'd type in psql.)

```sql
CREATE SCHEMA lab;
CREATE TABLE lab.owners  (id BIGINT PRIMARY KEY, name TEXT NOT NULL);
CREATE TABLE lab.wallets (id BIGINT PRIMARY KEY, owner_id BIGINT NOT NULL REFERENCES lab.owners, balance BIGINT NOT NULL);
INSERT INTO lab.owners  VALUES (1, 'Asha'), (2, 'Rahul');
INSERT INTO lab.wallets VALUES (1, 1, 1000), (2, 1, 500), (3, 2, 300);
```

---

## Case 1 - READ COMMITTED (the Postgres default)

```
A=> BEGIN;
BEGIN

A=> SELECT id, balance FROM lab.wallets WHERE id = 1;
 id | balance
----+---------
 1  | 1000
(1 row)

B=> UPDATE lab.wallets SET balance = balance - 100 WHERE id = 1;  -- autocommit
UPDATE 1

A=> SELECT id, balance FROM lab.wallets WHERE id = 1;
 id | balance
----+---------
 1  | 900
(1 row)

A=> COMMIT;
COMMIT
```

**Yes, A sees a different value.** Each statement in READ COMMITTED takes a fresh snapshot, so the
second `SELECT` sees B's committed update even though A is still in the same transaction. That's a
*non-repeatable read*. It's why a transfer can't "read the balance, check it in code, then write": by
the time it writes, the balance it checked may be stale.

## Case 2 - REPEATABLE READ

```
A=> BEGIN ISOLATION LEVEL REPEATABLE READ;
BEGIN

A=> SELECT id, balance FROM lab.wallets WHERE id = 1;
 id | balance
----+---------
 1  | 900
(1 row)

B=> UPDATE lab.wallets SET balance = balance - 100 WHERE id = 1;  -- autocommit
UPDATE 1

A=> SELECT id, balance FROM lab.wallets WHERE id = 1;
 id | balance
----+---------
 1  | 900
(1 row)

-- A still sees its snapshot. Now A tries to write the row B changed:

A=> UPDATE lab.wallets SET balance = balance - 50 WHERE id = 1;
ERROR:  could not serialize access due to concurrent update   (SQLSTATE 40001)

A=> ROLLBACK;
ROLLBACK
```

A keeps seeing **900**: the snapshot is taken at the first statement and kept for the whole
transaction. Postgres also refuses to let A *write* a row that changed after A's snapshot - instead of
silently losing B's update, it fails A with `40001` and the application must retry.

## Case 3 - SERIALIZABLE: two sessions read, then write based on what they read

The rule: Asha may withdraw only while her two wallets together hold at least ₹1000. Both sessions
check the total, then each withdraws 700 from a *different* wallet - so they never touch the same row.

```
A=> BEGIN ISOLATION LEVEL SERIALIZABLE;
BEGIN

B=> BEGIN ISOLATION LEVEL SERIALIZABLE;
BEGIN

A=> SELECT sum(balance) FROM lab.wallets WHERE owner_id = 1;
 sum
------
 1300
(1 row)

B=> SELECT sum(balance) FROM lab.wallets WHERE owner_id = 1;
 sum
------
 1300
(1 row)

A=> UPDATE lab.wallets SET balance = balance - 700 WHERE id = 1;
UPDATE 1

B=> UPDATE lab.wallets SET balance = balance - 700 WHERE id = 2;
UPDATE 1

A=> COMMIT;
COMMIT

B=> COMMIT;
ERROR:  could not serialize access due to read/write dependencies among transactions   (SQLSTATE 40001)

A=> SELECT id, balance FROM lab.wallets WHERE owner_id = 1 ORDER BY id;
 id | balance
----+---------
 1  | 100
 2  | 500
(2 rows)
```

**One of them is aborted at commit** with `40001 ... read/write dependencies among transactions`. Each
read a total the other one then changed; there is no serial order in which both results make sense,
so Serializable Snapshot Isolation kills the second committer. B has to retry, will see 600, and refuse.

For contrast, the same steps under REPEATABLE READ:

```
-- (reset: Asha wallets 1 = 1000, 2 = 500; Rahul wallet 3 = 300)

A=> BEGIN ISOLATION LEVEL REPEATABLE READ;
B=> BEGIN ISOLATION LEVEL REPEATABLE READ;
A=> SELECT sum(balance) FROM lab.wallets WHERE owner_id = 1;    -- 1500
B=> SELECT sum(balance) FROM lab.wallets WHERE owner_id = 1;    -- 1500
A=> UPDATE lab.wallets SET balance = balance - 700 WHERE id = 1;
UPDATE 1
B=> UPDATE lab.wallets SET balance = balance - 700 WHERE id = 2;
UPDATE 1
A=> COMMIT;
COMMIT
B=> COMMIT;
COMMIT

A=> SELECT id, balance FROM lab.wallets WHERE owner_id = 1 ORDER BY id;
 id | balance
----+---------
 1  | 300
 2  | -200
(2 rows)
```

Both commit and wallet 2 ends at **-200**: classic *write skew*. REPEATABLE READ only catches two
writers on the *same row* (case 2); it can't see that two transactions acted on the same *fact* (a
total) through different rows.

## Case 4 - REPEATABLE READ with a join

```
-- (reset: Asha wallets 1 = 1000, 2 = 500; Rahul wallet 3 = 300)

A=> BEGIN ISOLATION LEVEL REPEATABLE READ;
BEGIN

A=> SELECT o.name, w.id, w.balance FROM lab.wallets w JOIN lab.owners o ON o.id = w.owner_id ORDER BY w.id;
 name  | id | balance
-------+----+---------
 Asha  | 1  | 1000
 Asha  | 2  | 500
 Rahul | 3  | 300
(3 rows)

B=> UPDATE lab.owners SET name = 'Asha K' WHERE id = 1;  -- autocommit
UPDATE 1

B=> UPDATE lab.wallets SET balance = balance + 5000 WHERE id = 3;  -- autocommit
UPDATE 1

B=> INSERT INTO lab.wallets (id, owner_id, balance) VALUES (4, 2, 42);  -- autocommit
INSERT 0 1

A=> SELECT o.name, w.id, w.balance FROM lab.wallets w JOIN lab.owners o ON o.id = w.owner_id ORDER BY w.id;
 name  | id | balance
-------+----+---------
 Asha  | 1  | 1000
 Asha  | 2  | 500
 Rahul | 3  | 300
(3 rows)

A=> COMMIT;
COMMIT

A=> SELECT o.name, w.id, w.balance FROM lab.wallets w JOIN lab.owners o ON o.id = w.owner_id ORDER BY w.id;
 name   | id | balance
--------+----+---------
 Asha K | 1  | 1000
 Asha K | 2  | 500
 Rahul  | 3  | 5300
 Rahul  | 4  | 42
(4 rows)
```

**Yes, the guarantee holds across the join.** B changed a row in *each* table and inserted a new row,
and A's second query returned exactly the first result: no changed name, no changed balance, and no
*phantom* row 4. In Postgres the snapshot belongs to the transaction, not to a table, so every table a
query touches is read as of the same instant. (The SQL standard allows phantoms at REPEATABLE READ;
Postgres doesn't produce them.) Once A commits, a new snapshot shows all of B's changes.

This is exactly why the nightly reconciliation runs at REPEATABLE READ: five queries over three tables
see one consistent moment, so a transfer committed half-way through the run can't make check 1 and check
3 disagree.

---

<a id="break-it-first"></a>
## Break it first: a transfer with no locks

`NaiveTransferLabTest` fires 100 concurrent ₹10 transfers at a ₹500 wallet using the textbook approach:
read the balance (no `FOR UPDATE`), check it in Java, write. (A 2 ms pause stands in for application work
between the read and the write.) Output from `target/naive-lab.txt`:

```
Naive transfer lab: 100 concurrent ₹10 transfers from a ₹500 wallet, no row locks

A. read balance, check in code, SET balance = <value computed in Java>
  transfers that succeeded:          100   (correct answer: 50)
  rejected by the code's check:      0
  failed in the database:            0
  sender stored balance:             47000 paise
  sender balance from the ledger:    -50000 paise   (correct answer: 0)
  receiver stored balance:           100000 paise
  money created out of nothing:      97000 paise

B. read balance, check in code, SET balance = balance - amount (atomic decrement)
  transfers that succeeded:          50   (correct answer: 50)
  rejected by the code's check:      21
  failed in the database:            29   (failed: DataIntegrityViolationException)
  sender stored balance:             0 paise
  sender balance from the ledger:    0 paise   (correct answer: 0)
  receiver stored balance:           50000 paise
  money created out of nothing:      0 paise
```

* **Variant A** is the bug the brief describes, in full. Every request read ₹500, passed the check, and
  wrote "its" new balance. **All 100 succeeded**: the stored balance reads ₹470 (*lost updates* - each
  write overwrote the others), the ledger says the wallet is **-₹500** (an *overdraft*), and the receiver
  got ₹1,000. ₹970 was created out of nothing. The `CHECK (balance >= 0)` constraint couldn't help,
  because every value written was positive.
* **Variant B** writes `balance = balance - amount`, which the database applies atomically, so no update
  is lost. The application's check is still useless (it read stale balances), but the constraint
  catches the overdraft: 29 transfers die with a constraint violation instead of a clean
  `INSUFFICIENT_FUNDS`. Correct by accident, with errors instead of answers.

Note that this lab also leaves broken data behind on purpose: running the reconciliation checks on the
same database afterwards reports variant A's wallet (stored 47000 vs ledger -50000) under checks 3 and 4.
You can see that in [performance.md](performance.md), where the lab ran first.

## Fix it with row locks

The real transfer (`TransferService.peerTransfer`) locks both wallets with `SELECT ... FOR UPDATE` in
ascending id order, re-reads the balance under the lock, and only then inserts the transfer, its two
entries in one statement, and the new balances - all in one transaction. `StampedeTest` runs the same
load through the HTTP API:

| | Naive A | Naive B | Locked (`StampedeTest`) |
|---|---|---|---|
| Transfers that succeed (correct: 50) | 100 | 50 | **50, in each of 20 runs** |
| Clean `422 INSUFFICIENT_FUNDS` for the rest | 0 | 21 | **50** |
| Database errors | 0 | 29 | **0** |
| Ledger balance at the end (correct: ₹0) | -₹500 | ₹0 | **₹0**, never negative at any point |
| Debits = credits | yes (per transfer) | yes | **yes** |
| Stored balance = ledger balance | **no** | yes | **yes** |

Run at READ COMMITTED - see the README's design question 4 for why row locks beat Serializable here.
