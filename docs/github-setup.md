# Repository bootstrap & GitHub setup

One-off steps to turn this template into a working repository.

## 1. Generate the Maven Wrapper (first time only)

The wrapper scripts are generated rather than copied. With a local Maven (or from IntelliJ:
*Maven tool window → Execute Maven Goal*):

```bash
mvn -N wrapper:wrapper -Dmaven=3.9.11
git update-index --chmod=+x mvnw    # after `git add`, so the script is executable on Linux CI
```

Then check everything works:

```bash
./mvnw verify
```

## 2. First commit and push (the only direct push to main)

Do this **before** activating the git hooks or opening Claude Code (both block commits/pushes on `main`):

```bash
git add .
git update-index --chmod=+x mvnw .githooks/pre-commit .githooks/pre-push .claude/hooks/*.sh .github/scripts/*.sh
git commit -m "chore: project bootstrap"
git push -u origin main
```

If the hooks are already active: `ALLOW_MAIN_COMMIT=1 git commit ...` and `ALLOW_MAIN_PUSH=1 git push ...`.

## 3. Activate the versioned git hooks

```bash
git config core.hooksPath .githooks
```

(Claude Code's `SessionStart` hook does this automatically when you open the project with `claude`.)

## 4. GitHub settings

**Settings → General**
- [ ] *Template repository* (for the template repo only)
- [ ] *Automatically delete head branches*
- [ ] Pull requests: allow *Squash merging* (recommended: squash only, title = PR title)

**Settings → Rules → Rulesets → New branch ruleset** (target: default branch)
- [ ] Enforcement: *Active*; no bypass actors
- [ ] *Restrict deletions*, *Block force pushes*
- [ ] *Require a pull request before merging* — required approvals: **0** (you can't approve your own PRs)
- [ ] *Require conversation resolution before merging*
- [ ] *Require status checks to pass* + *Require branches to be up to date*, with:
  `Format (Spotless)`, `Build, tests & coverage`, `Dependency review (vulnerable dependencies)`,
  `Static security analysis (CodeQL)`
  (checks only appear in the picker after they have run once — open a first PR, then come back)

**Settings → Code security**
- [ ] Dependency graph, Dependabot alerts, Dependabot security updates
- [ ] Secret scanning + push protection
- [ ] CodeQL: keep **default setup disabled** — this repo uses the advanced setup in `.github/workflows/codeql.yml`
  (both at once conflict)

**Settings → Actions → General**
- [ ] Workflow permissions: *Read repository contents* (jobs request extra permissions explicitly)

## 5. Creating the delivery repo from the template

```bash
gh repo create <name> --public --template <your-user>/spring-boot-template --clone
```

Then repeat step 4 (settings and rulesets are not copied from templates), and rename:
- `groupId` / `artifactId` / `name` in `pom.xml`, `spring.application.name` in `application.yml`
- the base package `com.example.app` (IntelliJ: *Refactor → Rename* on the package) and the package
  in `ArchitectureTest` (`@AnalyzeClasses` and the slices pattern)
