# Architecture overview

## What this is

A rule engine for promo codes and referral rewards, generalized from real-world banking promo/referral requirements into a **reusable SaaS base**: the domain model (campaign → version → conditions/benefits/counters/script) and the tenant boundary are generic from day one, so a second deployment is a config change, not a fork. See `docs/decisions/` for what's still deployment-specific and what's already generalized.

## Six principles (non-negotiable)

1. **Money lives in the database, not the cache.** Quota, budget and per-user caps exist only in PostgreSQL, decremented by one atomic statement. Redis never holds them.
2. **Reads may fail open; writes may not fail closed silently.** Listing/calculating a promo degrading to "no promo" is fine. A reserve must never slip through without locking a counter.
3. **Every write is idempotent.** Every request carries a `requestId`; a retry from the caller returns the same stored result (`idempotency_key` table).
4. **Rules are never overwritten.** Every campaign change creates a new version. A redemption stores exactly which version it used.
5. **Script decides, the engine enforces.** A Groovy script may propose a `Decision` (benefits, consumes). Only the engine touches counters, reverses them on failure, and rejects any decision that exceeds the campaign's defined limits.
6. **Reward going down must never take payment down with it.** Core banking debit does not depend on the reward engine being up.

## Components

| Component | Responsibility | State |
|---|---|---|
| `core` (reward-core) | Rule engine, script sandbox, quote/reserve/confirm/cancel, counters, referral, scheduler, reconciliation | Stateless in memory; active-rule cache may vanish anytime |
| `gateway` (reward-gateway) — **not built yet** | Admin login (LDAP + local), JWT issuance, RBAC, routing to core's admin API | Sessions in Postgres, JWT blacklist in Redis |
| PostgreSQL | Single source of truth: rules, counters, redemptions, audit | All of it |
| Redis | Rate limiting, active-rule cache, JWT blacklist — all optional, all fail-open | Disposable |
| RabbitMQ (phase 2, optional) | Notification/report events via an outbox table, not a requirement for correctness | Durable, but core never blocks on it |

`gateway` is deliberately absent from `settings.gradle` right now — see `docs/decisions/open-decisions.md` before adding it back. The transaction path (the caller → core) never goes through a gateway in any option on the table; only the human admin path does.

## Rule model

One `CampaignVersion` = `conditions` (from an attribute catalog) + `benefits` + `counters` + `vars` + an optional sandboxed Groovy `script` + bilingual `content`. Most rules need no script at all.

Script contract (enforced by structure, not convention):
- Input: `facts` (request + customer snapshot, read-only), `vars`, `ref` (read-only reference lists).
- Output: one `Decision` — eligible?, `benefits`, `consumes`. Validated against the campaign's own declared benefit types and counter names.
- Forbidden: any write, any I/O, reflection, threads, wall-clock reads (use `facts.now`), anything outside an explicit allow-list of imports.
- Budget: 50ms execution, capped memory, static-compiled at save time, separate classloader.
- On exception or timeout: not eligible, no benefit — ever. Never "treat as a new customer and grant anyway" (that was the old system's bug).

## Redemption lifecycle

```
QUOTED (15 min TTL) → RESERVED → USED
                    ↘ RELEASED (cancel / expired reserve / core-down timeout)
          USED → REVERSED (full refund only)
       RESERVED → PENDING → USED | RELEASED  (unknown result / timeout from the payment side — poll, don't guess)
```

Quote locks nothing. A counter is only touched at `reserve`, i.e. when payment is actually sent — a customer backing out at the confirmation screen never burns quota.

`reserve` runs as one DB transaction: write `idempotency_key`, validate the quote hash hasn't drifted, consume every counter **in a fixed order by id** (so two concurrent transactions don't deadlock each other), write `redemption=RESERVED` + entitlements. One counter failing rolls back the whole reserve (`QUOTA_EXHAUSTED`).

## Deferred benefits (points/cashback)

Granted **after** `confirm`, outside the payment transaction, via an outbox row + worker with retry (`PENDING_GRANT → GRANTED | RETRY → FAILED`), keyed by `entitlementId` so retries can't double-grant. A failed grant never unwinds a successful transaction or releases a counter.

## Two instances, one truth

- `@Version` optimistic locking on `campaign`/`campaign_version` — concurrent admin edits get `409`, never a silent overwrite.
- Every scheduled job wrapped in ShedLock (table-based) — see `shedlock` table in the baseline migration. Two instances must never run the same job twice.
- Rule cache invalidation: Redis pub/sub is the fast path, a periodic read of an `epoch` column in Postgres is the fallback — Redis being down never lets two instances disagree about active rules for more than a few seconds.
- Monthly/daily counter periods are computed from **server time in the tenant's configured zone** (`tenant.time_zone`), never from a timestamp the caller sends.
- Counter limits are split in two: `counter_def` holds cap/unit/mode as approved in one campaign version (the single source of truth for the cap); `counter` holds usage per (campaign, name, scope key, period key) and carries it across versions. The consuming UPDATE takes the cap from the version being redeemed against and re-checks `campaign.status = ACTIVE` in the same statement, so a kill switch is effective immediately.

## What's intentionally not built yet

- `reward-gateway` / LDAP / JWT admin auth — see `docs/decisions/open-decisions.md` #2.
- RabbitMQ wiring — outbox table exists, the relay job and broker don't yet.
- Multiple campaigns-per-transaction resolution, referral fraud review UI, points/cashback crediting integration — all gated on open deployment decisions, tracked in `docs/decisions/open-decisions.md`.

## Full API / data model reference

The complete endpoint table (`/internal/reward/v1/*`, `/api/v1/reward/**`), business error codes, and the full table-by-table data model are documented in docs/architecture/overview.md as they're built. Keep new entities/endpoints consistent with those unless a decision in `docs/decisions/` explicitly changes them.
