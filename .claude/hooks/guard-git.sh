#!/usr/bin/env bash
# PreToolUse(Bash) hook: protects main and the quality gates.
#   - blocks pushes to main/master (explicit refspec, or from a checked-out main)
#   - blocks commits while on main/master (work happens on feature branches)
#   - blocks force pushes (--force, -f, +refspec; --force-with-lease is allowed) and --no-verify
# The command is split into sub-commands (&&, ||, ;, |, newlines outside quotes) and the rules are applied only
# to sub-commands that really are `git push` / `git commit`, so e.g. `git push && gh pr create --base main` is fine.
# Exit code 2 blocks the tool call and shows stderr to Claude. Tests: .claude/hooks/tests/guard-git.test.sh
set -uo pipefail
source "$(dirname "$0")/lib.sh"

block() {
  echo "BLOCKED by .claude/hooks/guard-git.sh: $1" >&2
  echo "Workflow: create a branch (feat/<spec-id>-<slug>), commit there, push it and open a PR (see CLAUDE.md)." >&2
  exit 2
}

US=$'\037' # word separator inside a sub-command

# Prints one sub-command per line, as words joined by $US. Quotes are removed but quoted text stays inside one
# word, so separators, flags or branch names inside a commit message are never parsed as a command.
# Splits on &&, ||, ;, |, & and newlines outside quotes. Unquoted heredoc bodies are treated as commands
# (conservative: a body line that looks like a forbidden git command blocks).
split_commands() {
  local s="$1" out="" word="" inword=0 quote="" c next i
  for ((i = 0; i < ${#s}; i++)); do
    c="${s:i:1}"
    next="${s:i+1:1}"
    if [ -n "$quote" ]; then
      if [ "$c" = "$quote" ]; then
        quote=""
      elif [ "$c" = "\\" ] && [ "$quote" = '"' ]; then
        word+="$next"
        i=$((i + 1))
      elif [ "$c" = $'\n' ]; then
        word+=" " # keep multi-line quoted text (e.g. a commit message) on its sub-command's line
      else
        word+="$c"
      fi
      continue
    fi
    case "$c" in
      "'" | '"') quote="$c"; inword=1 ;;
      "\\") word+="$next"; inword=1; i=$((i + 1)) ;;
      " " | $'\t' | ";" | $'\n' | "&" | "|")
        if [ "$inword" = 1 ]; then out+="$word$US"; word=""; inword=0; fi
        case "$c" in
          " " | $'\t') ;;
          *)
            out+=$'\n'
            if { [ "$c" = "&" ] || [ "$c" = "|" ]; } && [ "$next" = "$c" ]; then i=$((i + 1)); fi
            ;;
        esac
        ;;
      *) word+="$c"; inword=1 ;;
    esac
  done
  [ "$inword" = 1 ] && out+="$word$US"
  printf '%s\n' "$out"
}

FORCE_MSG="force push is not allowed (use --force-with-lease on your own feature branch if really needed)."

# Checks one sub-command. Ignores anything that is not `git [global options] push|commit ...`.
check_subcommand() {
  local -a words=()
  local IFS="$US"
  read -r -a words <<<"$1"
  unset IFS
  local n=${#words[@]} i=0
  [ "$n" -gt 0 ] || return 0

  # Skip environment assignments (VAR=value git push ...).
  while [ "$i" -lt "$n" ] && [[ "${words[i]}" =~ ^[A-Za-z_][A-Za-z0-9_]*= ]]; do i=$((i + 1)); done
  [ "$i" -lt "$n" ] || return 0
  case "${words[i]}" in git | */git) ;; *) return 0 ;; esac
  i=$((i + 1))

  # Skip git global options; -C and -c take a separate value.
  while [ "$i" -lt "$n" ] && [[ "${words[i]}" == -* ]]; do
    case "${words[i]}" in -C | -c) i=$((i + 2)) ;; *) i=$((i + 1)) ;; esac
  done
  [ "$i" -lt "$n" ] || return 0
  local action="${words[i]}"
  [ "$action" = "push" ] || [ "$action" = "commit" ] || return 0
  i=$((i + 1))

  # Options whose value is the next word (so the value is never read as a flag or refspec).
  local value_long value_short
  if [ "$action" = "push" ]; then
    value_long=" --push-option --repo --receive-pack --exec "
    value_short="o"
  else
    value_long=" --message --file --author --date --template --reuse-message --reedit-message --fixup --squash --cleanup --trailer --pathspec-from-file "
    value_short="mFCct"
  fi

  local -a positional=()
  local w flags j ch
  while [ "$i" -lt "$n" ]; do
    w="${words[i]}"
    i=$((i + 1))
    case "$w" in
      --) positional+=("${words[@]:i}"); break ;;
      --no-verify) block "--no-verify skips the git hooks (formatting and tests)." ;;
      --force | --force=*)
        [ "$action" = "push" ] && block "$FORCE_MSG"
        ;;
      --*=*) ;;
      --*) [[ "$value_long" == *" $w "* ]] && i=$((i + 1)) ;;
      -?*)
        # Short flag cluster, e.g. -fu or -am: -f (push) is force, -n (commit) is --no-verify.
        flags="${w#-}"
        for ((j = 0; j < ${#flags}; j++)); do
          ch="${flags:j:1}"
          if [[ "$value_short" == *"$ch"* ]]; then
            # The rest of the cluster is the value; if it is empty, the value is the next word.
            [ "$j" -eq $((${#flags} - 1)) ] && i=$((i + 1))
            break
          fi
          if [ "$action" = "push" ] && [ "$ch" = "f" ]; then block "$FORCE_MSG"; fi
          if [ "$action" = "commit" ] && [ "$ch" = "n" ]; then
            block "-n (--no-verify) skips the git hooks (formatting and tests)."
          fi
        done
        ;;
      *) positional+=("$w") ;;
    esac
  done

  local branch
  branch="$(current_branch)"

  if [ "$action" = "push" ]; then
    # positional[0] is the remote; the rest are refspecs ([+]<src>[:<dst>]).
    local remote="" spec dst k
    [ "${#positional[@]}" -gt 0 ] && remote="${positional[0]}"
    for ((k = 1; k < ${#positional[@]}; k++)); do
      spec="${positional[k]}"
      [[ "$spec" == +* ]] && block "force push (+refspec) is not allowed."
      dst="${spec##*:}"
      dst="${dst#refs/heads/}"
      [ -n "$remote" ] && dst="${dst#"$remote"/}"
      if [ "$dst" = "main" ] || [ "$dst" = "master" ]; then
        block "pushing to main/master is forbidden. main only changes through merged PRs."
      fi
    done
    if [ "$branch" = "main" ] || [ "$branch" = "master" ]; then
      block "you are on '$branch'; pushing from it is forbidden."
    fi
  elif [ "$branch" = "main" ] || [ "$branch" = "master" ]; then
    block "you are on '$branch'; commit on a feature branch instead (git switch -c feat/<spec-id>-<slug>)."
  fi
  return 0
}

input="$(cat)"
cmd="$(json_field "$input" '.tool_input.command')"
[ -z "$cmd" ] && exit 0

while IFS= read -r sub; do
  check_subcommand "$sub"
done < <(split_commands "$cmd")

exit 0
