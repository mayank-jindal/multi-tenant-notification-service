# Scope Decisions

The brief is open-ended and rewards breadth, but it was delivered against a fixed deadline.
This file records what was built, what was deliberately left out, and why — so the omissions
read as choices rather than gaps.

## What the brief names explicitly

Every item below is stated in the requirement, and every one is implemented:

| Requirement | Where |
|---|---|
| Multiple channels (email, SMS, push, in-app) | `Channel`, per-channel provider adapters |
| Tenant-defined templates with variable substitution | `TemplateService`, strict `{{var}}` renderer |
| Scheduled and immediate sends | Send API, schedule promoter |
| Per-tenant rate limiting | Token bucket, resolved most-specific-first |
| Retries with backoff on transient failures | Exponential backoff with jitter |
| Delivery tracking | `Notification` state machine, `DeliveryAttempt` history |
| Concurrent dispatch via bounded worker pools | Per-channel `ThreadPoolExecutor` |
| Per-tenant fairness under load | Weighted round-robin claiming |
| No duplicate deliveries on retry | `Idempotency-Key` + dispatch lease |
| State transitions and attempts persisted with an audit trail | `audit_events`, append-only |
| RBAC for platform admin and tenant admin | Spring Security, `@PreAuthorize` on services |
| Input validation and error handling | Bean Validation, RFC 7807 problem documents |
| Unit and integration tests for core flows | JUnit 5 + Testcontainers |

## Built beyond the brief

These were not required. They are noted because breadth is part of the evaluation, and because
an assessor should be able to tell the difference between "asked for" and "chosen".

| Feature | Why it was worth it |
|---|---|
| Immutable template versioning | A queued notification cannot have its meaning rewritten by a later template edit, and a historical delivery stays explicable. The most expensive of these choices. |
| AES-256-GCM credential encryption | Provider credentials are secrets; storing them in plaintext would be indefensible even in an assignment. |
| Suppression list | Hard bounces and opt-outs are a real operational need for any sending system, and checking at submission means a suppressed recipient never consumes dispatch capacity. |
| Audit trail breadth | Covers administrative changes as well as state transitions, which is what makes the trail useful for investigating a configuration change. |

## Deliberately not built

Cut to fit the deadline. Each is a considered omission, not an oversight.

| Omitted | Reasoning |
|---|---|
| ~~In-app inbox read API~~ | **Since built.** Added after the assignment scope was met, because without it the IN_APP channel was only half a feature: delivered but unreadable. See `docs/08-real-providers.md`. |
| Dead-letter replay endpoint | Exhausted notifications reach a terminal `FAILED` state and are queryable. Re-driving them is an operator convenience, not part of the delivery guarantee. |
| Template preview endpoint | Rendering is exercised directly by unit tests. A preview endpoint would add API surface without adding capability. |
| Postman collection | OpenAPI is served at `/v3/api-docs` with a Swagger UI, which any client can import. A hand-maintained collection would duplicate it and drift. |
| Cross-tenant delivery search for platform admins | Platform admins can already read any single tenant's deliveries through the root scope. A global search across tenants is a reporting feature, not an isolation or delivery concern. |
| Rich aggregate reporting (p95 latency, success-rate trends) | Delivery reports are provided as counts by status and channel. Percentile and trend analysis is a analytics problem that a real deployment would solve with a time-series store, not with `GROUP BY` over the transactional table. |
| Outbound delivery-status webhooks | Would require retry, signing and endpoint management — a second delivery system inside the first, for a feature the brief never asks for. |

## Testing scope

The brief asks for unit and integration tests of the **core flows**, not exhaustive coverage.

- **Unit tests** target the algorithms, where the logic is and where bugs hide: the rate limiter,
  the backoff calculator, the template renderer, the state machine, the fairness selector, and
  credential encryption.
- **Integration tests** target the flows: authentication, RBAC boundaries, tenant isolation,
  submission through to delivery, idempotency, rate limit rejection, and scheduling.
- **One concurrency test** drives many tenants at once and asserts that the worker pools stay
  bounded and that no tenant is starved. It is kept despite the deadline because it is the only
  thing that actually demonstrates the concurrency requirements rather than asserting them.

Not attempted: exhaustive branch coverage, mutation testing, or contract tests.
