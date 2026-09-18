# Walkthrough — the whole system in plain language

A guide to what this service does, why it is built the way it is, and how to work on it. Written
to be read start to finish without prior knowledge of the codebase.

The [README](../README.md) is the reference; this is the explanation.

---

## 1. The problem, in one paragraph

Imagine you run a platform that many companies use — call them **tenants**. Acme sells shoes,
Globex sells insurance. Both need to send messages to *their* customers: order confirmations,
password resets, delivery alerts. They want those messages to go out by email, SMS, mobile push,
or as a notification inside their app. They want to write the message once as a **template** and
fill in the details per customer. Sometimes they want it sent right now; sometimes at 9am
tomorrow. When a message fails because an email provider had a hiccup, they want it tried again —
but not forever, and not if the address was simply invalid. And crucially: when Acme sends a
million messages, Globex's single password reset must not be stuck behind them.

**This service does all of that.** It is the plumbing between "Acme's system says send this" and
"the message arrives".

---

## 2. The core idea

Everything rests on one decision: **the database table `notifications` *is* the work queue.**

Most systems like this bolt on a message broker (Kafka, RabbitMQ). This one does not, because the
brief put distributed systems out of scope. So when someone submits a send:

1. The service writes a row per message into `notifications` with status `QUEUED`.
2. A background loop wakes up every half second, grabs a batch of queued rows, and sends them.
3. Results are written back to the same rows.

That choice buys three things worth saying out loud:

- **Nothing is ever accepted and then lost.** The API response and the queued work are written in
  the same database transaction. Either both happen or neither does.
- **Everything is queryable.** "Show me every failed SMS for Acme last Tuesday" is a SQL query,
  not a hunt through broker logs.
- **It is simple enough to reason about.** No second system to run, monitor, or explain.

The cost: throughput is limited by PostgreSQL rather than by a broker. At assignment scale that is
irrelevant; at a genuinely huge scale you would swap the queue out. That trade is stated honestly
in the README rather than glossed over.

---

## 3. What actually happens when you send a notification

Follow one message end to end. Acme wants to tell Sam that order A-1001 shipped.

### Step 1 — Acme logs in

```
POST /api/v1/auth/login   { "email": "ops@acme.test", "password": "..." }
→ { "accessToken": "eyJhbGci..." }
```

That token is a **JWT** — a signed string containing "you are user X, of tenant Acme". It is
signed, so it cannot be edited. Every later request carries it.

> **Why this matters:** the tenant comes from the *token*, never from anything the caller types.
> If tenancy came from, say, an `X-Tenant-ID` header, any Acme admin could type Globex's id and
> read their data. Forging the token instead would require the signing key.

### Step 2 — Acme submits the send

```
POST /api/v1/notifications
{
  "templateCode": "order-shipped",
  "recipients": [{ "ref": "cust-7", "addresses": { "EMAIL": "sam@example.com" } }],
  "variables": { "customerName": "Sam", "orderId": "A-1001" }
}
```

Before writing anything, the service checks — **in this order**:

| Check | If it fails |
|---|---|
| Is Acme suspended? | `403` |
| Does template `order-shipped` exist and have a published version? | `400` |
| Were all required variables supplied, and no unknown ones? | `400` |
| Is the EMAIL channel configured and enabled for Acme? | `400` |
| Is `sam@example.com` on Acme's suppression list? | skipped |
| Is Acme within its rate limit? | `429` |

**Only if everything passes** does it write rows. This ordering is deliberate: a caller either
gets a clean error with *nothing* queued, or a clean acceptance. There is never a half-sent batch
to clean up.

It also **renders the message now**, not later — substituting `Sam` and `A-1001` into the template
and storing the finished text on the row.

> **Why render now?** If you rendered at send time instead, and Acme edited the template in the
> meantime, queued messages would silently change wording. Worse, a delivery record from six
> months ago could no longer be explained, because the text it used no longer exists anywhere.

The response is `202 Accepted` — "I have this, it will go out", not "it has gone out".

### Step 3 — The dispatcher picks it up

A background loop runs every 500ms. For each channel it:

1. Asks the database: *which tenants have work waiting on EMAIL right now?*
2. Asks the **fairness selector**: *given 20 free slots and these tenants, who gets how many?*
3. **Claims** each tenant's share — marks those rows as `SENDING` and stamps a **lease** on them.
4. Hands each claimed row to a worker thread.

### Step 4 — A worker sends it

The worker does three things, and the gaps between them matter:

1. **Writes an attempt record, and commits it** — "attempt 1 starting".
2. **Calls the provider** — outside any database transaction.
3. **Writes the result** — success, or a failure with its reason.

> **Why write the attempt first?** If the machine dies mid-send, the evidence that an attempt
> happened survives. Write it afterwards and the system would believe the message was never
> tried — and the retry would be a genuine duplicate that nothing recorded.

> **Why call the provider outside a transaction?** A database connection held open across a slow
> network call to a third party is how one sluggish email vendor freezes the whole application.

### Step 5 — The outcome

- **Success** → status `SENT`. (In-app messages go straight to `DELIVERED`, because writing the
  row *is* delivery — there is no external provider to hear back from.)
- **Transient failure** (timeout, provider 5xx) → status `RETRY_SCHEDULED`, try again after a
  delay that grows each time.
- **Permanent failure** (address is not a valid email) → status `FAILED` immediately. No retries.

> **Why distinguish them?** Retrying an invalid address five times wastes capacity that another
> tenant's real message could have used, and it will never succeed.

---

## 4. The concepts, defined

| Concept | What it is |
|---|---|
| **Tenant** | A customer company. Everything is owned by exactly one, except platform-level settings. |
| **Platform admin** | Runs the service. Creates tenants, sets global rate limits. Belongs to no tenant. |
| **Tenant admin** | Runs one tenant. Manages that tenant's templates, channels and reports. |
| **Channel** | How a message travels: EMAIL, SMS, PUSH, IN_APP. |
| **Channel config** | A tenant's setup for one channel — enabled or not, sender identity, provider credentials. |
| **Template** | A named message with `{{placeholders}}`. Holds no content itself. |
| **Template version** | The actual content. Immutable once published. Exactly one version is live at a time. |
| **Notification request** | One submission — "send this to these 500 people". |
| **Notification** | One message to one person on one channel. 500 recipients on 2 channels = 1 request, 1000 notifications. |
| **Delivery attempt** | One try at sending one notification. A notification that failed twice has three attempt records. |
| **Lease** | A temporary claim a worker stamps on a notification: "I am sending this one." |
| **Suppression** | An address that must never be written to — a hard bounce or an opt-out. |
| **Rate limit policy** | A speed limit, e.g. "Acme may send 50 emails a second, bursting to 100". |
| **Audit event** | An immutable record of something that happened. Never edited, never deleted. |

---

## 5. The four clever bits

These are the parts worth talking about in a video, because they are where judgement shows.

### Fairness — why one big tenant cannot block everyone

**The problem.** Acme queues a million notifications. Globex queues one password reset. If the
dispatcher simply took the oldest work first, Globex waits hours behind Acme.

**The solution.** Each cycle, the dispatcher divides its capacity between *tenants*, not across
one flat queue:

- Every tenant with waiting work gets **at least one slot**, guaranteed.
- What remains is shared in proportion to each tenant's **dispatch weight** (a premium tenant can
  be given a bigger share).
- If a tenant cannot use its allocation, the leftover is handed to others rather than wasted.
- A **rotating offset** changes who is considered first each cycle, so nobody is starved simply by
  being last in a list.

**The proof.** `ConcurrencyFairnessIT` gives one tenant 90 notifications and two tenants 6 each,
then requires the small tenants to finish *while the big one still has a backlog*. A
first-come-first-served dispatcher fails that test.

### Bounded pools — why the service cannot be overwhelmed

Each channel gets a fixed number of worker threads and a fixed-size waiting queue. When both are
full, the dispatcher **stops claiming work**, and it stays in the database.

That is the whole point. An unbounded queue would happily accept a million items, report itself
healthy, and then die of an out-of-memory error — losing everything held in memory. A bounded one
pushes back, and the work is safe on disk.

> **Worth knowing:** this deliberately does *not* use Java's virtual threads, even though they are
> the modern default. Virtual threads are unbounded by design — you spawn one per task. That is
> the opposite of what is wanted here, where the whole purpose is to *limit* how much simultaneous
> load reaches a rate-limited provider.

### Not sending things twice

Two independent protections, because each covers something the other does not.

**Layer 1 — the client retried.** Acme's server sends a request, the network times out, Acme never
learns it succeeded, so Acme sends it again. If Acme includes an `Idempotency-Key` header, the
second request returns the *original* result instead of sending a second time. The key is claimed
*before* the send runs, not after — otherwise two simultaneous copies could both slip through
before either recorded the key.

**Layer 2 — a worker crashed.** A worker claims a notification and dies. Its lease expires, a
reaper returns the row to the queue, another worker picks it up. But what if the first worker was
not dead, merely slow, and wakes up to send it after all? Each worker carries the **lease token**
it was given and refuses to send unless the row still holds that exact token. The stale worker
stands down.

> **The honest limit.** Delivery is **at-least-once**, not exactly-once. If a provider accepts a
> message and the machine dies before recording that, the retry sends a duplicate. Closing that
> gap would need a shared transaction with the email provider, which does not exist. What the
> design does guarantee is that the gap is small, bounded, and *recorded* — the ambiguous attempt
> is marked `ABANDONED` rather than quietly deleted.
>
> Saying this plainly is better than claiming exactly-once. Anyone who knows the problem will ask.

### Tenant isolation — why Acme cannot read Globex's data

Every tenant-owned table has a `tenant_id` column. Hibernate is told the current tenant at the
start of every database session and automatically adds `WHERE tenant_id = ?` to queries.

The subtle part is *which* Hibernate mechanism. There are two, and one has a hole:

| Mechanism | `findAll()` | `findById(someOtherTenantsId)` |
|---|---|---|
| `@Filter` | filtered ✅ | **returns the row** ❌ |
| `@TenantId` | filtered ✅ | returns nothing ✅ |

`@Filter` is silently ignored by lookups by id — no error, no warning, just another tenant's data.
Since fetching by id is the most common operation in any application, that hole would be hit
almost immediately. This project uses `@TenantId`, and
`TenantIsolationIT.findByIdDoesNotLeakAcrossTenants` tests that exact path with a real id from
another tenant.

---

## 6. Where things live

```
src/main/java/com/notifly/notification/
├── auth/          login endpoint
├── security/      JWT handling, password hashing, who-can-do-what rules
├── tenant/        tenants, and the platform-admin endpoints that manage them
├── user/          user accounts
├── channel/       per-tenant channel setup + credential encryption
├── template/      templates, versions, and the {{placeholder}} renderer
├── delivery/      the send API, notification records, delivery queries
├── dispatch/      ⭐ the engine: claiming, fairness, worker pools, retries
├── provider/      the pluggable "actually send it" adapters
├── ratelimit/     token buckets and limit policies
├── idempotency/   duplicate submission prevention
├── suppression/   do-not-send list
├── audit/         the append-only history
└── common/        shared plumbing: tenancy, errors, config, crypto
```

If you read only five files, read these:

1. **`delivery/SendService.java`** — everything that happens when someone sends.
2. **`dispatch/DispatchCoordinator.java`** — the loop that drives delivery.
3. **`dispatch/FairnessSelector.java`** — the fairness algorithm, pure logic, easy to follow.
4. **`dispatch/AttemptRecorder.java`** — the careful transaction sequencing around a send.
5. **`delivery/NotificationStatus.java`** — the state machine, with the legal transitions listed.

Database structure lives in `src/main/resources/db/migration/` as numbered SQL files, applied
automatically at startup.

---

## 7. Working on it

### Run it

```bash
docker compose up -d      # PostgreSQL
mvn spring-boot:run       # the service
```

Then open http://localhost:8080/swagger-ui/index.html.

Log in as `admin@notifly.io` / `admin123`, click **Authorize** at the top right, paste the
`accessToken` from the response, and every endpoint becomes clickable.

### Run the tests

```bash
mvn clean verify          # 123 tests, needs Docker running
mvn test                  # just the fast unit tests, no Docker
```

### See retries actually happen

Edit `src/main/resources/application.yml`:

```yaml
notifly:
  dispatch:
    simulator:
      EMAIL: { transient-failure-rate: 0.7 }   # 70% of sends fail
```

Restart, send something, then watch `GET /api/v1/notifications/{id}/attempts`. You will see
attempt 1, 2, 3… with the gaps between them growing. This is the single best thing to demo.

### Add a new channel (e.g. WhatsApp)

1. Add `WHATSAPP` to `common/model/Channel.java`.
2. Write a migration adding it to the `CHECK` constraints on the channel columns.
3. Add worker-pool and simulator settings for it in `application.yml`.
4. Optionally write a real `ChannelProvider` — otherwise it gets a simulator automatically.

### Plug in a real email provider

Implement `provider/ChannelProvider.java` — three methods — and annotate it `@Component`. The
registry picks up any such bean and prefers it over the simulator. Nothing else changes.

The important detail: **do not throw on a delivery failure.** Return a `ProviderResult` saying
whether the failure is transient or permanent. Only the provider knows which, and the whole retry
policy depends on that judgement.

### When something goes wrong

| Symptom | Where to look |
|---|---|
| Nothing is being sent | Is `notifly.dispatch.enabled` true? Is the tenant suspended? Is the channel enabled? |
| Everything returns 401 | Token expired (1 hour by default) — log in again. |
| Everything returns 403 | Wrong role. Platform admins cannot manage templates; tenant admins cannot create tenants. |
| Maven cannot download anything | TLS interception — see `docs/04-local-environment-notes.md`. |
| A notification is stuck in `SENDING` | Its worker died. Wait for the lease to expire (60s) and the reaper to requeue it. |

Full history of any notification:

```sql
SELECT event_type, from_state, to_state, occurred_at, details
FROM audit_events
WHERE entity_id = '<notification-id>'
ORDER BY occurred_at;
```

---

## 8. Explaining it in the video

A structure that works, and the honest answer to the questions most likely to follow.

**Open with the problem, not the tech.** "Many companies, each sending messages to their own
customers, on several channels, without interfering with each other." Then show the Swagger page.

**Demo in this order:** create a tenant → configure a channel → create and publish a template →
send → show the delivery state → show the attempt history. That is the whole system in five
minutes.

**Then talk about the parts requiring judgement** — fairness, bounded pools, duplicate prevention,
tenant isolation. For each, say *what would have gone wrong with the obvious approach*. That is
what separates an explanation from a tour.

### Questions you should expect

> **"Why no Kafka or RabbitMQ?"**
> Distributed systems were out of scope. A database-backed queue also means the API response and
> the queued work commit together, so nothing can be accepted and then lost. Throughput is capped
> by PostgreSQL — at genuinely large scale you would swap the queue out, and the dispatcher is the
> only part that would change.

> **"How do you guarantee a message is not sent twice?"**
> I don't guarantee it, and nothing can across a third-party boundary. Delivery is at-least-once.
> Duplicates are prevented wherever the service has authority — idempotency keys for client
> retries, lease ownership for worker crashes — and the remaining window is recorded rather than
> hidden.

> **"What happens if the service crashes mid-send?"**
> The notification stays leased. The lease expires, a reaper returns it to the queue, and another
> worker picks it up. Its half-finished attempt is marked `ABANDONED`, because the provider may or
> may not have received it, and the audit trail should say so rather than guess.

> **"Why not virtual threads?"**
> They are unbounded by design. The pool exists specifically to limit how much simultaneous load
> reaches a rate-limited provider, so an unbounded model would remove the property I wanted.

> **"How do you know fairness actually works?"**
> There's a test. One tenant with 90 notifications against two with 6 each, and the small ones
> must finish while the big one still has a backlog. A naive FIFO dispatcher fails it.

> **"What would you do differently with more time?"**
> Provider webhooks, so `DELIVERED` means confirmed rather than just handed over. Shared rate
> limit counters, so it scales past one instance. And `LISTEN`/`NOTIFY` instead of polling, to
> remove the half-second claim latency.

### Two things to be upfront about

Being straightforward about limitations reads as confidence, and these will be noticed anyway.

- **Rate limits are per instance.** Correct today because there is one instance; two instances
  would double the effective limit.
- **Only in-app reaches `DELIVERED`.** Every other channel stops at `SENT`, because confirming
  real delivery needs provider webhooks, which were out of scope.
