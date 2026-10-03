# ============================================================
# Shared test infrastructure, sourced by every run_*_test.sh.
#
# Gives tests three things they never had before:
#   1. Real pass/fail assertions with counters and a summary,
#      and an exit code CI can act on (0 = all passed).
#   2. Environment-driven DB config (DB_HOST/DB_PORT/DB_NAME/
#      DB_USER/DB_PASSWORD) so the SAME scripts run on a Mac
#      with Homebrew Postgres and in GitHub Actions with a
#      service container, with zero edits.
#   3. Common server lifecycle helpers (start/stop/compile).
# ============================================================

# --- DB config with local-first defaults, overridable by env ---
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
DB_NAME="${DB_NAME:-payment_ledger}"
DB_USER="${DB_USER:-postgres}"
DB_PASSWORD="${DB_PASSWORD:-postgres}"
export DB_HOST DB_PORT DB_NAME DB_USER DB_PASSWORD

# JDBC URL consumed by Db.java (matches its env-var contract)
export DB_URL="jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}"

# psql connection string
export PGHOST="$DB_HOST" PGPORT="$DB_PORT" PGDATABASE="$DB_NAME" PGUSER="$DB_USER"
export PGPASSWORD="$DB_PASSWORD"
PSQL=(psql --no-psqlrc --set ON_ERROR_STOP=1)

BASE_URL="${BASE_URL:-http://localhost:8090}"
JAR="${JAR:-postgresql.jar}"
CP="out:${JAR}"

# --- Counters ---
PASS_COUNT=0
FAIL_COUNT=0

# --- Assertion helpers -------------------------------------------------
# assert_equals "expected" "actual" "label"
assert_equals() {
  local expected="$1" actual="$2" label="$3"
  if [ "$expected" = "$actual" ]; then
    echo "  PASS: $label"
    PASS_COUNT=$((PASS_COUNT + 1))
  else
    echo "  FAIL: $label"
    echo "    expected: [$expected]"
    echo "    actual:   [$actual]"
    FAIL_COUNT=$((FAIL_COUNT + 1))
  fi
}

# assert_contains "needle" "haystack" "label"
assert_contains() {
  local needle="$1" haystack="$2" label="$3"
  case "$haystack" in
    *"$needle"*)
      echo "  PASS: $label"
      PASS_COUNT=$((PASS_COUNT + 1))
      ;;
    *)
      echo "  FAIL: $label"
      echo "    expected to contain: [$needle]"
      echo "    actual (first 300 chars): [${haystack:0:300}]"
      FAIL_COUNT=$((FAIL_COUNT + 1))
      ;;
  esac
}

# finish: print summary and exit non-zero on any failure (what CI needs)
finish() {
  echo ""
  echo "=============================================="
  echo " TESTS: ${PASS_COUNT} passed, ${FAIL_COUNT} failed"
  echo "=============================================="
  [ "$FAIL_COUNT" -eq 0 ]
}

# --- HTTP helper: returns "status_code<TAB>body" on one line ------------
http_post() {
  local path="$1" data="$2" timeout="${3:-10}"
  # trailing newline is load-bearing: concurrent jobs append `tail -1` output
  # to a shared file, and without it the codes concatenate into one blob
  curl -s --max-time "$timeout" -w $'\n%{http_code}\n' -X POST "${BASE_URL}${path}" -d "$data"
}
# get status / body from http_post output
status_of() { printf '%s' "$1" | tail -1; }
body_of()   { printf '%s' "$1" | sed '$d'; }

# --- DB helpers ----------------------------------------------------------
db_query() { "${PSQL[@]}" -t -A -c "$1"; }

# Re-run schema from scratch (idempotent -- schema.sql drops and recreates)
db_reset_schema() { "${PSQL[@]}" -f sql/schema.sql > /dev/null; }

# Compile, tolerating the driver being at either of the two usual locations
compile() {
  local jar_arg
  if [ -f "$JAR" ]; then jar_arg="$JAR"
  elif [ -f "/usr/share/java/postgresql.jar" ]; then jar_arg="/usr/share/java/postgresql.jar"
  else echo "ERROR: PostgreSQL JDBC driver not found (looked for ./$JAR and /usr/share/java/postgresql.jar)" >&2; return 1; fi
  mkdir -p out
  javac -cp "$jar_arg" -d out src/*.java tests/*.java
}

# --- Server lifecycle -----------------------------------------------------
SERVER_PID=""

start_server() {
  # Pin PORT explicitly: the service honors the PORT env var (Render-style),
  # and some dev shells export PORT=0 ("auto-assign"), which would make the
  # JVM bind a random port and the health check below would never reach it.
  PORT="${BASE_URL##*:}" java -cp "$CP" PaymentServer > server.log 2>&1 &
  SERVER_PID=$!
  for _ in $(seq 1 40); do
    if curl -s --max-time 2 "${BASE_URL}/health" > /dev/null 2>&1; then
      return 0
    fi
    sleep 0.25
  done
  echo "ERROR: server did not become healthy in time; last log lines:" >&2
  tail -20 server.log >&2
  return 1
}

stop_server() {
  if [ -n "$SERVER_PID" ]; then
    kill "$SERVER_PID" 2>/dev/null || true
    wait "$SERVER_PID" 2>/dev/null || true
    SERVER_PID=""
  fi
}

# Homebrew Postgres exists only on a local Mac; CI uses a service container,
# so only attempt brew when we're clearly on that local setup.
ensure_local_postgres() {
  if command -v brew > /dev/null 2>&1; then
    brew services list 2>/dev/null | grep -q "postgresql@16.*started" || brew services start postgresql@16 > /dev/null 2>&1
    sleep 2
  fi
}
