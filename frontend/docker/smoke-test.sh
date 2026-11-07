#!/usr/bin/env bash
# Smoke test of the frontend image (spec 006 AC-22, AC-23, AC-24), without the rest of the stack: builds the image,
# starts it with DEMO_CONTROLS off and on, and checks what nginx serves. With no aggregator or gds-mock running, the
# proxied paths must answer 502 (proxied, upstream down) rather than the app, and nginx must start anyway.
# Usage: bash frontend/docker/smoke-test.sh   (needs Docker; run by the CI frontend job)
set -uo pipefail

here="$(cd "$(dirname "$0")/.." && pwd)"
image="flight-aggregator/frontend:smoke"
passed=0
failed=0
# Every container of this run carries this label, so the end can assert none is left.
label="flight-aggregator.smoke-test=$$-$(date +%s)"
containers=()
cleanup() {
  if [ "${#containers[@]}" -gt 0 ]; then docker rm -f "${containers[@]}" >/dev/null 2>&1; fi
  containers=()
}
trap cleanup EXIT

check() { # check <description> <command...>
  local description="$1"
  shift
  if "$@" >/dev/null 2>&1; then
    passed=$((passed + 1)); echo "PASS  $description"
  else
    failed=$((failed + 1)); echo "FAIL  $description"
  fi
}

docker build -q -t "$image" "$here" >/dev/null || { echo "FAIL  docker build"; exit 1; }

# Containers are started here, in the main shell, so their ids reach the EXIT trap. (A helper called through
# "$(...)" or "< <(...)" runs in a subshell: its containers+=() is lost and the containers are left behind.)
start() { # start <DEMO_CONTROLS>: starts a container, appends its id to containers
  local id
  id="$(docker run -d -P --label "$label" -e DEMO_CONTROLS="$1" "$image")" || { echo "FAIL  docker run"; exit 1; }
  containers+=("$id")
}

base_url() { # base_url <id>: waits for /healthz, prints http://localhost:<port>
  local port
  port="$(docker port "$1" 8080/tcp | head -n1 | sed 's/.*://')"
  for _ in $(seq 1 50); do
    curl -fs "http://localhost:$port/healthz" >/dev/null 2>&1 && break
    sleep 0.2
  done
  echo "http://localhost:$port"
}

status() { curl -s -o /dev/null -w '%{http_code}' "$@"; }

start false
off_id="${containers[0]}"
start true
on_id="${containers[1]}"
off="$(base_url "$off_id")"
on="$(base_url "$on_id")"

echo "--- AC-24: image"
check "runs as a non-root user" test "$(docker exec "$off_id" id -u)" != "0"
check "contains no Node.js runtime" bash -c "! docker exec $off_id sh -c 'command -v node'"

echo "--- AC-23: static files and headers"
check "/healthz answers 200" test "$(status "$off/healthz")" = 200
check "the compose healthcheck command succeeds inside the container" \
  docker exec "$off_id" wget -qO- http://127.0.0.1:8080/healthz
check "/ serves the app" bash -c "curl -fs $off/ | grep -q '<div id=\"root\">'"
check "an unknown path falls back to index.html (URL state)" \
  bash -c "curl -fs '$off/some/deep/link?origin=MAD' | grep -q '<div id=\"root\">'"
check "index.html is not cached" bash -c "curl -s -D - -o /dev/null $off/ | grep -qi '^cache-control:.*no-cache'"
asset="$(curl -fs "$off/" | grep -o '/assets/[^"]*\.js' | head -n1)"
check "hashed assets are cached for a long time" \
  bash -c "curl -s -D - -o /dev/null $off$asset | grep -qi '^cache-control:.*immutable'"
check "text is gzipped" \
  bash -c "curl -s -D - -o /dev/null -H 'Accept-Encoding: gzip' $off$asset | grep -qi '^content-encoding: gzip'"
check "Content-Security-Policy restricts to self" bash -c "curl -s -D - -o /dev/null $off/ | grep -qi \"^content-security-policy:.*default-src 'self'\""
check "X-Content-Type-Options: nosniff" bash -c "curl -s -D - -o /dev/null $off/ | grep -qi '^x-content-type-options: nosniff'"
check "Referrer-Policy is set" bash -c "curl -s -D - -o /dev/null $off/ | grep -qi '^referrer-policy:'"

echo "--- AC-23: proxy"
check "/api/ is proxied to the aggregator (502 while it is down)" test "$(status "$off/api/v1/catalog")" = 502
check "/actuator is not proxied" bash -c "! curl -fs $off/actuator/health | grep -q status"
check "Swagger UI is not proxied" bash -c "curl -fs $off/swagger-ui.html | grep -q '<div id=\"root\">'"

check "/api/ responses carry the security headers too" \
  bash -c "curl -s -D - -o /dev/null $off/api/v1/catalog | grep -qi '^x-content-type-options: nosniff'"
check "an upstream URL with a path is refused at start" \
  bash -c "! docker run --rm -e AGGREGATOR_URL='http://x:8080/; evil' $image true"

echo "--- AC-22: demo controls flag"
check "DEMO_CONTROLS=false: config.json says off" bash -c "curl -fs $off/config.json | grep -q '\"demoControls\": *false'"
check "DEMO_CONTROLS=false: /gds-admin/ is not proxied (404)" test "$(status "$off/gds-admin/providers")" = 404
check "DEMO_CONTROLS=true: config.json says on" bash -c "curl -fs $on/config.json | grep -q '\"demoControls\": *true'"
check "DEMO_CONTROLS=true: /gds-admin/providers is proxied (502 while gds-mock is down)" \
  test "$(status "$on/gds-admin/providers")" = 502
check "DEMO_CONTROLS=true: only provider admin paths are proxied" test "$(status "$on/gds-admin/world/stats")" = 404

echo "--- cleanup"
cleanup
check "no container of this run is left" test -z "$(docker ps -aq --filter "label=$label")"

echo
echo "frontend image smoke test: $passed passed, $failed failed"
[ "$failed" -eq 0 ]
