# ADR-0004: Automated quality gates

- **Status:** Accepted
- **Date:** 2026-11-03

## Context

Quality must be guaranteed mechanically, not by discipline alone, and checks should run as early as possible
(editor → commit → push → PR) to keep feedback fast.

## Decision

| Concern | Tool | Local | CI |
|---|---|---|---|
| Formatting | Spotless + palantir-java-format | Claude hook after edits, pre-commit | `spotless:check` job |
| Unit tests | Surefire (`*Test`) | `verify`, pre-push, Claude Stop hook | `verify` |
| Integration tests | Failsafe (`*IT`) | same | `verify` |
| Architecture rules | ArchUnit | same | `verify` |
| Coverage | JaCoCo, merged unit + IT, gate 80 % lines / 70 % branches | same | summary + PR comment + HTML artifact |
| Vulnerable dependencies | Dependency Review (PRs), Dependabot (weekly) | — | yes |
| Static security analysis | CodeQL `security-and-quality` | — | yes |

`main` is protected by a GitHub ruleset that requires a PR and all checks green. Git hooks and Claude Code hooks
duplicate the "no direct push to main" rule locally for faster feedback.

## Alternatives considered

1. **OWASP Dependency-Check** — thorough, but slow and needs an NVD API key; Dependency Review + Dependabot cover
   the need with zero setup for a public repo.
2. **SonarCloud** — richer quality dashboard; can be added later (free for public repos) without changing this setup.
3. **Checkstyle/PMD** — overlap with Spotless (style) and ArchUnit/CodeQL (rules); left out to keep the build fast.

## Consequences

- **Positive:** problems surface before review; reviewers focus on design.
- **Negative:** `verify` takes longer as integration tests grow; mitigated by running the full build on Stop/pre-push
  rather than after every edit.
