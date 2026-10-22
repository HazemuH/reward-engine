# Milestones

Ticket-style progress tracking for this project. No dates, no timeline — each ticket is a checkpoint: `TODO`, `IN PROGRESS`, `DONE`, or `BLOCKED`. The point is being able to answer "what have we actually built" at a glance, not scheduling.

## Convention

- Ticket IDs are sequential and global: `RE-001`, `RE-002`, ... (not restarted per milestone), kept in `docs/milestones/*.md` one file per milestone.
- A ticket's status lives only in its own file — don't duplicate status in the index table below beyond the milestone-level roll-up.
- When a ticket is finished, flip its status in place and add a one-line note of what actually landed (file/PR, not a date). Don't delete finished tickets — they're the record of what was done and why, same spirit as `docs/decisions/open-decisions.md`.
- A new ticket can be added to a milestone file at any time; it doesn't need to have been planned here from the start.
- Tickets tagged `[money-safety-check]`, `[tenant-safety-check]`, or `[rule-authoring]` are a pointer to which `.claude/skills/` checklist applies before marking them done — see `CLAUDE.md`.

## Milestones

| # | Milestone | Status |
|---|---|---|
| [M1](M1-foundation.md) | Foundation & SaaS base | **DONE** |
| [M2](M2-rule-engine-core.md) | Rule engine core (campaign, conditions, calculate) | TODO |
| [M3](M3-redemption-lifecycle.md) | Redemption lifecycle (reserve/confirm/cancel, counters) | TODO |
| [M4](M4-script-sandbox.md) | Groovy script sandbox (hardened against RCE) | TODO |
| [M5](M5-referral.md) | Referral module | TODO |
| [M6](M6-fulfillment-and-jobs.md) | Fulfillment worker & scheduled jobs | TODO |
| [M7](M7-admin-api.md) | Admin API (campaign mgmt, approvals, reports) | TODO |
| [M8](M8-observability-and-security.md) | Observability & security hardening | TODO |
| [M9](M9-gateway-auth.md) | `reward-gateway` — LDAP/JWT admin auth | **BLOCKED** (see `docs/decisions/open-decisions.md` #2) |
| [M10](M10-tenant-onboarding.md) | SaaS tenant onboarding | TODO (stretch) |

M2–M4 are the actual engine and should land roughly in that order (calculate needs rule evaluation before reserve has anything to reserve). M5–M8 can interleave. M9 stays parked until the gateway decision is unblocked. M10 is explicitly stretch — don't start it before M2–M4 are solid.
