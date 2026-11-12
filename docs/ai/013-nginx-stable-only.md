# 013 — Dependabot nginx 1.31 bump declined: frontend image stays on nginx stable

- **Date:** 2026-11-12
- **Tool:** Claude Code (Claude Opus 5.5)
- **Branch / PR:** `chore/dependabot-ignore-nginx-mainline` (follows the decline of Dependabot PR #17)

## Prompts

### 1. Analysis request

````text
We continue the flight-aggregator. Read CLAUDE.md, docs/exercise.md, SOLUTION.md,
docs/adr/, docs/ai/ and specs/ (especially <relevant specs and their follow-ups>) before
planning. Same conventions as previous PRs: English, SDD workflow, SOLID applied
pragmatically.

Goal: dependanbot has left me a PR with a bump from version 1.30-alpine to 1.31-alpine. I want you to analyze it and tell me what impact it has and if there is any danger in merging into the main of this PR.
````

### 2. Close

````text
Close the PR with the comment for the moment
````

### 3. Ignore rule, review, verify, PR

````text
Go ahead and add the rule in dependabot.yml, it would only be for odd versions, right?
Then check that everything is going well

Then run code-reviewer and spec-reviewer, fix findings

And then verify: ./mvnw verify <and other checks> green (report durations); <manual or compose
   checks, measurements to record>. Then /pr-description, push after my OK, wait for the CI
   run of the pushed commit and report it by SHA, and check code scanning findings.
````

## Analysis of #17

| | |
|---|---|
| Bump | `frontend/Dockerfile`: `FROM nginx:1.30-alpine` → `FROM nginx:1.31-alpine` (runtime stage of the dashboard image only) |
| CI on the PR | all 8 checks green, including the image smoke test and the full-stack smoke test |
| Upstream changes (nginx.org CHANGES, 1.31.0 – 1.31.8, read on 2026-11-12) | behaviour changes in HTTP/2, HTTP/3/QUIC, DAV, XSLT, gRPC, SSL, `working_directory`; none in the directives `docker/nginx.conf` uses (`add_header`, `proxy_pass` with variables, `resolver`, `gzip`, `try_files`, `alias`, `listen`, temp paths) |
| Security | the fixes in mainline 1.31.6 / 1.31.7 (CVE-2026-90439, CVE-2026-15176 …) also shipped in stable 1.30.5 / 1.30.6, which the floating `1.30-alpine` tag already pulls |
| Problem | nginx numbers stable branches with an even minor and mainline with an odd one: 1.31 is mainline, and spec 006 AC-24 requires `nginx:<stable>-alpine`. The smoke tests do not check the branch, so merging would silently break AC-24; `docs/frontend.md` would also go stale (Dependabot edits only the Dockerfile) |

**Decision:** decline. Low runtime risk, but no security gain and a spec violation.

## Closing #17

The author asked to close it with `@dependabot ignore this minor version` (one comment, with the reason below the
command). Dependabot closed the PR at 15:36:59 UTC. That ignore state lives on GitHub, outside the repository, and
covers only 1.31. It adds to the `dependabot.yml` rule: if the rule is ever removed, 1.31 stays ignored until someone
comments `@dependabot unignore nginx` on a Dependabot PR.

## The ignore rule

`.github/dependabot.yml`, docker entry: `nginx` ignored for `>= 1.31, < 1.32`, `>= 1.33, < 1.34`, … up to 1.39.

- **Why a list of ranges:** Dependabot has no "odd minor" pattern. GitHub's docs say Docker ignore ranges use Ruby
  version syntax, hence `">= 1.31, < 1.32"`; dependabot-core strips the `-alpine` suffix before comparing
  (`Tag#numeric_version`), so the ranges match the tags we use. Even minors (1.32, 1.34 …, the next stable branches)
  are still proposed. Patches of the current stable never come through Dependabot: they arrive on rebuild through the
  floating `1.30-alpine` tag (1.30.6 at the time of writing).
- **Why up to 1.39:** nginx cuts one stable branch a year, so the list covers several years; the comment and
  `docs/frontend.md` say to extend it before 1.39.
- **Why in the file as well:** same reasoning as in [009](009-dependabot-triage.md): the decision is versioned and
  reviewed next to its reason and revisit condition, and it covers future mainline minors.
- **Verification after merge:** Dependabot reads the configuration from `main`; the *Dependabot Updates* runs on the
  merge commit must succeed (as in 009) and no PR for a 1.3x odd minor should appear.

## Docs

- `docs/frontend.md` → *Version constraints*: `nginx` row.
- `SOLUTION.md` § 3.5: one sentence on nginx stable only.

## Review findings

`code-reviewer` and `spec-reviewer` on the first commit. No blocking findings; the rule itself was checked against
dependabot-core's Docker update checker (suffix stripping, comma-separated requirements, nothing stable blocked).

| Finding | Action |
|---|---|
| AC-24 "stable" is enforced by nothing: a hand edit to an odd minor passes CI | **Fixed**: `frontend/docker/smoke-test.sh` reads `nginx -v` inside the image and checks the minor is even (24 checks; reports `1.30.6`). Checked that the expression fails for `31` and for an empty version |
| The stable-only policy is not visible next to the `FROM` line | **Fixed**: comment in `frontend/Dockerfile`; *Delivery* bullet in `docs/frontend.md` says "stable branch" |
| A stable bump (1.32) edits only the Dockerfile; the `1.30` in `docs/frontend.md` goes stale | **Fixed**: the row's revisit condition says to update the row and the *Delivery* bullet in the same PR |
| "Patches of the current stable keep flowing" is misleading (floating tag, Dependabot never proposes them) | **Fixed** (wording above) |
| Table header says "declared peer range", nginx is held by policy | **Fixed**: header names both |
| `SOLUTION.md` nginx sentence has no link | **Fixed**: links this record |
| The `@dependabot ignore` state lives outside the repo | **Fixed**: noted in *Closing #17* |
| CVE claims in the yml comment unverified | **Verified** against nginx.org/en/security_advisories.html: CVE-2026-90439 not vulnerable in 1.31.6+ / 1.30.5+; CVE-2026-15176, -93479, -95101, -97629, -97205 not vulnerable in 1.31.7+ / 1.30.6+. The only 1.31-only advisory (CVE-2026-42530, 1.31.0–1.31.1) never affected 1.30 |
| The same Docker entry also watches `node:26-alpine`; a `node:27-alpine` PR would edit only the Dockerfile and leave `.nvmrc` / `engines` behind (AC-26) | **Not changed here** (Node, not nginx; outside this PR): left to the author as a follow-up |
| Reviewer's own `./mvnw verify` failed | Environment: Testcontainers could not reach Docker from the reviewer's sandbox (Docker Desktop was also stopped at first). Re-run locally with Docker up: see *Verification* |

## Verification (local, final tree, Docker Desktop running)

| Gate | Result | Duration |
|---|---|---|
| `./mvnw verify` (unit + IT + coverage gate + Spotless + ArchUnit, all modules) | green | 77 s |
| `npm run check` (Prettier, ESLint, tsc, Vitest 118 tests, 99.74 % lines / 95.18 % branches) | green | 6 s |
| `bash frontend/docker/smoke-test.sh` | 24 passed, 0 failed (`nginx is a stable (even-minor) branch: 1.30.6`) | 3 s (cached image layers) |
| `scripts/check-mermaid.sh` | green | 18 s |
| `.claude/hooks/tests/guard-git.test.sh` / `classify-paths.test.sh` | 39 / 18 passed | 1 s / < 1 s |

The first local `./mvnw verify` failed with 112 × "Could not find a valid Docker environment": Docker Desktop was not
running, so the Testcontainers ITs (PostgreSQL, Redis) could not start. Not related to this branch; green once Docker
was started. No compose run or measurement is needed: the image, its nginx and its config are unchanged (only a
Dockerfile comment); the CI full-stack smoke job covers the stack.
