# Multi-Tenant Notification Service

> 🚧 **In development.** This README is filled in as the service is built. See
> `docs/` for the original requirement, the decisions taken, and the reasoning behind them.

A multi-tenant notification platform supporting email, SMS, push, and in-app channels with
tenant-defined templates, scheduled and immediate delivery, per-tenant rate limiting and fairness,
retries with exponential backoff, duplicate-delivery prevention, and a full delivery audit trail.

## Status

| Phase | Status |
|-------|--------|
| Repository initialised, decisions recorded | ✅ |
| Application scaffold | ⏳ |
| Domain model & migrations | ⏳ |
| Tenant isolation | ⏳ |
| Auth & RBAC | ⏳ |
| Admin APIs | ⏳ |
| Template engine | ⏳ |
| Send APIs | ⏳ |
| Dispatch engine | ⏳ |
| Rate limiting | ⏳ |
| Retries & dead-letter | ⏳ |
| Channel providers | ⏳ |
| Delivery tracking & reports | ⏳ |
| OpenAPI documentation | ⏳ |
| Tests | ⏳ |

## Stack

Java 25 · Spring Boot 4.1.1 · PostgreSQL 16 · Flyway · Spring Data JPA · Spring Security (JWT) ·
JUnit 5 / Testcontainers

## Documentation

| Document | Contents |
|----------|----------|
| [`docs/00-original-requirement.md`](docs/00-original-requirement.md) | The assignment brief, verbatim |
| [`docs/01-clarifying-questions-and-answers.md`](docs/01-clarifying-questions-and-answers.md) | Every scoping question and its answer |
| [`docs/02-decision-log.md`](docs/02-decision-log.md) | Architecture decisions and rejected alternatives |
| [`docs/03-development-process.md`](docs/03-development-process.md) | Tooling, skills, and working method |
| [`CLAUDE.md`](CLAUDE.md) | Agent guidance and architectural invariants |
