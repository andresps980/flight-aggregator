#!/usr/bin/env bash
# Tests for classify_paths in .claude/hooks/lib.sh: which quality gates a change affects (spec 006 AC-29).
# Usage: bash .claude/hooks/tests/classify-paths.test.sh
set -uo pipefail
source "$(cd "$(dirname "$0")/.." && pwd)/lib.sh"

passed=0
failed=0

# expect "<expected gates, space-separated>" <path>...
expect() {
  local expected="$1" actual
  shift
  actual="$(printf '%s\n' "$@" | classify_paths | tr '\n' ' ' | sed 's/ $//')"
  if [ "$actual" = "$expected" ]; then
    passed=$((passed + 1))
    printf 'PASS  [%s] %s\n' "$expected" "$*"
  else
    failed=$((failed + 1))
    printf 'FAIL  expected [%s] got [%s] for %s\n' "$expected" "$actual" "$*"
  fi
}

expect "backend" pom.xml
expect "backend" flight-aggregator/pom.xml
expect "backend" flight-aggregator/src/main/java/A.java
expect "backend" gds-mock/src/test/resources/x.json
expect "backend" .mvn/wrapper/maven-wrapper.properties
expect "frontend" frontend/src/App.tsx
expect "frontend" frontend/package.json
expect "frontend" frontend/nginx/default.conf.template
expect "frontend" docs/api/openapi.json
expect "" docs/frontend.md
expect "backend" docs/architecture/overview.md
expect "backend" docs/architecture/packages/gds-mock/chaos.md
expect "" docs/architecture.md
expect "" specs/006-dashboard/spec.md
expect "" .github/workflows/ci.yml README.md
expect "" ""
expect "backend frontend" docs/usage.md flight-aggregator/src/main/resources/application.yml frontend/src/main.tsx
expect "frontend" frontend/src/main.tsx frontend/src/App.tsx

echo
echo "classify_paths: $passed passed, $failed failed"
[ "$failed" -eq 0 ]
