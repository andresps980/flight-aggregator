#!/usr/bin/env bash
# PreToolUse(Bash) hook: protects main and the quality gates.
#   - blocks pushes to main/master (explicit or from a checked-out main)
#   - blocks commits while on main/master (work happens on feature branches)
#   - blocks force pushes (except --force-with-lease) and --no-verify
# Exit code 2 blocks the tool call and shows stderr to Claude.
set -uo pipefail
source "$(dirname "$0")/lib.sh"

input="$(cat)"
cmd="$(json_field "$input" '.tool_input.command')"
[ -z "$cmd" ] && exit 0

block() {
  echo "BLOCKED by .claude/hooks/guard-git.sh: $1" >&2
  echo "Workflow: create a branch (feat/<spec-id>-<slug>), commit there, push it and open a PR (see CLAUDE.md)." >&2
  exit 2
}

# Only inspect git invocations.
printf '%s' "$cmd" | grep -Eq '(^|[;&|[:space:]])git([[:space:]]+-C[[:space:]]+[^[:space:]]+)?[[:space:]]+(push|commit)' || exit 0

if printf '%s' "$cmd" | grep -Eq -- '--no-verify'; then
  block "--no-verify skips the git hooks (formatting and tests)."
fi

branch="$(current_branch)"

if printf '%s' "$cmd" | grep -Eq 'git([[:space:]]+-C[[:space:]]+[^[:space:]]+)?[[:space:]]+push'; then
  if printf '%s' "$cmd" | grep -Eq -- '(^|[[:space:]])(--force|-f)([[:space:]]|$)'; then
    block "force push is not allowed (use --force-with-lease on your own feature branch if really needed)."
  fi
  if printf '%s' "$cmd" | grep -Eq 'push.*([[:space:]:+/])(main|master)([[:space:]]|$)'; then
    block "pushing to main/master is forbidden. main only changes through merged PRs."
  fi
  if [ "$branch" = "main" ] || [ "$branch" = "master" ]; then
    block "you are on '$branch'; pushing from it is forbidden."
  fi
fi

if printf '%s' "$cmd" | grep -Eq 'git([[:space:]]+-C[[:space:]]+[^[:space:]]+)?[[:space:]]+commit'; then
  if [ "$branch" = "main" ] || [ "$branch" = "master" ]; then
    block "you are on '$branch'; commit on a feature branch instead (git switch -c feat/<spec-id>-<slug>)."
  fi
fi

exit 0
