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
covers only 1.31.

## The ignore rule

`.github/dependabot.yml`, docker entry: `nginx` ignored for `>= 1.31, < 1.32`, `>= 1.33, < 1.34`, … up to 1.39.

- **Why a list of ranges:** Dependabot has no "odd minor" pattern. GitHub's docs say Docker ignore ranges use Ruby
  version syntax, hence `">= 1.31, < 1.32"`. Even minors (1.32, 1.34 …, the next stable branches) and patch releases
  of the current stable keep flowing.
- **Why up to 1.39:** nginx cuts one stable branch a year, so the list covers several years; the comment and
  `docs/frontend.md` say to extend it before 1.39.
- **Why in the file as well:** same reasoning as in [009](009-dependabot-triage.md): the decision is versioned and
  reviewed next to its reason and revisit condition, and it covers future mainline minors.
- **Verification after merge:** Dependabot reads the configuration from `main`; the *Dependabot Updates* runs on the
  merge commit must succeed (as in 009) and no PR for a 1.3x odd minor should appear.

## Docs

- `docs/frontend.md` → *Version constraints*: `nginx` row.
- `SOLUTION.md` § 3.5: one sentence on nginx stable only.
