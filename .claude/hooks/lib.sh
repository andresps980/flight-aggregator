#!/usr/bin/env bash
# Shared helpers for Claude Code hooks.
# Hooks receive a JSON payload on stdin; json_field extracts a (possibly nested) string field
# using jq, falling back to python3, and finally to a best-effort sed for simple cases.

json_field() {
  local json="$1" path="$2"
  if command -v jq >/dev/null 2>&1; then
    printf '%s' "$json" | jq -r "$path // empty"
  elif command -v python3 >/dev/null 2>&1; then
    printf '%s' "$json" | python3 -c '
import json, sys
data = json.load(sys.stdin)
for key in sys.argv[1].lstrip(".").split("."):
    data = data.get(key) if isinstance(data, dict) else None
print("" if data is None else (str(data).lower() if isinstance(data, bool) else data))
' "$path"
  else
    local key="${path##*.}"
    printf '%s' "$json" | sed -n "s/.*\"$key\"[[:space:]]*:[[:space:]]*\"\{0,1\}\([^\",}]*\).*/\1/p" | head -n1
  fi
}

project_dir() {
  printf '%s' "${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}"
}

current_branch() {
  git -C "$(project_dir)" rev-parse --abbrev-ref HEAD 2>/dev/null || echo ""
}

# Reads repository-relative paths on stdin and prints which quality gates they affect, one per line, each at most
# once: "backend" (Maven build: poms, wrapper, module sources) and/or "frontend" (anything under frontend/). Docs,
# specs and CI files affect neither. Used by verify-on-stop.sh and .githooks/pre-push.
classify_paths() {
  local path backend=0 frontend=0
  while IFS= read -r path; do
    case "$path" in
      "") ;;
      frontend/*) frontend=1 ;;
      pom.xml | */pom.xml | mvnw | mvnw.cmd | .mvn/* | */src/*) backend=1 ;;
    esac
  done
  [ "$backend" = 1 ] && echo backend
  [ "$frontend" = 1 ] && echo frontend
  return 0
}

# The frontend checks (format, lint, types, unit tests with coverage), installing dependencies first if needed.
run_frontend_checks() {
  local dir
  dir="$(project_dir)/frontend"
  command -v npm >/dev/null 2>&1 || { echo "npm not found: skipping the frontend checks" >&2; return 0; }
  if [ ! -d "$dir/node_modules" ]; then
    npm --prefix "$dir" ci --no-audit --no-fund || return 1
  fi
  npm --prefix "$dir" run --silent check
}
