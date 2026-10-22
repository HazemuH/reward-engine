# Reward Engine

A rule engine for promo codes and referral rewards — campaigns, quota/budget counters, and redemption lifecycle, decoupled from payment. Built as a **multi-tenant SaaS base**, generalized from real-world banking reward-system requirements: a new deployment is a configuration change, not a fork.

Personal project — the starting scaffold for future engagements that need this kind of system, instead of rebuilding it each time.

## What it covers

- A rule engine for campaigns: conditions, benefits, counters, and an optional sandboxed script for cases the data model alone can't express.
- A redemption lifecycle (quote → reserve → confirm/cancel) built around a small set of non-negotiable principles: money lives in the database, not the cache; every write is idempotent; rule versions are immutable; a reward outage never blocks payment.
- A multi-tenant domain model from the first migration onward, so a second deployment doesn't mean forking the codebase.
- A referral module, reusing the same campaign engine for the reward side.

## Status

Early-stage scaffold: domain model, schema, and module structure exist; the service layer (actual rule evaluation, reserve/confirm/cancel) is still being built out.
