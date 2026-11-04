# ADR-0005: AI-assisted development with Claude Code

- **Status:** Accepted
- **Date:** 2026-11-03

## Context

The solution is developed with an AI coding agent (Claude Code). AI assistance speeds up delivery but needs
guardrails so that output stays consistent with the architecture, the specs and the team's workflow, and so that
the process is transparent to reviewers.

## Decision

AI configuration is versioned in the repository and shared by anyone who clones it:

- `CLAUDE.md`: project rules (architecture, testing, git workflow, definition of done).
- `.claude/skills/`: repeatable workflows — `spec-feature`, `implement-spec`, `pr-description`, `adr`.
- `.claude/agents/`: focused reviewers — `code-reviewer`, `spec-reviewer`, `test-engineer`.
- `.claude/settings.json` hooks: block commits/pushes on `main`, force-push and `--no-verify`; run Spotless after
  edits; run `./mvnw verify` before the agent finishes a turn.

The human author owns every change: specs are approved and PRs are reviewed and merged by a person.

## Alternatives considered

1. **No AI tooling in the repo (personal prompts only)** — not reproducible and invisible to reviewers.
2. **AI guardrails only in CI** — catches problems later and costs more iterations.

## Consequences

- **Positive:** consistent, reviewable AI output; workflow is documented and reproducible.
- **Negative:** hooks add some latency (Spotless per edit, full build on Stop); hooks only constrain the agent,
  which is why GitHub rulesets and git hooks remain the source of truth.
