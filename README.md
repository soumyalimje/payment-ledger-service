# Payment Ledger Service

[![CI](https://github.com/soumyalimje/payment-ledger-service/actions/workflows/ci.yml/badge.svg)](https://github.com/soumyalimje/payment-ledger-service/actions/workflows/ci.yml)

A backend-only service modeling the core reliability guarantees of a payment
gateway like Razorpay: idempotency, a database-enforced double-entry ledger,
safe concurrency, and durable webhook delivery. No UI — this is an API you
exercise with curl, and a test suite that fails loudly.

## Quickstart

```bash
docker-compose up --build        # Postgres + app, schema auto-applied
# ...or locally: see "Running without Docker" below
```

```bash
curl -X POST http://localhost:8090/payments \
  -d '{"idempotency_key":"abc123","from_account":"customer_1","to_account":"merchant_A","amount":500,"webhook_url":"https://example.com/hook"}'
```

## Tests — run by CI on every push

One command locally, zero edits needed for CI (the same scripts run against
Homebrew Postgres on a Mac and a service container on GitHub Actions via
`DB_HOST`/`DB_PORT`/`DB_NAME` env config):

```bash
bash tests/run_functional_tests.sh        # 28 assertions: functional, concurrency, all 7 bug regressions
bash tests/run_webhook_restart_test.sh    # proves retry state survives a process crash
```

Exit code 0 only if every assertion passes. What the suites actually prove:

| Property | How it's proven |
|---|---|
| No double-charging | 25 concurrent requests, same idempotency key: balance moved exactly once |
| No lost updates | 25 concurrent distinct transfers: final balance exact, zero unbalanced transactions |
| Bug 1 regression | Key reuse with different params → `409 idempotency_key_reused` |
| Bug 2 regression | Validation failure resolves the key to `FAILED`; retry replays `422`, never stuck `in_flight` |
| Bugs 3–5 regressions | Self-transfer, negative/oversized amounts, malformed JSON — all clean `400`s, server stays up |
| Bug 6 regression | Amount above the overflow cap rejected |
| Bug 7 regression | Loopback/private webhook URLs rejected (`SSRF`), public URLs accepted |
| **Restart safety (v2)** | Webhook killed mid-retry with a `PENDING` row: the next boot recovers it and completes the full 4-attempt backoff lifecycle |

## The four guarantees

**Idempotency.** `IdempotencyService.checkAndStart()` does
`INSERT ... ON CONFLICT (idempotency_key) DO NOTHING`. Postgres itself
guarantees only one concurrent insert of the same key wins — atomicity comes
from the unique constraint, not from check-then-insert in Java (which has its
own race).

**Double-entry ledger.** Every transfer writes two rows to `ledger_entries`
(negative debit, positive credit) sharing one `transaction_id`. A deferred
constraint trigger (`check_ledger_balance()`) runs at COMMIT and aborts the
whole transaction if a `transaction_id`'s entries don't sum to zero — enforced
by the database, not trusted from application code.

**Concurrency control.** `LedgerService.transfer()` uses
`SELECT ... FOR UPDATE` on both account rows, always locked in a fixed
alphabetical order regardless of from/to — which is what makes deadlock
structurally impossible when two transfers touch the same accounts in
opposite order.

**Webhook delivery — persisted (v2).** Every notification is a row in
`webhook_deliveries`, not just an in-memory queue entry. The background
worker retries with exponential backoff (2s → 4s → 8s, max 4 attempts); on
startup, every still-`PENDING` row is reloaded and its schedule resumes
exactly where the dead process left off. Delivery is **at-least-once**:
if the process dies after the POST reached the receiver but before the row
was marked `DELIVERED`, the next boot delivers again — receivers dedupe on
`transaction_id`, the same tradeoff real gateways make and document.
Previously this state lived only in memory and a restart silently dropped
every owed retry; a test now proves the new behavior.

## The performance investigation that failed (kept on purpose)

The "obvious" optimization — connection pooling with HikariCP — was
implemented, measured rigorously, and **reverted because the data said it
made things worse**:

| | Before (plain `DriverManager`) | After (HikariCP pool) |
|---|---|---|
| Avg latency, sequential | 1.53 ms | 115.44 ms (**75× worse**) |
| Throughput, 50 concurrent | 129 req/s | 25 req/s (**5× worse**) |

At this project's actual scale — Postgres on the same machine over localhost,
tens of requests — opening a raw connection was already nearly free, while
the pool added validation, bookkeeping, and a 1.7s warm-up. Pooling pays off
when connections are genuinely expensive (remote DB, real network latency,
thousands of req/s); neither was true here. The full reasoning is preserved
as a comment in `Db.java`. Measuring before adopting a "best practice" beat
cargo-culting it — this experiment is the most transferable thing in the repo.

## The bug audit

A deliberate adversarial re-read of every file (then a second, deeper pass)
found **7 real bugs**, all fixed with regression tests:

1. **Idempotency key reuse with different parameters silently accepted** — `request_hash` existed in the schema but was never compared. Now returns `409 idempotency_key_reused`.
2. **An invalid input permanently stranded an idempotency key** (most serious) — an uncaught exception skipped the mark-FAILED step, so the key returned `409 in_flight` forever. Fixed with defense in depth: validation caught explicitly, a `RuntimeException` safety net, and atomic 30s stale-key reclaim for genuine crashes.
3. **Self-transfers silently allowed** — now rejected with `400`.
4. **Negative/oversized amounts crashed or parsed wrong** — regex and parsing fixed; clean `400`s.
5. **No top-level exception handler** — any unexpected error hung the client's connection; now a guaranteed `500`.
6. **Balance overflow** (second pass) — an amount near `Long.MAX_VALUE` could silently wrap a balance negative; capped by `MAX_TRANSFER_AMOUNT`.
7. **SSRF via `webhook_url`** (second pass) — the server would POST anywhere, including `http://localhost:5432`; now loopback/private/link-local targets are rejected before the request is accepted.

## Architecture (7 files, ~500 lines — read in this order)

1. `sql/schema.sql` — 4 tables + the DB-level ledger trigger + the durable webhook table
2. `src/Db.java` — one method, opens a JDBC connection (env-driven config)
3. `src/IdempotencyService.java` — the atomic "claim the key" logic
4. `src/LedgerService.java` — money movement with `FOR UPDATE` locking
5. `src/WebhookService.java` — persisted retry queue with startup recovery
6. `src/PaymentService.java` — orchestrates the above for one request
7. `src/PaymentServer.java` — the HTTP layer (`POST /payments`, `GET /health`)
8. `src/Json.java` — tiny hand-rolled JSON helpers, no external library

Built with plain `javac`, JDK built-in `HttpServer`, and raw JDBC — zero
framework dependencies, so every line of request handling is visible and
explainable.

## Running without Docker

Requires Java 21+, PostgreSQL, and the PostgreSQL JDBC jar
(`postgresql.jar` in the repo root, or `/usr/share/java/postgresql.jar` on Debian/Ubuntu).

```bash
createdb payment_ledger
psql -d payment_ledger -f sql/schema.sql
javac -cp postgresql.jar -d out src/*.java
java -cp "out:postgresql.jar" PaymentServer
```

Connection details come from `DB_URL` / `DB_USER` / `DB_PASSWORD` env vars
(sensible local defaults; this is exactly how the CI workflow and
docker-compose supply theirs).

## Known limitations, stated up front

- **No connection pooling** — deliberately, per the measurement above; would matter at much larger scale.
- **Hand-rolled JSON parsing** — regex-based, fine for this fixed request shape, not for adversarial general-purpose input.
- **No request body size limit** and **no API authentication**.
- **The 30s stale-key reclaim has a theoretical edge case**: a genuinely slow (not crashed) request running past 30s could have its key reclaimed.
- **At-least-once webhooks** — receivers must dedupe on `transaction_id` (documented tradeoff, not an oversight).
- No real bank/UPI/card-network integration — all accounts are simulated; this models payment-system *correctness*, not a production gateway.

## What's honestly scoped here

A single-service backend proving understanding of the correctness problems
underneath every real payment system — idempotency, ledger consistency, safe
concurrency, durable notifications — tested under real concurrent load,
audited for bugs, and documented including its limits. Not a distributed,
multi-node production network.
