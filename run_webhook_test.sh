#!/bin/bash
# Legacy entry point -- superseded by tests/run_webhook_restart_test.sh,
# which proves the stronger property: retries survive a server RESTART.
cd "$(dirname "$0")"
exec bash tests/run_webhook_restart_test.sh
