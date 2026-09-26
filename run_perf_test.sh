#!/bin/bash
# Manual benchmark, not part of CI: measures latency and throughput and
# prints a comparison table. No assertions -- numbers are for the README
# and for interview storytelling, not pass/fail gates.
cd "$(dirname "$0")"
source tests/lib.sh

ensure_local_postgres
db_reset_schema
compile || exit 1
start_server || exit 1
trap stop_server EXIT

echo "===== Sequential latency: 20 requests ====="
rm -f /tmp/latencies.txt
for i in $(seq 1 20); do
  curl -s -o /dev/null -w "%{time_total}\n" -X POST "${BASE_URL}/payments" \
    -d "{\"idempotency_key\":\"perf-seq-$i\",\"from_account\":\"customer_1\",\"to_account\":\"merchant_A\",\"amount\":10}" \
    >> /tmp/latencies.txt
done
awk '{sum+=$1; count++} END {printf "Average latency: %.2f ms\n", (sum/count)*1000}' /tmp/latencies.txt

echo ""
echo "===== Throughput: 50 concurrent distinct requests ====="
START=$(date +%s.%N)
CURL_PIDS=""
for i in $(seq 1 50); do
  curl -s -o /dev/null -X POST "${BASE_URL}/payments" \
    -d "{\"idempotency_key\":\"perf-conc-$i\",\"from_account\":\"customer_1\",\"to_account\":\"merchant_A\",\"amount\":5}" &
  CURL_PIDS="$CURL_PIDS $!"
done
# Wait only on the curl jobs, not the background server.
wait $CURL_PIDS
END=$(date +%s.%N)
ELAPSED=$(awk -v a="$START" -v b="$END" 'BEGIN {printf "%.3f", b - a}')
awk -v e="$ELAPSED" 'BEGIN {printf "50 requests in %ss -> %.1f requests/sec\n", e, 50/e}'

stop_server
trap - EXIT
