# Content APIs

> **Audience:** Staff (ADMIN / EMPLOYEE back-office) ·
> **Base paths:** `/api/audio`, `/api/video`, `/api/image`, `/api/text`, `/api/category`,
> `/api/person`, `/api/project`, `/api/items`, `/api/tags`, `/api/keywords`, `/api/khi-logo` ·
> **Parent:** [Internal documentation index](../README.md)

This folder documents the **staff write surface over archive content**: the seven content types
(audio, video, image, text, category, person, project), the merged back-office grid that spans the
four media types, the shared tag/keyword vocabularies, and the site logo. Every endpoint here
requires a token and a `<resource>:<action>` authority, and every one of them can change data —
create, update, toggle visibility, trash, restore, purge. It deliberately does **not** hold the
public side of the same records: anonymous browsing, guest search, facets and the public
`/api/guest/**` streaming proxies are documented in [`../../external/`](../../external/), and no
document in this folder describes a surface a logged-out visitor can reach. It also does not hold
the tables underneath these endpoints (see [`../database/`](../database/)), the S3 and cache
mechanics they rely on (see [`../operations/`](../operations/)), or the two domain-specific content
modules — maqam and physical-media inventory — which live in
[`../specialised/`](../specialised/). Internal docs are back-office plus database and operations,
never for public consumption.

## Contents

| File | What it covers | Read it when |
|---|---|---|
| [`audio.md`](./audio.md) | `/api/audio` — filtered list, fuzzy search, multipart create, bulk create, update, visibility toggle, trash/restore/purge, and the authenticated Range stream proxy | An audio filter returns an empty page, a multipart create is rejected, or you need to know why `audioFileUrl` is an in-API path and never the S3 URL |
| [`category.md`](./category.md) | `/api/category` — the shared classification vocabulary every project is filed under, plus its per-category `keywords` list and trash lifecycle | A category refuses to delete with `CATEGORY_IN_USE`, or you need the exact codes a project's `categoryCodes` will accept |
| [`image.md`](./image.md) | `/api/image` — CRUD, bulk create, trash lifecycle, and the ETag-capable byte proxy at `/api/image/{imageCode}/view` | A `PATCH` blanked fields you did not send (this endpoint is a whole-document update), or your grid keeps getting `304` from `/view` |
| [`items.md`](./items.md) | `/api/items` — one paged, searchable, sortable union of the four media read-caches, plus the lightweight per-row visibility `PATCH` | You are building the mixed-media back-office grid and do not want four calls, or a user with only some `:read` grants gets `403` on it |
| [`khi-logo.md`](./khi-logo.md) | `/api/khi-logo` — upload, fetch, replace and hard-delete the site branding logo. No trash, no visibility flag, no tags, no audit trail | The site logo needs replacing, or an employee gets `403` trying to upload one and you need to know why it stayed ADMIN-only |
| [`person.md`](./person.md) | `/api/person` — the authority register of people that projects and media are attributed to: names, gender/type/region enums, fuzzy dates, portrait, trash lifecycle | Deleting a person unexpectedly trashed their projects, a portrait upload is rejected, or you need the enum values a person form must offer |
| [`project.md`](./project.md) | `/api/project` — the collection entity that groups media under one code, its category and person links, the visibility cascade, and the trash model | Flipping a project public did not make its media public, or a purge needs to remove the child media and their S3 objects too |
| [`tags-and-keywords.md`](./tags-and-keywords.md) | `/api/tags/suggest`, `/api/keywords/suggest` and the ADMIN-only `/api/admin/tags`, `/api/admin/keywords` — canonicalization, global rename/merge, global delete | A typo must be renamed across every entity at once, autocomplete keeps returning a value nobody uses any more, or you are adding a new tag-bearing entity |
| [`text.md`](./text.md) | `/api/text` — book and document records (PDF, EPUB, DOCX, TXT, HTML), cover images, and the two byte proxies `/read` and `/cover` | A book upload needs both a file and a cover part, or an in-browser reader needs Range support from `/read` |
| [`video.md`](./video.md) | `/api/video` — CRUD, bulk create, visibility toggle, trash lifecycle, and the Range streaming proxy | Seeking in a video fails, or you discover a trashed video still streams (it does — the stream endpoint looks up by code without the `removedAt` filter) |

## Start here

1. [`../01-conventions.md`](../01-conventions.md) — read this first. Pagination, filter binding,
   multipart shape, trash, visibility, caching and auditing are documented once there and are
   *not* repeated in the files above.
2. [`../02-authorization.md`](../02-authorization.md) — the four roles and the full permission
   matrix, so you can predict which of these endpoints a given account can call.
3. [`./project.md`](./project.md) — the spine of the content model: projects group the media and
   carry the links to [`./person.md`](./person.md) and [`./category.md`](./category.md).
4. [`./audio.md`](./audio.md) — the worked example of a media type. Video, image and text repeat
   the same lifecycle with different fields, so read one in full and skim the rest.
5. [`./items.md`](./items.md) — the merged grid, once you understand the per-type endpoints it
   unions.

## Conventions

Know these before reading any endpoint file in this folder:

- **Authentication** — every path here needs a JWT; the filter reads `Authorization: Bearer` first
  and falls back to the HttpOnly `khi_auth_token` cookie.
  See [`../01-conventions.md#authentication`](../01-conventions.md#authentication).
- **Authority strings** — access is `<resource>:<action>`, not role names; ADMIN holds all of them
  through the role, EMPLOYEE holds a seeded, editable grant set.
  See [`../02-authorization.md`](../02-authorization.md).
- **Pagination** — list endpoints return the standard Spring `Page` envelope; `/search` endpoints
  are the exception and return a bare array.
  See [`../01-conventions.md#paged-responses`](../01-conventions.md#paged-responses).
- **Filters** — the media list endpoints bind query keys straight onto a params object, so an
  unknown parameter is silently ignored rather than rejected.
  See [`../01-conventions.md#style-b-filter-parameters-modelattribute`](../01-conventions.md#style-b-filter-parameters-modelattribute).
- **Error envelope** — one `ApiErrorResponse` shape everywhere, with a machine `error` code and an
  optional `details` payload.
  See [`../01-conventions.md#error-envelope`](../01-conventions.md#error-envelope) and the full
  catalog in [`../03-errors.md`](../03-errors.md).
- **Null omission** — `spring.jackson.default-property-inclusion=non_null`, so responses are ragged
  and a missing key means null.
  See [`../01-conventions.md#serialization-and-formats`](../01-conventions.md#serialization-and-formats).
- **Timestamps** — serialized in `Asia/Baghdad`; requests parse `yyyy-MM-dd` and
  `yyyy-MM-dd HH:mm:ss`.
  See [`../01-conventions.md#serialization-and-formats`](../01-conventions.md#serialization-and-formats).
- **Multipart create/update** — a JSON `data` part plus the file part; the JSON part is parsed by a
  separate Jackson 2 mapper, not the response mapper.
  See [`../01-conventions.md#multipart-create-and-update`](../01-conventions.md#multipart-create-and-update).
- **Trash model** — `DELETE` is a soft trash; listing the trash, restoring and purging are gated on
  the `:delete` authority and re-checked in the service, making them admin-only in practice.
  See [`../01-conventions.md#the-trash-model`](../01-conventions.md#the-trash-model).
- **Visibility** — `isPublic` on media and `isVisibleToPublic` on projects control what the external
  surface can see; the project toggle can cascade.
  See [`../01-conventions.md#the-visibility-toggle`](../01-conventions.md#the-visibility-toggle).
- **Caching** — reads are served from in-process Caffeine read-caches, and writes evict them; there
  is no Redis. See [`../01-conventions.md#caching`](../01-conventions.md#caching) and
  [`../operations/caching.md`](../operations/caching.md).
- **Media bytes** — S3 URLs are never returned to a browser; every file is proxied through an API
  endpoint. See [`../operations/storage-and-media.md`](../operations/storage-and-media.md).
- **`{{BASE_URL}}`** — the placeholder used in every curl example in this folder; substitute your
  own host, e.g. `http://localhost:8080`.
  See [`../00-overview.md#calling-the-api`](../00-overview.md#calling-the-api).

## Related

- [`../README.md`](../README.md) — the internal documentation index, one level up.
- [`../00-overview.md`](../00-overview.md) — what the internal surface is for, who uses it, and the
  full controller inventory.
- [`../../external/`](../../external/) — the public counterpart of this folder: the same records as
  an anonymous visitor sees them, via
  [`05-catalog.md`](../../external/05-catalog.md),
  [`06-media.md`](../../external/06-media.md) and
  [`07-streaming.md`](../../external/07-streaming.md).
- [`../database/schema-content.md`](../database/schema-content.md) — the tables, columns and
  constraints behind every endpoint documented here.
- [`../specialised/`](../specialised/) — the two content modules kept out of this folder:
  [`maqam.md`](../specialised/maqam.md) and
  [`physical-media.md`](../specialised/physical-media.md).
- [`../admin/sessions-and-audit-logs.md`](../admin/sessions-and-audit-logs.md) — where the audit
  rows written by the writes in this folder end up.
