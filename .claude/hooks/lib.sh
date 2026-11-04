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
