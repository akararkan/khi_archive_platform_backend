# Database

> **Audience:** Backend engineers, DBAs and operators · **Scope:** the physical PostgreSQL schema
> behind every endpoint · **Source of truth:** the `@Entity` classes under
> `src/main/java/ak/dev/khi_archive_platform/` plus the initializer beans in `platform/config/`
> and `user/configs/`

This folder documents the **data layer**: all 59 PostgreSQL tables, their columns, keys, enum
CHECK constraints and indexes; how those tables relate to each other; how schema change actually
happens in a project with no Flyway and no Liquibase; and how to keep a query fast. It documents
the tables, **not** the HTTP surface over them — request shapes, query parameters, authority
strings and response DTOs live in the sibling folders [`../content/`](../content/),
[`../specialised/`](../specialised/), [`../admin/`](../admin/) and [`../analytics/`](../analytics/),
while runtime behavior around the database (configuration, the Caffeine cache, S3, seed data) lives
in [`../operations/`](../operations/). Everything here is internal: staff back-office, database and
operations material, never for public consumption. The public and signed-in-visitor surface is
documented separately in [`../../external/`](../../external/), and no document in this folder
should be handed to an anonymous consumer of the API.

## Contents

| File | What it covers | Read it when |
|---|---|---|
| [`erd.md`](./erd.md) | Eight `erDiagram` views over all 59 tables, the full relationship inventory, and an alphabetical table index mapping every table to the file that documents it | You need to know what joins to what before writing a query, or whether a link is a real foreign key or a denormalized `*_id` / `*_code` snapshot that nothing stops from dangling |
| [`important-fields.md`](./important-fields.md) | The ten conventions that repeat across almost every table — business codes, soft delete, visibility, timestamps, attribution, enum columns, tag/keyword collections, media URL columns, numeric ranges, optimistic locking — plus a query cookbook | Your first query returned trashed or non-public rows, a timestamp reads three hours off, a save came back `409`, or you are unsure which of `is_public` and `is_visible_to_public` gates the row |
| [`indexes-and-performance.md`](./indexes-and-performance.md) | Every index and where it comes from (JPA `@Table(indexes=...)` vs the four startup initializers), the `pg_trgm` extension, the two-phase fuzzy search, the Hibernate batch tuning that **is written into `application.yaml` but does not take effect**, the Caffeine read-cache layer, and a slow-endpoint runbook | A list or search endpoint got slow, a `%term%` search is sequential-scanning, boot logged `Skipped index` or `Failed to create`, or you are adding a filter and need to know whether an index backs it |
| [`migrations.md`](./migrations.md) | How schema change works here — `ddl-auto=update` plus `ApplicationReadyEvent` `JdbcTemplate` initializers, the full initializer inventory, seven recipes, environment safety checks, and a Flyway adoption proposal | You are about to add, widen, rename or drop a column, add an enum value, or add an entity — or a schema change deployed and silently did not apply |
| [`schema-audit.md`](./schema-audit.md) | The twelve `*_audit_logs` tables, their shared row shape, the two guest activity tables (`guest_search_logs`, `guest_interaction_logs`), the `UNION ALL` analytics view and the CHECK-constraint re-sync | You are reconstructing who changed what, building a report over the audit union, or an audit insert failed a `CHECK (action IN ...)` after a new action was added |
| [`schema-content.md`](./schema-content.md) | The core archive tables — `audios`, `videos`, `images`, `texts`, `projects`, `categories`, `person`, `khi_logo` — and the 26 element-collection / join tables they own | You are writing SQL against a media or project row, adding a field to a content entity, or need to know which child table holds a genre, subject, contributor, color, tag or keyword |
| [`schema-corrections.md`](./schema-corrections.md) | `guest_corrections` and `guest_correction_audit_logs`: the suggested-fix workflow, its status columns, and why neither table declares a JPA association | You are auditing the correction pipeline, or you deleted a media row and want to know what happened to the corrections pointing at it |
| [`schema-maqam.md`](./schema-maqam.md) | `list_of_maqam`, `maqam_teacher_votes` and the per-play `maqam_audio_listen_sessions` log | You are querying the teacher voting panel or the listen-tracking data, or wondering why the maqam listen table grows faster than everything else |
| [`schema-physical-media.md`](./schema-physical-media.md) | `physical_media` (a 1:1 mirror of the 29-column source spreadsheet) and `physical_media_types`, the seeded type catalog, the `digitization` CHECK constraint and the `size` → `physical_size` column migration | You are mapping a spreadsheet column to a database column for the `.xlsx` import, chasing a duplicate inventory row, or an import rejected an unknown media type |
| [`schema-users-security.md`](./schema-users-security.md) | `users_tbl`, `user_permissions`, `sessions`, `token_blacklist` and `user_warnings` — the tables on the hot path of every authenticated request | You are debugging why a token is still accepted after logout, why a permission edit did or did not take effect, or which columns must never reach an API response |

## The SQL itself lives next door

This folder is the **prose**. The statements — index DDL, the enum `CHECK` re-sync, the idempotent
backfills, the diagnostics and the query cookbooks — are checked in as one runnable file,
[`../../database/khi-archive.sql`](../../database/khi-archive.sql), in twelve numbered sections,
so you can pipe a section into `psql` instead of copying out of a code fence. Each section names
the initializer bean or repository class it came from.

## Start here

1. [`important-fields.md`](./important-fields.md) — read the ten conventions first; every table in
   the schema assumes them, and getting soft delete or visibility wrong is silent.
2. [`erd.md`](./erd.md) — see how the tables connect, then use its
   [table index](./erd.md#table-index) to find the file that documents any table by name.
3. The `schema-*.md` file for your area — [content](./schema-content.md),
   [users and security](./schema-users-security.md), [audit](./schema-audit.md),
   [maqam](./schema-maqam.md), [physical media](./schema-physical-media.md) or
   [corrections](./schema-corrections.md) — for column-level detail.
4. [`migrations.md`](./migrations.md) — before you change any entity, because there is no
   migration tool to catch your mistake.
5. [`indexes-and-performance.md`](./indexes-and-performance.md) — before you ship a new filter,
   sort or search path.

## Conventions

Know these before reading anything else in this folder.

- **No Flyway, no Liquibase.** DDL comes from Hibernate `ddl-auto=update` plus boot-time
  `JdbcTemplate` initializer beans — see [`migrations.md`](./migrations.md).
- **Business codes, not ids, are the API-facing key** (`audio_code`, `project_code`, `pm_code`) —
  see [business codes](./important-fields.md#1-business-codes).
- **`DELETE` is a soft trash.** Rows carry `removed_at` / `removed_by` and stay in the table;
  every query needs the filter — see [soft delete](./important-fields.md#2-soft-delete--trash).
- **Two different visibility columns.** `is_public` on media, `is_visible_to_public` on projects —
  see [visibility](./important-fields.md#3-visibility).
- **Timestamps serialize in `Asia/Baghdad`** while the column is `timestamptz`, and the intended
  UTC binding zone is not actually in effect — see
  [timestamps and time zones](./important-fields.md#4-timestamps-and-time-zones).
- **Hibernate's batch-fetch and JDBC-batching settings are inert.** Eight keys in
  `application.yaml` sit at property paths Spring Boot does not bind, so list endpoints run N+1 on
  a cache miss and bulk inserts are unbatched — see
  [the inert keys](./indexes-and-performance.md#eight-hibernate-keys-are-inert-verified).
- **Enum columns get a generated `CHECK` that `update` never refreshes**; initializers drop and
  rebuild it at boot — see [enum-backed columns](./important-fields.md#6-enum-backed-columns).
- **`version` is an optimistic lock**; a stale write surfaces as `409` — see
  [optimistic locking](./important-fields.md#10-optimistic-locking-version).
- **Media bytes are never S3 URLs to the browser.** URL columns are internal and proxied — see
  [media URL columns](./important-fields.md#8-media-url-columns) and
  [`../operations/storage-and-media.md`](../operations/storage-and-media.md).
- **Auth model:** JWT via `Authorization: Bearer` or the HttpOnly `khi_auth_token` cookie, with
  revocation backed by `sessions` + `token_blacklist`; roles plus `<resource>:<action>`
  authorities — see [`../02-authorization.md`](../02-authorization.md) and
  [`../01-conventions.md#authentication`](../01-conventions.md#authentication).
- **Error envelope:** every failure returns the same `ApiErrorResponse` shape with an `error`
  code — see [`../03-errors.md`](../03-errors.md) and the public catalog in
  [`../../external/02-errors.md`](../../external/02-errors.md).
- **Pagination:** list endpoints return the standard Spring `Page` envelope with
  `@PageableDefault(size = 100)` — see
  [`../01-conventions.md#paged-responses`](../01-conventions.md#paged-responses).
- **`{{BASE_URL}}`** is the placeholder in every curl example (for example
  `http://localhost:8080`); no production hostname appears in these docs.
- **The cache is Caffeine, in memory — not Redis**, so a direct `UPDATE` in psql will not be
  reflected until the cache is evicted — see
  [the Caffeine read-cache layer](./indexes-and-performance.md#the-caffeine-read-cache-layer) and
  [`../operations/caching.md`](../operations/caching.md).

## Related

- [`../../database/khi-archive.sql`](../../database/khi-archive.sql) — the same material as one
  runnable file: index DDL, enum `CHECK` constraint re-sync, backfills, diagnostics and query
  cookbooks, in twelve numbered sections. Its section map is
  [`../../database/README.md`](../../database/README.md).
- [`../../diagrams/er-00-full-schema.svg`](../../diagrams/er-00-full-schema.svg) — all 59 tables
  and every relationship between them on one sheet.
  [`../../diagrams/README.md`](../../diagrams/README.md) indexes it and the other 44 diagrams,
  13 of which are per-area ER views of the tables documented here.
- [`../README.md`](../README.md) — the internal documentation index, one level up.
- [`../00-overview.md`](../00-overview.md) — the internal API overview and controller inventory.
- [`../operations/`](../operations/) — the closest sibling: how these tables behave at runtime.
  [`configuration.md`](../operations/configuration.md) for the datasource and JPA keys,
  [`caching.md`](../operations/caching.md) for the Caffeine layer in front of the tables,
  [`seeding.md`](../operations/seeding.md) for the rows created at boot, and
  [`storage-and-media.md`](../operations/storage-and-media.md) for the S3 objects the URL columns
  point at.
- [`../content/items.md`](../content/items.md) — the merged back-office list over the four media
  tables documented in [`schema-content.md`](./schema-content.md).
- [`../admin/sessions-and-audit-logs.md`](../admin/sessions-and-audit-logs.md) — the API over the
  tables in [`schema-users-security.md`](./schema-users-security.md) and
  [`schema-audit.md`](./schema-audit.md).
- [`../analytics/team-activity.md`](../analytics/team-activity.md) — the reports built on the
  `UNION ALL` across the audit tables.
- [`../../external/00-overview.md`](../../external/00-overview.md) — the public surface this
  folder deliberately excludes.
