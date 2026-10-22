# M5 — Referral module

**Status: TODO**

Reuses the campaign engine for the actual reward ("Issued by referral" campaigns) — this milestone is attribution/qualification plumbing, not a second rule engine.

| Ticket | Description | Status | Depends on |
|---|---|---|---|
| RE-040 | Referral schema (`referral_program`, `referral_code`, `referral_sequence`, `referral_share`, `referral_attribution`, `referral_qualification`) + Flyway migration, tenant-scoped `[tenant-safety-check]` | TODO | M1 |
| RE-041 | `referral/code` — idempotent get-or-create, code generated via atomic `referral_sequence` (`INSERT ... ON CONFLICT DO UPDATE ... RETURNING`), unique index as the final guard | TODO | RE-040 |
| RE-042 | `referral/redeem` — attribution validation (not self, not past the program's attribution window, not already used), device-match fraud signal → `FLAGGED` for review, never auto-rejected on one signal alone | TODO | RE-040 |
| RE-043 | `referral/qualify` — event-driven, `eventId` + `(attribution, event type)` both unique so duplicate/out-of-order events can't double-reward | TODO | RE-042 |
| RE-044 | Referral status lifecycle: `ISSUED → ATTRIBUTED → QUALIFIED → REWARDED`, with `FLAGGED`/`REJECTED`/`EXPIRED` off-ramps | TODO | RE-041–043 |
| RE-045 | Referral review screen's backend (list `FLAGGED`, approve/reject with reason) — API only, no UI yet (see M7) | TODO | RE-042 |

## Open decision this milestone is partly gated on

`docs/decisions/open-decisions.md` #20 (clawback policy if a qualifying transaction is cancelled after reward) and #8 and #21 (referral code capacity and format) — both resolvable with a documented assumption per `CLAUDE.md`, not a hard blocker.

## Exit criteria

- A duplicate `referral/qualify` event (same `eventId`, replayed) provably does not create a second entitlement.
- `referral/redeem` on an already-used or self-referred code is rejected with a reason recorded, not silently ignored.
