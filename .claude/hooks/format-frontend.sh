#!/usr/bin/env bash
# PostToolUse(Edit|Write|MultiEdit) hook: runs Prettier on a frontend file Claude just changed (the frontend's
# Spotless). If Prettier fails (usually a syntax error), exit 2 feeds the error back to Claude.
set -uo pipefail
source "$(dirname "$0")/lib.sh"

input="$(cat)"
file="$(json_field "$input" '.tool_input.file_path')"

root="$(cd "$(project_dir)" && pwd -P)"
case "$file" in
  "$root"/frontend/*) ;;
  *) exit 0 ;;
esac
case "$file" in
  */node_modules/* | */dist/* | */coverage/* | */src/api/schema.d.ts) exit 0 ;;
  *.ts | *.tsx | *.js | *.css | *.json | *.html | *.md) ;;
  *) exit 0 ;;
esac

prettier="$root/frontend/node_modules/.bin/prettier"
[ -x "$prettier" ] || { echo "format-frontend: run 'npm ci' in frontend/ to enable Prettier" >&2; exit 0; }

if ! output="$(cd "$root/frontend" && "$prettier" --write --log-level warn "$file" 2>&1)"; then
  echo "Prettier failed after editing $file:" >&2
  printf '%s\n' "$output" | tail -n 40 >&2
  exit 2
fi
exit 0
