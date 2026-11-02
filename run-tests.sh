#!/usr/bin/env bash
# Runs every automated test suite: backend unit + integration (Testcontainers), frontend unit,
# and Playwright E2E against the already running Docker Compose stack.
# Exits non-zero on the first failing suite, and also fails if any suite reports skipped tests.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE_URL="${E2E_BASE_URL:-http://localhost:3000}"
LOG_DIR="$(mktemp -d)"

fail() { echo "run-tests: $*" >&2; exit 1; }

for cmd in docker java node npm curl; do
  command -v "$cmd" >/dev/null || fail "'$cmd' is required but not on PATH"
done

curl -fsS "$BASE_URL/actuator/health" | grep -q '"status":"UP"' \
  || fail "the Compose stack is not healthy at $BASE_URL; run 'docker compose up --build -d --wait' first"

# The surefire/failsafe summary, node:test TAP summary and Playwright list summary each print a
# skipped count; any non-zero count fails the run.
check_no_skips() {
  local log="$1" pattern="$2" suite="$3"
  if grep -Eq "$pattern" "$log"; then
    fail "$suite reported skipped tests (see $log)"
  fi
}

echo "==> Backend unit and integration tests"
(cd "$ROOT/backend" && ./mvnw -B clean verify -DskipTests=false -Dmaven.test.skip=false) \
  2>&1 | tee "$LOG_DIR/backend.log"
check_no_skips "$LOG_DIR/backend.log" 'Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [1-9]' backend

echo "==> Frontend unit tests"
(cd "$ROOT/frontend" && npm ci && npm test) 2>&1 | tee "$LOG_DIR/frontend.log"
check_no_skips "$LOG_DIR/frontend.log" '^# (skipped|todo) [1-9]' "frontend unit tests"

echo "==> Playwright E2E tests against $BASE_URL"
(cd "$ROOT/frontend" && npx playwright install chromium && E2E_BASE_URL="$BASE_URL" npx playwright test --forbid-only) \
  2>&1 | tee "$LOG_DIR/e2e.log"
check_no_skips "$LOG_DIR/e2e.log" '^ *[0-9]+ (skipped|flaky)' "Playwright"

echo "==> All suites passed with no skipped tests (logs: $LOG_DIR)"
