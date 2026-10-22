# M6 — Fulfillment worker & scheduled jobs

**Status: TODO**

Everything that runs outside the payment transaction. Nothing here may ever unwind a `USED` redemption or release a counter on failure.

| Ticket | Description | Status | Depends on |
|---|---|---|---|
| RE-050 | `outbox_event` table + outbox-relay job (DB-only for now — no RabbitMQ yet, see `docs/decisions/open-decisions.md` #5) | TODO | M1 |
| RE-051 | Fulfillment worker: `PENDING_GRANT → GRANTED \| RETRY → FAILED`, keyed by `entitlementId` so retries can't double-grant `[money-safety-check]` | TODO | RE-050. **Blocked** on open-decisions.md #11 (how points/cashback are actually credited) — build against a stub interface until answered |
| RE-052 | ShedLock table + wiring for every scheduled job below (two instances must not double-run a job) | TODO | M1 |
| RE-053 | `activate-campaign` job — `APPROVED` past start date → `ACTIVE`, bump `epoch`, publish invalidation | TODO | RE-052, M2 (RE-015) |
| RE-054 | `end-campaign` job — `ACTIVE` past end date → `ENDED` | TODO | RE-052 |
| RE-055 | `expire-quote`, `expire-reserve`, `reconcile-pending` jobs | TODO | RE-052, M3 |
| RE-056 | `budget-alert` job — flag campaigns at 80%/90% of quota or budget | TODO | RE-052 |
| RE-057 | `snapshot-loader` job — load customer attribute snapshot (demographics, branch, tenure) from file or API, atomic swap, last-loaded timestamp surfaced | TODO | M2 (RE-012). Blocked on open-decisions.md #13 (data source) |

## Exit criteria

- Starting two `core` instances against the same DB and schedule never produces two concurrent runs of the same job (verify by log inspection, not just by ShedLock being present).
- A fulfillment grant failure three times in a row lands in `FAILED` and shows up wherever M7's operations endpoint surfaces it — doesn't just disappear.
