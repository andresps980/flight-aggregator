#!/usr/bin/env bash
# Stop hook: before Claude finishes a turn, run the quality gates whose sources changed since their last green run:
#   - backend:  `./mvnw verify` (unit + integration tests, coverage gate, Spotless check) when poms or module sources
#               changed;
#   - frontend: `npm run check` in frontend/ (Prettier check, ESLint, tsc, Vitest with coverage) when frontend/ changed.
# On failure, exit 2 sends the tail of the log back to Claude so it fixes it.
# To avoid loops, if Claude is already continuing because of this hook, only warn.
set -uo pipefail
source "$(dirname "$0")/lib.sh"

input="$(cat)"
already_retrying="$(json_field "$input" '.stop_hook_active')"

cd "$(project_dir)" || exit 0
git rev-parse --is-inside-work-tree >/dev/null 2>&1 || exit 0

# Fingerprint of the working tree for some paths (tracked diff + untracked files), empty if nothing changed vs HEAD.
fingerprint() {
  [ -n "$(git status --porcelain -- "$@")" ] || return 0
  {
    git diff HEAD -- "$@" 2>/dev/null
    git ls-files --others --exclude-standard -- "$@" 2>/dev/null | while read -r f; do
      echo "$f $(git hash-object "$f")"
    done
  } | git hash-object --stdin
}

failures=""

# gate <name> <pathspec...> -- <command...>: runs the command unless the paths are unchanged or already green.
gate() {
  local name="$1" print log
  shift
  local -a paths=()
  while [ "$1" != "--" ]; do paths+=("$1"); shift; done
  shift # what remains in "$@" is the command
  print="$(fingerprint "${paths[@]}")"
  [ -n "$print" ] || return 0
  local marker="target/.claude-last-green-$name"
  if [ -f "$marker" ] && [ "$(cat "$marker")" = "$print" ]; then
    return 0
  fi
  log="$(mktemp)"
  if "$@" >"$log" 2>&1; then
    mkdir -p target && echo "$print" >"$marker"
  else
    failures+="$name checks FAILED ($*). Fix them before finishing. Last lines of the log:"$'\n'
    failures+="$(tail -n 60 "$log")"$'\n\n'
  fi
  rm -f "$log"
}

# Multi-module Maven build: the root pom plus every module's pom.xml and src/ (the frontend is not a Maven module).
if [ -x ./mvnw ]; then
  gate backend pom.xml ':(glob)*/pom.xml' ':(glob)*/src/**' ':(exclude)frontend/**' -- ./mvnw -q -B verify
fi
if [ -f frontend/package.json ]; then
  # docs/api/openapi.json: the frontend's types are generated from it (npm run check verifies they match).
  gate frontend frontend docs/api/openapi.json -- run_frontend_checks
fi

[ -z "$failures" ] && exit 0
printf '%s' "$failures" >&2

if [ "$already_retrying" = "true" ]; then
  echo "(verify-on-stop: still failing after a retry; reporting to the user instead of looping)" >&2
  exit 0
fi
exit 2
