# Specialised Feature APIs

> **Audience:** Staff (ADMIN, EMPLOYEE, TEACHER) · **Base paths:** `/api/maqam`,
> `/api/admin/maqam`, `/api/physical-media`, `/api/admin/physical-media` ·
> **Source:** `platform/api/maqam/**`, `platform/api/physicalmedia/**`

This folder documents the two staff domains that do **not** follow the uniform media-CRUD shape
used everywhere else in the back office: the **maqam classification workflow** (a teacher panel
votes on a song's maqam type, with the audio proxied and every listened second tracked) and the
**physical-media inventory** (the shelf register of cassettes, reels, vinyl and tapes, plus its
`.xlsx` bulk importer). Both carry mechanics no other entity has — a role-scoped visibility split,
a per-type inventory sequence, spreadsheet header binding — which is why they live apart from the
regular content docs. What this folder does **not** hold: the ordinary media and catalog CRUD
(audio, video, image, text, project, person, category, tags) — that is [`../content/`](../content/);
the table, column and index definitions behind these two features — that is
[`../database/`](../database/); and the cross-record rollups built on their audit tables — that is
[`../analytics/`](../analytics/). Everything here is internal: staff back-office plus the database
and operations context it depends on, never for public consumption. The public and anonymous
surface is documented in [`../../external/`](../../external/), and no document in this folder
describes an endpoint a visitor can reach — maqam and physical media have no guest surface at all.

## Contents

This folder has no subfolders. Two documents, either of which can be read on its own:

| File | What it covers | Read it when |
|---|---|---|
| [`maqam.md`](./maqam.md) | All 21 endpoints across `MaqamAPI`, `AdminMaqamAPI` and `MaqamStreamAPI`: records CRUD, the 1–3 teacher panel, voting, range-streamed audio, listen-session tracking, trash/restore/purge, and the `maqam_audit_logs` action list. | A TEACHER gets `403 MAQAM_PANEL_ACCESS_DENIED` on a record they can see in a list; you need to prove a teacher actually listened before their vote counted; the player is offering a download it should not; `voteStatus=none` is returning records with no teachers on them; you are wiring the assign-teachers or cast-vote screen. |
| [`physical-media.md`](./physical-media.md) | All 17 endpoints across `PhysicalMediaAPI`, `PhysicalMediaTypeAPI` and `AdminPhysicalMediaAPI`: the 29 spreadsheet columns, the `pmCode` and per-type `inventoryNumber` sequences, `.xlsx` header-name resolution and row handling, the type catalog with its nine technical defaults, and admin trash. | An import returns `unknownHeaders` or fails with "No recognisable columns found in header row"; re-uploading a workbook duplicated every row and you need to know why there is no dedupe; you need the next `Number` for a media type before saving; an EMPLOYEE gets `403` trying to trash a row or edit the type catalog; a new media type needs its defaults registered. |

## Start here

1. [Internal conventions](../01-conventions.md) — the Spring `Page` envelope, Style-B
   `@ModelAttribute` filters, the trash model and multipart create/update. Both documents assume it
   and do not repeat it.
2. [Roles, permissions and authorization](../02-authorization.md) — where `maqam:*` and
   `physical_media:*` authorities come from, and which roles are seeded with them. Neither feature
   uses a class-level `@PreAuthorize`, so every endpoint is gated on its own.
3. The feature you are working on: [`maqam.md`](./maqam.md) or
   [`physical-media.md`](./physical-media.md). Read its **Access** section before its endpoints.
4. The matching schema: [`../database/schema-maqam.md`](../database/schema-maqam.md) or
   [`../database/schema-physical-media.md`](../database/schema-physical-media.md) — the tables,
   constraints and indexes the endpoints above sit on.
5. [Inventory and maqam analytics](../analytics/inventory-and-maqam.md) — the cross-record and
   cross-teacher rollups, which are deliberately not in this folder.

## Conventions

Know these before reading either document — each links to the page that defines it.

- **Authentication** — every path here requires a valid JWT; nothing in this folder is public.
  The token arrives as `Authorization: Bearer <token>` or, for browsers, the HttpOnly
  `khi_auth_token` cookie, with the header winning when both are sent.
  See [Conventions § Authentication](../01-conventions.md#authentication).
- **Authorization** — authorities are `<resource>:<action>` strings such as `maqam:vote` and
  `physical_media:import`, declared per method. ADMIN holds all of them through the role; EMPLOYEE
  and TEACHER hold an editable seeded subset.
  See [Roles, permissions and authorization](../02-authorization.md).
- **Error envelope** — every failure returns the same `ApiErrorResponse` body
  (`timestamp`, `status`, `error`, `category`, `message`, `hint`, `path`). Maqam adds one domain code,
  `MAQAM_PANEL_ACCESS_DENIED`, meaning *you hold the permission but this record is not yours*.
  See [Errors](../03-errors.md) and [Conventions § Error envelope](../01-conventions.md#error-envelope).
- **Pagination** — list endpoints return the standard Spring `Page` envelope; only the `content[]`
  element shape is documented per endpoint. Page controls are `page` (zero-based) and `size`.
  See [Conventions § Paged responses](../01-conventions.md#paged-responses).
- **Filtering and sorting** — both list endpoints use Style-B `@ModelAttribute` filter params with
  `sortBy` / `sortDirection`, not Spring's `sort`. An unknown key is ignored rather than rejected,
  which is the usual reason a filter appears to do nothing.
  See [Conventions § Style-B filter parameters](../01-conventions.md#style-b-filter-parameters-modelattribute).
- **`{{BASE_URL}}`** — the placeholder in every curl example; substitute your own host and port
  (the server binds `${PORT:8080}`). No production hostname appears in these docs.
  See [Overview § Calling the API](../00-overview.md#calling-the-api).
- **Trash model** — `DELETE` soft-trashes; listing the trash, restoring and purging are admin-side
  and live under `/api/admin/...` for both features.
  See [Conventions § The trash model](../01-conventions.md#the-trash-model).
- **Timestamps** — `Instant` fields serialize as ISO-8601 with `spring.jackson.time-zone=Asia/Baghdad`;
  date filter parameters are plain `YYYY-MM-DD` resolved to day bounds in that zone. Null fields are
  omitted from every response.
  See [Conventions § Serialization and formats](../01-conventions.md#serialization-and-formats).
- **No read-cache** — unlike the content entities, neither maqam nor physical media is backed by a
  Caffeine `ReadCache`; both read fresh from Postgres on every request.
  See [Caching](../operations/caching.md).
- **Auditing** — each feature writes to its own `*_audit_logs` table in a `REQUIRES_NEW`
  transaction, including on read paths, so a failed operation still leaves a record.
  See [Sessions and audit logs](../admin/sessions-and-audit-logs.md).

## Related

- [Internal API index](../README.md) — the parent index for the whole staff surface.
- [Database docs](../database/README.md) — the closest sibling: the tables these two features write
  to, in particular [`schema-maqam.md`](../database/schema-maqam.md) and
  [`schema-physical-media.md`](../database/schema-physical-media.md).
- [Content APIs](../content/README.md) — the regular media, project and person CRUD these documents
  contrast themselves against.
- [Analytics](../analytics/README.md) — vote and listen-engagement rollups, plus inventory reporting.
- [Storage and media](../operations/storage-and-media.md) — the S3 proxy model behind the maqam
  stream endpoint; pre-signed URLs are never handed to a client.
