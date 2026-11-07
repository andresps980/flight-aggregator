#!/usr/bin/env bash
# Full-stack smoke test (spec 007): proves that `docker compose up --build` boots the whole stack and that it works end
# to end, with the same script locally and in CI. Steps:
#   1. build, then up --wait (AC-1)             4. e2e reconciliation IT against the running gds-mock (AC-4)
#   2. first ingestion COMPLETED (AC-2)         5. mock-beta outage: run PARTIAL, breaker OPEN, search still works (AC-5)
#   3. catalog and searches return data (AC-3)  6. outage removed (AC-6)
# Every HTTP check goes through the frontend's nginx (port 3000), so its proxy is exercised. On failure it prints the
# compose logs (also saved under target/stack-smoke/); it always tears down with `down -v` (AC-7, AC-8).
#
# Usage: scripts/stack-smoke.sh            (needs Docker, curl, jq and JDK 21 for the Maven wrapper)
# Settings (environment):
#   STACK_SMOKE_BUILD_TIMEOUT      seconds for `docker compose build` (default 600; needs `timeout` or `gtimeout`,
#                                  otherwise the build is unbounded and the script says so)
#   STACK_SMOKE_UP_TIMEOUT         seconds for `up --wait` to bring every service to healthy, build excluded (default 300)
#   STACK_SMOKE_INGESTION_TIMEOUT  seconds to wait for each ingestion run (default 180)
#   STACK_SMOKE_EXTRA_COMPOSE      an extra compose file, e.g. CI's build cache settings (default none)
#   STACK_SMOKE_LOG_DIR            where the compose log is saved on failure (default target/stack-smoke)
#   FRONTEND_PORT, POSTGRES_PORT, REDIS_PORT   published ports, as in docker-compose.yml
#
# It runs under its own compose project, so `down -v` never touches the volumes of your own `docker compose up` stack.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

project="flight-aggregator-smoke"
compose=(docker compose -p "$project" -f docker-compose.yml -f docker-compose.ci.yml)
if [ -n "${STACK_SMOKE_EXTRA_COMPOSE:-}" ]; then compose+=(-f "$STACK_SMOKE_EXTRA_COMPOSE"); fi
build_timeout="${STACK_SMOKE_BUILD_TIMEOUT:-600}"
up_timeout="${STACK_SMOKE_UP_TIMEOUT:-300}"
ingestion_timeout="${STACK_SMOKE_INGESTION_TIMEOUT:-180}"
log_dir="${STACK_SMOKE_LOG_DIR:-target/stack-smoke}"
base="http://localhost:${FRONTEND_PORT:-3000}"
outage_provider="mock-beta"
started_at="$(date +%s)"
outage_forced=false
passed=0

pass() { passed=$((passed + 1)); echo "PASS  $*"; }
fail() { echo "FAIL  $*" >&2; exit 1; }
step() { echo; echo "--- $* ($(($(date +%s) - started_at)) s)"; }

# http <method> <path>: sets $code and $body. A connection error is code 000, never an abort.
http() {
  local out
  out="$(curl -sS -X "$1" -w '\n%{http_code}' "$base$2" 2>/dev/null)" || out=$'\n000'
  code="${out##*$'\n'}"
  body="${out%$'\n'*}"
}

recover() { # AC-6: remove the forced outage
  http DELETE "/gds-admin/providers/$outage_provider/outage"
  [ "$code" = 204 ] || fail "DELETE /gds-admin/providers/$outage_provider/outage answered $code, expected 204"
  outage_forced=false
  pass "outage of $outage_provider removed (204)"
}

finish() {
  local status=$?
  trap - EXIT INT TERM
  set +e
  if $outage_forced; then
    echo; echo "--- removing the outage before teardown"
    (recover)
  fi
  if [ "$status" -ne 0 ]; then
    mkdir -p "$log_dir"
    "${compose[@]}" logs --no-color --timestamps > "$log_dir/compose.log" 2>&1
    echo; echo "=== docker compose logs (saved to $log_dir/compose.log) ==="
    cat "$log_dir/compose.log"
    echo "=== end of docker compose logs ==="
  fi
  echo; echo "--- teardown: docker compose down -v"
  "${compose[@]}" down -v --remove-orphans >/dev/null 2>&1
  local elapsed=$(($(date +%s) - started_at))
  echo
  if [ "$status" -eq 0 ]; then
    echo "stack smoke test: PASSED ($passed checks) in $((elapsed / 60)) min $((elapsed % 60)) s (build ${build_seconds:-?} s, health wait ${health_seconds:-?} s)"
  else
    echo "stack smoke test: FAILED (exit $status, $passed checks passed) after $((elapsed / 60)) min $((elapsed % 60)) s"
  fi
  exit "$status"
}

# wait_for_run <path>: polls an ingestion run until it is no longer RUNNING; prints its JSON.
wait_for_run() {
  local deadline=$(($(date +%s) + ingestion_timeout)) status
  while :; do
    http GET "$1"
    if [ "$code" = 200 ]; then
      status="$(jq -er .status <<<"$body" 2>/dev/null)" || fail "GET $1: 200 without a run status (not JSON?): ${body:0:300}"
      if [ "$status" != RUNNING ]; then printf '%s' "$body"; return 0; fi
    fi
    [ "$(date +%s)" -lt "$deadline" ] || fail "GET $1: no finished run within ${ingestion_timeout} s (last: $code ${body:0:300})"
    sleep 2
  done
}

for tool in docker curl jq; do command -v "$tool" >/dev/null || { echo "FAIL  $tool is required" >&2; exit 1; }; done

# A run killed hard (SIGKILL) could not tear down: clean up its leftovers before checking the ports.
"${compose[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
for port in "${FRONTEND_PORT:-3000}" "${POSTGRES_PORT:-5432}" "${REDIS_PORT:-6379}" 8080 8081; do
  if (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null; then
    echo "FAIL  port $port is already in use (another 'docker compose up' stack?); stop it first" >&2
    exit 1
  fi
done

trap finish EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# `up --wait-timeout` bounds only the wait for health after start, not the image builds; so the build is its own step,
# with its own bound, and both durations are reported.
step "AC-1: docker compose build (timeout ${build_timeout} s)"
bounded=()
if command -v timeout >/dev/null; then bounded=(timeout "$build_timeout")
elif command -v gtimeout >/dev/null; then bounded=(gtimeout "$build_timeout")
else echo "note: no timeout/gtimeout command, the build is not bounded"; fi
phase_start="$(date +%s)"
${bounded[@]+"${bounded[@]}"} "${compose[@]}" build || fail "docker compose build failed or exceeded ${build_timeout} s"
build_seconds=$(($(date +%s) - phase_start))
pass "images built in ${build_seconds} s"

step "AC-1: docker compose up --wait (health timeout ${up_timeout} s)"
phase_start="$(date +%s)"
"${compose[@]}" up -d --wait --wait-timeout "$up_timeout" \
  || fail "the stack did not become healthy within ${up_timeout} s"
health_seconds=$(($(date +%s) - phase_start))
pass "all services healthy in ${health_seconds} s: $("${compose[@]}" ps --format '{{.Service}}' | sort | tr '\n' ' ')"

step "AC-2: first ingestion through nginx"
run="$(wait_for_run /api/v1/ingestions/latest)"
[ "$(jq -r .status <<<"$run")" = COMPLETED ] \
  || fail "first run finished $(jq -r .status <<<"$run"), expected COMPLETED: $(jq -c '{status, failure}' <<<"$run")"
[ "$(jq -r .trigger <<<"$run")" = SCHEDULED ] || fail "the first run was $(jq -r .trigger <<<"$run"), expected SCHEDULED"
pass "first run $(jq -r '.trigger + " " + .status + ", " + (.report.storedItineraries | tostring) + " itineraries stored in " + (.durationMs | tostring) + " ms"' <<<"$run")"

step "AC-3: catalog and search through nginx"
http GET /api/v1/catalog
[ "$code" = 200 ] || fail "GET /api/v1/catalog answered $code"
origin="$(jq -r '.routes[0].origin // empty' <<<"$body")"
destination="$(jq -r '.routes[0].destinations[0] // empty' <<<"$body")"
[ -n "$origin" ] && [ -n "$destination" ] || fail "the catalog lists no route: ${body:0:300}"
pass "catalog lists $(jq '.routes | length' <<<"$body") origins with routes, e.g. $origin -> $destination"
search() { # search <query>: at least one item
  http GET "/api/v1/itineraries$1"
  [ "$code" = 200 ] || fail "GET /api/v1/itineraries$1 answered $code"
  [ "$(jq '.items | length' <<<"$body")" -gt 0 ] || fail "GET /api/v1/itineraries$1 returned no items"
}
search "?limit=5"
pass "unfiltered search returns $(jq '.items | length' <<<"$body") items, cheapest $(jq -r '.items[0].finalPrice' <<<"$body") EUR"
search "?origin=$origin&destination=$destination&limit=5"
pass "search $origin -> $destination returns $(jq '.items | length' <<<"$body") items"

step "AC-4: e2e reconciliation IT against the running gds-mock"
rm -f flight-aggregator/target/failsafe-reports/TEST-*IngestionReconciliationE2EIT.xml
./mvnw -B -ntp -q -pl flight-aggregator verify -Pe2e-tests \
  -Dit.test=IngestionReconciliationE2EIT -Dfailsafe.failIfNoSpecifiedTests=false \
  -Dtest=NoUnitTestsInThisRun -Dsurefire.failIfNoSpecifiedTests=false \
  -Djacoco.skip=true -Dspotless.check.skip=true \
  || fail "IngestionReconciliationE2EIT failed (reports: flight-aggregator/target/failsafe-reports/)"
report_xml="$(ls flight-aggregator/target/failsafe-reports/TEST-*IngestionReconciliationE2EIT.xml 2>/dev/null | head -n1 || true)"
[ -n "$report_xml" ] || fail "IngestionReconciliationE2EIT did not run (no report)"
tests="$(grep -o 'tests="[0-9]*"' "$report_xml" | head -n1)"
skipped="$(grep -o 'skipped="[0-9]*"' "$report_xml" | head -n1)"
[ "$tests" != 'tests="0"' ] && [ -n "$tests" ] || fail "IngestionReconciliationE2EIT ran no test"
[ "$skipped" = 'skipped="0"' ] || fail "IngestionReconciliationE2EIT skipped tests ($skipped): a skip is not a pass"
pass "IngestionReconciliationE2EIT reconciles with /admin/world/stats ($tests)"

step "AC-5: outage of $outage_provider through nginx /gds-admin/"
http POST "/gds-admin/providers/$outage_provider/outage"
[ "$code" = 204 ] || fail "POST /gds-admin/providers/$outage_provider/outage answered $code, expected 204"
outage_forced=true
pass "outage of $outage_provider forced (204)"
http POST /api/v1/ingestions
[ "$code" = 202 ] || fail "POST /api/v1/ingestions answered $code, expected 202: ${body:0:300}"
id="$(jq -r .id <<<"$body")"
pass "manual run $id started (202)"
run="$(wait_for_run "/api/v1/ingestions/$id")"
[ "$(jq -r .status <<<"$run")" = PARTIAL ] \
  || fail "the run finished $(jq -r .status <<<"$run"), expected PARTIAL: $(jq -c '.report.providers | map({provider, status, circuitState})' <<<"$run")"
pass "run finished PARTIAL"
provider() { jq -r --arg p "$1" ".report.providers[] | select(.provider == \$p) | $2" <<<"$run"; }
[ "$(provider "$outage_provider" .status)" = FAILED ] || fail "$outage_provider is $(provider "$outage_provider" .status), expected FAILED"
[ "$(provider "$outage_provider" .circuitState)" = OPEN ] \
  || fail "$outage_provider breaker is $(provider "$outage_provider" .circuitState), expected OPEN"
pass "$outage_provider FAILED $(provider "$outage_provider" '"\(.partitionsSucceeded)/\(.partitionsRequested)"') with its breaker OPEN"
total_others="$(jq -r --arg p "$outage_provider" '[.report.providers[] | select(.provider != $p)] | length' <<<"$run")"
[ "$total_others" = 2 ] || fail "expected the 2 other providers in the report, found $total_others"
others="$(jq -r --arg p "$outage_provider" '[.report.providers[] | select(.provider != $p and .status != "COMPLETED")] | length' <<<"$run")"
[ "$others" = 0 ] || fail "other providers did not complete: $(jq -c '.report.providers | map({provider, status})' <<<"$run")"
pass "the other providers COMPLETED: $(jq -r --arg p "$outage_provider" '[.report.providers[] | select(.provider != $p) | .provider] | join(", ")' <<<"$run")"
search "?limit=5"
pass "search still returns data during the outage ($(jq '.items | length' <<<"$body") items)"

step "AC-6: recovery"
recover
http GET /gds-admin/providers
[ "$code" = 200 ] || fail "GET /gds-admin/providers answered $code"
[ "$(jq '[.[] | select(.outage)] | length' <<<"$body")" = 0 ] || fail "providers still in outage: $body"
pass "no provider in outage"
