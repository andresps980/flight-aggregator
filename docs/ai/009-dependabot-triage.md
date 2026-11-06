# 009 — Dependabot triage after the dashboard merge: one merged, two majors declined, ignore rules

- **Date:** 2026-11-06
- **Tool:** Claude Code (Claude Opus 5.5)
- **Branch / PR:** `chore/dependabot-ignore-majors` (closes the triage of Dependabot PRs #9, #10, #11)

## Prompts

### 1. Triage request

````text
Dependabot opened 3 PRs after the merge; 2 are failing CI. Help me handle them:

1. List the open Dependabot PRs (gh pr list --author app/dependabot) with what each bumps
   and its CI status. For the failing ones, find the exact cause from the failed logs
   (gh run view <id> --log-failed).
2. Show me a table before changing anything: PR, bump, CI, cause, proposed action. Possible actions:
   - merge as is (green, low risk);
   - supersede: if it needs code changes, do the bump + fixes on our own branch
     (chore/deps-<name>), open our PR referencing "Supersedes #N", and close the Dependabot one
     (never push commits to Dependabot's branch);
   - decline: if it's a major version our toolchain can't support yet (e.g. TypeScript 7 or
     ESLint 10, which we deliberately avoided), close it and add an ignore rule for that
     major version in .github/dependabot.yml, with a comment explaining why and when to revisit.
3. Remember: a Dependabot command (e.g. "@dependabot close") must be alone in its own comment.
4. Record the decisions in docs/ai/ and, if relevant, the version constraints in docs/frontend.md.

Merge order: one at a time, waiting for CI on main to be green between merges, and report
each result by commit SHA.
````

### 2. Approval

````text
OK, proceed with the plan as proposed. I'll approve each merge when prompted.
````

## Decisions

| PR | Bump | CI | Cause (from `gh run view --log-failed`) | Action |
|---|---|---|---|---|
| #9 | `spotless-maven-plugin` 3.10.4 → 3.11.0 (build-plugins group, minor) | green, including the Spotless format check (no reformatting) | — | **Merged** (merge commit `872e3a2`) |
| #10 | `eslint` 9.39.5 → 10.14.0, `@eslint/js` 9.39.5 → 10.0.1 (lint-and-format group, major) | Frontend job red at `npm ci` (run 42265253013) | `ERESOLVE`: `eslint-plugin-jsx-a11y@6.10.2` (latest) declares `peer eslint "^3 \|\| … \|\| ^9"` | **Declined**: closed; ignore `eslint` / `@eslint/js` `>=10` |
| #11 | `typescript` 5.9.3 → 7.0.2 (typescript group, major) | Frontend job red at `npm ci` (run 42265287933) | `ERESOLVE`: `openapi-typescript@7.13.0` (latest; `next` is an older rc) declares `peer typescript "^5.x"`; `typescript-eslint@8.72.1` accepts `<6.1.0` | **Declined**: closed; ignore `typescript` `>=6` (6 is blocked too) |

Nothing was superseded: no code change can satisfy those peer ranges; the alternatives were forcing peers or
dropping the accessibility lint plugin (spec 006 AC-21), both rejected in PR 8.

**Why version ranges in the ignore rules** (`versions: [">=6"]`, `[">=10"]`) rather than
`update-types: ["version-update:semver-major"]`: they name exactly what is blocked, and minors and patches of the
current major keep flowing. **Why rules in `dependabot.yml`** rather than `@dependabot ignore this major version`:
the latter stores the ignore state on GitHub, outside the repository; the file keeps the decision versioned, reviewed
and next to its reason and revisit condition. The constraints are also listed in
[docs/frontend.md](../frontend.md#version-constraints).

**Closing.** Each declined PR got one comment with the cause and the revisit condition, then a separate comment
containing only `@dependabot close` (a Dependabot command must be alone in its comment).

## Found during the triage: the `minimatch` security update

After #9 was merged, a **Dependabot Updates** run on `main` (42266947385, on `872e3a2`) failed. It is not our CI and
not caused by #9: it is Dependabot's *security* update for `minimatch`, the 7 moderate dev-only advisories accepted in
PR 8. Its log: lowest non-vulnerable version 11.0.1, latest resolvable 10.2.6 (`@typescript-eslint/typescript-estree`
requires `^10`), `security_update_not_possible`, `fix_available: false`. It will fail on every such run until
`typescript-eslint` moves to `minimatch` 11. Recorded in docs/frontend.md (*Version constraints*); dismissing the
alerts as tolerable risk is left to the author (it changes the repository's security state).

## Merges and CI on `main`, by commit SHA

| Step | Commit on `main` | CI | CodeQL |
|---|---|---|---|
| #9 merged by the author (18:01 UTC) | `872e3a2` | run 42266919025: success (Spotless, build/tests/coverage, frontend) | run 42266918866: success |
| #12 (this branch: ignore rules + docs) merged; its own PR CI on `174a123`: run 42267763229 success, CodeQL 42267763535 success | `77c2f42` | run 42268252537: success | run 42268252426: success |

Three **Dependabot Updates** runs on `77c2f42` also succeeded (42268260959, 42268261386, 42268261211), so Dependabot
accepted the new `ignore` configuration.

**How #10 and #11 were actually closed.** The `@dependabot close` comments (18:04 UTC) got no reply or reaction from
Dependabot. Both PRs were closed by `dependabot[bot]` at 18:10 UTC, right after #12 merged, with *"Looks like these
dependencies are no longer being updated by Dependabot, so this is no longer needed"* (#10) and *"Looks like
typescript is no longer being updated by Dependabot"* (#11): **the ignore rules closed them**, not the commands. Same
outcome; noted so the record says what happened. Next time: the ignore rule alone is enough to close a declined
Dependabot PR once merged.

## Open item for the author

The `minimatch` security update keeps reporting "not possible" on Dependabot security runs until `typescript-eslint`
moves to `minimatch` 11. The usual way to stop the repeated attempts is to dismiss those Dependabot alerts in the
Security tab as *tolerable risk* (dev-only lint tooling, see docs/frontend.md → *Version constraints*). Not done by the
agent: it changes the repository's security state.
