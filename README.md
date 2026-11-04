# spring-boot-template

Spring Boot 4 / Java 21 service template with **hexagonal architecture**, a **spec-driven development (SDD)**
workflow, automated **quality gates** and shared **Claude Code** tooling (skills, agents and hooks).

> The `greeting` feature is a minimal end-to-end example (domain → use case → REST + config adapter, with unit,
> integration and architecture tests). Delete it once the first real feature lands.

## Requirements

- JDK 21
- Git, and optionally the [GitHub CLI](https://cli.github.com/) (`gh`)
- Docker (only if you add Testcontainers-based integration tests)
- [Claude Code](https://docs.claude.com/en/docs/claude-code/overview) for the AI-assisted workflow
- `jq` or `python3` recommended (used by the Claude Code hooks to parse JSON)

## Quick start

```bash
./mvnw verify                 # format check, unit + integration tests, architecture rules, coverage gate
./mvnw spring-boot:run        # start on http://localhost:8080
curl "http://localhost:8080/api/v1/greetings?name=Ana"
```

See [docs/usage.md](docs/usage.md) for the API and configuration.

## Quality gates

| Gate | Tool | Where |
|---|---|---|
| Formatting | Spotless (palantir-java-format) | `verify`, pre-commit hook, Claude hook, CI |
| Unit tests (`*Test`) | JUnit 5, Mockito, AssertJ (Surefire) | `verify`, CI |
| Integration tests (`*IT`) | `@SpringBootTest` on a random port (Failsafe) | `verify`, CI |
| Architecture & SOLID rules | ArchUnit (`ArchitectureTest`) | `verify`, CI |
| Coverage ≥ 80 % lines / 70 % branches | JaCoCo (merged unit + IT) | `verify`, CI (summary + PR comment) |
| Vulnerable dependencies | Dependency Review, Dependabot | CI on PRs, weekly |
| Static security analysis | CodeQL (`security-and-quality`) | CI on PRs, weekly |

Coverage report: `target/site/jacoco/index.html`.

## Development workflow (SDD)

1. **Spec** the feature in `specs/NNN-<slug>/spec.md` (Claude Code: `/spec-feature`).
2. Record significant **design decisions** as ADRs in `docs/adr/` (`/adr`).
3. **Implement** on a branch, tests first (`/implement-spec NNN`).
4. **Review** with the `code-reviewer` and `spec-reviewer` agents.
5. Open a **PR** with the What / Why / Changes format (`/pr-description`). CI must be green to merge.

`main` is protected: no direct commits or pushes (enforced by GitHub rulesets, git hooks and Claude Code hooks).

## Claude Code tooling

| Type | Name | Purpose |
|---|---|---|
| Skill | `spec-feature` | Requirement → spec with testable acceptance criteria |
| Skill | `implement-spec` | Spec → branch → tests → code → docs |
| Skill | `pr-description` | PR body in the required format + `gh pr create` |
| Skill | `adr` | Architecture Decision Record from the template |
| Agent | `code-reviewer` | SOLID / architecture / tests / security review of the branch |
| Agent | `spec-reviewer` | Acceptance-criteria traceability matrix, scope creep, spec drift |
| Agent | `test-engineer` | Writes tests and closes coverage gaps |
| Hook | `guard-git.sh` | Blocks commit/push on `main`, force-push and `--no-verify` |
| Hook | `format-java.sh` | Spotless after every Java/POM edit |
| Hook | `verify-on-stop.sh` | `./mvnw verify` before Claude finishes, if sources changed |
| Hook | `session-start.sh` | Activates `.githooks`, reports the current branch |

Project rules for Claude live in [CLAUDE.md](CLAUDE.md).

## Project structure

```
.claude/              shared Claude Code config: settings + hooks, skills, agents
.githooks/            versioned git hooks (pre-commit, pre-push)
.github/              CI, CodeQL, Dependabot, PR template
docs/                 usage, architecture, ADRs, GitHub setup
specs/                one spec per feature (SDD)
src/main/java/com/example/app/
  <feature>/domain             model and invariants (framework-free)
  <feature>/application        ports and use cases (framework-free)
  <feature>/infrastructure     adapters (REST, config, persistence...) and wiring
  shared/                      cross-feature building blocks
```

## Documentation

- [Usage](docs/usage.md)
- [Architecture](docs/architecture.md)
- [Architecture Decision Records](docs/adr/README.md)
- [Specs](specs/README.md)
- [Repository bootstrap & GitHub setup](docs/github-setup.md)
