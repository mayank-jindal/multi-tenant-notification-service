# Deployment

> **Not part of the assignment.** The brief puts deployment out of scope, and `main` contains no
> deployment artifacts. Everything here lives on the `deploy` branch so the project can be hosted
> as a portfolio piece without changing what was submitted.

## What the branch adds

| File | Purpose |
|---|---|
| `Dockerfile` | Multi-stage build: JDK to compile, JRE to run, non-root user |
| `render.yaml` | Render blueprint — web service and database defined together |
| `src/main/resources/application-prod.yml` | Production profile, sized for a small instance |
| `src/main/resources/static/` | The dashboard (three files, no build step) |

## Deploying to Render

1. Push the branch: `git push origin deploy`
2. Go to **render.com → New → Blueprint**, connect the repository, select the `deploy` branch.
3. Render reads `render.yaml` and creates both the web service and the PostgreSQL database.
4. Set the two secrets it will prompt for: `ADMIN_EMAIL` and `ADMIN_PASSWORD`.
   `JWT_SECRET` and `ENCRYPTION_KEY` are generated automatically.
5. First build takes roughly five minutes. Flyway migrates the database on first boot.

The dashboard is then at the service URL, and Swagger at `/swagger-ui/index.html`.

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
