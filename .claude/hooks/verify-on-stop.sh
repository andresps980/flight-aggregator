#!/usr/bin/env bash
# Stop hook: before Claude finishes a turn, run `./mvnw verify` (unit + integration tests,
# coverage gate, Spotless check) if source code changed since the last green build.
# On failure, exit 2 sends the tail of the build log back to Claude so it fixes it.
# To avoid loops, if Claude is already continuing because of this hook, only warn.
set -uo pipefail
source "$(dirname "$0")/lib.sh"

input="$(cat)"
already_retrying="$(json_field "$input" '.stop_hook_active')"

cd "$(project_dir)" || exit 0
git rev-parse --is-inside-work-tree >/dev/null 2>&1 || exit 0
[ -x ./mvnw ] || exit 0

# Fingerprint of the working tree for build-relevant paths (tracked diff + untracked files).
# Multi-module build: the root pom plus every module's pom.xml and src/.
paths=(pom.xml ':(glob)*/pom.xml' ':(glob)*/src/**')

fingerprint="$( {
  git diff HEAD -- "${paths[@]}" 2>/dev/null
  git ls-files --others --exclude-standard -- "${paths[@]}" 2>/dev/null | while read -r f; do
    echo "$f $(git hash-object "$f")"
  done
} | git hash-object --stdin )"

# Nothing changed versus HEAD: nothing to verify.
if [ -z "$(git status --porcelain -- "${paths[@]}")" ]; then
  exit 0
fi

marker="target/.claude-last-green"
if [ -f "$marker" ] && [ "$(cat "$marker")" = "$fingerprint" ]; then
  exit 0
fi

log="$(mktemp)"
if ./mvnw -q -B verify >"$log" 2>&1; then
  mkdir -p target && echo "$fingerprint" >"$marker"
  rm -f "$log"
  exit 0
fi

{
  echo "./mvnw verify FAILED. Fix the build before finishing. Last lines of the log:"
  tail -n 80 "$log"
} >&2
rm -f "$log"

if [ "$already_retrying" = "true" ]; then
  echo "(verify-on-stop: still failing after a retry; reporting to the user instead of looping)" >&2
  exit 0
fi
exit 2
