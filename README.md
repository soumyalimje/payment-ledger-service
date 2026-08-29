# Payment Ledger Service

A single-service backend modeling the core reliability guarantees of a
payment gateway like Razorpay: idempotency, a double-entry ledger, safe
concurrency, and reliable webhook delivery. No UI — this is an API you'd
test with curl or Postman.

## Files (7 Java classes, ~500 lines total — read in this order)

1. **`sql/schema.sql`** — the 3 tables + the trigger that enforces ledger
   balance *in the database itself*, not just in application code.
2. **`Db.java`** — one method, opens a JDBC connection. Nothing else.
3. **`IdempotencyService.java`** — the "create-or-get" logic. This is the
   file to read to understand how duplicate requests get caught.
4. **`LedgerService.java`** — the actual money movement, with the
   `SELECT ... FOR UPDATE` locking that prevents race conditions.
5. **`WebhookService.java`** — background thread + retry queue with
   exponential backoff (2s, 4s, 8s, 16s).
6. **`PaymentService.java`** — wires the above three together for one
   incoming payment request.
7. **`PaymentServer.java`** — the HTTP layer. `POST /payments`.
8. **`Json.java`** — tiny hand-rolled JSON helpers (no external library needed).

## How each of the 4 requirements is actually implemented

**Idempotency:** `IdempotencyService.checkAndStart()` does
`INSERT ... ON CONFLICT (idempotency_key) DO NOTHING`. Postgres itself
guarantees only one concurrent insert of the same key wins — that's what
makes it atomic, not a check-then-insert in Java (which would have a race
condition).

**Double-entry ledger:** every transfer writes two rows to
`ledger_entries` (a negative debit, a positive credit) sharing one
`transaction_id`. A **deferred constraint trigger** in Postgres
(`check_ledger_balance()`) runs at COMMIT time and raises an exception —
aborting the whole transaction — if a `transaction_id`'s entries don't
sum to zero. This is enforced by the database, not just trusted from
application code.

**Concurrency control:** `LedgerService.transfer()` uses
`SELECT ... FOR UPDATE` to lock both account rows before checking
balances. It always locks accounts in a fixed alphabetical order
regardless of which is "from" and which is "to," which is what prevents
deadlocks when two transfers touch the same two accounts in opposite
order at the same time.

**Webhook retry:** `WebhookService` runs a background daemon thread
reading from a `DelayQueue` — a queue that only releases an item once its
scheduled time has arrived. Failed deliveries get re-enqueued with
double the previous delay, up to 4 attempts. The payment API response
never waits for this — it's fired after the HTTP response is already
being sent.

## Running it with Docker (recommended — solves every local setup issue we hit)

Everything earlier in this project's history — the missing JDBC driver, the Postgres role mismatch, the zsh quoting issue, port conflicts — was purely local-environment friction. Docker packages the exact right environment (Java, Postgres, the driver, schema) into two containers so none of that setup is needed:

```bash
docker-compose up --build
```

That's it — this starts Postgres (with the schema auto-applied on first run) and the app, wired together correctly. Test it the same way as before:
```bash
curl -X POST http://localhost:8090/payments -d '{"idempotency_key":"docker-1","from_account":"customer_1","to_account":"merchant_A","amount":500}'
```

**Honest disclosure:** this sandbox environment doesn't have Docker installed, so the Dockerfile and docker-compose.yml were written carefully and validated (YAML syntax checked, `Db.java`'s environment-variable fallback confirmed working for local/non-Docker use) but **not run end-to-end with actual `docker-compose up`**. If something doesn't work on the first try, that's expected for untested infrastructure code, not a sign to give up — the same debugging approach used throughout this whole project (read the actual error, check what changed) applies here too.

## Running it without Docker (the original way)

Requires: Java 21+ JDK, PostgreSQL, the `postgresql-jdbc` driver jar
(on Debian/Ubuntu: `apt-get install openjdk-21-jdk-headless postgresql
libpostgresql-jdbc-java`).

```bash
# one-time setup
service postgresql start
su postgres -c "createdb payment_ledger"
su postgres -c "psql -d payment_ledger -f sql/schema.sql"

# compile
javac -cp "/usr/share/java/postgresql.jar" -d out src/*.java

# run
java -cp "out:/usr/share/java/postgresql.jar" PaymentServer
```

Then: `POST http://localhost:8090/payments`
```json
{"idempotency_key":"abc123","from_account":"customer_1","to_account":"merchant_A","amount":500,"webhook_url":"https://example.com/hook"}
```

## Tests — what was actually proven, not just claimed

Run `run_all_tests.sh` (starts Postgres + the server + runs everything)
and `run_webhook_test.sh` separately (needs its own ~10s wait to observe
retries).

Results from the last run:

| Test | Result |
|---|---|
| Basic payment | Balances move correctly (500 debited/credited) |
| Duplicate idempotency key | Second request returns cached response; balance unchanged |
| Insufficient funds | Rejected with `422 insufficient_funds`, no ledger entries created |
| **25 concurrent requests, same idempotency key** | **Exactly 1 succeeded, 24 got `409 in_flight`. Exactly one transaction's worth of ledger entries exists.** |
| **25 concurrent requests, distinct keys, same account** | **All 25 succeeded. Balance decreased by exactly 25×100. Zero ledger transactions found unbalanced.** |
| Webhook to an unreachable endpoint | Delivery attempted async (didn't block the payment response), retried at 2s, then 4s, then 8s — doubling each time as designed |

## Bugs found and fixed (senior-level audit)

A full re-read of every file turned up 5 real bugs, all now fixed and covered by `run_bugfix_tests.sh`:

1. **Idempotency key reuse with different parameters was silently accepted.** Sending the same key with a different amount returned the *original* cached result instead of an error. **Fix:** `request_hash` is now actually compared; a mismatch returns `409 idempotency_key_reused`.

2. **An invalid account (or any validation error) left the idempotency key permanently stuck.** `LedgerService` threw `IllegalArgumentException` for a bad account, but `PaymentService` only caught `InsufficientFundsException` and `SQLException` — the exception escaped uncaught, Step 3 (mark COMPLETED/FAILED) never ran, and that key returned `409 in_flight` forever. **Fix:** all validation errors are now caught and properly resolve the key to `FAILED`; a `RuntimeException` catch-all is added as a last line of defense; and as a second, independent safety net, any key stuck in `STARTED` for 30+ seconds (e.g. from a genuine server crash) can be atomically reclaimed by a later request.

3. **Self-transfers (`from_account == to_account`) were silently allowed**, creating two no-op ledger entries. **Fix:** explicitly rejected with `400 invalid_request`.

4. **Negative or absurdly oversized amounts weren't handled cleanly.** The regex used to parse `amount` from JSON didn't match negative numbers, and an oversized digit string would throw an uncaught `NumberFormatException`. **Fix:** regex now handles negative numbers, and the parse is wrapped so malformed input returns a clean `400` instead of crashing the request.

5. **No top-level exception handler in the HTTP layer.** Any unexpected error anywhere would leave the client with a hung connection instead of a real response. **Fix:** `PaymentServer.handlePayment()` now wraps everything in a catch-all that always returns a proper `500` JSON error.

**Known limitation, not fixed (by design):** the webhook retry queue lives in memory — a server restart loses any pending retries. A production system would persist retry state to the database. Worth mentioning proactively if asked "what would you improve further."

## Second pass: two more issues found on deeper review

6. **Balance overflow.** A single transfer with an amount close to `Long.MAX_VALUE` could silently overflow a balance in Java (wraps to a negative number, no exception). **Fix:** `LedgerService.MAX_TRANSFER_AMOUNT` caps any single transfer, rejected with a clear `400` if exceeded.

7. **SSRF via `webhook_url`.** The server would happily POST to *any* URL supplied by the client, including internal addresses like `http://localhost:5432` — letting a client probe the server's internal network. **Fix:** `WebhookService.isUrlSafe()` resolves the hostname and rejects loopback, private (`10.x`/`172.16-31.x`/`192.168.x`), and link-local addresses before the request is even accepted.

**Still-honest residual risks (disclosed, not fixed — reasonable for this project's scope):**
- No DB connection pooling — a large burst of concurrent traffic (hundreds+, not the 25 we tested) could exhaust Postgres's connection limit.
- The 30-second stale-idempotency-key reclaim has a theoretical edge case: a *genuinely slow* (not crashed) request could have its key reclaimed by mistake if it runs past 30s.
- The hand-rolled JSON parser is regex-based and fine for this fixed, simple schema, but wouldn't survive adversarial/malformed input in a general-purpose API.
- No request body size limit.

## What this is honestly scoped as

A single-service backend proving understanding of payment-system
correctness problems (idempotency, ledger consistency, safe
concurrency) — not a distributed, multi-node, production payment
network. That's the honest, defensible framing for a resume bullet or
an interview.
