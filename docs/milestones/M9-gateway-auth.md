# M9 — `reward-gateway` (LDAP/JWT admin auth)

**Status: BLOCKED** — parked by explicit request until the engine base (M2–M4) is solid, and on the open decision below.

| Ticket | Description | Status | Depends on |
|---|---|---|---|
| RE-080 | Add `gateway` module to `settings.gradle`, scaffold matching `core`'s conventions | TODO | Unblock decision below |
| RE-081 | `users`/`roles`/`permissions`/`user_sessions`/`security_policies`/`ldap_settings` schema (`auth` schema, separate from `reward` schema — see `docs/architecture/overview.md` section 14) | TODO | RE-080 |
| RE-082 | LDAP bind + local BCrypt auth behind one login endpoint, `auth_source` column distinguishing them | TODO | RE-081 |
| RE-083 | JWT issuance (RS256, 15min, gateway holds private key, core holds only the public key) + refresh-token-as-hash in `user_sessions` | TODO | RE-082 |
| RE-084 | Logout + JWT blacklist in Redis (`jti`), fail-safe behavior documented if Redis is down (session revocation in DB still holds) | TODO | RE-083 |
| RE-085 | RBAC wiring into `core`'s admin endpoints (`@PreAuthorize`), activating the permission strings M7 already attached | TODO | RE-083, M7 |
| RE-086 | Login hardening — failed-attempt lockout from `security_policies`, Redis-backed rate limit/IP block | TODO | RE-082 |

## What's blocking this

`docs/decisions/open-decisions.md` #2: one app vs. separate `reward-gateway`. Current recommendation on file is a separate gateway; nothing here should start until that's confirmed, per the explicit instruction to defer LDAP/admin-auth work. Revisit this file's status once that decision is closed.
