# M3 — Redemption lifecycle

**Status: TODO**

The money-critical path. Every ticket here is `[money-safety-check]` — read that skill before starting any of them, not just before marking them done.

| Ticket | Description | Status | Depends on |
|---|---|---|---|
| RE-020 | Idempotency service — `idempotency_key` check/write wrapping every mutating endpoint | TODO | M1 |
| RE-021 | `reserve`: validate quote hash/expiry, consume all counters in fixed order-by-id inside one transaction (`ALL_OR_NOTHING` via conditional `UPDATE`, `PARTIAL` via `SELECT ... FOR UPDATE`), write `redemption=RESERVED` + entitlements | TODO | RE-020, M2 |
| RE-022 | `confirm` / `cancel` — `RESERVED → USED` / `RESERVED → RELEASED` (counter given back on cancel) | TODO | RE-021 |
| RE-023 | `status` + `expire-reserve` scheduled job — `RESERVED → PENDING` handling for an unknown result or timeout from the payment side, polls until final instead of guessing | TODO | RE-022, M6 (ShedLock) |
| RE-024 | `reverse` — full-refund-only path, `USED → REVERSED`, counter given back | TODO | RE-022 |
| RE-025 | Concurrency test suite: two simulated callers racing the last unit of a counter, Testcontainers-backed (not H2) | TODO | RE-021 |
| RE-026 | `expire-quote` scheduled job — discard `QUOTED` rows past 15 minutes (no counter impact, nothing to release) | TODO | M2, M6 (ShedLock) |

## Exit criteria

- RE-025's concurrency test actually fails if the atomic `UPDATE` is replaced with a read-then-write (prove the test catches the bug it exists for).
- A forced debit failure after `reserve` but before `confirm` reliably ends in `RELEASED` with the counter restored — verified, not assumed.
- `PENDING` never silently resolves to `RELEASED` on a timeout alone; it only moves on an actual status answer from downstream.
