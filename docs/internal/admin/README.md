# Admin

> **Audience:** Staff — ADMIN for almost everything here ·
> **Base paths:** `/api/admin/users`, `/api/admin/warnings`, `/api/admin/corrections`,
> `/api/auth/sessions`, `/api/warnings` ·
> **Source:** `user/api/AdminUserAPI.java`, `user/api/UserAuditLogAPI.java`,
> `user/api/SessionAPI.java`, `user/api/AdminUserWarningAPI.java`,
> `user/api/UserWarningAPI.java`,
> `platform/api/correction/AdminGuestCorrectionAPI.java`

This folder documents the surfaces that govern **people and their access**, not the archive
itself: user accounts and their roles, the per-user `<resource>:<action>` grants layered on top of
those roles, the `sessions` rows that make a stateless JWT revocable, the `user_audit_logs` trail
that records who changed which account, the in-app warnings an admin sends to a staff member, and
the review queue for corrections that visitors suggest on published records. It deliberately holds
**no content endpoints**: creating, editing, trashing or publishing an audio/video/image/text
record, a category, person or project belongs to [`../content/`](../content/); maqam voting and
physical-media inventory to [`../specialised/`](../specialised/); reporting built on these same
audit tables to [`../analytics/`](../analytics/); the table and column definitions behind them to
[`../database/`](../database/); and cache, config and storage behavior to
[`../operations/`](../operations/). Nothing in this folder is public: every path requires a token,
and the visitor-facing halves of these same features — registration, login, and submitting a
correction — are documented in [`../../external/`](../../external/).

## Contents

| File | What it covers | Read it when |
|---|---|---|
| [`corrections.md`](./corrections.md) | `/api/admin/corrections` — the `guest_corrections` review queue: paged search, forward to the record's creator, apply the suggested value to the record, resolve, reject, soft-delete, plus the status/media-type catalogs and the `guest_correction_audit_logs` trail | A visitor reported a wrong date on a record and you need it fixed and closed out; `/forward`, `/resolve` or `/reject` answers `409 CORRECTION_ALREADY_PROCESSED`; or `/apply` will not write the field you want and you need to know which fields it can write |
| [`sessions-and-audit-logs.md`](./sessions-and-audit-logs.md) | `/api/auth/sessions` — the caller's own device list, revoke one, revoke all; and `/api/admin/users/audit-logs` — paged, filtered search of `user_audit_logs`, one row by id, and the action/actor catalogs | Someone's account was used from a device they don't recognize and you need that token to stop working before it expires; or you need to answer "who deactivated this account, from which session, at what second" |
| [`users-and-permissions.md`](./users-and-permissions.md) | `/api/admin/users` — create/edit/delete accounts, change role, grant and revoke individual authorities, activate, deactivate, lock, unlock, reset failed attempts, force-logout, per-user audit history, and the role/permission catalogs | You are onboarding an employee and must decide role versus per-user grant; a user gets `403` on an endpoint you believe they can call; an account is locked out after failed logins; or you need the exact list of permission strings the server accepts |
| [`warnings.md`](./warnings.md) | `/api/admin/warnings` — issue, search, edit and revoke `INFO`/`WARNING`/`CRITICAL` warnings; `/api/warnings` — the recipient's own inbox, unread count and acknowledge | You need to tell one staff member their records are being entered wrong and have proof they read it; a revoked warning still shows in someone's list; or a "you have unread warnings" badge is wrong and you need the counting rule |

## Start here

1. [`../02-authorization.md`](../02-authorization.md) — the two authorization layers, the four
   roles, and how a principal's authority set is assembled. Everything below assumes it.
2. [`users-and-permissions.md`](./users-and-permissions.md) — accounts, roles and grants; the
   surface that decides what every other staff endpoint will allow.
3. [`sessions-and-audit-logs.md`](./sessions-and-audit-logs.md) — how access is taken away again,
   and where every change made in step 2 is recorded.
4. [`warnings.md`](./warnings.md) — the in-app channel for telling a staff member something.
5. [`corrections.md`](./corrections.md) — the moderation queue, which uses steps 2 through 4
   together: it identifies the record's creator and notifies them with a warning.

## Conventions

- **Authentication** — JWT, read from the `Authorization: Bearer <token>` header first and from
  the HttpOnly `khi_auth_token` cookie only when that header is absent; sessions are stateless.
  See [`../01-conventions.md#authentication`](../01-conventions.md#authentication).
- **Authorization** — a class-level `@PreAuthorize` and a method-level one are **not** AND-ed;
  Spring resolves the nearest annotation, so a method-level `hasAuthority(...)` replaces the
  class-level `hasRole('ADMIN')`. Every page here restates the effective gate per endpoint. See
  [`../02-authorization.md#the-two-authorization-layers`](../02-authorization.md#the-two-authorization-layers).
- **Permission strings** — authorities are `<resource>:<action>`; `remove` is a soft remove and
  `delete` is a hard delete. See
  [`../02-authorization.md#permission-matrix`](../02-authorization.md#permission-matrix).
- **Grants take effect on the next request**, not after re-login, because the JWT filter reloads
  authorities per request through a one-minute Caffeine cache that every mutating admin method
  evicts. See
  [`./users-and-permissions.md#when-a-grant-takes-effect`](./users-and-permissions.md#when-a-grant-takes-effect).
- **Error envelope** — every failure is an `ApiErrorResponse` (`timestamp`, `status`, `error`,
  `category`, `message`, `hint`, `path`, `traceId`, `details`); match on `error`, never on
  `message`. See [`../03-errors.md`](../03-errors.md).
- **Pagination** — list endpoints return the standard Spring `Page` envelope with `page`, `size`
  and `sort`; `GET /api/admin/users` is the exception and returns a bare array with no paging.
  See [`../01-conventions.md#paged-responses`](../01-conventions.md#paged-responses).
- **`{{BASE_URL}}`** — the placeholder every curl example uses for the server origin, e.g.
  `http://localhost:8080`. See
  [`../00-overview.md#calling-the-api`](../00-overview.md#calling-the-api).
- **Null fields are omitted** from responses (`spring.jackson.default-property-inclusion=non_null`),
  so an unprocessed correction or unacknowledged warning simply lacks its resolution fields. See
  [`../01-conventions.md#serialization-and-formats`](../01-conventions.md#serialization-and-formats).
- **Timestamps** serialize in `Asia/Baghdad` as `yyyy-MM-dd HH:mm:ss`. See
  [`../01-conventions.md#serialization-and-formats`](../01-conventions.md#serialization-and-formats).
- **Auditing** — mutations on this folder's surfaces write to `user_audit_logs`, and corrections
  additionally to `guest_correction_audit_logs`. See
  [`../01-conventions.md#auditing`](../01-conventions.md#auditing).

## Related

- [Internal API overview](../00-overview.md) — the parent index: the whole staff surface, the
  controller inventory, and the map of every folder under `docs/internal/`.
- [`../analytics/`](../analytics/) — the closest sibling: it reports on exactly the tables this
  folder writes. Per-user and per-day activity in
  [`analytics/team-activity.md`](../analytics/team-activity.md), inventory and maqam reporting in
  [`analytics/inventory-and-maqam.md`](../analytics/inventory-and-maqam.md).
- [`../database/schema-users-security.md`](../database/schema-users-security.md) — the
  `users_tbl`, `sessions`, `token_blacklist` and `user_warnings` tables behind these endpoints;
  [`schema-audit.md`](../database/schema-audit.md) and
  [`schema-corrections.md`](../database/schema-corrections.md) cover the audit and correction
  tables.
- [`../content/`](../content/) — the records a correction points at and the `<resource>:<action>`
  authorities granted here are spent on.
- [`../../external/08-corrections.md`](../../external/08-corrections.md) — the submission side of
  the queue reviewed in [`corrections.md`](./corrections.md).
- [`../../external/03-authentication.md`](../../external/03-authentication.md) — register, login
  and logout, the public counterpart to the account and session management documented here.
