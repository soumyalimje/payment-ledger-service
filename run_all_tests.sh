#!/bin/bash
# Legacy entry point kept for muscle memory -- now runs the hardened,
# assertion-based suite (see tests/run_functional_tests.sh).
cd "$(dirname "$0")"
exec bash tests/run_functional_tests.sh
