#!/usr/bin/env bash
# Tests for .claude/hooks/guard-git.sh.
# Runs the hook against a throw-away git repository with Claude Code-style JSON payloads and checks that each
# command is allowed (exit 0) or blocked (exit 2). Usage: bash .claude/hooks/tests/guard-git.test.sh
set -uo pipefail

hook="$(cd "$(dirname "$0")/.." && pwd)/guard-git.sh"
repo="$(mktemp -d)"
trap 'rm -rf "$repo"' EXIT

git -C "$repo" init -q -b main
git -C "$repo" -c user.name=test -c user.email=test@example.invalid commit -q --allow-empty -m init

passed=0
failed=0

# JSON string escaping for the payload (backslash, double quote, newline, tab).
json_escape() {
  local s="$1"
  s="${s//\\/\\\\}"
  s="${s//\"/\\\"}"
  s="${s//$'\n'/\\n}"
  s="${s//$'\t'/\\t}"
  printf '%s' "$s"
}

# expect <allow|block> <command>
expect() {
  local expected="$1" cmd="$2" code
  printf '{"tool_name":"Bash","tool_input":{"command":"%s"}}' "$(json_escape "$cmd")" \
    | CLAUDE_PROJECT_DIR="$repo" bash "$hook" >/dev/null 2>&1
  code=$?
  if { [ "$expected" = allow ] && [ "$code" -eq 0 ]; } || { [ "$expected" = block ] && [ "$code" -eq 2 ]; }; then
    passed=$((passed + 1))
    printf 'PASS  %-5s [%s] %s\n' "$expected" "$(git -C "$repo" branch --show-current)" "$cmd"
  else
    failed=$((failed + 1))
    printf 'FAIL  %-5s [%s] %s (exit %s)\n' "$expected" "$(git -C "$repo" branch --show-current)" "$cmd" "$code"
  fi
}

# --- On a feature branch -------------------------------------------------------------------------------------
git -C "$repo" switch -q -c feat/001-example

# The original bug: `--base main` belongs to gh, not to git push.
expect allow 'git push -u origin HEAD && gh pr create --base main --title "feat: x"'
expect allow 'git push origin feat/001-example'
expect allow 'git push -u origin HEAD'
expect allow 'git push --force-with-lease origin feat/001-example'
expect allow 'git commit -m "fix: handle a; git push origin main"'
expect allow 'git commit -m "docs: explain --no-verify and -n"'
expect allow $'git commit -m "$(cat <<\'EOF\'\nfeat: x\n\ngit push origin main\nEOF\n)"'
expect allow 'git commit -am "wip"'
expect allow 'git log main..HEAD'
expect allow 'git status && echo main'
expect allow 'git diff main...HEAD | grep push'
expect allow 'gh pr create --base main --title "x"'
expect allow 'ls -la'

expect block 'git push origin main'
expect block 'git push origin HEAD:main'
expect block 'git push origin HEAD:refs/heads/main'
expect block 'git push origin master'
expect block 'git push origin "main"'
expect block 'git push origin +feat/001-example'
expect block 'git push --force'
expect block 'git push --force origin feat/001-example'
expect block 'git push -f origin feat/001-example'
expect block 'git push -uf origin feat/001-example'
expect block 'git commit --no-verify -m "x"'
expect block 'git commit -n -m "x"'
expect block 'git commit -anm "x"'
expect block 'git push --no-verify'
expect block 'ls && git push origin main'
expect block 'git status; git push origin main'
expect block 'false || git push origin main'
expect block 'git -C . push origin main'
expect block 'GIT_TRACE=1 git push origin main'

# --- On main -------------------------------------------------------------------------------------------------
git -C "$repo" switch -q main

expect block 'git commit -m "x"'
expect block 'git push -u origin HEAD'
expect block 'git push'
expect block 'echo ok && git commit -m "x"'
expect allow 'git status'
expect allow 'git switch -c fix/something'
expect allow 'gh pr view 1'

echo
echo "guard-git: $passed passed, $failed failed"
[ "$failed" -eq 0 ]
