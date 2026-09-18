# Deployment

> **Not part of the assignment.** The brief puts deployment out of scope. None of this existed
> when the assignment work was completed; it was added afterwards, on a separate branch, so the
> project could be hosted as a portfolio piece. That branch has since been merged, so everything
> below tracks `main`.

## What the branch adds

| File | Purpose |
|---|---|
| `Dockerfile` | Multi-stage build: JDK to compile, JRE to run, non-root user |
| `render.yaml` | Render blueprint — web service and database defined together |
| `src/main/resources/application-prod.yml` | Production profile, sized for a small instance |
| `src/main/resources/static/` | The dashboard (three files, no build step) |

## Deploying to Render

1. Go to **render.com → New → Blueprint**, connect the repository, select the `main` branch.
2. Render reads `render.yaml` and creates both the web service and the PostgreSQL database.
3. Set the two secrets it will prompt for: `ADMIN_EMAIL` and `ADMIN_PASSWORD`.
   `JWT_SECRET` and `ENCRYPTION_KEY` are generated automatically.
4. First build takes roughly five minutes. Flyway migrates the database on first boot.

To send real email from the deployed instance, add the SMTP variables described in
`08-real-providers.md`. Without them the EMAIL channel uses the simulator, which is a perfectly
reasonable default for a public demo.

The dashboard is then at the service URL, and Swagger at `/swagger-ui/index.html`.

## Verifying before you deploy

The image and the production profile were exercised locally before ever reaching Render, because
a failed build there costs five minutes per attempt and tells you very little:

```bash
docker build -t notifly:test .

# The prod profile must refuse the development keys. A refusal here is the correct outcome.
docker run --rm --network multi-tenant-notification-service_default   -e SPRING_PROFILES_ACTIVE=prod -e DB_SSLMODE=disable   -e DB_HOST=notifly-postgres -e DB_PORT=5432 -e DB_NAME=notifications   -e DB_USERNAME=notify -e DB_PASSWORD=notify   notifly:test

# With real secrets it should start and serve.
docker run --rm -p 8090:8080 --network multi-tenant-notification-service_default   -e SPRING_PROFILES_ACTIVE=prod -e DB_SSLMODE=disable   -e DB_HOST=notifly-postgres -e DB_PORT=5432 -e DB_NAME=notifications   -e DB_USERNAME=notify -e DB_PASSWORD=notify   -e JWT_SECRET=<32+ chars> -e ENCRYPTION_KEY=<32+ chars>   -e ADMIN_EMAIL=you@example.com -e ADMIN_PASSWORD=<password>   notifly:test
```

Two problems were found this way and fixed rather than discovered in a deploy log:

- **The blueprint fed Render's `connectionString` straight to JDBC.** Render supplies
  `postgres://user:pass@host/db`; the driver only accepts `jdbc:postgresql://host:port/db`. The
  host, port and database are now passed separately and the URL is assembled in the profile.
- **`sslmode=require` was hardcoded**, which is right for a managed database reached over the
  internet but made the production profile impossible to exercise anywhere without TLS. A profile
  that cannot be tested before deployment is a profile nobody has tested. It is now
  `${DB_SSLMODE:require}` — secure by default, overridable for a local run.

Measured in the container: **~310MB resident**, comfortably inside Render's 512MB free instance.

## A bootstrap detail worth knowing

The first platform administrator is created **only when none exists**. It is not an upsert, so a
redeploy cannot silently reset the credentials of an account whose password someone has since
changed.

The consequence at deploy time: pointed at a **fresh** database, `ADMIN_EMAIL` and
`ADMIN_PASSWORD` create your account. Pointed at a database that already has an administrator,
both are ignored and the existing account remains — which is confusing if you expected otherwise
and are staring at a login failure. Render's blueprint creates a new database, so the first deploy
behaves as expected.

## Things that behave differently once deployed

**The free instance sleeps after 15 minutes of inactivity.** The first request afterwards takes
around 30 seconds while it wakes. More importantly, **scheduled sends and retries do not fire
while it is asleep** — they all fire at once on waking. A paid instance or Railway avoids this.
It is worth knowing before someone clicks a portfolio link at 2am and sees a cold start.

**Rate limit buckets are per instance.** Scaling to two instances would double the effective
limit, because the buckets are in memory. Fixing that properly means Redis or a database-backed
counter.

**The free database is capped** at 1GB and expires after 90 days on Render's free tier. Fine for a
portfolio; worth a calendar reminder.

## Deploying elsewhere

The `Dockerfile` is the only thing most platforms need. Set these environment variables:

```
SPRING_PROFILES_ACTIVE=prod
DB_URL=jdbc:postgresql://host:5432/notifications
DB_USERNAME=...
DB_PASSWORD=...
JWT_SECRET=<32+ random characters>
ENCRYPTION_KEY=<32+ random characters>
ADMIN_EMAIL=...
ADMIN_PASSWORD=...
```

The service **refuses to start** under the `prod` profile if `JWT_SECRET` or `ENCRYPTION_KEY`
still hold their development defaults. That is deliberate, and it is the one failure you want at
startup rather than in production.

**A note on `DB_URL`:** most managed providers hand out a `postgres://user:pass@host/db`
connection string. The JDBC driver will not accept that form — it needs
`jdbc:postgresql://host:5432/db` with the credentials supplied separately.

## The dashboard

Three static files served by Spring: `index.html`, `app.js`, `styles.css`. No framework, no npm,
no build step, no separate deployment.

It calls exactly the same REST API as any other client — there are no private endpoints behind it,
and the security rules that apply to a curl request apply to it identically. Its static files are
allow-listed individually in `SecurityConfig` rather than by a wildcard, because a pattern like
`/**` would quietly open every unmatched path and undo the default-deny posture without anything
appearing to break.
