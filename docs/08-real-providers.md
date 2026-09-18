# Real provider delivery

Which channels actually deliver, which are simulated, and how to switch.

## Current state

| Channel | Delivery | What that means |
|---|---|---|
| **EMAIL** | **Real**, when configured | Sends over SMTP. Messages genuinely arrive. Simulated by default. |
| **IN_APP** | **Real, always** | There is no third party: delivery means the message is stored and readable through the inbox API. |
| **SMS** | Simulated | No vendor integration. The pipeline is real; nothing leaves the machine. |
| **PUSH** | Simulated | Same. A real integration also needs a mobile or web client to receive. |

Everything *around* the provider is real on all four channels — validation, rendering, queuing,
fairness, rate limiting, retries, state tracking, audit. The simulator occupies exactly the
position a vendor would, behind the `ChannelProvider` interface.

## Turning on real email

The `ChannelProvider` SPI is a genuine seam: enabling real delivery publishes one bean, and
nothing else in the application changes.

### With Gmail

Gmail requires an **app password**, not your account password, and two-factor authentication must
be on.

1. Enable 2FA at <https://myaccount.google.com/security>
2. Create an app password at <https://myaccount.google.com/apppasswords>
3. Set:

```
EMAIL_PROVIDER=SMTP
SMTP_HOST=smtp.gmail.com
SMTP_PORT=587
SMTP_USERNAME=you@gmail.com
SMTP_PASSWORD=the-16-character-app-password
EMAIL_FROM_NAME=Notifly
```

On Windows PowerShell for a local run:

```powershell
$env:EMAIL_PROVIDER="SMTP"
$env:SMTP_USERNAME="you@gmail.com"
$env:SMTP_PASSWORD="your-app-password"
mvn spring-boot:run
```

Startup then logs `EMAIL channel will use real SMTP delivery, sending as ...`.

### Without giving out a real mailbox

[Mailtrap](https://mailtrap.io) gives a free inbox that captures everything sent to it without
delivering onward — better for a demo, since you can send to any address without mailing a
stranger.

```
EMAIL_PROVIDER=SMTP
SMTP_HOST=sandbox.smtp.mailtrap.io
SMTP_PORT=587
SMTP_USERNAME=your-mailtrap-username
SMTP_PASSWORD=your-mailtrap-password
```

### A caveat worth knowing

Most relays refuse to send with a From address other than the authenticated account. The tenant's
configured sender identity is therefore used as the **display name** and as **Reply-To**, while
the envelope sender stays the authenticated account. Sending as an address the relay has not
verified would either be rejected or land in spam, and silently pretending otherwise would be
worse than saying so.

A production deployment would use a relay supporting per-tenant verified domains — SES, SendGrid,
Postmark. That is a configuration change here, not a code change.

### How failures are classified

The retry policy depends entirely on the transient/permanent judgement, so it is read from the
SMTP reply rather than guessed:

| Failure | Classified | Why |
|---|---|---|
| 5xx, "no such user", "recipient rejected" | **Permanent** | The address will never work; retrying wastes the budget |
| Authentication rejected | **Permanent** | Nothing will change until someone fixes configuration |
| Malformed message or address | **Permanent** | Identical failure every time |
| 4xx, timeout, connection refused, greylisting | **Transient** | Worth another attempt after a backoff |
| Anything unrecognised | **Transient** | Risks a wasted retry; the alternative risks discarding a message that would have gone through |

## The in-app channel

Real without any configuration, because there is nothing external to configure. A message sent on
IN_APP is stored and then readable:

```
GET  /api/v1/inbox?recipientRef=cust-7
GET  /api/v1/inbox/unread-count?recipientRef=cust-7
POST /api/v1/inbox/{notificationId}/read
POST /api/v1/inbox/read-all?recipientRef=cust-7
```

It is also the only channel that reaches `DELIVERED` rather than stopping at `SENT` — because it
is the only one where we genuinely know the message arrived.

**Who calls these.** Recipients are the tenant's customers and have no accounts here, so a
tenant's own backend reads the inbox on their behalf, authenticated as a tenant admin and naming
the recipient. Giving every recipient an account would mean this service owning the tenant's user
directory, which is a much larger and quite different product.

The recipient reference is therefore a parameter rather than an identity, and the tenant scope is
what keeps two tenants' inboxes apart — `InboxIT.inboxIsTenantScoped` sends to the reference
`shared-customer` in two different tenants and asserts each sees only its own.

## Adding SMS or push

Both follow the same path as SMTP:

1. Implement `ChannelProvider` — three methods.
2. Publish it as a bean, conditional on configuration.
3. Return a `ProviderResult` classifying failure as transient or permanent. **Do not throw** for a
   delivery failure: only the provider knows which kind it is, and an exception discards that.

`ProviderRegistry` prefers any discovered bean over the simulator, so that is the whole
integration.

For SMS, Twilio's trial only delivers to verified numbers, which limits a demo to your own phone.
Push additionally requires a client application to receive the message, which is a larger piece of
work than the integration itself.
