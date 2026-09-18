# Clarifying Questions & Answers

Before any code was written, every decision the brief left open was raised explicitly and
answered by the project owner. Nothing below was assumed. `⭐` marks the option that was
recommended; the **Answer** column records what was actually chosen.

## Round 1 — 54 questions

| # | Question | Recommended ⭐ | Answer |
|---|----------|---------------|--------|
| 1 | Language / framework | Java + Spring Boot | ⭐ accepted |
| 2 | Java version | 21 LTS | initially overridden to 25, then **returned to 21** (see Round 3) |
| 3 | Build tool | Maven | ⭐ accepted |
| 4 | Package / group id | `com.notifly.notification` | ⭐ accepted |
| 5 | Artifact id | `multi-tenant-notification-service` | ⭐ accepted |
| 6 | Database | PostgreSQL | ⭐ accepted, **run locally via Docker** |
| 7 | Schema management | Flyway versioned migrations | ⭐ accepted |
| 8 | Data access | Spring Data JPA + native queries on hot paths | ⭐ accepted |
| 9 | Test database | Testcontainers (real Postgres) | ⭐ accepted |
| 10 | Tenancy isolation | Shared schema + `tenant_id` discriminator + Hibernate filter | ⭐ accepted |
| 11 | Tenant identification | Derived from authenticated principal, not a client header | ⭐ accepted |
| 12 | Platform admin cross-tenant access | Read-only cross-tenant + full tenant CRUD | ⭐ accepted |
| 13 | Auth mechanism | Spring Security + stateless JWT, BCrypt passwords | ⭐ accepted |
| 14 | Roles | `PLATFORM_ADMIN`, `TENANT_ADMIN` only | ⭐ accepted |
| 15 | Bootstrap admin | Seeded via migration, configurable password | ⭐ accepted |
| 16 | Template versioning | Immutable versions + published pointer | ⭐ accepted |
| 17 | Recipient management | Inline addresses + suppression list | ⭐ accepted |
| 18 | In-app channel storage + read API | Include | ⭐ accepted |
| 19 | API surface | Auth / platform admin / tenant admin / send / inbox | ⭐ accepted |
| 20 | API versioning | `/api/v1` prefix | ⭐ accepted |
| 21 | Pagination | page + size with standard envelope | ⭐ accepted |
| 22 | Error format | RFC 7807 `application/problem+json` | ⭐ accepted |
| 23 | Provider integration | Pluggable SPI with configurable simulators, no real vendors | ⭐ accepted |
| 24 | Channels | EMAIL, SMS, PUSH, IN_APP | ⭐ accepted |
| 25 | Channel credentials | Encrypted at rest (AES) | ⭐ accepted |
| 26 | Async engine | DB-backed outbox + `FOR UPDATE SKIP LOCKED` + bounded pools | ⭐ accepted |
| 27 | Scheduler | Spring `@Scheduled` poller | ⭐ accepted |
| 28 | Worker pool sizing | Configurable per channel in `application.yml` | ⭐ accepted |
| 29 | Fairness | Weighted round-robin across tenants + max in-flight per tenant | ⭐ accepted |
| 30 | Threading model | Platform threads via explicit bounded `ThreadPoolExecutor` | ⭐ accepted |
| 31 | Rate limit algorithm | Token bucket per (tenant, channel) + global | ⭐ accepted |
| 32 | Behaviour on limit | `429` at ingress; defer (never drop) at dispatch | ⭐ accepted |
| 33 | Limit ownership | Platform admin sets; tenant admin may only lower | ⭐ accepted |
| 34 | Retry policy | Exponential backoff + jitter, 5 attempts, transient/permanent split | ⭐ accepted |
| 35 | Dead letter | Terminal `FAILED` + DLQ view + manual replay endpoint | ⭐ accepted |
| 36 | Duplicate prevention | `Idempotency-Key` header **and** dispatch-side claim/lease | ⭐ accepted |
| 37 | Delivery semantics | At-least-once with idempotent de-dup, documented | ⭐ accepted |
| 38 | State machine | `CREATED → SCHEDULED → QUEUED → SENDING → SENT → DELIVERED / FAILED / CANCELLED / SUPPRESSED` + `RETRY_SCHEDULED` | ⭐ accepted |
| 39 | Audit scope | State transitions **and** admin config changes | ⭐ accepted |
| 40 | Reporting | Aggregates by status/channel/template over a time range | ⭐ accepted |
| 41 | Outbound webhooks | Skip | ⭐ accepted (skipped) |
| 42 | Test split | Unit for algorithms, integration for flows, one concurrency/fairness test | ⭐ accepted |
| 43 | Test libraries | JUnit 5, AssertJ, Mockito, Awaitility | ⭐ accepted |
| 44 | Template engine | Custom strict `{{variable}}` renderer with declared variable schema | ⭐ accepted |
| 45 | Per-channel template variants | One template holds per-channel variants | ⭐ accepted |
| 46 | README contents | Assumptions, architecture, ER, state machine, API table, run/test | ⭐ accepted |
| 47 | CLAUDE.md | Created early, kept updated | ⭐ accepted |
| 48 | `docs/` folder for raw files | Requirement, Q&A, decision log, process notes | ⭐ accepted |
| 49 | OpenAPI / Swagger UI | Include (API documentation, not a frontend) | ⭐ accepted |
| 50 | Postman collection | Include in `docs/` | ⭐ accepted |
| 51 | Commit strategy | Frequent, conventional commits per milestone | ⭐ accepted |
| 52 | Branching | Work directly on `main` | ⭐ accepted |
| 53 | GitHub repo | — | Created manually in browser, public |
| 54 | AI co-author trailer on commits | — | **Omitted at owner's request** |

## Round 2 — environment-driven decisions

The machine was inspected before scaffolding. Three findings required a decision:

| Finding | Options presented | Answer |
|---------|-------------------|--------|
| Only JDK 25 installed (⭐ had proposed 21) | (a) install JDK 21 + Boot 3.5.16, (b) keep JDK 25 + Boot 4.1.1, (c) install JDK 21 + Boot 4.1.1 | **(b) Java 25 + Spring Boot 4.1.1** |
| Postgres needs to run locally | (a) `docker-compose.yml` committed, (b) `docker run` documented in README | **(a) docker-compose.yml** |
| `gh` CLI not installed | (a) install gh, (b) create repo in browser, (c) gh + private repo | **(b) created in browser, public** |

### Note on `docker-compose.yml` vs "no containerization"

The brief places *deployment and containerization* out of scope. The committed compose file
contains **only PostgreSQL** — it is a local development dependency, exactly like installing
Postgres natively would be. The application itself is never containerized, and no deployment
artifacts (Dockerfile, k8s manifests, CI pipelines) exist in this repository.


## Round 3 — returning to Java 21

Java 25 was chosen in Round 2 because it was the only JDK installed on the development machine
and avoided an installation step. Revisited before submission and reversed.

**Reasoning.** The project is submitted to be built and run by someone else. Java 25 is recent
enough that an assessor is unlikely to have it, and `mvn verify` fails immediately on an older
JDK with an unhelpful class-version error — a failure that says nothing about the work itself.
Java 21 is the current mainstream LTS and the version most likely to already be present.

**Cost of the change.** None to the source. Temurin 21 was installed, `java.version` was changed
to 21, and all 123 tests passed with no code modifications — no Java 22+ language or library
feature had been used. Spring Boot 4's baseline is Java 17, so the framework was unaffected.

**Result.** The project targets Java 21 and builds unchanged on later JDKs.
