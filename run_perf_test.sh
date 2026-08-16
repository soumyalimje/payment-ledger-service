#!/bin/bash
cd "$(dirname "$0")"
brew services start postgresql@16
sleep 2
psql -d payment_ledger -f sql/schema.sql > /dev/null

java -cp "out:postgresql.jar" PaymentServer > perf_server.log 2>&1 &
SERVER_PID=$!
sleep 4

echo "===== Measuring single-request latency (20 sequential requests) ====="
rm -f /tmp/latencies.txt
for i in $(seq 1 20); do
  curl -s -o /dev/null -w "%{time_total}\n" -X POST http://localhost:8090/payments \
    -d "{\"idempotency_key\":\"perf-seq-$i\",\"from_account\":\"customer_1\",\"to_account\":\"merchant_A\",\"amount\":10}" \
    >> /tmp/latencies.txt
done
echo "Average latency (ms):"
awk '{sum+=$1; count++} END {printf "%.2f ms\n", (sum/count)*1000}' /tmp/latencies.txt

echo ""
echo "===== Measuring throughput: 50 concurrent distinct requests, wall-clock time ====="
START=$(date +%s.%N)
CURL_PIDS=""
for i in $(seq 1 50); do
  curl -s -o /dev/null -X POST http://localhost:8090/payments \
    -d "{\"idempotency_key\":\"perf-conc-$i\",\"from_account\":\"customer_1\",\"to_account\":\"merchant_A\",\"amount\":5}" &
  CURL_PIDS="$CURL_PIDS $!"
done
wait $CURL_PIDS
END=$(date +%s.%N)
ELAPSED=$(echo "$END - $START" | bc)
echo "50 concurrent requests completed in ${ELAPSED}s"
echo "scale=2; 50 / $ELAPSED" | bc
echo "requests/sec"

kill $SERVER_PID 2>/dev/null || true
