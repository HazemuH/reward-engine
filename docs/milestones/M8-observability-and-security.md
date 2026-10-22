# M8 — Observability & security hardening

**Status: TODO**

Can and should start as soon as there's anything to observe (don't wait for every other milestone to finish) — pull individual tickets forward as the relevant feature lands.

| Ticket | Description | Status | Depends on |
|---|---|---|---|
| RE-070 | Core metrics — `reward.calculate.duration`, `reward.reserve.result{ok,quota,dup,error}`, `reward.redemption.pending.age`, `reward.budget.used.ratio{campaign}`, `reward.recon.lag` | TODO | M2, M3 |
| RE-071 | Structured logging — `requestId`/`redemptionId` on every line, no raw CIF ever (hashed or `customerRef` only) | TODO | M2 |
| RE-072 | Rate limiting end to end — Redis-backed, documented fallback to per-instance limits when Redis is down (never a hard failure) | TODO | M2 (RE-016) |
| RE-073 | Startup secret validation — app fails fast on any empty required secret (JWT key, DB password, encryption key), no default values anywhere | TODO | — |
| RE-074 | Bulkhead: separate thread/connection pools for `/internal` (transaction path) vs admin endpoints, so one can't starve the other | TODO | M7 |
| RE-075 | Actuator lockdown — only `health`, `info`, `prometheus` exposed; Swagger off by default | TODO | — |
| RE-076 | Load/concurrency/soak test pass against the targets in `docs/architecture/overview.md` (p95 < 50ms for `calculate`) once real traffic projections exist | TODO | M2, M3. Blocked on open-decisions.md #22 |

## Exit criteria

- Grepping logs for a CIF pattern turns up nothing, across every log level, in a full local run exercising every endpoint built so far.
- Killing the app with a required secret unset fails at startup with a clear message, not three requests later.
