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

---

## ADR-012 — Tenant isolation via Hibernate `@TenantId`, not `@Filter`

**Decision.** Tenant-owned entities carry a `@TenantId` column. A
`CurrentTenantIdentifierResolver` reads the scope from a `TenantContext` thread local, which a
servlet filter populates from the authenticated principal.

**Rejected.** A Hibernate `@FilterDef` / `@Filter` enabled per session by an aspect. It reads
well and makes the platform-admin bypass trivial, but **a filter is silently ignored by
`EntityManager.find()`** — so `repository.findById(otherTenantsId)` would return the row. That is
the single most common way to read data by id, and the failure is invisible: no error, no log,
just another tenant's data.

This was verified rather than assumed. `TenantIsolationIT.findByIdDoesNotLeakAcrossTenants`
exercises exactly that path and passes under `@TenantId`; it would fail under a filter.

**Consequence.** Platform admins get unrestricted reads through `isRoot()`, which Hibernate 7
honours by skipping the discriminator entirely — verified by
`TenantIsolationIT.rootScopeSeesAllTenants`. Root is therefore the one dangerous value in the
system, so it is a single exact-match sentinel granted in exactly one place, and the default for
any thread that has not established a scope is "sees nothing" rather than "sees everything".

---

## ADR-013 — Tenant scope must be established before the transaction opens

**Decision.** The tenant scope is set at the edge — the servlet filter for requests, and
explicitly by the dispatcher for background work — always before any transaction begins.

**Why it is not merely a convention.** Hibernate binds the tenant identifier **when the session
is opened**, not per statement. Changing `TenantContext` inside an open transaction has no effect
on that transaction: every statement keeps using the scope that was in force when the session
started.

This was discovered by a failing test rather than by reading documentation. The first version of
`TenantIsolationIT` was `@Transactional`, so one session opened while unscoped and every insert
silently used the "no tenant" sentinel, failing against the tenant foreign key. The test was the
thing that revealed the constraint.

**Consequence.** Two rules follow, and both are recorded as invariants in `CLAUDE.md`:

1. A service method must never try to change tenant scope for work already inside a transaction.
2. The dispatcher must set the scope for a work item *before* opening the transaction that
   processes it — not inside it. This directly shapes the dispatch loop built in a later phase.

---

## ADR-014 — Login failures are deliberately indistinguishable

**Decision.** An unknown email address and a wrong password return byte-identical responses. When
no user is found, a BCrypt comparison is still performed against a dummy hash before returning.

**Rejected.** Distinguishing the two, which is friendlier and is what most tutorials do. It also
turns the login endpoint into an account enumeration oracle: anyone can discover which addresses
are registered by reading the error code, and a legitimate user gains nothing from knowing which
half of their credentials was wrong.

The dummy comparison exists because response *timing* leaks the same information. Returning early
for an unknown address makes that request measurably faster than one for a known address with the
wrong password, so the identical body would be undermined by the clock.

**Consequence.** `AuthenticationIT.failuresDoNotRevealWhetherAnAccountExists` asserts the two
bodies are equal rather than merely both being 401 — a weaker assertion would pass even if a
future change started distinguishing them.

---

## ADR-015 — Audit writes run in their own transaction and never fail the caller

**Decision.** `AuditService` methods use `REQUIRES_NEW`, and a failed audit write is logged at
ERROR but swallowed rather than propagated.

**Why a separate transaction.** The trail must record *rejected* actions — failed logins, refused
sends, rate-limited submissions. Those happen in transactions that roll back. Joining the
caller's transaction would roll the evidence back with the action, producing a trail that records
only successes and silently omits everything worth investigating.

**Why failures are swallowed.** A broken audit write must not convert a successful business
operation into a failed one. The alternative trades a working system for a perfectly recorded
broken one.

**Consequence.** The trail is not transactionally atomic with the work it describes. Under
database failure it can in principle miss an entry — which is why the failure is logged loudly
rather than ignored.

---

## ADR-016 — Authorization defaults to deny

**Decision.** The security chain ends in `anyRequest().authenticated()`; public paths are an
explicit, short allow-list.

**Rejected.** Enumerating the protected paths. Under that arrangement every endpoint added later
is public until someone remembers to protect it, and nothing fails to remind them.

**Consequence.** An unknown path returns 401 rather than 404, which
`AuthenticationIT.unknownPathsRequireAuthentication` asserts precisely because it is the
observable proof that the default is deny. A side effect is that unauthenticated callers cannot
probe which endpoints exist.

---

## ADR-017 — Authorization is declared on services, not controllers

**Decision.** `@PreAuthorize` sits on the service class or method. Controllers map HTTP to calls
and contain no rules.

**Rejected.** Annotating controllers, which is more visible when reading a URL map. It only
protects the HTTP path: a scheduled job, an event listener, or another service calling the same
method reaches it with no check at all. Putting the rule on the service means it holds however
the method is reached.

**Consequence.** Reading a controller does not tell you who may call it — the annotation is one
level down. `PlatformAdminIT.RoleBoundary` therefore asserts the boundary explicitly for every
platform endpoint rather than leaving it to inspection.

---

## ADR-018 — Tenants are suspended, never deleted

**Decision.** There is no tenant delete endpoint. The lifecycle is ACTIVE ↔ SUSPENDED.

**Rejected.** A delete that cascades. The schema would happily do it, and that is the problem:
tenant data is referenced by an append-only audit trail and by delivery history. Cascading a
delete would erase the record of what the tenant did, which is precisely the evidence a deletion
is most likely to be investigated against.

**Consequence.** Suspension holds queued work rather than cancelling it. Suspension is usually
temporary — a billing problem, an investigation — and destroying a backlog that cannot be
reconstructed would turn a reversible action into an irreversible one. Reactivation resumes the
backlog.

---

## ADR-019 — Rate limits resolve most-specific-first across two dimensions

**Decision.** A policy is keyed by (tenant or platform) × (channel or all channels). Resolution
takes the most specific enabled match, computed from a single query returning all four candidates.

**Rejected.** A flat per-tenant limit. It forces every tenant to be configured explicitly before
any limit applies, so the default state of a new tenant is unlimited — the wrong direction for a
protection mechanism.

**Consequence.** A platform admin sets one floor for everyone and overrides per tenant or per
channel. The API reports a tenant's *effective* limits including inherited defaults, because
listing only its own rows would make an inherited limit look like no limit.

A configuration guard rejects `capacity < refillTokens`: the bucket could never hold one period's
worth of tokens, so the configured rate would be silently unreachable and the real limit would be
the capacity instead. That is a typo, not an intention.

---

## ADR-020 — Provider credentials are write-only

**Decision.** Credentials are encrypted with AES-256-GCM before storage and are never returned by
any endpoint. Responses carry `credentialsSet` and a masked `credentialsHint` (last four
characters) instead. The plaintext is produced in exactly one place — the dispatch path, when a
provider is actually called.

**Rejected.** A read-back endpoint for the owning tenant admin. It is more convenient for
recovery, and it turns every tenant-admin token into a credential exfiltration tool while putting
plaintext secrets into HTTP responses, proxy logs and browser history. Stripe, Twilio and AWS all
refuse to show a secret twice, for this reason.

**Consequence.** A tenant admin who loses a credential re-enters it rather than recovering it.
The audit trail records only that a credential was `replaced` — writing the value, or even its
length, into an append-only table that many people can read would defeat the encryption.

**Why GCM and not CBC.** GCM is authenticated: tampering with stored ciphertext produces a
decryption failure rather than silently different plaintext. Silently altered plaintext would be
sent to a provider as though genuine. A fresh random 12-byte IV is generated per encryption and
prepended to the ciphertext — IV reuse under GCM is catastrophic rather than merely weak, and
`CredentialCipherTest.encryptionIsNonDeterministic` asserts fifty encryptions of the same value
produce fifty distinct ciphertexts. Without that, identical credentials across tenants would
produce identical ciphertext, and anyone with table access could tell which tenants share a key
without decrypting anything.

---

## ADR-021 — Published template versions are immutable

**Decision.** A version is editable only while it is a `DRAFT`. Publishing archives whichever
version was published before, and a unique partial index permits at most one published version
per template. Editing a published version returns 409.

**Rejected.** Editing templates in place, which is what most template systems do. It means a
correction to a typo silently rewrites what already-queued notifications will say, and makes a
delivery record from six months ago unexplainable — the text that was sent no longer exists
anywhere.

**Consequence.** Fixing a typo requires creating and publishing a new version, which is more
ceremony than editing a field. In exchange, `notification.template_version_id` always resolves to
the exact content that was rendered, and the publish swap is atomic: the old version is archived
and flushed before the new one is published, because the unique index would otherwise reject the
second row.
