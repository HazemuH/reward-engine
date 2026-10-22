# M1 — Foundation & SaaS base

**Status: DONE**

Everything needed before any actual rule-evaluation or redemption logic could start.

| Ticket | Description | Status | Note |
|---|---|---|---|
| RE-001 | Gradle multi-module skeleton (`common` + `core`) | DONE | Now Java 25, Spring Boot 4.1, Gradle 9.8 with `gradle/libs.versions.toml` (upgraded in RE-095) |
| RE-002 | Multi-tenant domain model baseline — `Tenant`, `ClientApplication`, `Campaign`/`CampaignVersion`, `Counter`, `Redemption` + Flyway `V1__baseline.sql` | DONE | First version was marked DONE but **never booted**: `tenant_id` was `String` vs `uuid` in the DB and `@Lob` expected `oid`. Fixed and reworked in RE-094 |
| RE-003 | Shared kernel — `ApiResponse`, `TenantContext`, `TenantScopedEntity`, `BusinessRuleException` | DONE | `common/src/main/java/com/rewardengine/common/**` |
| RE-004 | Architecture reference (`docs/architecture/overview.md`) and decision log (`docs/decisions/open-decisions.md`) | DONE | — |
| RE-005 | AI workflow — `CLAUDE.md` + `.claude/skills/` (`reward-mode`, `compress-context`, `rule-authoring`, `money-safety-check`, `tenant-safety-check`) | DONE | — |
| RE-006 | `docker-compose.yml` for local Postgres + Redis | DONE | RabbitMQ deliberately left commented out — see M6 |
| RE-007 | Verified the app actually boots | DONE | Originally only checked compilation. Now proven by `RewardCoreApplicationTests` (full context on a migrated schema) and a manual `bootRun` against docker-compose |
| RE-008 | Vendored general Java/Spring Boot skills from [decebals/claude-code-java](https://github.com/decebals/claude-code-java) (MIT) as the base for backend work, wired into `reward-mode`'s routing table | DONE | 18 vendored, later pruned to 12 (six irrelevant here or duplicated by built-ins — see CLAUDE.md "AI workflow"). `.claude/skills/THIRD_PARTY_LICENSE-claude-code-java.txt` |
| RE-009 | Tests + CI: Testcontainers smoke test and `CounterRepositoryTest`; GitHub Actions running `./gradlew build` and a gitleaks history scan | DONE | `core/src/test/**`, `.github/workflows/ci.yml` |
| RE-094 | Model fixes found in review: tenant isolation via `@TenantId` + ScopedValue `TenantContext`; idempotency and `redemption.request_id` unique per tenant; version status on `CampaignVersion` (+ approval check constraint, one ACTIVE/one PENDING per campaign); `counter_def` split from `counter`; `Counter.used` writable only by the conditional UPDATE (which bumps `@Version`); kill-switch check inside that UPDATE; `MONEY` unit + `tenant.currency`; no default DB credentials | DONE | Each behavior covered by `CounterRepositoryTest` |
| RE-095 | Stack upgrade: Java 21 → 25, Spring Boot 3.4 → 4.1.1, Gradle 8.10 → 9.8.2; Prometheus registry added (endpoint was exposed but missing); unused Groovy dependency removed pending open-decisions #24 | DONE | — |

## What's explicitly not in M1

- No service/business logic — entities and the baseline schema only. `CounterRepository` has one method (`consumeAllOrNothing`) as a pattern reference for M3, not a finished feature.
- Nothing binds `TenantContext` from a real caller yet (API key / mTLS → `client_application` → tenant). Enforcement exists; resolution comes with the first `/internal` endpoint (M2).
- No admin auth (`gateway`) — see M9.
- No REST controllers at all yet.
