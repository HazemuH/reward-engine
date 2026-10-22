# M7 — Admin API

**Status: TODO**

The API surface the (future) Admin Panel talks to. Built and testable **without** `reward-gateway` existing yet — these endpoints assume a trusted caller for now; `[tenant-safety-check]` + real auth/RBAC wiring happens when M9 unblocks, not before.

| Ticket | Description | Status | Depends on |
|---|---|---|---|
| RE-060 | Campaign/version endpoints — list/create/draft-edit/submit-for-approval, optimistic-lock `409` on concurrent edit | TODO | M2 |
| RE-061 | Approval inbox — `GET /approvals`, `POST /approvals/{id}/approve\|reject`, maker ≠ checker enforced at the service layer (not just UI) | TODO | RE-060 |
| RE-062 | Pause/Resume/Stop-request/Kill-switch endpoints, matching the status diagram in `docs/architecture/overview.md` | TODO | RE-060 |
| RE-063 | Codes endpoint — common/unique code management, revoke | TODO | M2 |
| RE-064 | Customer search + redemption history (`customerRef`-based, no CIF in path/query — see `CLAUDE.md`) | TODO | M3 |
| RE-065 | Operations endpoints — pending transactions, entitlement retry, manual resolution requests | TODO | M3, M6 |
| RE-066 | Reports + CSV export (BOM UTF-8, CIF masked except for authorized roles, every full-CIF reveal audited) `[tenant-safety-check]` | TODO | M3 |
| RE-067 | Library endpoints — reference sets, attribute catalog, snapshot feed config | TODO | M2 |
| RE-068 | Audit log endpoint (read-only, for the Auditor role once RBAC exists) | TODO | — |

## Exit criteria

- Every mutating admin endpoint has a permission string attached in the code (even if nothing enforces it yet pre-M9) so wiring RBAC later is additive, not a rewrite.
- A concurrent edit to the same campaign version from two callers reliably produces one `409`, not a silent overwrite.
