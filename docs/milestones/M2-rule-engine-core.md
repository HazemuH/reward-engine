# M2 — Rule engine core

**Status: TODO**

Gets `calculate` returning a real quote from a real campaign version. No writes yet (quoting only) — this milestone stays entirely on the "reads may fail open" side of the six principles.

| Ticket | Description | Status | Depends on |
|---|---|---|---|
| RE-010 | Campaign CRUD service + `DRAFT` lifecycle (model only, no approval API — that's M7) | DONE — `CampaignService` (create/list/get/rename/openDraft/editDraft, expected-version `409`), content writable only via `CampaignVersion.editDraft` while DRAFT (DB triggers freeze it, counter_def caps included), openDraft carries content + counter limits forward, one open draft per campaign (partial unique index) | M1 |
| RE-011 | `CampaignVersion` authoring: JSON schema for `conditions`/`benefits`/`counters`/`vars`/`content`, validated on save; store `rawDefinition` canonicalized (jsonb normalizes spacing, so a non-canonical resave bumps `@Version` and gives other editors a false `409`); `CounterDef` authoring path with a DRAFT guard `[rule-authoring]` | DONE — `RuleDefinitionValidator` (shape-only schema on every save, all violations in `details`; schema in docs/architecture/rule-definition.md), definition stored as Postgres's own jsonb text so respelled resaves don't bump `@Version`; counters stay out of the definition — `CounterDefService.replaceDraftLimits` replaces a DRAFT's `counter_def` list under the version's `@Version` (`claimDraft`); `CounterDef` has no setters | RE-010 |
| RE-012 | Attribute catalog + snapshot table (`attribute_def`, `attribute_snapshot`), sensitive-attribute flag enforced (no sensitive attrs in scripts/logs/exports); submit checks `conditions[].attr` against the catalog `[tenant-safety-check]` | TODO | M1 |
| RE-013 | Condition evaluator against REQUEST/SNAPSHOT attribute sources (LOOKUP deliberately out of scope — see docs/architecture/overview.md) | TODO | RE-011, RE-012 |
| RE-014 | `POST /internal/reward/v1/calculate` — fail-open on any downstream error, builds a `QUOTED` quote with 15-min TTL, no counter touched `[money-safety-check]` | TODO | RE-013 |
| RE-015 | Active-rule cache (in-memory per instance) + Redis pub/sub invalidation + DB `epoch` fallback poll (two-instance consistency, see docs/architecture/overview.md, "Two instances, one truth") | TODO | RE-014 |
| RE-016 | Calculate rate limiting (`reward.calculate-rate-limit-per-minute`, Redis-backed with per-instance fallback) | TODO | RE-014 |

## Exit criteria

- A campaign version with no script evaluates correctly end to end against `calculate`.
- Killing Redis mid-run doesn't break `calculate` (manually verify the fail-open path).
- Two `core` instances started against the same DB converge on the same active rules within the epoch-poll interval after an approval (can be faked by flipping `campaign.status` directly for the test).
