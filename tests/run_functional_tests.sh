#!/bin/bash
# ============================================================
# Main functional + concurrency + regression suite.
# Runs unchanged on a Mac (Homebrew Postgres) and in CI
# (GitHub Actions Postgres service) via tests/lib.sh env config.
# Exit code 0 only if every assertion passes.
# ============================================================
cd "$(dirname "$0")/.."
source tests/lib.sh

ensure_local_postgres
db_reset_schema
compile || exit 1
start_server || exit 1
trap stop_server EXIT

# ---------- TEST 1: basic payment ----------
resp=$(http_post /payments '{"idempotency_key":"t1-basic","from_account":"customer_1","to_account":"merchant_A","amount":500}')
assert_equals "200" "$(status_of "$resp")" "basic payment returns 200"
assert_contains '"status":"SUCCESS"' "$(body_of "$resp")" "basic payment body says SUCCESS"
assert_contains '"amount":500' "$(body_of "$resp")" "basic payment body echoes amount"

bal=$(db_query "SELECT balance FROM accounts WHERE account_id='merchant_A'")
assert_equals "500" "$bal" "merchant balance is exactly 500 after one transfer"

# ---------- TEST 2: duplicate idempotency key (same params) ----------
resp2=$(http_post /payments '{"idempotency_key":"t1-basic","from_account":"customer_1","to_account":"merchant_A","amount":500}')
assert_equals "200" "$(status_of "$resp2")" "duplicate key returns cached 200"
assert_contains '"status":"SUCCESS"' "$(body_of "$resp2")" "duplicate key body is the cached success"
bal=$(db_query "SELECT balance FROM accounts WHERE account_id='merchant_A'")
assert_equals "500" "$bal" "no double charge: balance still 500"

# ---------- TEST 3: duplicate key with DIFFERENT params (Bug 1) ----------
resp3=$(http_post /payments '{"idempotency_key":"t1-basic","from_account":"customer_1","to_account":"merchant_A","amount":300}')
assert_equals "409" "$(status_of "$resp3")" "key reuse with different amount rejected 409"
assert_contains 'idempotency_key_reused' "$(body_of "$resp3")" "rejection names idempotency_key_reused"

# ---------- TEST 4: insufficient funds ----------
resp4=$(http_post /payments '{"idempotency_key":"t4-funds","from_account":"customer_1","to_account":"merchant_A","amount":99999999}')
assert_equals "422" "$(status_of "$resp4")" "insufficient funds returns 422"
assert_contains 'insufficient_funds' "$(body_of "$resp4")" "error names insufficient_funds"
n=$(db_query "SELECT COUNT(*) FROM ledger_entries WHERE transaction_id IN (SELECT idempotency_key FROM idempotency_keys WHERE idempotency_key='t4-funds')" )
assert_equals "0" "$n" "failed transfer wrote no ledger entries"

# ---------- TEST 5: 25 concurrent requests, SAME key ----------
# Exactly one may win; 24 must be rejected. Postgres's unique constraint
# on idempotency_key is the referee -- this test proves it.
rm -f /tmp/t5_results.txt
CURL_PIDS=""
for i in $(seq 1 25); do
  http_post /payments '{"idempotency_key":"t5-race","from_account":"customer_1","to_account":"merchant_A","amount":100}' \
    | tail -1 >> /tmp/t5_results.txt &
  CURL_PIDS="$CURL_PIDS $!"
done
# Wait ONLY on the curl jobs -- a bare `wait` would also wait for the
# background server process, which never exits (learned the hard way).
wait $CURL_PIDS
ok=$(grep -c '^200$' /tmp/t5_results.txt || true)
conflict=$(grep -c '^409$' /tmp/t5_results.txt || true)
assert_equals "25" "$((ok + conflict))" "same-key race: all 25 requests answered"
# We do NOT hard-assert "exactly 1 x 200, 24 x 409": a request that arrives
# after the winner finished may legitimately get the cached 200 instead of
# 409 -- both are correct, neither re-processes. The invariant that must
# always hold is enforced by the balance assertion below: money moved ONCE.
bal=$(db_query "SELECT balance FROM accounts WHERE account_id='merchant_A'")
assert_equals "600" "$bal" "same-key race moved money exactly once (500+100)"

# ---------- TEST 6: 25 concurrent distinct transfers ----------
CURL_PIDS=""
for i in $(seq 1 25); do
  http_post /payments "{\"idempotency_key\":\"t6-conc-$i\",\"from_account\":\"customer_1\",\"to_account\":\"merchant_A\",\"amount\":10}" \
    | tail -1 > /dev/null &
  CURL_PIDS="$CURL_PIDS $!"
done
wait $CURL_PIDS
bal=$(db_query "SELECT balance FROM accounts WHERE account_id='merchant_A'")
assert_equals "850" "$bal" "distinct-key race: balance = 600 + 25*10 exactly"
unbalanced=$(db_query "SELECT COUNT(*) FROM (SELECT transaction_id FROM ledger_entries GROUP BY transaction_id HAVING SUM(amount) <> 0) x")
assert_equals "0" "$unbalanced" "distinct-key race: zero unbalanced transactions"

# ---------- TEST 7: Bug 2 regression -- invalid account doesn't strand key ----------
resp7=$(http_post /payments '{"idempotency_key":"t7-bad-acct","from_account":"fake_account","to_account":"merchant_A","amount":100}')
assert_equals "400" "$(status_of "$resp7")" "invalid account returns clean 400"
resp7b=$(http_post /payments '{"idempotency_key":"t7-bad-acct","from_account":"fake_account","to_account":"merchant_A","amount":100}')
assert_equals "422" "$(status_of "$resp7b")" "retry of failed key replays cached failure as 422, not 409 in_flight"

# ---------- TEST 8: Bug 3 -- self-transfer rejected ----------
resp8=$(http_post /payments '{"idempotency_key":"t8-self","from_account":"customer_1","to_account":"customer_1","amount":100}')
assert_equals "400" "$(status_of "$resp8")" "self-transfer rejected"

# ---------- TEST 9: Bug 4 -- negative and oversized amounts ----------
resp9=$(http_post /payments '{"idempotency_key":"t9-neg","from_account":"customer_1","to_account":"merchant_A","amount":-500}')
assert_equals "400" "$(status_of "$resp9")" "negative amount rejected cleanly"
resp9b=$(http_post /payments '{"idempotency_key":"t9-huge","from_account":"customer_1","to_account":"merchant_A","amount":99999999999999999999999999999}')
assert_equals "400" "$(status_of "$resp9b")" "overflow-sized amount rejected cleanly"

# ---------- TEST 10: Bug 5 -- malformed JSON ----------
resp10=$(http_post /payments 'not even json')
assert_equals "400" "$(status_of "$resp10")" "malformed JSON gets a real response, not a hang"

# ---------- TEST 11: overflow cap (Bug 6) ----------
resp11=$(http_post /payments '{"idempotency_key":"t11-cap","from_account":"customer_1","to_account":"merchant_A","amount":999999999999}')
assert_equals "400" "$(status_of "$resp11")" "amount above MAX_TRANSFER_AMOUNT rejected"

# ---------- TEST 12: SSRF protection (Bug 7) ----------
resp12=$(http_post /payments '{"idempotency_key":"t12-ssrf1","from_account":"customer_1","to_account":"merchant_A","amount":50,"webhook_url":"http://localhost:5432/steal"}')
assert_equals "400" "$(status_of "$resp12")" "localhost webhook_url rejected"
assert_contains 'unsafe_webhook_url' "$(body_of "$resp12")" "SSRF rejection names unsafe_webhook_url"
resp12b=$(http_post /payments '{"idempotency_key":"t12-ssrf2","from_account":"customer_1","to_account":"merchant_A","amount":50,"webhook_url":"http://192.168.1.1/admin"}')
assert_equals "400" "$(status_of "$resp12b")" "private-range webhook_url rejected"
resp12c=$(http_post /payments '{"idempotency_key":"t12-ssrf3","from_account":"customer_1","to_account":"merchant_A","amount":50,"webhook_url":"https://example.com/hook"}')
assert_equals "200" "$(status_of "$resp12c")" "public webhook_url accepted, payment proceeds"

# ---------- TEST 13: server survived all abuse above ----------
resp13=$(curl -s --max-time 5 "${BASE_URL}/health")
assert_contains '"status":"ok"' "$resp13" "server still healthy after every bad-input test"

stop_server
trap - EXIT
finish
