#!/bin/bash
cd "$(dirname "$0")"

echo "===== Starting PostgreSQL ====="
brew services start postgresql@16
sleep 2

echo "===== Fresh schema ====="
psql -d payment_ledger -f sql/schema.sql > /dev/null

echo "===== Starting server ====="
java -cp "out:postgresql.jar" PaymentServer > bugfix_server.log 2>&1 &
SERVER_PID=$!
sleep 4

echo ""
echo "===== BUG 1 REGRESSION: same idempotency key, DIFFERENT amount ====="
echo "--- first call (amount 500) ---"
curl -s -X POST http://localhost:8090/payments -d '{"idempotency_key":"bug1","from_account":"customer_1","to_account":"merchant_A","amount":500}'
echo ""
echo "--- second call, SAME key, amount 300 (must be rejected, not silently return old result) ---"
curl -s -w "\nHTTP_STATUS:%{http_code}\n" -X POST http://localhost:8090/payments -d '{"idempotency_key":"bug1","from_account":"customer_1","to_account":"merchant_A","amount":300}'

echo ""
echo "===== BUG 2 REGRESSION: invalid account no longer strands the idempotency key ====="
echo "--- first call with bad account (should fail cleanly, not hang) ---"
curl -s -w "\nHTTP_STATUS:%{http_code}\n" --max-time 5 -X POST http://localhost:8090/payments -d '{"idempotency_key":"bug2","from_account":"fake_account","to_account":"merchant_A","amount":100}'
echo ""
echo "--- retry SAME key + SAME bad account (must return cached FAILED, 422, not stuck in_flight) ---"
curl -s -w "\nHTTP_STATUS:%{http_code}\n" --max-time 5 -X POST http://localhost:8090/payments -d '{"idempotency_key":"bug2","from_account":"fake_account","to_account":"merchant_A","amount":100}'

echo ""
echo "===== BUG 3 REGRESSION: self-transfer must be rejected ====="
curl -s -w "\nHTTP_STATUS:%{http_code}\n" --max-time 5 -X POST http://localhost:8090/payments -d '{"idempotency_key":"bug3","from_account":"customer_1","to_account":"customer_1","amount":100}'

echo ""
echo "===== BUG 4 REGRESSION: negative amount must be rejected cleanly, not crash ====="
curl -s -w "\nHTTP_STATUS:%{http_code}\n" --max-time 5 -X POST http://localhost:8090/payments -d '{"idempotency_key":"bug4","from_account":"customer_1","to_account":"merchant_A","amount":-500}'

echo ""
echo "===== BUG 4b REGRESSION: absurdly oversized number must not crash the server ====="
curl -s -w "\nHTTP_STATUS:%{http_code}\n" --max-time 5 -X POST http://localhost:8090/payments -d '{"idempotency_key":"bug4b","from_account":"customer_1","to_account":"merchant_A","amount":99999999999999999999999999999}'

echo ""
echo "===== BUG 5 REGRESSION: malformed JSON body must not hang the connection ====="
curl -s -w "\nHTTP_STATUS:%{http_code}\n" --max-time 5 -X POST http://localhost:8090/payments -d 'not even json'

echo ""
echo "===== Confirming server is STILL ALIVE after all the bad input above ====="
curl -s --max-time 5 http://localhost:8090/health
echo ""

echo ""
echo "===== Server log tail (checking for any crash traces) ====="
tail -30 bugfix_server.log

echo ""
echo "===== Shutting down ====="
kill $SERVER_PID 2>/dev/null || true
