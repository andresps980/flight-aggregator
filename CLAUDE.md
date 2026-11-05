# CLAUDE.md

Guidance for Claude Code (and humans) working in this repository.

## Project

**Flight Aggregation & Cache Optimization System** (assessment statement: `docs/exercise.md`).
Spring Boot 4 / Java 21, built with **hexagonal architecture** and a **spec-driven development (SDD)** workflow.
Every change starts from a spec in `specs/`, is delivered through a PR, and must pass the quality gates.

## Language rule

Everything in this repository is written in **English**: code, identifiers, comments, tests, docs, specs, ADRs,
commit messages and PR descriptions — even when the conversation with the user happens in another language.

## Modules

Maven multi-module build; the parent `pom.xml` and `./mvnw` live at the repository root.

| Module | Purpose | Base package |
|---|---|---|
| `flight-aggregator/` | Ingestion, deduplication and search API | `com.andresps.flights` |
| `gds-mock/` | Mock GDS providers (Mock-Alpha JSON, Mock-Beta XML, Mock-Gamma CSV) with chaos; test double, must not depend on `flight-aggregator` | `com.andresps.gdsmock` |
| `frontend/` | React + TypeScript dashboard (planned, ADR-0009) | — |

Shared plugin configuration (Spotless, JaCoCo, Failsafe) lives in the parent `pluginManagement`; each module
activates it in its own `<build><plugins>`, so formatting, the coverage gate and ArchUnit rules apply per module.

## Commands

| Task | Command |
|---|---|
| Format code | `./mvnw spotless:apply` (one module: `-pl <module>`) |
| Unit tests only | `./mvnw test` (one module: `./mvnw -pl <module> test`) |
| Full build, all modules (unit + IT + coverage gate + format check) | `./mvnw verify` |
| Also run tests tagged `slow` (1M-record volume tests, excluded by default) | `./mvnw verify -Pslow-tests` |
| Run the mock providers | `docker compose up --build` or `./mvnw -pl gds-mock spring-boot:run` |
| Run the aggregator | `./mvnw -pl flight-aggregator spring-boot:run` |
| Coverage report | `<module>/target/site/jacoco/index.html` (after `verify`) |

Always run `./mvnw verify` before declaring a task done. The Stop hook also runs it automatically.

## Architecture (enforced by `ArchitectureTest`)

Package layout per feature, under `<base-package>.<feature>` (e.g. `com.andresps.flights.<feature>`).
Every module with code has its own `ArchitectureTest` (`<module>/src/test/java/.../architecture/`):

```
domain/            model, value objects, domain exceptions. Plain Java: no Spring, no Jakarta, no Jackson.
application/
  port/in/         use case interfaces (driving ports)
  port/out/        interfaces the use cases need (driven ports)
  service/         use case implementations. Plain Java: no Spring annotations.
infrastructure/
  adapter/in/      REST controllers, DTOs, listeners
  adapter/out/     persistence, HTTP clients, config-backed providers
  config/          @Configuration wiring use cases and adapters (composition root)
shared/            cross-feature building blocks (e.g. DomainValidationException, global error handler)
```

Rules:
- Dependencies point inwards: `infrastructure -> application -> domain`. Never the other way.
- Use cases are wired as `@Bean`s in `infrastructure/config`, never annotated with `@Service`.
- Controllers expose DTOs (`*Request`/`*Response` records), never domain objects.
- Constructor injection only. No field injection, no `System.out`, no generic exceptions.
- Errors go out as RFC 9457 `ProblemDetail` via `GlobalExceptionHandler`.
- Features must not have cyclic dependencies on each other.
- `gds-mock` has no `application/` layer (no use cases or driven ports): `domain` + `infrastructure` only, and it
  must never depend on `com.andresps.flights` (its own `ArchitectureTest` enforces both).

SOLID in practice here: one use case per interface (SRP/ISP), new behaviour through new adapters/implementations rather
than editing existing ones (OCP), ports as abstractions owned by the application (DIP), implementations honour port
contracts (LSP).

## SDD workflow

1. **Spec** — `/spec-feature`: write `specs/NNN-<slug>/spec.md` from the template. Acceptance criteria in Given/When/Then.
   Do not invent requirements: list doubts under *Open questions* and ask the user.
2. **Design decision?** — `/adr`: record significant decisions in `docs/adr/`.
3. **Implement** — `/implement-spec NNN`: branch, tests first from the acceptance criteria, then code.
4. **Review** — run the `code-reviewer` and `spec-reviewer` agents on the branch.
5. **PR** — `/pr-description`: push the branch and open the PR with the required format.

## Git workflow

- **Never commit or push to `main`.** Hooks block it; `main` only changes through merged PRs.
- Branch names: `feat/NNN-<slug>`, `fix/<slug>`, `chore/<slug>`, `docs/<slug>`.
- Conventional Commits: `feat: ...`, `fix: ...`, `test: ...`, `docs: ...`, `refactor: ...`, `chore: ...`.
- One functional increment per PR, with its unit and integration tests and docs updates.
- Never use `--no-verify` or force-push.

## Testing conventions

- Unit tests: `*Test.java` (Surefire). No Spring context. Mock ports with Mockito.
- Integration tests: `*IT.java` (Failsafe). `@SpringBootTest(webEnvironment = RANDOM_PORT)` and real HTTP calls.
- Each acceptance criterion in the spec maps to at least one test; reference it in the test name or `@DisplayName`.
- Coverage gate: 80% lines / 70% branches on merged unit + IT coverage. Don't game it with trivial tests.
- AssertJ for assertions, given/when/then structure.

## Definition of done

- [ ] Spec exists and acceptance criteria are covered by tests
- [ ] `./mvnw verify` is green locally
- [ ] Docs updated: `docs/usage.md` for behaviour, an ADR for design decisions, `SOLUTION.md` when a decision,
      limitation or trade-off changes
- [ ] Prompts/agents used recorded in `docs/ai/`
- [ ] PR description follows the What / Why / Changes format

## Hooks active in this repo (`.claude/settings.json`)

- `SessionStart` — activates `.githooks` and reports the current branch.
- `PreToolUse(Bash)` — `guard-git.sh` blocks commits/pushes on main, pushes to main, force-push and `--no-verify`.
  Rules apply per sub-command (split on `&&`, `||`, `;`, `|` outside quotes), only to real `git push` / `git commit`.
  Tests: `bash .claude/hooks/tests/guard-git.test.sh` (also run in CI); extend them when changing the hook.
- `PostToolUse(Edit|Write)` — `format-java.sh` runs Spotless after Java/POM edits (scoped to the edited module).
- `Stop` — `verify-on-stop.sh` runs `./mvnw verify` when any `pom.xml` or `<module>/src` changed since the last green build.
