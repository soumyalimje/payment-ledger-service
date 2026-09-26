#!/bin/bash
# Legacy entry point -- SSRF/overflow cases are now assertions inside the
# hardened functional suite (TEST 11-12).
cd "$(dirname "$0")"
exec bash tests/run_functional_tests.sh
