# CLAUDE.md

Guidance for Claude Code (and any other agent) working in this repository.

## What this project is

A multi-tenant notification service: tenants define templates, configure channels, and send
notifications (email / SMS / push / in-app) immediately or on a schedule. The service dispatches
them concurrently through bounded worker pools, enforces per-tenant rate limits and fairness,
retries transient failures with backoff, prevents duplicate deliveries, and persists every state
transition to an audit trail.

Built as an assignment. The full brief is in `docs/00-original-requirement.md`.

## Stack

| Concern | Choice |
|---------|--------|
| Language | Java 25 |
| Framework | Spring Boot 4.1.1 |
| Build | Maven 3.9+ |
| Database | PostgreSQL 16 (local, via `docker compose up -d`) |
| Migrations | Flyway |
| Data access | Spring Data JPA |
| Security | Spring Security, stateless JWT |
| Tests | JUnit 5, AssertJ, Mockito, Awaitility, Testcontainers |
| API docs | springdoc-openapi |

Base package: `com.notifly.notification`.

## Commands

```bash
docker compose up -d          # start PostgreSQL (required before running or testing)
mvn clean verify              # compile + run all tests
mvn test                      # unit + integration tests
mvn spring-boot:run           # run the service on :8080
```

Swagger UI: `http://localhost:8080/swagger-ui.html`

## Architectural invariants

These are load-bearing. Do not change them without updating `docs/02-decision-log.md`.

1. **Every tenant-owned entity carries `tenant_id`.** Isolation is enforced by a Hibernate filter
   driven by `TenantContext`. Never write a repository method that could return another tenant's
   row. New tenant-owned tables need a corresponding isolation test.
2. **Tenant identity comes from the JWT, never from a request header.** A client-supplied
   `X-Tenant-ID` is not trusted.
3. **Worker pools are bounded.** No unbounded executors, no virtual-thread-per-task for dispatch.
   Backpressure must push work back to the database, not into memory.
4. **Dispatch claims work with `FOR UPDATE SKIP LOCKED`.** Never claim by plain `SELECT` + update.
5. **Accepted notifications are never dropped.** Rate limiting defers at dispatch; only ingress
   rejects, with `429` + `Retry-After`.
6. **Every state transition is audited.** Writing a new status onto a notification without an
   accompanying audit event is a bug.
7. **Delivery is at-least-once.** Do not claim exactly-once anywhere in code, comments, or docs.

## Package layout

Feature-oriented, not layer-oriented — an entity, its repository, its service and its controller
sit together, so a change to one concern touches one package.

```
com.notifly.notification
├── common/model/   BaseEntity, TenantOwnedEntity, Channel
├── tenant/         Tenant, TenantStatus
├── user/           User, UserRole, UserStatus
├── channel/        TenantChannelConfig
├── template/       Template, TemplateVersion, TemplateChannelBody, TemplateVariable
├── delivery/       NotificationRequest, Notification, DeliveryAttempt, status enums
├── ratelimit/      RateLimitPolicy
├── idempotency/    IdempotencyKey
├── suppression/    Suppression, SuppressionReason
└── audit/          AuditEvent, AuditEventType, ActorKind
```

The feature package is `delivery/`, not `notification/`, to avoid the unreadable
`com.notifly.notification.notification`.

## Conventions

- REST paths are versioned: `/api/v1/...`
- Errors are RFC 7807 `application/problem+json`, produced centrally — controllers do not build
  error bodies by hand.
- Request/response DTOs are records, validated with Jakarta Bean Validation. Entities are never
  exposed directly through a controller.
- Business rules live in services; controllers do mapping and nothing else.
- Authorization is declarative via `@PreAuthorize` on service or controller methods.
- Template rendering is strict: an undeclared or missing variable is a validation error, never a
  silently empty string.

## Testing expectations

- Algorithms (rate limiter, backoff calculator, template renderer, state machine, fairness
  selector) get unit tests with no Spring context.
- Flows (auth, RBAC, tenant isolation, send → dispatch → delivered, idempotency, `429`,
  scheduling) get integration tests against a real Postgres via Testcontainers.
- Async assertions use Awaitility, never `Thread.sleep`.

## Out of scope — do not build these

UI/frontend, deployment, containerization of the app itself, CI/CD, microservices, message
brokers, OAuth/SSO/MFA, production observability stacks. The committed `docker-compose.yml`
contains only PostgreSQL as a local development dependency.

## Git

- Conventional commits (`feat:`, `fix:`, `test:`, `docs:`, `chore:`), one per logical milestone.
- Work happens directly on `main`.
- **Do not add AI co-authorship trailers to commits.**
