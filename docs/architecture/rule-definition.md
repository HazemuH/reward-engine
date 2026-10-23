# Rule definition schema

What a campaign version's `raw_definition` may contain, and what `counter_def` holds beside it. Enforced by `RuleDefinitionValidator` (definition) and `CounterDefService` (limits) on every draft save.

## Save vs. submit

A draft is a work in progress, so **save checks shape only**: every section is optional and may be empty, but whatever is present must be well-formed. All violations come back at once — `RULE_DEFINITION_INVALID` / `COUNTER_LIMITS_INVALID`, each with a `details` list of `path: problem` entries.

Whether a version is **complete enough to approve** is checked at submit (RE-060), when the definition and its limits are final together:

- at least one benefit;
- at least one `CAMPAIGN`-scoped `MONEY` or `POINT` counter, so the campaign's maximum cost is bounded;
- discount benefits only alongside `ALL_OR_NOTHING` counters (the quoted discount must be exactly what gets debited); `PARTIAL` only for points/cashback;
- content present in every language the tenant serves;
- condition attributes exist in the attribute catalog (RE-012) and `ref` names an existing reference set (RE-067).

## Definition (`raw_definition`)

Top-level sections: `conditions`, `benefits`, `vars`, `content`. Anything else is rejected — `counters` included, because limits live in `counter_def` (one source of truth for caps).

Stored in jsonb's own canonical text (Postgres is asked to produce it), so resending an unchanged definition with different spacing or key order doesn't bump `@Version`. Duplicate keys are rejected rather than silently collapsed, and so is anything jsonb can't store (a NUL escape, an unpaired surrogate, a number past its range).

```json
{
  "vars": { "startDate": "2026-08-01T00:00:00", "endDate": "2026-10-31T23:59:59", "minAmount": 20000 },
  "conditions": [
    { "attr": "amount", "op": ">=", "value": { "var": "minAmount" } },
    { "attr": "customer.segment", "op": "in", "values": ["GOLD", "PLATINUM"] },
    { "attr": "activityType", "op": "in", "ref": "wallet_activity_types" },
    { "attr": "now", "op": "between", "from": { "var": "startDate" }, "to": { "var": "endDate" } }
  ],
  "benefits": [
    { "type": "DISCOUNT_PERCENT", "percent": 20, "max": 100000 },
    { "type": "POINT", "amount": 50 }
  ],
  "content": {
    "en": { "title": "Top-up points", "message": "You earned {points} points" },
    "id": { "title": "Poin top up", "message": "Kamu dapat {points} poin" }
  }
}
```

### `vars`

Object of named parameters, read by conditions and scripts. Names: a letter, then letters/digits/`_`, at most 100. Values: string, number or boolean.

### `conditions`

All must hold (AND). Each has `attr` (dotted name, e.g. `customer.segment`) and `op`; the operator decides the operand fields:

| `op` | Operand fields |
|---|---|
| `=` `!=` | `value` |
| `>` `>=` `<` `<=` | `value` (string or number — no boolean) |
| `in` `not_in` | exactly one of `values` (non-empty array of scalars) or `ref` (reference set name) |
| `between` | `from` and `to`, inclusive — both numbers (`from` ≤ `to`) or both strings |

An operand is a literal or `{"var": "<name>"}` naming a declared var; the var's value must satisfy the same type rule the literal would.

### `benefits`

Each type at most once, and at most one discount per version. Amounts are whole numbers greater than 0, in the same unit as the matching counters (`MONEY` amounts in the tenant's currency).

| `type` | Fields | Granted |
|---|---|---|
| `DISCOUNT_FIXED` | `amount` | at debit |
| `DISCOUNT_PERCENT` | `percent` (0 < p ≤ 100), `max` (required cap per transaction) | at debit |
| `POINT` | `amount` | after confirm |
| `CASHBACK` | `amount` | after confirm |

### `content`

Object keyed by language tag (`en`, `id`, `pt-BR`), each holding any of `title`, `description`, `terms`, `message` as non-blank text. Every language must carry the same fields, so no outcome is shown untranslated.

## Counter limits (`counter_def`)

Authored as an ordered list through `CounterDefService.replaceDraftLimits(versionId, expectedVersion, limits)`; list position is the evaluation order (a `PARTIAL` counter shrinks what's offered to the counters after it). The list replaces the draft's limits as a whole and bumps the version's `@Version`, so a limits edit and a content edit made from the same stale read can't both win. An unchanged list writes nothing.

| Field | Rule |
|---|---|
| `name` | Unique within the version; a letter, then letters/digits/`_`, at most 100 (scripts consume counters by name) |
| `scope` | `CAMPAIGN`, `USER`, `MERCHANT` |
| `unit` | `COUNT`, `MONEY`, `POINT` |
| `period` | `CAMPAIGN`, `DAY`, `WEEK`, `MONTH` |
| `mode` | `ALL_OR_NOTHING`, or `PARTIAL` (only with `MONEY`/`POINT`) |
| `cap` | Greater than 0 |
