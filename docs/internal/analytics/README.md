# Analytics

> **Audience:** Staff (ADMIN only) · **Base path:** `/api/analytics` ·
> **Source:** `src/main/java/ak/dev/khi_archive_platform/platform/api/analytics/`

This folder documents the read-only reporting surface served by the three controllers under
`platform/api/analytics/` — the seventeen `GET` endpoints behind the back-office dashboard, all of
them gated by a **class-level** `@PreAuthorize("hasRole('ADMIN')")`. It covers two different kinds
of number: *activity* reports assembled from the `UNION ALL` over the ten `*_audit_logs` tables
(who did what, when, over a date window), and *state snapshots* counted straight off the
operational tables (how many items exist, how many are trashed, how many are public right now).
It is part of `docs/internal/` — the staff back-office plus the database and operations references
— and is **never for public consumption**: nothing here is reachable without an ADMIN token, and
no endpoint in this folder appears on the anonymous or signed-in-visitor surface. Two things it
deliberately does **not** hold. First, the public/guest surface: browse, search, streaming
proxies, visitor profile and correction submission are documented in
[`../../external/`](../../external/), which requires no staff permission at all. Second, the
*write* side that produces these numbers — the content CRUD, trash, visibility and admin actions
that emit the audit rows live in [`../content/`](../content/) and [`../admin/`](../admin/), and the
table definitions they write to live in [`../database/`](../database/). This folder only reads.

## Contents

| File | What it covers | Read it when |
|---|---|---|
| [`inventory-and-maqam.md`](./inventory-and-maqam.md) | The five snapshot endpoints — `/inventory`, `/visibility`, `/maqam/overview`, `/maqam/teachers`, `/maqam/teachers/{username}` — from `InventoryAnalyticsAPI` and `MaqamAnalyticsAPI`. Live row counts (active vs trashed), the public-vs-hidden split including items inside hidden projects, and the teacher-classification progress and leaderboard. No query parameters, no date window, no `Page` envelope, no caching | A KPI tile disagrees with the row count in the items grid; you need "how many items exist / are trashed / are public **right now**"; you are wiring the maqam classification-progress panel or teacher leaderboard; or a dashboard polling these is hammering the database and you need to know what each call actually costs |
| [`team-activity.md`](./team-activity.md) | The twelve activity endpoints on `AnalyticsAPI` — `/me`, `/users`, `/users/{username}`, `/overview`, `/feed`, `/actions`, `/actions/catalog`, `/entities`, `/daily`, `/weekly`, `/monthly`, `/yearly` — plus the shared `AnalyticsFilter` contract (window, entities, actions, actor, entity code, free text), the audit-log `UNION ALL` behind them, the indexes created at startup, and which four are cached | You need who-did-what-when for a person, a period or an entity type; a dashboard number is minutes behind reality; `entities=` or `actions=` quietly returns everything instead of your selection; a request fails `400` because the resolved `from` lands after `to`; or you are choosing which single call feeds a dashboard panel |

This folder has no subdirectories.

## Start here

1. [`../02-authorization.md`](../02-authorization.md) — first, because there is no `analytics:*`
   permission. Both docs are ADMIN-role-only and the gate sits on the class, which also changes
   what a `403` body reports.
2. [`team-activity.md` → Filter semantics](./team-activity.md#filter-semantics) — the window,
   entity and action rules that eleven of the twelve activity endpoints share. Read once here
   rather than per endpoint.
3. [`team-activity.md` → Endpoints](./team-activity.md#endpoints) — the twelve reports themselves,
   with response shapes.
4. [`inventory-and-maqam.md` → Endpoints](./inventory-and-maqam.md#endpoints) — the five snapshot
   counters, and why they answer a different question than the activity reports.
5. [`../database/schema-audit.md`](../database/schema-audit.md) — the ten `*_audit_logs` tables
   the union reads, when you need to know exactly what a row records.

## Conventions

- **Authentication** — JWT; the filter reads `Authorization: Bearer <token>` first and falls back
  to the HttpOnly `khi_auth_token` cookie. See
  [`../01-conventions.md` → Authentication](../01-conventions.md#authentication).
- **Authority** — every endpoint in this folder is `hasRole('ADMIN')`, declared on the controller
  class, and cannot be delegated through a per-user grant. See
  [`../02-authorization.md` → The four roles](../02-authorization.md#the-four-roles).
- **Error envelope** — the shared `timestamp` / `status` / `error` / `message` / `details` /
  `traceId` shape, including what `details.requiredAuthority` says when a class-level gate denies
  you. See [`../01-conventions.md` → Error envelope](../01-conventions.md#error-envelope) and the
  full code catalog in [`../03-errors.md`](../03-errors.md).
- **Pagination** — only the paged slices inside `/me`, `/users/{username}` and `/feed` use the
  standard Spring `Page` envelope; the snapshot endpoints return plain objects or a plain array.
  See [`../01-conventions.md` → Paged responses](../01-conventions.md#paged-responses).
- **`{{BASE_URL}}`** — the placeholder used in every curl example in this folder; substitute your
  own host, e.g. `http://localhost:8080`. See
  [`../00-overview.md` → Calling the API](../00-overview.md#calling-the-api).
- **Timestamps** — serialized in `Asia/Baghdad`, and null fields are omitted from every response
  (`default-property-inclusion=non_null`). See [`../01-conventions.md`](../01-conventions.md).
- **Caching** — Caffeine, not Redis, and only four activity endpoints are cached, conditionally on
  the filter being default. Staleness is bounded by a 5-minute TTL and content writes do not evict.
  See [`team-activity.md` → Caching](./team-activity.md#caching) and
  [`../operations/caching.md`](../operations/caching.md).
- **These reads are themselves audited** — every one of the seventeen calls writes a row to
  `analytics_audit_logs`. See [`team-activity.md` → Audit trail](./team-activity.md#audit-trail)
  and [`inventory-and-maqam.md` → Audit trail](./inventory-and-maqam.md#audit-trail).

## Related

- [`../README.md`](../README.md) — the internal documentation index, one level up.
- [`../admin/`](../admin/) — the closest sibling folder: the administration surface whose actions
  fill these reports. Start with
  [`sessions-and-audit-logs.md`](../admin/sessions-and-audit-logs.md), the per-row read side of
  `user_audit_logs`, and [`users-and-permissions.md`](../admin/users-and-permissions.md) for
  granting the ADMIN role that opens this folder.
- [`../database/schema-audit.md`](../database/schema-audit.md) — the audit tables behind the union;
  [`../database/indexes-and-performance.md`](../database/indexes-and-performance.md) for why the
  union stays fast.
- [`../content/items.md`](../content/items.md) and [`../content/project.md`](../content/project.md)
  — the writes that move the visibility and trash counters reported here.
- [`../specialised/maqam.md`](../specialised/maqam.md) — the voting and listen-tracking workflow
  that the maqam analytics aggregate.
- [`../../external/`](../../external/) — the public and signed-in-visitor surface, which has no
  analytics endpoints at all.
