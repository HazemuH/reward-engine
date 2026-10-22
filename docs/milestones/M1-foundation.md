# M1 — Foundation & SaaS base

**Status: DONE**

Everything needed before any actual rule-evaluation or redemption logic could start.

| Ticket | Description | Status | Note |
|---|---|---|---|
| RE-001 | Gradle multi-module skeleton (`common` + `core`, Java 21, Spring Boot 3.4) | DONE | `settings.gradle`, `build.gradle`, `core/build.gradle`, `common/build.gradle` |
| RE-002 | Multi-tenant domain model baseline — `Tenant`, `ClientApplication`, `Campaign`/`CampaignVersion`, `Counter`, `Redemption` + Flyway `V1__baseline.sql` | DONE | `core/src/main/java/com/rewardengine/core/**`, `core/src/main/resources/db/migration/V1__baseline.sql` |
| RE-003 | Shared kernel — `ApiResponse`, `TenantContext`, `TenantScopedEntity`, `BusinessRuleException` | DONE | `common/src/main/java/com/rewardengine/common/**` |
| RE-004 | Architecture reference (`docs/architecture/overview.md`) and decision log (`docs/decisions/open-decisions.md`) | DONE | — |
| RE-005 | AI workflow — `CLAUDE.md` + `.claude/skills/` (`reward-mode`, `compress-context`, `rule-authoring`, `money-safety-check`, `tenant-safety-check`) | DONE | — |
| RE-006 | `docker-compose.yml` for local Postgres + Redis | DONE | RabbitMQ deliberately left commented out — see M6 |
| RE-007 | Verified `:common:compileJava :core:compileJava` actually builds | DONE | Needs `JAVA_HOME` ≤ JDK 23 to run Gradle itself; toolchain compiles on 21 — noted in `README.md` |
| RE-008 | Vendored 18 general Java/Spring Boot skills from [decebals/claude-code-java](https://github.com/decebals/claude-code-java) (MIT) as the base for backend work, adapted `maven-dependency-audit` with a Gradle-equivalents note, wired them into `reward-mode`'s routing table | DONE | `.claude/skills/{git-commit,java-code-review,api-contract-review,concurrency-review,...}`, `.claude/skills/THIRD_PARTY_LICENSE-claude-code-java.txt` |

## What's explicitly not in M1

- No service/business logic — entities and the baseline schema only. `CounterRepository` has one method (`consumeAllOrNothing`) as a pattern reference for M3, not a finished feature.
- No admin auth (`gateway`) — see M9.
- No REST controllers at all yet.
