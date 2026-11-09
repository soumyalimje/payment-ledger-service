#!/bin/bash
# ============================================================
# Runs the full test suite (34 assertions total across functional,
# concurrency, bug regressions, and webhook restart recovery).
# Exit code 0 only if every assertion passes.
# ============================================================
set -e
cd "$(dirname "$0")/.."

echo "=== Running functional + concurrency suite (28 assertions) ==="
bash tests/run_functional_tests.sh

echo ""
echo "=== Running webhook restart-safety suite (6 assertions) ==="
bash tests/run_webhook_restart_test.sh

echo ""
echo "============================================================"
echo " ALL TESTS PASSED (34/34 assertions)"
echo "============================================================"
