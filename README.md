# Reward Engine

A rule engine for promo codes and referral rewards — campaigns, quota/budget counters, and redemption lifecycle, decoupled from payment. Personal project: a **multi-tenant SaaS base** generalized from real-world banking reward-system requirements, so a new deployment is configuration, not a fork — meant to be the starting scaffold for future client engagements rather than rebuilt each time.

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

Java 25, Spring Boot 4.1, Gradle 9 (multi-module, versions in `gradle/libs.versions.toml`), PostgreSQL 16 + Flyway, Redis 7, ShedLock. Rule scripts will be Groovy, hardened against RCE (`docs/milestones/M4-script-sandbox.md`), added back as a dependency when M4 starts.

## Running locally

Needs Docker and any JDK that can run Gradle 9; the build's toolchain provisions Java 25 itself (via the foojay resolver) if it isn't installed.

```bash
docker compose up -d postgres redis   # Postgres on 5433, Redis on 6380
./gradlew :core:bootRun               # runs with the `local` profile; Flyway migrates on startup
```

`core` listens on `:8081`. Health/info/Prometheus are the only exposed actuator endpoints. Outside the `local` profile the app refuses to start unless `REWARD_DB_URL`, `REWARD_DB_USERNAME` and `REWARD_DB_PASSWORD` are set — there are no default credentials.

## Testing

```bash
./gradlew build   # compile + all tests; needs Docker (Testcontainers starts a real Postgres)
```

`RewardCoreApplicationTests` boots the whole context against the Flyway-migrated schema with `ddl-auto=validate`, so entity/migration drift fails the build. `CounterRepositoryTest` covers the money path: cap enforcement, kill switch, tenant isolation, stale-entity overwrite and a concurrent race on one counter. CI (`.github/workflows/ci.yml`) runs the same build plus a gitleaks scan of the full history.

## Publishing to GitHub

Remote: [HazemuH/reward-engine](https://github.com/HazemuH/reward-engine). Two branches, two purposes:

- **`main`** on GitHub — a standalone landing README describing the project. Not generated from this repo; edit it directly on GitHub (or regenerate it by hand) if it needs updating.
- **`feat/master`** on GitHub — the actual code, regenerated from local `feat/master` by `./scripts/publish-to-origin.sh`.

Local `feat/master` is the working branch and the full repo, including `.claude/` and `CLAUDE.md`; those stay local/internal by request — only the project files go to GitHub. Work branches come off `feat/master` and merge back into it. The publish script strips those two paths out of a filtered history and force-pushes it to `origin/feat/master`. Run it from a clean `feat/master`; never push directly to `origin`.

## Progress

Ticket-style milestones (no timeline, just status per ticket) live in [`docs/milestones/`](docs/milestones/README.md) — start there for what's actually done vs. still open.

## Tenancy

Every tenant-owned table carries `tenant_id` from the baseline migration onward (`core/src/main/resources/db/migration/V1__baseline.sql`). Entities extending `TenantScopedEntity` use Hibernate's `@TenantId`: the tenant is stamped on insert and added to every JPA query automatically, from whatever `TenantContext.callAs(tenantId, ...)` bound for the current unit of work. With nothing bound, tenant-scoped reads return nothing and writes fail — never another tenant's data. `tenant` and `client_application` are deliberately global (the latter is how a request's tenant gets resolved).

## Working on this with AI assistance

This repo has a Claude Code workflow set up under `.claude/skills/`, inspired by [pstack](https://github.com/cursor/plugins/tree/main/pstack): a `reward-mode` entry point routing to 5 domain-specific playbooks (rule authoring, money-safety, tenant-safety) plus 12 general Java/Spring Boot skills vendored from [decebals/claude-code-java](https://github.com/decebals/claude-code-java) (MIT) as the base for backend work — code review, architecture, JPA/Spring patterns, security, and more. An explicit token-budget skill (`.claude/skills/compress-context`) keeps following all of this from being expensive. Start with `/reward-mode` for anything non-trivial; see [`CLAUDE.md`](CLAUDE.md) for the full set of conventions and the skill list.
