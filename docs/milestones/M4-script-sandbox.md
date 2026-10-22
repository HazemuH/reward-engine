# M4 — Groovy script sandbox

**Status: TODO**

Only needed once a rule actually requires one — most don't (see `.claude/skills/rule-authoring`). This milestone can trail M2/M3 rather than block them, as long as `CampaignVersion.optionalScript` is simply never populated until this lands.

| Ticket | Description | Status | Depends on |
|---|---|---|---|
| RE-030 | Sandboxed Groovy execution: separate classloader, static compilation at save time, import allow-list, no `System`/reflection/threads | TODO | M2 |
| RE-031 | `Decision` contract — script returns `{ eligible, benefits[], consumes[] }`; engine validates benefit types and counter names against the campaign version's own declarations, rejects anything undeclared | TODO | RE-030 |
| RE-032 | 50ms execution budget + memory cap; exception or timeout → not eligible, no benefit, logged to `reward.script.failure` metric (never a default grant) | TODO | RE-030 |
| RE-033 | `facts`/`vars`/`ref` read-only bindings — no DB/network access reachable from script context, verified by test, not just by omission | TODO | RE-030 |
| RE-034 | Script test harness: required edge-case tests (zero amount, at-cap, missing attribute) stored as part of the version before it can be approved | TODO | RE-031 |
| RE-035 | Dry-run / simulate endpoint — reuses the `calculate` path, confirms no write path exists for scripts even under simulation | TODO | RE-031, M2 (calculate) |

## Exit criteria

- A script attempting file I/O, `System.exit`, or a DB call fails at compile-or-run time, with a test proving it (per `[money-safety-check]`).
- A script that throws mid-execution never grants a benefit — covered by test, not just by the `try/catch` existing.
