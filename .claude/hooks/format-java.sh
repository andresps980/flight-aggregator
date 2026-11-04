#!/usr/bin/env bash
# PostToolUse(Edit|Write|MultiEdit) hook: runs Spotless after Claude changes a Java file or the POM.
# If Spotless fails (usually a syntax error), exit 2 feeds the error back to Claude.
set -uo pipefail
source "$(dirname "$0")/lib.sh"

input="$(cat)"
file="$(json_field "$input" '.tool_input.file_path')"

case "$file" in
  *.java|*pom.xml) ;;
  *) exit 0 ;;
esac

cd "$(project_dir)" || exit 0
[ -x ./mvnw ] || { echo "format-java: ./mvnw not found, skipping Spotless" >&2; exit 0; }

# Limit Spotless to the module that owns the file (first path segment with its own pom.xml);
# edits to the root pom format every module.
root="$(pwd -P)"
rel="${file#"$root"/}"
module="${rel%%/*}"
scope=()
if [ "$module" != "$rel" ] && [ -f "$module/pom.xml" ]; then
  scope=(-pl "$module")
fi

if ! output="$(./mvnw -q -B spotless:apply "${scope[@]+"${scope[@]}"}" 2>&1)"; then
  echo "Spotless failed after editing $file:" >&2
  printf '%s\n' "$output" | tail -n 40 >&2
  exit 2
fi
exit 0
