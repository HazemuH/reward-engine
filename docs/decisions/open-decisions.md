# Open decisions

Questions every deployment of this engine has to answer, plus the decisions already taken for the base itself. Update the status column as the team answers each one — don't delete rows.

| # | Decision | Status | Recommendation on file | Owner |
|---|---|---|---|---|
| 1 | Who calls reward-core: the channel backend, or the tenant's integration layer | Open | API contract is caller-agnostic (`client_application.allowed_channels`) — build against the contract, don't block on this | tenant |
| 2 | One app, or `reward-core` + separate `reward-gateway` for admin LDAP/JWT | **Deferred by request** — `gateway` module not scaffolded yet | Separate gateway (4 instances); if rejected, one app with two filter chains, code still split by module so it can be split out later | tenant / user |
| 3 | PostgreSQL or SQL Server | Resolved for this base | PostgreSQL — migrations in `core/src/main/resources/db/migration` assume it (upsert/partial-index syntax) | — |
| 4 | Redis available or not | Assumed available | Needed for even rate limiting and safe logout; without it, fall back to per-instance limits + DB-backed sessions | tenant |
| 5 | RabbitMQ | Deferred to phase 2 | Outbox table exists now; relay job/broker wiring comes later | tenant |
| 6 | Transaction-path auth: mTLS or API key | Open | mTLS if the caller supports it, else per-client API key | tenant |
| 7 | Who is checker: permission-based or per-user supervisor | Open | `change_request` design supports either | Team |
| 8 | Referral code capacity per period | Open | Decide code length vs. name-prefix scheme | Team + tenant |
| 9 | Campaign content icons: URL or uploaded asset | Open | — | Team |
| 10 | Business policy settings (who absorbs the discount, refund scope, etc.) | Open | Expose as Admin Panel settings so it's not a code change later | tenant |
| 11 | How points/cashback actually get credited (real-time API vs. daily batch) | Open — **blocks fulfillment worker implementation** | Until answered, entitlements can be recorded/reported but not granted | tenant |
| 12 | Point-to-currency conversion for budget math | Open | One config value, or a separate POINT-unit budget | tenant |
| 13 | Source of customer attribute snapshot data (demographics, branch, tenure) | Open | File or API from the tenant; need load schedule + data owner | tenant |
| 14 | Sensitive-attribute targeting policy | Open | Confirm with the tenant's data protection policy owner | tenant |
| 15 | Partial budget on last transaction | Resolved for this base | Reject whole (`QUOTA_EXHAUSTED`); PARTIAL mode only applies to deferred benefits | Team |
| 16 | Monthly period boundary: server time or request time | Resolved for this base | Server time, tenant-configured zone (`reward.period-zone`) | Team |
| 17 | AI-assisted script generation | Deferred | Only if rule descriptions are allowed to leave the tenant's network | tenant |
| 18 | Other apps beyond the pilot tenant's app using this engine | Partially resolved — this is why the base is multi-tenant | Stage 1 is one tenant/one channel; foundation (tenant, channel, client credential) already generalized | tenant |
| 19 | Referral cross-app vs. origin-app only | Open | Stage 1: origin app only | tenant |
| 20 | Clawback policy when a qualifying transaction is later cancelled | Open | Only if the points system supports reversal; otherwise record + report | tenant |
| 21 | Referral code format: structured or random | Open | Random without personal data is safer; a structured format is a product choice the tenant makes | tenant |
| 22 | End-to-end latency budget and traffic projection | Open | Core `calculate` target is p95 < 50ms; end-to-end budget is separate | tenant |
| 23 | Who approves user/role/parameter changes | Open | Not the promo checker — a separate administrative role | tenant |

## Decisions made specifically for this SaaS base

| Decision | Why |
|---|---|
| Generalize `channel`/`client_application` into tenant + channel | So a second customer is a new `tenant` row, not a fork. See `docs/architecture/overview.md` and `TenantContext`. |
| Every tenant-owned table gets a mandatory `tenant_id` from the baseline migration onward | Retrofitting multi-tenancy later means a painful migration across every table; cheap to do now. |
| Business-tunable values (quote TTL, rate limits, period zone) live in `application.yml` under `reward:`, not hardcoded | Keeps "adjust per deployment" true in practice, not just in a doc. |
| `reward-gateway` not scaffolded yet | Explicit request: ship the engine base first, add LDAP/admin auth as its own pass. |
