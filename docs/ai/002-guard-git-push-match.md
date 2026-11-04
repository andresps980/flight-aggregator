# 002 — Fixing a false positive in the git guard hook

- **Date:** 2026-11-04
- **Tool:** Claude Code (Claude Opus 5.5), plan mode first, then implementation
- **Branch / PR:** `fix/guard-git-push-match`

An example of iterating on the agent tooling itself: a guardrail misfired during session
[001](001-project-setup.md). The fix ships with tests so the guardrail is held to the same standard as product code.

## The bug

When opening PR 1, the agent ran this command on the feature branch `chore/project-setup`:

```bash
git push -u origin HEAD && gh pr create --base main --title "..." --body-file ...
```

The `PreToolUse(Bash)` hook `.claude/hooks/guard-git.sh` blocked it:

```text
BLOCKED by .claude/hooks/guard-git.sh: pushing to main/master is forbidden. main only changes through merged PRs.
```

**Root cause:** once the command contained `git push`, the hook ran the "push to main" regex
`push.*([[:space:]:+/])(main|master)([[:space:]]|$)` over the **whole command line**. So `--base main`, an
argument of `gh pr create`, matched. The `--no-verify` and `--force` checks had the same problem, and quoted text
(for example a commit message mentioning `--no-verify`) was also treated as arguments.

**Workaround in session 001:** the agent ran `git push` and `gh pr create` as separate commands. Nothing reached
`main`; the hook failed safe (false positive, not false negative). The agent reported the problem and proposed
this fix instead of working around it silently.

## Prompt

```text
Yes, fix it in a fix/guard-git-push-match PR:
- Split the command into sub-commands on &&, ||, ; and | and apply the push/commit checks
  only to the sub-commands that are actually `git push` / `git commit`.
- Add a test script (e.g. .claude/hooks/tests/guard-git.test.sh) that runs the hook against
  a temporary git repo with allow/block cases, including:
  `git push -u origin HEAD && gh pr create --base main` (allow on a feature branch),
  `git push origin main`, `git push origin HEAD:main`, `git push --force`,
  `git commit --no-verify`, and any commit/push while on main (block).
- Run that test script in CI as a step in the format job.
- Record the bug and the fix in docs/ai/ (it's a good example of iterating on the agent tooling).
```

## The fix

- **Quote-aware tokenizer** in `guard-git.sh`. It splits the command into sub-commands on `&&`, `||`, `;`, `|`, `&`
  and newlines outside quotes. Each sub-command becomes a list of words, and quoted text stays as one word.
- Rules run **only on sub-commands that are `git [global options] push|commit`**. Leading `VAR=value`
  assignments and `-C <path>` / `-c <k=v>` are handled.
- Arguments are parsed instead of grepped:
  - options that take a value (`-m`, `-F`, `--push-option`…) consume it, so a commit message is never read as a
    flag;
  - short flag clusters are inspected (`-uf` is force, `-anm` contains `-n`);
  - for push refspecs, the destination is checked (`main`, `HEAD:main`, `refs/heads/main`, `origin/main`).
- Writing the tests exposed gaps in the old hook, which this PR also closes:
  - `git push origin "main"` was allowed;
  - `+refspec` force pushes were allowed;
  - `-uf` was allowed;
  - `git commit -n` / `-anm` (short `--no-verify`) were allowed.
- Bash 3.2 compatible (macOS default shell), no new dependencies.

## Tests

`.claude/hooks/tests/guard-git.test.sh` creates a temporary git repository and pipes Claude Code-style JSON
payloads into the hook. It asserts exit code `0` (allow) or `2` (block) for 39 cases on a feature branch and on
`main`, including the exact command that triggered the bug. It runs in CI in the `Format (Spotless)` job.

Mutation check: running the same suite against the previous hook gives **8 failures**, including the original
`git push … && gh pr create --base main` case. That shows the tests catch the regression.

## Lessons

- Guardrail hooks are code: they need their own tests and CI, otherwise a false positive pushes the agent toward
  workarounds.
- Regex over a whole shell command is fragile. Parse into sub-commands and arguments, and stay conservative when
  unsure (block rather than allow).
- Known limitation: unquoted heredoc bodies and `$(...)` / backtick sub-shells outside quotes are not parsed as
  separate scopes. The hook only constrains the agent; the git hooks in `.githooks/` and the GitHub rulesets stay
  the source of truth ([ADR-0005](../adr/0005-ai-assisted-development.md)).
