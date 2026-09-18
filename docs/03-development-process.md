# Development Process, Tooling & Skills Used

The submission brief requires the agent guidance file, the skills used, and the raw working files
to be included in the repository. This file records the process; `CLAUDE.md` is the guidance file
itself, and `docs/` holds the raw material.

## Tooling

| Tool | Use |
|------|-----|
| Claude Code (Opus 5) | Primary development agent — scaffolding, implementation, tests, docs |
| IntelliJ IDEA | Editor, debugger, database console |
| Maven 3.9.16 | Build and test runner |
| Docker Desktop | Runs the local PostgreSQL 16 instance only |
| git + GitHub | Version control, submission |

## Agent guidance files

- **`CLAUDE.md`** (repository root) — the persistent instruction file read by Claude Code at the
  start of every session. It encodes the stack, the architectural invariants that must not be
  violated, naming/testing conventions, and the explicit out-of-scope list.

## Skills used

No custom or third-party Claude Code *skills* were authored or installed for this project. The
work was driven by `CLAUDE.md` plus the built-in tooling. Where a built-in capability was used it
is noted below:

| Capability | Where it was used |
|------------|-------------------|
| Built-in file/search/edit tools | All source and documentation authoring |
| Shell execution | Maven builds, Docker lifecycle, git operations |
| Environment inspection | Verifying JDK, Maven, Docker, git before scaffolding |

If a skill is added later in development, it will be committed under `.claude/skills/` and listed
here.

## Working method

The requirement was deliberately open-ended, so the process front-loaded decision-making:

1. **Inspect before assuming.** The machine was checked for JDK, Maven, Docker, `gh`, and git
   identity before any technology was pinned. This surfaced two constraints that changed the
   plan — only JDK 25 was installed, and the Docker daemon was stopped.
2. **Ask, don't assume.** 54 open questions were raised and answered before the first line of
   code — covering tenancy strategy, auth, entity set, API surface, concurrency model, rate
   limiting, retry semantics, and test scope. The full list and its answers are in
   `01-clarifying-questions-and-answers.md`.
3. **Record the reasoning, not just the outcome.** Each significant decision is an ADR in
   `02-decision-log.md` stating what was rejected and what it costs.
4. **Build in dependency order, commit per milestone.** Schema before entities, entities before
   services, services before dispatch, dispatch before tests that exercise it.

## Raw files retained

| File | Contents |
|------|----------|
| `00-original-requirement.md` | The brief, verbatim and un-edited |
| `01-clarifying-questions-and-answers.md` | Every question asked and the answer given |
| `02-decision-log.md` | Architecture decision records with rejected alternatives |
| `03-development-process.md` | This file |


## Outcome

Final state of the submission:

| Measure | Value |
|---|---|
| Flyway migrations | 7 |
| Database tables | 14 |
| Indexes | 50 (4 partial, covering the dispatcher's hot paths) |
| Check constraints | 140 |
| REST endpoints | 39 |
| Tests | 123 — 46 unit, 77 integration |
| Architecture decision records | 23 |

## Defects found by testing

Recorded because they are the clearest evidence that the tests do real work rather than
restating the implementation. None of these were visible by reading the code.

| Defect | How it would have manifested |
|---|---|
| The configured retry budget was never applied | `notifly.dispatch.retry.max-attempts` silently had no effect; every notification used a hardcoded default of five attempts |
| Duplicate-send race between lease expiry and re-claiming | A task queued behind a saturated pool could outlive its lease, be reclaimed, and then send a message the new owner was also sending |
| `EnumMap` copy constructor throws on an empty map | Omitting the `workers` configuration block would have crashed startup |
| `@Transactional` bypassed by self-invocation | Dispatch would have run without a transaction; caught before it shipped |
| Flyway autoconfiguration missing under Spring Boot 4 | Migrations silently never ran, and the application started happily against an empty database |
| Flaky JWT tampering test | An HS256 signature's final base64url character carries four significant bits, so flipping it sometimes decoded to the same bytes and the token stayed valid |
