# Reward Engine

A rule engine for promo codes and referral rewards — campaigns, quota/budget counters, and redemption lifecycle, decoupled from payment. Originally scoped for the tenant's mobile app, built as a **multi-tenant SaaS base** so a new deployment is configuration, not a fork.

Read [`docs/architecture/overview.md`](docs/architecture/overview.md) before touching `core/`. It's short, and the six principles in it are the difference between a correct change and a dangerous one.

## Status

Early scaffold. `core` (the engine) has its domain model, baseline schema, and module structure; the actual rule-evaluation, reserve/confirm/cancel services and the admin API are not implemented yet. `gateway` (admin LDAP/JWT auth) is **not built** — deferred on purpose, see [`docs/decisions/open-decisions.md`](docs/decisions/open-decisions.md) #2.

## Modules

```
common/   shared kernel — ApiResponse, TenantContext, TenantScopedEntity, BusinessRuleException
core/     reward-core — the engine: campaign, counter, redemption, (referral/catalog to come)
```

`gateway/` will hold admin login (LDAP + local), JWT, RBAC once that phase starts.

## Tech stack

Java 21, Spring Boot 3.4, Gradle (multi-module), PostgreSQL 16 + Flyway, Redis 7, Groovy (sandboxed rule scripts), ShedLock. See `build.gradle` / `core/build.gradle` for exact versions.

## Running locally

Gradle 8.10.2 (this wrapper) can't run on a JDK newer than ~23 — if your default `java` is newer (check `java -version`), point `JAVA_HOME` at a JDK ≤ 23 just to run Gradle; the build's own toolchain (`javaVersion = 21` in root `build.gradle`) still compiles and runs the app on 21 regardless, auto-provisioning it via the foojay resolver if it's not installed locally.

```bash
export JAVA_HOME=/path/to/jdk-17-or-similar   # only if your default JDK is newer than Gradle supports
docker compose up -d postgres redis            # Postgres on 5433, Redis on 6380
./gradlew :core:flywayMigrate
./gradlew :core:bootRun --args='--spring.profiles.active=local'
```

`core` listens on `:8081`. Health/info/Prometheus are the only exposed actuator endpoints (`management.endpoints.web.exposure.include` in `core/src/main/resources/application.yml`).

## Progress

Ticket-style milestones (no timeline, just status per ticket) live in [`docs/milestones/`](docs/milestones/README.md) — start there for what's actually done vs. still open.

## Tenancy

Every domain table carries `tenant_id` from the baseline migration onward (`core/src/main/resources/db/migration/V1__baseline.sql`). `TenantContext` (in `common`) must be resolved from the authenticated caller before any tenant-scoped query runs — see `.claude/skills/tenant-safety-check` for the checklist when adding new tenant-owned data.

## Working on this with AI assistance

This repo has a Claude Code workflow set up under `.claude/skills/`, inspired by [pstack](https://github.com/cursor/plugins/tree/main/pstack), scoped down to what this codebase actually needs and with an explicit token-budget discipline (`.claude/skills/compress-context`) so following the workflow doesn't mean burning context on every small change. Start with `/reward-mode` for anything non-trivial; see [`CLAUDE.md`](CLAUDE.md) for the full set of conventions and the skill list.

## Reference material

