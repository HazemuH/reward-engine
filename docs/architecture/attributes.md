# Attributes: catalog and customer snapshot

What conditions and scripts can see about a request and a customer, and where each value comes from. Code: `core.attribute` (a leaf package — rules read it, it depends on no other feature).

## Catalog (`attribute_def`)

Conditions and scripts may only refer to attributes in the tenant's catalog, plus the engine's built-ins. A missing or empty attribute makes the condition false — it never passes.

| Field | Rule |
|---|---|
| `name` | Dotted name, e.g. `customer.segment`, at most 100 characters; unique per tenant regardless of letter case. Scripts will read names as nested properties (`facts.customer.segment`), so no segment may be a Groovy or Java reserved word or `class`/`metaClass`/`properties` (in any letter case), and no name may nest with another (`customer` next to `customer.segment` would make one both a value and a namespace). Built-ins are reserved in any letter case, names under them (`now.offset`) too |
| `source` | `REQUEST` (sent by the caller with each request) or `SNAPSHOT` (loaded periodically). No live lookup source: it would put a downstream call on the calculate path |
| `data_type` | `STRING`, `NUMBER`, `BOOLEAN`, `DATE`, `DATETIME` (dates local to `tenant.time_zone`) |
| `sensitive` | See below |
| `description` | Optional, at most 500 characters, none the database would refuse (NUL, unpaired surrogates) |

Catalog changes are serialized per tenant by an advisory lock (the nesting rule has no constraint behind it); tenants never wait on each other. Name, source and type are fixed once defined, because stored values and approved conditions are read through them. A different meaning needs a new attribute. Only `sensitive` and `description` change (`AttributeCatalogService.describe`, under `@Version`). Attributes are never removed.

Built-ins: `now` (`DATETIME`, server time in the tenant's zone — never a time the caller sends).

`AttributeCatalogService.requireDefined` checks a set of names (`ATTRIBUTE_NOT_DEFINED` lists each unknown one, naming the stored spelling when only the case differs). **Not called yet:** submit (RE-060) is where it runs over every `conditions[].attr`. Saving a draft never will, so a draft may name an attribute before it is added.

## Sensitive attributes

Flagged in the catalog. A sensitive attribute may be used in **data conditions**, which are reviewed through approval. It never reaches:

| Where | How it's kept out | Status |
|---|---|---|
| Scripts | `CustomerSnapshot.scriptView()` omits it; script facts are built from that view, and a script naming one fails compilation | View in place; the script side is RE-035 |
| Logs | `CustomerSnapshot.toString()` masks it (and leaves out the user key); `SnapshotRow.toString()` names attributes only; validation messages never echo a value; the datasource runs with `logServerErrorDetail=false`, so database errors (which Hibernate logs) carry no row values or bound parameters | In place |
| Exports / stored results | Exports and quotes/redemptions carry no sensitive values | RE-066, M3 |

This covers `SNAPSHOT` attributes. A `REQUEST` attribute can be flagged too, but request facts don't exist until RE-013/RE-014, which must give them the same two views.

The flag is read when a value is used, not when it is stored, so flagging an attribute protects values that are already loaded — immediately, without a new rule version, because it is a data-protection control. That also means an approved script that read the attribute now sees it as absent. Clearing the flag goes the other way (it exposes values to every script) and should need a checker's approval; who approves parameter changes is open decision #23, so until the admin API exists (RE-067) `describe` is not exposed to anyone.

Whether a tenant may target on sensitive attributes at all is open decision #14. Until it is answered, conditions may use them and nothing else may.

## Customer snapshot (`snapshot_load`, `attribute_snapshot`, `snapshot_batch`)

Snapshot attributes are loaded in **generations**:

1. `beginLoad()` opens a `LOADING` load.
2. `append(loadId, batchKey, rows)` adds rows in batches. It is idempotent by `batchKey`, the loader's id for the batch, recorded in `snapshot_batch` with a hash of the request as sent (the same pattern as `idempotency_key`; checked before validation, so a redeploy mid-load can't turn a retry into a failure): resending the same batch is a no-op — even while the original is still in flight (the retry waits on the record's unique key) or after the load was activated, though not once it failed or was superseded — and reusing the key for a different batch, including other values for the same customers or an earlier empty batch, is refused as a loader bug. A customer already in the load under another batch is refused, so a duplicate in the source is reported by row rather than silently dropped. A batch is all or nothing: one bad row rejects it with every problem listed (`SNAPSHOT_ROWS_INVALID`), naming row and attribute, never the value. Values are checked against the catalog: unknown or `REQUEST` attributes are refused, types must match, strings are stripped and then at most `reward.snapshot.max-string-length` characters (1,000 by default), numbers stay within 30 integer and 20 fraction digits, and a null or blank value means the attribute is absent. At most 10,000 rows per batch. User keys are stripped on write and on read.
3. `activate(loadId, expectedRows)` makes it `ACTIVE` and supersedes the previous `ACTIVE` load in the same transaction. This is the atomic swap: reads only ever see the `ACTIVE` load, so a half-written load is never served, and a customer missing from the new load has no snapshot. It must hold exactly `expectedRows` rows — the count the loader read from its source — so an empty (`SNAPSHOT_LOAD_EMPTY`) or truncated (`SNAPSHOT_LOAD_INCOMPLETE`) load never replaces a complete one. `rowCount(loadId)` reports progress while loading. `fail(loadId)` abandons a load. Both are safe to retry: activating an already-`ACTIVE` load with the same count, or failing a `FAILED` one, returns it unchanged. A retry with a different count is `SNAPSHOT_LOAD_INCOMPLETE`; a retry on a load that did activate but has since been replaced is `SNAPSHOT_LOAD_SUPERSEDED`, so a loader never reports a replaced generation as live.

Appends to one load may run in parallel (each takes the load row `FOR SHARE` and inserts in user-key order, so overlapping batches wait instead of deadlocking). Activation takes it `FOR UPDATE`, so it waits for in-flight appends, counts the rows (`rowCount`, recorded at activation), and any append arriving later is refused as `SNAPSHOT_LOAD_NOT_OPEN` — no row joins a serving load uncounted.

At most one `ACTIVE` load per tenant (partial unique index). Activations that overlap: one wins, the others fail with an optimistic-lock conflict (`ObjectOptimisticLockingFailureException`, 409 once the web layer maps it in M7) and roll back, their load still `LOADING`.

`find(userKey)` returns the customer's typed values from the `ACTIVE` load, with `loadedAt` (the load's activation time) so callers can judge staleness; `activeLoad()` gives the "last loaded" time an admin sees.

Not here yet:
- The loader itself (file or API, schedule, row-count checks) is RE-057, blocked on open decision #13.
- Purging old generations is RE-057's retention — `SUPERSEDED` loads, and also `FAILED` and abandoned `LOADING` ones, which hold customer values (sensitive ones included) that will never be served; deleting a `snapshot_load` cascades to its rows.
- Catalog and snapshot reads hit the database on every `find`; caching them belongs with the active-rule cache (RE-015).
