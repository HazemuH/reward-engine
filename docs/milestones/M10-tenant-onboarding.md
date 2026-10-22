# M10 — SaaS tenant onboarding

**Status: TODO (stretch)** — don't start before M2–M4 are solid; this is what turns "multi-tenant-ready schema" into "actually onboard a second tenant without touching code."

| Ticket | Description | Status | Depends on |
|---|---|---|---|
| RE-090 | Tenant provisioning flow — create `tenant` + initial `client_application` + default config, scriptable (CLI or admin endpoint, either is fine) | TODO | M1 |
| RE-091 | Per-tenant settings table replacing the `reward.*` keys in `application.yml` that are plausibly tenant-specific (`quote-ttl-minutes`, rate limits) with DB-backed overrides, `application.yml` values becoming defaults only. Time zone and currency already moved to the `tenant` row in RE-094 | TODO | RE-090, `[tenant-safety-check]` |
| RE-092 | Verify end to end: spin up a second tenant locally, run a campaign through `calculate`→`reserve`→`confirm` without it ever touching tenant 1's rows, counters, or cache keys | TODO | RE-090, RE-091, M2, M3 |
| RE-093 | Document the actual onboarding steps in this repo (`docs/`) once RE-090–092 land — this ticket is "write it down," not "build it" | TODO | RE-092 |

## Exit criteria

This milestone's own exit criteria **is** RE-092: a second tenant, fully isolated, provable rather than assumed from the schema alone.
