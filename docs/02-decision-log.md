# Decision Log

Architecture decisions with their reasoning. Each entry states the decision, why it was taken,
what was rejected, and the consequence — including the unpleasant ones.

---

## ADR-001 — Shared schema with a `tenant_id` discriminator

**Decision.** Every tenant-owned table carries a non-null `tenant_id`. Isolation is enforced by a
Hibernate filter enabled automatically on every session, fed by a `TenantContext` populated from
the authenticated principal.

**Rejected.** Schema-per-tenant and database-per-tenant. Both give stronger isolation but require
migration fan-out across N schemas, a connection-pool-per-tenant story, and cross-tenant reporting
via `UNION` — cost that buys nothing at assignment scale.

**Consequence.** Isolation becomes a code-level invariant rather than a database-level one. A
missing filter is a data leak, so tenant isolation is covered by explicit integration tests rather
than trusted by inspection.

---

## ADR-002 — Tenant identity comes from the token, never from a header

**Decision.** `tenant_id` is read from the authenticated JWT's claims. A client-supplied
`X-Tenant-ID` header is ignored for tenant users.

**Rejected.** Header-driven tenancy. It is trivially spoofable — any tenant admin could read
another tenant's delivery reports by editing one header.

**Consequence.** Platform admins, who legitimately act across tenants, get explicit scoped
endpoints instead of an ambient override.

---

## ADR-003 — Database-backed outbox instead of a message broker

**Decision.** Notifications are rows in a queue table. A poller claims batches with
`SELECT ... FOR UPDATE SKIP LOCKED` and hands them to bounded per-channel worker pools.

**Rejected.** Kafka / RabbitMQ / SQS — the brief puts distributed systems out of scope. A pure
in-memory queue was also rejected because it loses scheduled and in-flight work on restart, which
would make the persistence and audit requirements hollow.

**Consequence.** Throughput is bounded by the database rather than by a broker. In exchange, the
send request and its queue entry commit in a single transaction, so a notification can never be
accepted and then lost — and the entire delivery lifecycle is queryable in SQL.

---

## ADR-004 — Explicit bounded thread pools, not virtual threads

**Decision.** Each channel gets a `ThreadPoolExecutor` with a fixed core size, a bounded queue,
and a caller-runs style rejection policy, sized from configuration.

**Rejected.** Virtual threads. They are unbounded by design; the requirement explicitly asks for
*bounded* worker pools, and unbounded concurrency against a rate-limited downstream provider is
the failure mode the requirement is guarding against.

**Consequence.** Backpressure is real and observable — when a channel saturates, work stays in the
database instead of piling up in memory.

---

## ADR-005 — Per-tenant fairness by weighted round-robin claiming

**Decision.** The dispatcher does not claim work in pure insertion order. It round-robins across
tenants with pending work, weighted by tenant priority, and caps each tenant's in-flight count.

**Rejected.** FIFO. A single tenant enqueueing a million notifications would starve every other
tenant for hours — precisely the "per-tenant fairness under load" the requirement calls out.

**Consequence.** Slightly more complex claim logic, and a tenant's own bulk send may interleave
with others rather than draining as one block. That is the intended trade.

---

## ADR-006 — Two independent layers of duplicate prevention

**Decision.** (1) An `Idempotency-Key` header scoped per tenant makes *submission* idempotent —
a replayed request returns the original result instead of creating new work. (2) A claim/lease
with attempt versioning makes *dispatch* idempotent — a worker that crashes mid-send cannot have
its row picked up and re-sent without the attempt being recorded first.

**Rejected.** Relying on either one alone. The header protects against client retries but not
worker crashes; the lease protects against worker crashes but not a client double-submitting.

**Consequence.** Delivery is **at-least-once**, not exactly-once. Exactly-once across a network
boundary to a third-party provider is not achievable; the honest guarantee is that duplicates are
prevented wherever the system has authority, and the remaining window is documented.

---

## ADR-007 — Rate limits defer at dispatch, reject at ingress

**Decision.** Exceeding a limit on an API call returns `429` with `Retry-After`. Exceeding a limit
inside the dispatcher does **not** fail the notification — it is deferred and retried later.

**Rejected.** Uniform behaviour. Dropping an already-accepted notification because of an internal
throughput limit would violate the acceptance contract the API made with the caller.

**Consequence.** Accepted work is never lost to rate limiting; it is only delayed.

---

## ADR-008 — Simulated channel providers behind an SPI

**Decision.** A `ChannelProvider` interface with per-channel simulator implementations whose
latency and failure rate are configurable, including a distinction between transient and permanent
failures.

**Rejected.** Real vendor integrations (Twilio, SES, FCM). They require credentials no grader has,
introduce network flakiness into the test suite, and would make the retry/backoff behaviour — the
actually interesting part — impossible to demonstrate deterministically.

**Consequence.** The retry, backoff, and dead-letter paths are exercised on demand by turning a
configuration knob, and the SPI boundary is where a real provider would drop in unchanged.

---

## ADR-009 — Raw UUID foreign keys instead of JPA associations

**Decision.** Entities reference each other by `UUID` columns (`templateId`, `requestId`,
`notificationId`) rather than `@ManyToOne` / `@OneToMany` associations. Joins happen explicitly in
queries when a query needs them.

**Rejected.** A fully mapped object graph. It reads more naturally for CRUD, but the dispatcher
reads notifications in tight batched loops, and a lazy association traversed inside one of those
loops turns a single query into thousands. That failure mode is invisible in a unit test and
catastrophic under the load this service is specifically supposed to handle.

**Consequence.** Loading a related entity is a deliberate repository call rather than a field
access, so the cost is always visible at the call site. Since controllers map to DTOs and never
serialise entities, the object graph was never needed for responses anyway.

---

## ADR-010 — The state machine lives on the enum, not in the services

**Decision.** `NotificationStatus.canTransitionTo` declares every legal transition, and
`Notification.transitionTo` is the only way to change status. An illegal transition throws
`IllegalStateException` rather than being rejected as user input.

**Rejected.** Letting each service set the status it needs. With a dispatcher, a scheduler, a
lease reaper and a tenant-facing cancel endpoint all mutating the same column, "which transitions
are legal" would have been spread across four places and would have drifted.

**Consequence.** An illegal transition is a bug in our code, not bad input from a caller, and is
surfaced as a 500 rather than a 400 — deliberately, because it should never reach production and
must be loud when it does.

---

## ADR-011 — Append-only tables do not extend the mutable entity base

**Decision.** `delivery_attempts` and `audit_events` carry no `updated_at` or `version` column and
do not extend `BaseEntity`.

**Rejected.** A single base class for everything. Uniformity would have added an update timestamp
and an optimistic-lock version to tables that are never updated, implying a mutability that the
audit trail specifically must not have.

**Consequence.** The type system now distinguishes mutable records from append-only ones. Writing
code that updates an audit event does not compile against a setter that does not exist.
