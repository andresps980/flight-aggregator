---
name: pr-description
description: Write the pull request description for the current branch in the required What/Why/Changes format and open the PR with gh. Use when the user asks to open, create or describe a PR.
---

# Pull request description

## Steps

1. Make sure you are NOT on `main` and the working tree is clean. `./mvnw verify` must be green.
2. Run `git fetch origin main` and then `git diff origin/main...HEAD` (plus `git log origin/main..HEAD --oneline`)
   to see all changes on this branch.
3. Find the related spec in `specs/` (from the branch name `feat/NNN-...` or the diff).
4. Write the description following **exactly** this format:

```markdown
## What
One sentence explaining what this PR does.

## Why
Brief context on why this change is needed

## Changes
- Bullet points of specific changes made
- Group related changes together
- Mention any files deleted or renamed
```

   After `## Changes`, append:

```markdown
## Spec
specs/NNN-<slug>/spec.md — covers AC-1, AC-2, ...

## How to test
./mvnw verify
<curl example if there is an endpoint>
```

5. Save it to a temp file outside the repo and show it to the user.
6. After the user confirms: `git push -u origin HEAD` and
   `gh pr create --base main --title "<conventional-commit-style title>" --body-file <file>`.
7. Report the PR URL and the CI status (`gh pr checks`).

## Rules

- "What" is one sentence. "Why" is the problem, not a restatement of the changes.
- Group the changes by concern (domain, API, tests, docs, build/CI).
- Explicitly list deleted or renamed files.
