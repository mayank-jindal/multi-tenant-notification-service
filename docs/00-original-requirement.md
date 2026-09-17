# Original Requirement (verbatim)

> This file preserves the assignment brief exactly as it was provided, before any
> interpretation. All scoping decisions derived from it are recorded in
> `02-decision-log.md`.

## The Project

A multi-tenant notification service at scale supporting multiple channels (email, SMS, push, in-app),
tenant-defined templates with variable substitution, scheduled and immediate sends, per-tenant rate
limiting, retries with backoff on transient failures, and delivery tracking. The service should
dispatch high volumes of notifications across channels concurrently using bounded worker pools,
enforce per-tenant fairness and rate limits under load, and avoid duplicate deliveries on retry.
Delivery state transitions and retry attempts should be persisted with an audit trail.

Roles: platform admin (manage tenants and global limits) and tenant admin (manage templates,
channel configuration, and view delivery reports).

## Instructions

### Framing

The Product Requirement above is intentionally open-ended. You own the scoping decisions — which
entities to model, which APIs to expose, which edge cases to handle, and what to leave out. Your
interpretation of the requirement and the features you choose to build are themselves part of what
is being evaluated. You are encouraged to interpret the requirement generously and submit a
feature-rich solution — both the breadth and the depth of the features you build contribute to the
evaluation. Document every meaningful assumption in the README.md and explain your reasoning in the
recorded video.

### In Scope

The following are expected in your submission:

- REST APIs covering the core flows
- Persistence to a database of your choice
- Basic role-based access control for the roles defined in the requirement
- Input validation and error handling
- Unit & Integration Tests for the core flows

### Out of Scope

Do not spend time on:

- UI or frontend
- Deployment, containerization, or CI/CD
- Distributed systems or microservices
- Advanced authentication (OAuth, SSO, MFA)
- Production-grade observability, monitoring, or alerting

### What to Submit

- GitHub repository (mandatory)
- Your personal GitHub Project repository link
- Multiple commits are expected during the development phase
- Must include a README.md
- Must include the Agents.md / Claude.md file used during development
- Must include the skills used during development
- Must include all raw files used during development
