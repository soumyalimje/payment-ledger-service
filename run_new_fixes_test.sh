#!/bin/bash
cd "$(dirname "$0")"
brew services start postgresql@16
sleep 2
psql -d payment_ledger -f sql/schema.sql > /dev/null

java -cp "out:postgresql.jar" PaymentServer > new_fixes_server.log 2>&1 &
SERVER_PID=$!
sleep 4

echo "===== Overflow cap: amount above MAX_TRANSFER_AMOUNT must be rejected ====="
curl -s -w "\nHTTP_STATUS:%{http_code}\n" --max-time 5 -X POST http://localhost:8090/payments \
  -d '{"idempotency_key":"overflow-1","from_account":"customer_1","to_account":"merchant_A","amount":999999999999}'

echo ""
echo "===== Overflow cap: amount just under the cap should succeed normally (small legit amount for sanity) ====="
curl -s -w "\nHTTP_STATUS:%{http_code}\n" --max-time 5 -X POST http://localhost:8090/payments \
  -d '{"idempotency_key":"overflow-2","from_account":"customer_1","to_account":"merchant_A","amount":200}'

echo ""
echo "===== SSRF: webhook_url pointing at localhost must be rejected ====="
curl -s -w "\nHTTP_STATUS:%{http_code}\n" --max-time 5 -X POST http://localhost:8090/payments \
  -d '{"idempotency_key":"ssrf-1","from_account":"customer_1","to_account":"merchant_A","amount":50,"webhook_url":"http://localhost:5432/steal"}'

echo ""
echo "===== SSRF: webhook_url pointing at a private IP (192.168.x) must be rejected ====="
curl -s -w "\nHTTP_STATUS:%{http_code}\n" --max-time 5 -X POST http://localhost:8090/payments \
  -d '{"idempotency_key":"ssrf-2","from_account":"customer_1","to_account":"merchant_A","amount":50,"webhook_url":"http://192.168.1.1/admin"}'

echo ""
echo "===== SSRF: a normal public-looking webhook_url should be ACCEPTED (payment proceeds) ====="
curl -s -w "\nHTTP_STATUS:%{http_code}\n" --max-time 5 -X POST http://localhost:8090/payments \
  -d '{"idempotency_key":"ssrf-3","from_account":"customer_1","to_account":"merchant_A","amount":50,"webhook_url":"https://example.com/hook"}'

kill $SERVER_PID 2>/dev/null || true
