# Internal Documentation

> **Audience:** Staff clients — ADMIN, EMPLOYEE, TEACHER — plus backend engineers and operators ·
> **Scope:** `docs/internal/` · **Base path of everything documented here:** `/api/**` (token required)

This folder documents the **staff / back-office** half of the KHI Archive Platform: content CRUD for
the seven content types, the trash and visibility lifecycles, bulk create, tag and keyword
vocabularies, the maqam voting panel, the physical-media inventory and its Excel import, admin user
and permission management, warnings, correction review, audit logs and analytics — plus the
operational layer underneath them: the PostgreSQL schema and ERD, indexes, schema evolution,
caching, configuration, S3 and seeding. Every route described here requires a valid token and, in
almost every case, a specific `<resource>:<action>` authority. It deliberately does **not** document
the public surface — anonymous browsing, `/api/guest/**`, register / login / logout, a signed-in
visitor's own profile and sessions, or guest correction submission. That surface, which never
requires a staff permission, lives in [`../external/`](../external/README.md), and nothing in this
folder should be treated as safe to expose to a public client.

## Contents

| File | What it covers | Read it when |
|---|---|---|
| [`00-overview.md`](./00-overview.md) | What the internal surface is for, who uses it, how a request is authorized end to end, how to call the API, and the complete controller inventory mapping every base path to its class and class-level gate | You are new to the staff API, or you have a path like `/api/admin/warnings` in hand and need to know which controller serves it and which doc describes it |
| [`01-conventions.md`](./01-conventions.md) | The rules shared by every staff endpoint: authentication, the Spring `Page` envelope, Style-B `@ModelAttribute` filter and sort parameters, multipart create/update, the trash model, the visibility toggle, caching, auditing, bulk create, the error envelope and serialization formats | A list endpoint ignores your filter or `sort` key, a `DELETE` left the row in the database, a multipart update is rejected, or an edit you just saved does not appear in the next list response |
| [`02-authorization.md`](./02-authorization.md) | The four roles, all 66 permissions, the full permission matrix, how a request's authority set is assembled, the `@PreAuthorize` expression styles, why stateless tokens delay permission changes, and what a denial looks like on the wire | You get `403` on a call the user should be allowed to make, an admin edited someone's permissions and nothing changed, or you need to know which role holds an authority before gating a menu on it |
| [`03-errors.md`](./03-errors.md) | The staff-side error companion: which of the two `@RestControllerAdvice` classes answers a request and where they diverge, the complete custom-exception inventory, every `details` payload shape, internal-only codes, and what `traceId` is actually worth | You need to parse the `details` map of a failure, two endpoints answer the same mistake with different shapes, or you are trying to find one request in the logs |

## Subfolders

| Folder | What it covers |
|---|---|
| [`content/`](./content/README.md) | The seven content types and the vocabularies they share: [`audio.md`](./content/audio.md), [`video.md`](./content/video.md), [`image.md`](./content/image.md), [`text.md`](./content/text.md), [`project.md`](./content/project.md), [`category.md`](./content/category.md), [`person.md`](./content/person.md), the merged back-office grid [`items.md`](./content/items.md), [`tags-and-keywords.md`](./content/tags-and-keywords.md) and the branding row [`khi-logo.md`](./content/khi-logo.md) |
| [`specialised/`](./specialised/README.md) | The two domain-specific modules: [`maqam.md`](./specialised/maqam.md) — List-of-Maqam records, 1–3 teacher panels, votes and per-second listen tracking; [`physical-media.md`](./specialised/physical-media.md) — the physical artifact inventory, its type catalog and the `.xlsx` import |
| [`admin/`](./admin/README.md) | Administration: [`users-and-permissions.md`](./admin/users-and-permissions.md), [`sessions-and-audit-logs.md`](./admin/sessions-and-audit-logs.md), [`warnings.md`](./admin/warnings.md) and the correction review queue [`corrections.md`](./admin/corrections.md) |
| [`analytics/`](./analytics/README.md) | Reporting: [`team-activity.md`](./analytics/team-activity.md) — who did what and when, from the `UNION ALL` over the audit tables; [`inventory-and-maqam.md`](./analytics/inventory-and-maqam.md) — current-state counts for inventory, visibility and the maqam workflow |
| [`database/`](./database/README.md) | The PostgreSQL layer: [`erd.md`](./database/erd.md), the per-domain schema references [`schema-content.md`](./database/schema-content.md), [`schema-users-security.md`](./database/schema-users-security.md), [`schema-audit.md`](./database/schema-audit.md), [`schema-maqam.md`](./database/schema-maqam.md), [`schema-physical-media.md`](./database/schema-physical-media.md), [`schema-corrections.md`](./database/schema-corrections.md), plus [`important-fields.md`](./database/important-fields.md), [`indexes-and-performance.md`](./database/indexes-and-performance.md) and [`migrations.md`](./database/migrations.md) |
| [`operations/`](./operations/README.md) | Running the thing: [`configuration.md`](./operations/configuration.md), [`caching.md`](./operations/caching.md), [`storage-and-media.md`](./operations/storage-and-media.md) — S3 and the byte proxies — and [`seeding.md`](./operations/seeding.md) |

## Start here

1. [`00-overview.md`](./00-overview.md) — what the staff surface is, the four roles, and which
   controller owns each base path.
2. [`01-conventions.md`](./01-conventions.md) — the page envelope, filter parameters, trash and
   visibility. Endpoint docs assume you have read this and do not repeat it.
3. [`02-authorization.md`](./02-authorization.md) — look up the exact authority your screen needs
   and which roles hold it by default.
4. The endpoint doc for what you are building — for example [`content/audio.md`](./content/audio.md),
   [`content/items.md`](./content/items.md) or
   [`admin/users-and-permissions.md`](./admin/users-and-permissions.md).
5. [`03-errors.md`](./03-errors.md) — when the first call comes back with something other than `2xx`.

## Conventions

- **Auth** — every path in this folder requires a JWT; `Authorization: Bearer <token>` is read first,
  the HttpOnly cookie `khi_auth_token` is the fallback: [`01-conventions.md`](./01-conventions.md#authentication).
- **Authorities** — `<resource>:<action>` strings; ADMIN holds all of them through the role, EMPLOYEE
  and TEACHER hold an editable per-user grant set: [`02-authorization.md`](./02-authorization.md).
- **Pagination** — list endpoints return the stock Spring `Page` envelope with `page` / `size` /
  `sort`; `/search` endpoints are unpaged bare arrays capped by `limit`:
  [`01-conventions.md`](./01-conventions.md#paged-responses).
- **Filtering and sorting** — Style-B `@ModelAttribute` parameter objects, with a fixed key
  vocabulary per type: [`01-conventions.md`](./01-conventions.md#style-b-filter-parameters-modelattribute).
- **Error envelope** — one shape for every failure, produced by two advice classes; the public
  contract and `ErrorCode` catalog are in [`../external/02-errors.md`](../external/02-errors.md), the
  staff-side divergences and `details` shapes in [`03-errors.md`](./03-errors.md).
- **Trash model** — `DELETE` soft-trashes; listing the trash, restoring and purging are separate,
  admin-gated routes: [`01-conventions.md`](./01-conventions.md#the-trash-model).
- **Visibility** — public exposure is a stored `isPublic` flag toggled explicitly, with an optional
  project-to-media cascade: [`01-conventions.md`](./01-conventions.md#the-visibility-toggle).
- **`{{BASE_URL}}`** — the placeholder used in every curl example in these docs; substitute your own
  host and port: [`00-overview.md`](./00-overview.md#calling-the-api).
- **Timestamps and nulls** — serialized in `Asia/Baghdad` as `yyyy-MM-dd HH:mm:ss`, and null fields
  are omitted from responses entirely: [`01-conventions.md`](./01-conventions.md#serialization-and-formats).
- **Media bytes** — never returned as S3 URLs; every file is proxied through an authenticated API
  path: [`operations/storage-and-media.md`](./operations/storage-and-media.md).
- **Cache** — in-process Caffeine, not Redis, so a stale read is per-instance:
  [`operations/caching.md`](./operations/caching.md).
- **Schema evolution** — no Flyway or Liquibase; Hibernate `ddl-auto=update` plus `JdbcTemplate`
  initializer beans: [`database/migrations.md`](./database/migrations.md).

## Related

- [`../README.md`](../README.md) — the documentation root index for the whole project.
- [`../external/README.md`](../external/README.md) — the sibling folder: the public / anonymous and
  signed-in-visitor surface that this folder deliberately excludes.
- [`../external/00-overview.md`](../external/00-overview.md) — the public surface at a glance, for
  when you need to know what an unauthenticated client can already see.
- [`../legacy/README.md`](../legacy/README.md) — earlier documentation kept for reference.
