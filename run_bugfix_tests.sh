#!/bin/bash
# Legacy entry point -- bug regressions are now assertions inside the
# hardened functional suite (Bug 1-7 cases, TEST 3/7-12).
cd "$(dirname "$0")"
exec bash tests/run_functional_tests.sh
