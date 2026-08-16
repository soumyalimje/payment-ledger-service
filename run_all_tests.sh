#!/bin/bash
cd "$(dirname "$0")"

echo "===== Starting PostgreSQL ====="
brew services start postgresql@16
sleep 2

echo "===== Resetting database ====="
psql -d payment_ledger -c "UPDATE accounts SET balance = 1000000 WHERE account_id='customer_1'; UPDATE accounts SET balance = 0 WHERE account_id='merchant_A'; DELETE FROM ledger_entries; DELETE FROM idempotency_keys;"

echo "===== Starting server ====="
java -cp "out:postgresql.jar" PaymentServer > server.log 2>&1 &
SERVER_PID=$!
sleep 4
echo "Server PID: $SERVER_PID"
cat server.log

echo ""
echo "===== TEST 1: Basic payment ====="
curl -s -X POST http://localhost:8090/payments -d '{"idempotency_key":"basic-1","from_account":"customer_1","to_account":"merchant_A","amount":500}'
echo ""

echo ""
echo "===== TEST 2: Duplicate idempotency key (should return cached, no double charge) ====="
curl -s -X POST http://localhost:8090/payments -d '{"idempotency_key":"basic-1","from_account":"customer_1","to_account":"merchant_A","amount":500}'
echo ""
echo "Balances after TEST 1+2 (expect customer_1=999500, merchant_A=500 -- only ONE transfer happened):"
psql -d payment_ledger -c "SELECT account_id, balance FROM accounts;"

echo ""
echo "===== TEST 3: Insufficient funds ====="
curl -s -X POST http://localhost:8090/payments -d '{"idempotency_key":"basic-2","from_account":"customer_1","to_account":"merchant_A","amount":99999999}'
echo ""

echo ""
echo "===== TEST 4: 25 concurrent requests, SAME idempotency key (must only process once) ====="
rm -f /tmp/concurrent_same_key_results.txt
CURL_PIDS=""
for i in $(seq 1 25); do
  curl -s --max-time 10 -X POST http://localhost:8090/payments \
    -d '{"idempotency_key":"concurrent-same-key","from_account":"customer_1","to_account":"merchant_A","amount":1000}' \
    >> /tmp/concurrent_same_key_results.txt &
  CURL_PIDS="$CURL_PIDS $!"
done
wait $CURL_PIDS
echo "--- Response outcomes (status field counts) ---"
grep -o '"status":"[A-Z]*"' /tmp/concurrent_same_key_results.txt | sort | uniq -c
echo "--- error_code counts (to see how many hit in_flight vs success) ---"
grep -o '"error_code":"[a-z_]*"' /tmp/concurrent_same_key_results.txt | sort | uniq -c
echo "--- ledger_entries created for this key's transfer (must be exactly 2 rows = ONE transfer) ---"
psql -d payment_ledger -c "SELECT transaction_id, account_id, amount FROM ledger_entries ORDER BY entry_id;"

echo ""
echo "===== TEST 5: 25 concurrent requests, DISTINCT idempotency keys, SAME account (ledger must balance, no lost updates) ====="
rm -f /tmp/concurrent_distinct_results.txt
BALANCE_BEFORE=$(psql -d payment_ledger -t -c "SELECT balance FROM accounts WHERE account_id='customer_1';" | tr -d ' ')
echo "customer_1 balance before: $BALANCE_BEFORE"

CURL_PIDS2=""
for i in $(seq 1 25); do
  curl -s --max-time 10 -X POST http://localhost:8090/payments \
    -d "{\"idempotency_key\":\"concurrent-distinct-$i\",\"from_account\":\"customer_1\",\"to_account\":\"merchant_A\",\"amount\":100}" \
    >> /tmp/concurrent_distinct_results.txt &
  CURL_PIDS2="$CURL_PIDS2 $!"
done
wait $CURL_PIDS2

echo "--- outcomes ---"
grep -o '"status":"[A-Z]*"' /tmp/concurrent_distinct_results.txt | sort | uniq -c

BALANCE_AFTER=$(psql -d payment_ledger -t -c "SELECT balance FROM accounts WHERE account_id='customer_1';" | tr -d ' ')
MERCHANT_BALANCE=$(psql -d payment_ledger -t -c "SELECT balance FROM accounts WHERE account_id='merchant_A';" | tr -d ' ')
echo "customer_1 balance after: $BALANCE_AFTER (expect exactly BEFORE - 2500, i.e. 25 x 100)"
echo "merchant_A balance: $MERCHANT_BALANCE"

echo ""
echo "--- Ledger integrity check: every transaction_id's entries must sum to zero ---"
psql -d payment_ledger -c "SELECT transaction_id, SUM(amount) as should_be_zero FROM ledger_entries GROUP BY transaction_id HAVING SUM(amount) <> 0;"
echo "(empty result above = PASS, every transaction balances)"

echo ""
echo "===== Shutting down server ====="
kill $SERVER_PID 2>/dev/null || true
