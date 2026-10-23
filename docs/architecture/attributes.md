# Attributes: catalog and customer snapshot

What conditions and scripts can see about a request and a customer, and where each value comes from. Code: `core.attribute` (a leaf package — rules read it, it depends on no other feature).

## Catalog (`attribute_def`)

Conditions and scripts may only refer to attributes in the tenant's catalog, plus the engine's built-ins. A missing or empty attribute makes the condition false — it never passes.

| Field | Rule |
|---|---|
| `name` | Dotted name, e.g. `customer.segment`, at most 100 characters; unique per tenant; built-in names are reserved |
| `source` | `REQUEST` (sent by the caller with each request) or `SNAPSHOT` (loaded periodically). No live lookup source: it would put a downstream call on the calculate path |
| `data_type` | `STRING`, `NUMBER`, `BOOLEAN`, `DATE`, `DATETIME` (dates local to `tenant.time_zone`) |
| `sensitive` | See below |
| `description` | Optional, at most 500 characters |

Name, source and type are fixed once defined, because stored values and approved conditions are read through them. A different meaning needs a new attribute. Only `sensitive` and `description` change (`AttributeCatalogService.describe`, under `@Version`). Attributes are never removed.

Built-ins: `now` (`DATETIME`, server time in the tenant's zone — never a time the caller sends).

Submit checks every `conditions[].attr` with `AttributeCatalogService.requireDefined` (`ATTRIBUTE_NOT_DEFINED` lists each unknown name). Saving a draft doesn't, so a draft may name an attribute before it is added.

## Sensitive attributes

Flagged in the catalog. A sensitive attribute may be used in **data conditions**, which are reviewed through approval. It never reaches:

| Where | How it's kept out |
|---|---|
| Scripts | `CustomerSnapshot.scriptView()` omits it; script facts are built from that view (RE-035), and a script naming one should fail compilation |
| Logs | `CustomerSnapshot.toString()` masks it (and leaves out the user key); `SnapshotRow.toString()` names attributes only; snapshot validation messages never echo a value |
| Exports / stored results | Exports (RE-066) and quotes/redemptions (M3) carry no sensitive values |

The flag is read when a value is used, not when it is stored, so flagging an attribute protects values that are already loaded.

Whether a tenant may target on sensitive attributes at all is open decision #14. Until it is answered, conditions may use them and nothing else may.

## Customer snapshot (`snapshot_load`, `attribute_snapshot`)

Snapshot attributes are loaded in **generations**:

1. `beginLoad()` opens a `LOADING` load.
2. `append(loadId, rows)` adds rows in batches. A batch is all or nothing: one bad row rejects it with every problem listed (`SNAPSHOT_ROWS_INVALID`), naming row and attribute, never the value. Values are checked against the catalog: unknown or `REQUEST` attributes are refused, types must match, strings are stripped, numbers stay within 30 integer and 20 fraction digits, and a null or blank value means the attribute is absent. At most 10,000 rows per batch. User keys are stripped on write and on read.
3. `activate(loadId)` makes it `ACTIVE` and supersedes the previous `ACTIVE` load in the same transaction. This is the atomic swap: reads only ever see the `ACTIVE` load, so a half-written load is never served, and a customer missing from the new load has no snapshot. `fail(loadId)` abandons a load.

At most one `ACTIVE` load per tenant (partial unique index). Activations that overlap: one wins, the others roll back with their load still `LOADING`.

`find(userKey)` returns the customer's typed values from the `ACTIVE` load, with `loadedAt` (the load's activation time) so callers can judge staleness; `activeLoad()` gives the "last loaded" time an admin sees.

Not here yet:
- The loader itself (file or API, schedule, row-count checks) is RE-057, blocked on open decision #13.
- Purging superseded generations is RE-057's retention; deleting a `snapshot_load` cascades to its rows.
- Catalog and snapshot reads hit the database on every `find`; caching them belongs with the active-rule cache (RE-015).
