#!/usr/bin/env bash
# SessionStart hook: activates the versioned git hooks and gives Claude the current context.
# Anything printed to stdout is added to Claude's context.
set -uo pipefail
source "$(dirname "$0")/lib.sh"

cd "$(project_dir)" || exit 0
git rev-parse --is-inside-work-tree >/dev/null 2>&1 || exit 0

if [ "$(git config --get core.hooksPath)" != ".githooks" ]; then
  git config core.hooksPath .githooks
  echo "Activated versioned git hooks (core.hooksPath=.githooks)."
fi

branch="$(current_branch)"
echo "Current git branch: ${branch:-unknown}."
if [ "$branch" = "main" ] || [ "$branch" = "master" ]; then
  echo "You are on $branch: create a feature branch before changing code (see CLAUDE.md > Git workflow)."
fi
exit 0
