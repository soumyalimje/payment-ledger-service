#!/bin/bash
# ============================================================
# Webhook persistence + restart-safety suite (v2 feature).
#
# Proves the thing the old code could not do: a webhook that is
# mid-retry when the process DIES is picked back up by the next
# boot, its backoff schedule resumes exactly where it left off,
# and the delivery lifecycle completes with zero lost retries.
#
# Runs at the service layer via tests/RestartRecoveryDriver.java
# (the HTTP API's SSRF guard correctly refuses loopback webhook
# URLs, so end-to-end testing of loopback retries is impossible
# BY DESIGN -- SSRF itself is covered in the functional suite).
#
# Uses a local fail-fast endpoint (tests/FailFastServer.java) so
# every attempt fails in milliseconds: deterministic, offline.
# ============================================================
cd "$(dirname "$0")/.."
source tests/lib.sh

ensure_local_postgres
db_reset_schema
compile || exit 1

HITFILE=$(mktemp)
FAILPORT=8091
java tests/FailFastServer.java $FAILPORT "$HITFILE" > /dev/null 2>&1 &
FAILPID=$!
trap 'kill $FAILPID 2>/dev/null; pkill -f RestartRecoveryDriver 2>/dev/null; rm -f "$HITFILE"' EXIT
for _ in $(seq 1 20); do
  if nc -z localhost $FAILPORT 2>/dev/null; then break; fi
  sleep 0.25
done

# ---------- Phase 1: enqueue -> durable reschedule -> hard crash ----------
java -cp "$CP" tests/RestartRecoveryDriver.java phase1 $FAILPORT crash-1
p1=$?
assert_equals "0" "$p1" "phase 1: enqueue + reschedule + crash completed"
hits_1=$(wc -l < "$HITFILE" | tr -d ' ')
assert_equals "1" "$hits_1" "exactly one delivery attempt before the crash"
state_1=$(db_query "SELECT status || ':' || attempt FROM webhook_deliveries WHERE delivery_id = 1")
assert_equals "PENDING:2" "$state_1" "durable state after crash: PENDING with attempt 2 scheduled"

# ---------- Phase 2: a FRESH process must recover and finish the job ----------
java -cp "$CP" tests/RestartRecoveryDriver.java phase2 $FAILPORT crash-1
p2=$?
assert_equals "0" "$p2" "phase 2: recovery process completed"

sleep 1 # let the last DB write settle
state_2=$(db_query "SELECT status || ':' || attempt FROM webhook_deliveries WHERE delivery_id = 1")
assert_equals "ABANDONED:4" "$state_2" "lifecycle completed across crash: ABANDONED after 4 attempts"
hits_2=$(wc -l < "$HITFILE" | tr -d ' ')
assert_equals "4" "$hits_2" "exactly 4 delivery attempts total (1 pre-crash + 3 post-recovery)"

finish
