#!/bin/bash
cd "$(dirname "$0")"
brew services start postgresql@16
sleep 2

java -cp "out:postgresql.jar" PaymentServer > webhook_server.log 2>&1 &
SERVER_PID=$!
sleep 4

echo "===== Firing payment with an unreachable webhook_url ====="
curl -s -X POST http://localhost:8090/payments \
  -d '{"idempotency_key":"webhook-test-1","from_account":"customer_1","to_account":"merchant_A","amount":50,"webhook_url":"http://localhost:59999/nonexistent"}'
echo ""

echo "Waiting 9s (should see attempt 1 fail immediately, retry at 2s, retry at ~6s/4s-after-that)..."
sleep 9

echo ""
echo "===== webhook_server.log (webhook lines only) ====="
grep webhook webhook_server.log

kill $SERVER_PID 2>/dev/null || true
