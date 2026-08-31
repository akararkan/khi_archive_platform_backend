# Text API

> **Audience:** Staff (ADMIN / EMPLOYEE) · **Base path:** `/api/text` · **Source:** `platform/api/text/TextAPI.java`, `platform/api/text/TextStreamAPI.java`

The back-office CRUD surface for text/book records (PDF, EPUB, DOCX, TXT, HTML). Covers the
filtered list, fuzzy search, multipart create/update, bulk create, visibility toggle, the
soft-trash lifecycle (trash → restore → purge), and the two authenticated byte proxies that
serve the book file and its cover image without ever exposing an S3 URL.

## Access

| Requirement | Value |
|---|---|
| Authentication | required (JWT in the `Authorization: Bearer` header, read first, or the `khi_auth_token` HttpOnly cookie) |
| Authority | per-endpoint: `text:read`, `text:create`, `text:update`, `text:delete` |
| Roles that hold it by default | ADMIN (all four, via the role); EMPLOYEE (`text:read`, `text:create`, `text:update` — seeded per-user grants) |

`@PreAuthorize` on `TextAPI` is declared **per method**, not on the class — each endpoint
section below repeats its exact authority string.

`TextStreamAPI` carries **no** `@PreAuthorize` at all. Its two `/api/text/**` endpoints are
gated only by `SecurityConfig`'s `.requestMatchers("/api/**").authenticated()` rule, so any
signed-in account can read the bytes regardless of which `text:*` permissions it holds.

`text:remove` exists in `user/enums/Permission.java` but no text endpoint references it —
soft-delete is gated on `text:delete`.

Missing/invalid token returns `401` with `error` = `TOKEN_MISSING` (no credentials presented)
or `AUTHENTICATION_FAILED`. A valid token without the required authority returns `403`
`ACCESS_DENIED`, whose `details.requiredAuthority` echoes the `@PreAuthorize` string.

## Endpoints

| Method | Path | Authority | Purpose |
|---|---|---|---|
| `GET` | `/api/text` | `text:read` | Paged list of active records with filters + sort |
| `GET` | `/api/text/search` | `text:read` | Fuzzy multi-token search, flat array |
| `GET` | `/api/text/trash` | `text:delete` | Paged list of trashed records |
| `GET` | `/api/text/{textCode}` | `text:read` | Single active record |
| `POST` | `/api/text` | `text:create` | Create from multipart (`data` + `file` + optional `coverImage`) |
| `POST` | `/api/text/bulk` | `text:create` | Bulk create from a JSON array of pre-uploaded URLs |
| `PATCH` | `/api/text/{textCode}` | `text:update` | Update metadata and/or replace the file/cover |
| `PATCH` | `/api/text/{textCode}/visibility` | `text:update` | Toggle `isPublic` |
| `DELETE` | `/api/text/{textCode}` | `text:delete` | Soft delete — send to trash |
| `POST` | `/api/text/{textCode}/restore` | `text:delete` | Restore from trash |
| `DELETE` | `/api/text/{textCode}/purge` | `text:delete` | Permanent delete + S3 cleanup |
| `GET` | `/api/text/{textCode}/read` | _authentication only_ | Proxy the book file bytes (Range-capable) |
| `GET` | `/api/text/{textCode}/cover` | _authentication only_ | Proxy the cover image bytes (ETag-capable) |

---

### `GET /api/text`

Paged list of active (non-trashed) text records, filtered and sorted in memory over the
`texts:all` read cache.

**Authority:** `text:read`

**Query parameters — pagination**

| Name | Type | Default | Description |
|---|---|---|---|
| `page` | int | `0` | Zero-based page index |
| `size` | int | `100` | Page size (`@PageableDefault(size = 100)`) |
| `sort` | string | — | Standard Spring `Pageable` sort. Ignored by this endpoint: ordering comes from `sortBy`/`sortDirection` below, applied before slicing |

> `size` is bound twice: Spring's `Pageable` resolver reads it as the page size **and**
> `TextFilterParams.size` binds the same query parameter as the physical-size filter. One
> `size=` value feeds both.

**Query parameters — sort** (`TextFilterParams`)

| Name | Type | Default | Description |
|---|---|---|---|
| `sortBy` | string | — | Sort key; unrecognized values leave the list unsorted (cache order) |
| `sortDirection` | string | `asc` | `desc` (case-insensitive) reverses the comparator; anything else is ascending |

Accepted `sortBy` values (matched case-insensitively, synonyms grouped):

| Sorts on | Accepted values |
|---|---|
| `textCode` | `textCode`, `code` |
| `originalTitle` | `originalTitle`, `title`, `name`, `alpha`, `alphabet`, `alphabetical` |
| `author` | `author` |
| `language` | `language`, `lang` |
| `createdAt` (audit) | `createdAt`, `created`, `added`, `dateAdded`, `date_added` |
| `updatedAt` (audit) | `updatedAt`, `updated`, `modified`, `dateModified`, `date_modified` |
| `dateCreated` (metadata) | `dateCreated`, `date_created` |
| `printDate` | `printDate`, `print_date` |
| `dateModified` (metadata) | `dateModifiedField`, `dateMod` |
| `datePublished` | `datePublished`, `date_published`, `published` |
| `dateCopyrighted` | `dateCopyrighted`, `copyrighted` |
| `versionNumber` | `versionNumber`, `version` |
| `copyNumber` | `copyNumber`, `copy` |
| `pageCount` | `pageCount`, `pages`, `page_count` |

> `sortBy=dateModified` sorts on the **audit** `updatedAt` column. To sort on the record's own
> `dateModified` metadata field use `sortBy=dateModifiedField` (or `dateMod`).

Nulls sort last in ascending order for every key.

**Query parameters — categorical equals**

Case- and script-insensitive exact match (both sides normalized through `KurdishText.normalize`:
NFC, Yeh/Kaf folding, tashkeel + joiner stripping, whitespace collapse, lower-case). All are
`string`, all default to unset.

| Name | Description |
|---|---|
| `documentType` | Exact match on `documentType` |
| `script` | Exact match on `script` |
| `edition` | Exact match on `edition` |
| `volume` | Exact match on `volume` |
| `series` | Exact match on `series` |
| `textVersion` | Exact match on `textVersion` |
| `textStatus` | Exact match on `textStatus` |
| `audience` | Exact match on `audience` |
| `extension` | Exact match on `extension` |
| `orientation` | Exact match on `orientation` |
| `size` | Exact match on `size` (see the page-size collision note above) |
| `physicalDimensions` | Exact match on `physicalDimensions` |
| `language` | Exact match on `language` |
| `dialect` | Exact match on `dialect` |
| `printingHouse` | Exact match on `printingHouse` |
| `accrualMethod` | Exact match on `accrualMethod` |
| `lccClassification` | Exact match on `lccClassification` |
| `availability` | Exact match on `availability` |
| `licenseType` | Exact match on `licenseType` |
| `isbn` | Exact match on `isbn` |
| `assignmentNumber` | Exact match on `assignmentNumber` |

**Query parameters — substring contains**

Case- and script-insensitive substring match, same normalization. All `string`, all unset by
default.

| Name | Description |
|---|---|
| `description` | Substring of `description` |
| `transcription` | Substring of `transcription` |
| `author` | Substring of `author` |
| `contributors` | Substring of `contributors` |
| `provenance` | Substring of `provenance` |
| `archiveCataloging` | Substring of `archiveCataloging` |
| `physicalLabel` | Substring of `physicalLabel` |
| `locationInArchiveRoom` | Substring of `locationInArchiveRoom` |
| `note` | Substring of `note` |
| `copyright` | Substring of `copyright` |
| `rightOwner` | Substring of `rightOwner` |
| `usageRights` | Substring of `usageRights` |
| `owner` | Substring of `owner` |
| `publisher` | Substring of `publisher` |

**Query parameters — collections**

Repeat the parameter (`?subject=a&subject=b`) or pass a comma-separated value. Matching is
`trim()` + `toLowerCase(ROOT)` on both sides — plain lower-casing, **not** the
`KurdishText.normalize` folding used by the scalar filters above. A record with an empty or
absent collection never matches an active collection filter.

| Name | Type | Default | Description |
|---|---|---|---|
| `subject` | string[] | — | Wanted subjects |
| `subjectMatch` | string | `any` | `all` (case-insensitive) requires every wanted subject; any other value, including unset, means "any" |
| `genre` | string[] | — | Wanted genres |
| `genreMatch` | string | `any` | `all` (case-insensitive) requires every wanted genre |
| `tags` | string[] | — | Wanted tags |
| `tagMatch` | string | `any` | `all` (case-insensitive) requires every wanted tag |
| `keywords` | string[] | — | Wanted keywords |
| `keywordMatch` | string | `any` | `all` (case-insensitive) requires every wanted keyword |

**Query parameters — boolean and numeric ranges**

Ranges are inclusive. When a bound is set, records whose value is `null` are excluded.

| Name | Type | Default | Description |
|---|---|---|---|
| `physicalAvailability` | boolean | — | Exact match on `physicalAvailability` |
| `versionNumberMin` | int | — | Lower bound on `versionNumber` |
| `versionNumberMax` | int | — | Upper bound on `versionNumber` |
| `copyNumberMin` | int | — | Lower bound on `copyNumber` |
| `copyNumberMax` | int | — | Upper bound on `copyNumber` |
| `pageCountMin` | int | — | Lower bound on `pageCount` |
| `pageCountMax` | int | — | Upper bound on `pageCount` |

**Query parameters — date ranges**

All are `LocalDate` bound with `@DateTimeFormat(iso = DATE)`, i.e. `yyyy-MM-dd`. Bounds are
resolved to Asia/Baghdad start-of-day / end-of-day instants before comparison, and are
inclusive. When a bound is set, records with a `null` date are excluded.

| Name | Type | Default | Description |
|---|---|---|---|
| `dateCreatedFrom` | date | — | Lower bound on `dateCreated` |
| `dateCreatedTo` | date | — | Upper bound on `dateCreated` |
| `printDateFrom` | date | — | Lower bound on `printDate` |
| `printDateTo` | date | — | Upper bound on `printDate` |
| `dateModifiedFrom` | date | — | Lower bound on `dateModified` |
| `dateModifiedTo` | date | — | Upper bound on `dateModified` |
| `datePublishedFrom` | date | — | Lower bound on `datePublished` |
| `datePublishedTo` | date | — | Upper bound on `datePublished` |
| `dateCopyrightedFrom` | date | — | Lower bound on `dateCopyrighted` |
| `dateCopyrightedTo` | date | — | Upper bound on `dateCopyrighted` |
| `createdFrom` | date | — | Lower bound on the audit `createdAt` |
| `createdTo` | date | — | Upper bound on the audit `createdAt` |
| `updatedFrom` | date | — | Lower bound on the audit `updatedAt` |
| `updatedTo` | date | — | Upper bound on the audit `updatedAt` |

**Response** `200 OK`

Standard Spring `Page` envelope (see [`../01-conventions.md`](../01-conventions.md)) wrapping
`TextResponseDTO` elements. Null fields are omitted (`spring.jackson.default-property-inclusion=non_null`).

```json
{
  "content": [
    {
      "id": 41,
      "textCode": "HASAZIRA_TXT_MASTER_V1_Copy(1)_000001",
      "projectId": 7,
      "projectCode": "HASAZIRA_PRJ_000001",
      "projectName": "Hasan Zirak manuscripts",
      "personId": 3,
      "personCode": "HASAZIRA",
      "personName": "Hasan Zirak",
      "categoryCodes": ["CAT_LIT_000002"],
      "textFileUrl": "/api/text/HASAZIRA_TXT_MASTER_V1_Copy(1)_000001/read",
      "coverImageUrl": "/api/text/HASAZIRA_TXT_MASTER_V1_Copy(1)_000001/cover",
      "fileName": "diwani-hasan.pdf",
      "volumeName": "ARCHIVE_VOL_02",
      "directory": "texts/kurdish/poetry",
      "pathInExternalVolume": "/Volumes/ARCHIVE_VOL_02/texts/diwani-hasan.pdf",
      "autoPath": "texts/HASAZIRA_TXT_MASTER_V1_Copy(1)_000001",
      "originalTitle": "دیوانی حەسەن",
      "alternativeTitle": "Diwani Hasan",
      "titleInCentralKurdish": "دیوانی حەسەن زیرەک",
      "romanizedTitle": "Diwani Hasan Zirak",
      "subject": ["poetry", "folklore"],
      "genre": ["ballad"],
      "documentType": "book",
      "description": "Collected poems, first print.",
      "script": "Arabic",
      "transcription": "Diwani Hasan Zirak",
      "isbn": "978-0-00-000000-0",
      "assignmentNumber": "AN-4471",
      "edition": "1st",
      "volume": "2",
      "series": "Kurdish Poets",
      "textVersion": "MASTER",
      "versionNumber": 1,
      "copyNumber": 1,
      "fileSize": "18.4 MB",
      "extension": "pdf",
      "orientation": "portrait",
      "pageCount": 312,
      "size": "A5",
      "physicalDimensions": "14.8 x 21 cm",
      "language": "Kurdish",
      "dialect": "Sorani",
      "author": "Hasan Zirak",
      "contributors": "Editor: A. Karim",
      "printingHouse": "Sulaymaniyah Press",
      "audience": "general",
      "accrualMethod": "donation",
      "provenance": "Family archive, 2019",
      "textStatus": "complete",
      "archiveCataloging": "Shelf 12, box 4",
      "physicalAvailability": true,
      "physicalLabel": "BOX-04-12",
      "locationInArchiveRoom": "Room B, shelf 12",
      "lccClassification": "PK6501",
      "note": "Cover slightly damaged.",
      "tags": ["kurdish", "poetry"],
      "keywords": ["diwan", "ballad", "sorani"],
      "dateCreated": "1972-01-01T00:00:00Z",
      "printDate": "1974-06-01T00:00:00Z",
      "dateModified": "2024-02-11T09:15:00Z",
      "datePublished": "1974-09-01T00:00:00Z",
      "copyright": "Public domain",
      "rightOwner": "KHI Archive",
      "dateCopyrighted": "1974-09-01T00:00:00Z",
      "licenseType": "CC0",
      "usageRights": "Free for research use",
      "availability": "available",
      "owner": "KHI Archive",
      "publisher": "Sulaymaniyah Press",
      "isPublic": true,
      "projectVisibleToPublic": true,
      "createdAt": "2025-11-04T10:22:31Z",
      "updatedAt": "2026-01-18T14:03:07Z",
      "createdBy": "aram",
      "updatedBy": "shilan"
    }
  ],
  "pageable": { "pageNumber": 0, "pageSize": 100 },
  "totalElements": 1,
  "totalPages": 1,
  "number": 0,
  "size": 100,
  "first": true,
  "last": true,
  "numberOfElements": 1,
  "empty": false
}
```

Field notes for `TextResponseDTO`:

- `textFileUrl` is **always** rewritten to `/api/text/{textCode}/read`. The raw S3 URL is never
  returned.
- `coverImageUrl` is rewritten to `/api/text/{textCode}/cover`, and omitted entirely when the
  record has no stored cover.
- `personId` / `personCode` / `personName` come from the parent project's person and are
  omitted when the project has none.
- `categoryCodes` comes from the parent project's categories.
- `physicalAvailability` is always present (the entity column is a primitive `boolean`).
- `projectVisibleToPublic` mirrors the parent project's flag, defaulting to `true` when the
  project's own flag is null.
- `removedAt` / `removedBy` are omitted on active records; they are populated on trash rows.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `VALIDATION_ERROR` | A filter value cannot be bound — e.g. `pageCountMin=abc`, or `printDateFrom=1900` instead of `1900-01-01`. Filters arrive through `@ModelAttribute`, so conversion failures raise `BindException` and surface as `VALIDATION_ERROR` with `details` keyed by field, not as `TYPE_MISMATCH` |
| `401` | `TOKEN_MISSING` | No `khi_auth_token` cookie / `Authorization` header |
| `403` | `ACCESS_DENIED` | Caller lacks `text:read` |
| `500` | `DATABASE_ERROR` | Cache miss reload fails against PostgreSQL |

**Example**

```bash
curl -s "{{BASE_URL}}/api/text?documentType=book&language=Kurdish&dialect=Sorani&pageCountMin=100&sortBy=originalTitle&sortDirection=asc&page=0&size=20" \
  -H "Cookie: khi_auth_token=$TOKEN"
```

```bash
curl -s "{{BASE_URL}}/api/text?subject=poetry&subject=folklore&subjectMatch=all&printDateFrom=1900-01-01&printDateTo=1999-12-31" \
  -H "Cookie: khi_auth_token=$TOKEN"
```

**Notes**

- Served from the Caffeine cache `texts:all` (`maximumSize=1`, 10-minute TTL) — one entry
  holding the full active list. With no filter parameters the cached list is passed straight to
  the paginator.
- Filtering and sorting run in memory over that list in a single linear pass; they never hit the
  database.
- Audits `LIST` to `text_audit_logs` with `details` recording page, size, returned count, total,
  and `filtered=true` when any filter parameter was present.

---

### `GET /api/text/search`

Two-phase fuzzy search across the `texts` table and its `text_subjects`, `text_genres`,
`text_tags` and `text_keywords` child tables. Multi-token AND semantics.

**Authority:** `text:read`

**Query parameters**

| Name | Type | Default | Description |
|---|---|---|---|
| `q` | string | — | **Required.** Free-text query; trimmed then tokenized |
| `limit` | int | `20` | Max hits. Values `<= 0` or absent fall back to `20`; capped at `100` |

**Response** `200 OK`

A flat JSON array of `TextResponseDTO` (not a `Page`), element shape identical to
`GET /api/text`. Returns `[]` when `q` is blank or tokenizes to nothing.

```json
[
  {
    "id": 41,
    "textCode": "HASAZIRA_TXT_MASTER_V1_Copy(1)_000001",
    "originalTitle": "دیوانی حەسەن",
    "author": "Hasan Zirak",
    "textFileUrl": "/api/text/HASAZIRA_TXT_MASTER_V1_Copy(1)_000001/read",
    "isPublic": true
  }
]
```

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `MISSING_PARAMETER` | `q` was not supplied |
| `400` | `TYPE_MISMATCH` | `limit` is not an integer |
| `401` | `TOKEN_MISSING` | No token presented |
| `403` | `ACCESS_DENIED` | Caller lacks `text:read` |
| `500` | `DATABASE_ERROR` | The native search query fails |
| `504` | `TIMEOUT` | The native search query times out |

**Example**

```bash
curl -s "{{BASE_URL}}/api/text/search?q=hasan%20zirak&limit=25" \
  -H "Cookie: khi_auth_token=$TOKEN"
```

**Notes**

- Runs a native query built by `MediaSearchSqlBuilder` against a 2 000-row prefilter, then
  ranks and truncates to `limit`. It reads the database directly and bypasses the `texts:all`
  cache.
- Prefix-ranked columns: `original_title`, `alternative_title`, `title_in_central_kurdish`,
  `romanized_title`, `text_code`, `file_name`, `author`, `isbn`.
- Audits `SEARCH` with the trimmed query, the token list, the effective limit, and the hit count.
- Trashed records are excluded. `MediaSearchSqlBuilder` emits `WHERE e.removed_at IS NULL` twice —
  once in every per-token candidate CTE and again in the ranking `SELECT` — so a soft-deleted
  record can never surface here.

---

### `GET /api/text/trash`

Paged list of trashed (soft-deleted) text records.

**Authority:** `text:delete`

The service additionally calls `requireAdminRole(...)`, which re-checks the caller holds
`text:delete` and throws `AccessDeniedException` otherwise.

**Query parameters**

| Name | Type | Default | Description |
|---|---|---|---|
| `page` | int | `0` | Zero-based page index |
| `size` | int | `100` | Page size (`@PageableDefault(size = 100)`) |
| `sort` | string | — | Standard Spring `Pageable` sort; the list is sliced in repository order |

No `TextFilterParams` binding on this endpoint — filters are not available on trash.

**Response** `200 OK`

Standard `Page` envelope of `TextResponseDTO`. Trash rows carry `removedAt` and `removedBy`:

```json
{
  "content": [
    {
      "id": 41,
      "textCode": "HASAZIRA_TXT_MASTER_V1_Copy(1)_000001",
      "originalTitle": "دیوانی حەسەن",
      "textFileUrl": "/api/text/HASAZIRA_TXT_MASTER_V1_Copy(1)_000001/read",
      "isPublic": true,
      "projectVisibleToPublic": true,
      "createdAt": "2025-11-04T10:22:31Z",
      "updatedAt": "2026-01-18T14:03:07Z",
      "removedAt": "2026-02-02T08:41:00Z",
      "createdBy": "aram",
      "updatedBy": "shilan",
      "removedBy": "admin"
    }
  ],
  "totalElements": 1,
  "totalPages": 1,
  "number": 0,
  "size": 100,
  "first": true,
  "last": true,
  "numberOfElements": 1,
  "empty": false
}
```

**Errors**

| Status | `error` code | When |
|---|---|---|
| `401` | `TOKEN_MISSING` | No token presented |
| `403` | `ACCESS_DENIED` | Caller lacks `text:delete` |
| `500` | `DATABASE_ERROR` | Trash query fails |

**Example**

```bash
curl -s "{{BASE_URL}}/api/text/trash?page=0&size=50" \
  -H "Cookie: khi_auth_token=$TOKEN"
```

**Notes**

- Reads `findAllByRemovedAtIsNotNull()` straight from the database — the `texts:all` cache holds
  only active records.
- Audits `LIST` with page, size, returned count and total.

---

### `GET /api/text/{textCode}`

Fetch one active text record by its business code.

**Authority:** `text:read`

**Path parameters**

| Name | Type | Description |
|---|---|---|
| `textCode` | string | Business key, e.g. `HASAZIRA_TXT_MASTER_V1_Copy(1)_000001`. Trimmed before lookup |

**Response** `200 OK`

A single `TextResponseDTO` — same shape as one `content[]` element of `GET /api/text`.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `TEXT_VALIDATION_ERROR` | `textCode` resolves to blank after trimming |
| `401` | `TOKEN_MISSING` | No token presented |
| `403` | `ACCESS_DENIED` | Caller lacks `text:read` |
| `404` | `TEXT_NOT_FOUND` | No record with that code, or the record is in the trash |
| `500` | `DATABASE_ERROR` | Lookup fails |

**Example**

```bash
curl -s "{{BASE_URL}}/api/text/HASAZIRA_TXT_MASTER_V1_Copy(1)_000001" \
  -H "Cookie: khi_auth_token=$TOKEN"
```

**Notes**

- Looks up `findByTextCodeAndRemovedAtIsNull` — trashed records are invisible here.
- Audits `READ` with `details` = `Read text record`.

---

### `POST /api/text`

Create one text record from a multipart upload. Consumes `multipart/form-data`, produces
`application/json`.

**Authority:** `text:create`

**Request parts**

| Part | Required | Content | Description |
|---|---|---|---|
| `data` | yes | JSON string | `TextCreateRequestDTO` payload, parsed and validated in the controller |
| `file` | yes | file | The book file. Uploaded to S3 under `texts/{textCode}` |
| `coverImage` | no | file | Cover image. Uploaded to S3 under `texts/covers/{textCode}` |

**Request body** — the `data` part (`TextCreateRequestDTO`; unknown properties are ignored)

| Field | Type | Required | Notes |
|---|---|---|---|
| `projectCode` | string | yes | Must resolve to a non-trashed project |
| `textVersion` | string | yes | One of `RAW`, `MASTER`, `RESTORED`, `ARCHIVE`, `ORIGINAL`, `DIGITIZED`, `PROFESSIONAL` (upper-cased before storage) |
| `versionNumber` | int | yes | `>= 1` |
| `copyNumber` | int | yes | `>= 1` |
| `fileName` | string | no | Falls back to the uploaded file's original filename when blank |
| `volumeName` | string | no | |
| `directory` | string | no | |
| `pathInExternalVolume` | string | no | |
| `autoPath` | string | no | |
| `originalTitle` | string | no | |
| `alternativeTitle` | string | no | |
| `titleInCentralKurdish` | string | no | |
| `romanizedTitle` | string | no | |
| `subject` | string[] | no | |
| `genre` | string[] | no | |
| `documentType` | string | no | |
| `description` | string | no | |
| `script` | string | no | |
| `transcription` | string | no | |
| `isbn` | string | no | |
| `assignmentNumber` | string | no | |
| `edition` | string | no | |
| `volume` | string | no | |
| `series` | string | no | |
| `fileSize` | string | no | Free text, not computed by the server |
| `extension` | string | no | |
| `orientation` | string | no | |
| `pageCount` | int | no | |
| `size` | string | no | |
| `physicalDimensions` | string | no | |
| `language` | string | no | |
| `dialect` | string | no | |
| `author` | string | no | |
| `contributors` | string | no | |
| `printingHouse` | string | no | |
| `audience` | string | no | |
| `accrualMethod` | string | no | |
| `provenance` | string | no | |
| `textStatus` | string | no | |
| `archiveCataloging` | string | no | |
| `physicalAvailability` | boolean | no | Applied only when non-null |
| `physicalLabel` | string | no | |
| `locationInArchiveRoom` | string | no | |
| `lccClassification` | string | no | |
| `note` | string | no | |
| `tags` | string[] | no | Canonicalized and deduplicated on save |
| `keywords` | string[] | no | Canonicalized and deduplicated on save |
| `dateCreated` | instant | no | |
| `printDate` | instant | no | |
| `dateModified` | instant | no | |
| `datePublished` | instant | no | |
| `copyright` | string | no | |
| `rightOwner` | string | no | |
| `dateCopyrighted` | instant | no | |
| `licenseType` | string | no | |
| `usageRights` | string | no | |
| `availability` | string | no | |
| `owner` | string | no | |
| `publisher` | string | no | |
| `coverImageUrl` | string | no | Pre-existing cover URL for imports/external assets. Overwritten when a `coverImage` part is uploaded |

`textCode` is **not** accepted — it is generated as
`{PARENT}_TXT_{TEXTVERSION}_V{versionNumber}_Copy({copyNumber})_{000000 sequence}`, where
`{PARENT}` is the project's person code (upper-cased) or the project's untitled-media prefix.

```json
{
  "projectCode": "HASAZIRA_PRJ_000001",
  "textVersion": "MASTER",
  "versionNumber": 1,
  "copyNumber": 1,
  "originalTitle": "دیوانی حەسەن",
  "romanizedTitle": "Diwani Hasan Zirak",
  "documentType": "book",
  "language": "Kurdish",
  "dialect": "Sorani",
  "author": "Hasan Zirak",
  "pageCount": 312,
  "subject": ["poetry", "folklore"],
  "genre": ["ballad"],
  "tags": ["kurdish", "poetry"],
  "keywords": ["diwan", "sorani"],
  "physicalAvailability": true,
  "printDate": "1974-06-01T00:00:00Z"
}
```

**Response** `200 OK`

The created `TextResponseDTO` (same shape as `GET /api/text/{textCode}`).

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `TEXT_VALIDATION_ERROR` | `data` blank or unparsable; bean validation failed (`details` keyed by `projectCodePresent`, `textVersionValid`, `versionNumberValid`, `copyNumberValid`); `file` empty; `projectCode` blank; `textVersion` outside the allowed set; `versionNumber`/`copyNumber` below 1 |
| `400` | `MISSING_REQUEST_PART` | The `data` or `file` part is absent from the multipart body |
| `400` | `BAD_REQUEST` | The multipart envelope itself could not be parsed |
| `401` | `TOKEN_MISSING` | No token presented |
| `403` | `ACCESS_DENIED` | Caller lacks `text:create` |
| `404` | `PROJECT_NOT_FOUND` | `projectCode` does not match an active project |
| `409` | `TEXT_ALREADY_EXISTS` | The generated text code is already taken |
| `409` | `CONFLICT` | A database constraint blocked the insert |
| `413` | `UPLOAD_TOO_LARGE` | Upload exceeds the configured limit (`max-file-size: 5GB`, `max-request-size: 6GB`) |
| `415` | `UNSUPPORTED_MEDIA_TYPE` | Request was not sent as `multipart/form-data` |
| `500` | `INTERNAL_SERVER_ERROR` | The S3 upload failed (`UserStorageException` has no dedicated handler in `ApiExceptionHandler`) |
| `500` | `DATABASE_ERROR` | The insert failed |

**Example**

```bash
curl -s -X POST "{{BASE_URL}}/api/text" \
  -H "Cookie: khi_auth_token=$TOKEN" \
  -F 'data={"projectCode":"HASAZIRA_PRJ_000001","textVersion":"MASTER","versionNumber":1,"copyNumber":1,"originalTitle":"Diwani Hasan","documentType":"book","language":"Kurdish"};type=application/json' \
  -F "file=@./diwani-hasan.pdf" \
  -F "coverImage=@./diwani-cover.jpg"
```

**Notes**

- Concurrent creates within the same project serialize on a `CodeGenLock` keyed
  `text-code:{projectId}` so the count-based sequence cannot collide.
- Evicts three Caffeine caches on success: `texts:all`, `tags:suggest`, `keywords:suggest`.
- Audits `CREATE` with the generated code, project code, S3 file URL and cover URL.

---

### `POST /api/text/bulk`

Bulk-create text records from a JSON array. Each entry carries its own pre-uploaded
`textFileUrl` — there is no multipart upload. One transaction, one audit summary.

**Authority:** `text:create`

**Request body** — a JSON array of `TextBulkCreateRequestDTO`

Same fields as `TextCreateRequestDTO` (table above) plus:

| Field | Type | Required | Notes |
|---|---|---|---|
| `textFileUrl` | string | no | Pre-uploaded S3 or external URL. Stored verbatim; may be null/blank |

The controller binds the body **without** `@Valid`, so the DTO's `@AssertTrue` constraints do
not produce a `400`. Instead the service skips any entry that fails a check:

| Skip reason |
|---|
| Entry is `null` |
| `projectCode` is null or blank |
| `textVersion` is null, or not one of `RAW`, `MASTER`, `RESTORED`, `ARCHIVE`, `ORIGINAL`, `DIGITIZED`, `PROFESSIONAL` |
| `versionNumber` is null or `< 1` |
| `copyNumber` is null or `< 1` |
| `projectCode` does not resolve to an active project |
| The generated text code already exists |

```json
[
  {
    "projectCode": "HASAZIRA_PRJ_000001",
    "textVersion": "ARCHIVE",
    "versionNumber": 1,
    "copyNumber": 1,
    "originalTitle": "Diwani Hasan, vol. 1",
    "textFileUrl": "https://s3.example.invalid/khi/texts/imported/vol1.pdf",
    "coverImageUrl": "https://s3.example.invalid/khi/texts/covers/imported/vol1.jpg",
    "language": "Kurdish",
    "pageCount": 210
  },
  {
    "projectCode": "HASAZIRA_PRJ_000001",
    "textVersion": "ARCHIVE",
    "versionNumber": 1,
    "copyNumber": 2,
    "originalTitle": "Diwani Hasan, vol. 2",
    "textFileUrl": "https://s3.example.invalid/khi/texts/imported/vol2.pdf",
    "language": "Kurdish",
    "pageCount": 198
  }
]
```

**Response** `200 OK` — `TextService.BulkCreateResult`

| Field | Type | Description |
|---|---|---|
| `requested` | int | Number of entries in the submitted array |
| `inserted` | int | Number of rows actually saved |
| `skipped` | int | Number of entries rejected by the checks above |
| `elapsedMs` | long | Wall-clock duration of the bulk run |

```json
{
  "requested": 2,
  "inserted": 2,
  "skipped": 0,
  "elapsedMs": 214
}
```

An empty or null array short-circuits to `{"requested":0,"inserted":0,"skipped":0,"elapsedMs":0}`
without writing an audit row.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `JSON_PARSE_ERROR` | Body is not valid JSON or a field has the wrong type |
| `401` | `TOKEN_MISSING` | No token presented |
| `403` | `ACCESS_DENIED` | Caller lacks `text:create` |
| `409` | `CONFLICT` | A database constraint blocked the batch insert |
| `415` | `UNSUPPORTED_MEDIA_TYPE` | Body was not sent as `application/json` |
| `500` | `DATABASE_ERROR` | The batch insert failed |

**Example**

```bash
curl -s -X POST "{{BASE_URL}}/api/text/bulk" \
  -H "Cookie: khi_auth_token=$TOKEN" \
  -H "Content-Type: application/json" \
  -d @bulk-texts.json
```

**Notes**

- Codes are generated with an in-memory per-project counter seeded from
  `countByProject(project) + 1`, so a batch numbers consecutively; the `CodeGenLock` is taken
  once per project.
- `createdAt`/`updatedAt`/`createdBy`/`updatedBy` are stamped from a single `Instant.now()` and
  the caller's username for the whole batch.
- Evicts `texts:all`, `tags:suggest` and `keywords:suggest`.
- Audits a single `CREATE` row with no `textId`/`textCode`, `details` =
  `Bulk created texts: requested=… inserted=… skipped=… elapsedMs=…`.

---

### `PATCH /api/text/{textCode}`

Update an active text record's metadata, and optionally replace the book file and/or cover
image. Consumes `multipart/form-data`, produces `application/json`.

**Authority:** `text:update`

**Path parameters**

| Name | Type | Description |
|---|---|---|
| `textCode` | string | Business key of an active record. Trimmed before lookup |

**Request parts**

| Part | Required | Content | Description |
|---|---|---|---|
| `data` | yes | JSON string | `TextUpdateRequestDTO` payload |
| `file` | no | file | Replacement book file. Uploaded to `texts/{textCode}`; the previous S3 object is deleted when the URL changed |
| `coverImage` | no | file | Replacement cover. Uploaded to `texts/covers/{textCode}`; the previous S3 object is deleted when the URL changed |

**Request body** — the `data` part (`TextUpdateRequestDTO`)

`TextUpdateRequestDTO` extends `TextBaseRequestDTO` and adds **no** validation constraints, so
every field is optional as far as bean validation is concerned. Field list is identical to the
create table above.

Merge semantics are not uniform, and matter:

| Field group | Behavior |
|---|---|
| `projectCode` | Never copied onto the entity. If present and different from the record's current project code, the request is rejected — the project cannot be changed after creation |
| `physicalAvailability`, `textVersion`, `tags`, `keywords` | Applied only when non-null; omitting them preserves the stored value. `textVersion` is upper-cased; `tags`/`keywords` are canonicalized and deduplicated |
| Every other field | Copied wholesale by `BeanUtils.copyProperties` — a field omitted from `data` is written as `null` and clears the stored value |

Send the complete metadata object on every PATCH unless you intend to clear the omitted fields.

```json
{
  "originalTitle": "دیوانی حەسەن زیرەک",
  "romanizedTitle": "Diwani Hasan Zirak",
  "documentType": "book",
  "language": "Kurdish",
  "dialect": "Sorani",
  "author": "Hasan Zirak",
  "pageCount": 318,
  "note": "Re-scanned at 600 dpi.",
  "tags": ["kurdish", "poetry", "rescanned"]
}
```

**Response** `200 OK`

The updated `TextResponseDTO`.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `TEXT_VALIDATION_ERROR` | `data` blank or unparsable; `textCode` blank after trimming; `projectCode` in the body differs from the record's current project |
| `400` | `MISSING_REQUEST_PART` | The `data` part is absent |
| `400` | `BAD_REQUEST` | The multipart envelope could not be parsed |
| `401` | `TOKEN_MISSING` | No token presented |
| `403` | `ACCESS_DENIED` | Caller lacks `text:update` |
| `404` | `TEXT_NOT_FOUND` | No active record with that code (trashed records are not updatable) |
| `409` | `STALE_VERSION` | Another writer bumped the record's `@Version` mid-edit |
| `409` | `CONFLICT` | A database constraint blocked the update |
| `413` | `UPLOAD_TOO_LARGE` | Replacement upload exceeds the configured limit |
| `415` | `UNSUPPORTED_MEDIA_TYPE` | Request was not sent as `multipart/form-data` |
| `500` | `INTERNAL_SERVER_ERROR` | The S3 upload failed |
| `500` | `DATABASE_ERROR` | The update failed |

**Example**

```bash
curl -s -X PATCH "{{BASE_URL}}/api/text/HASAZIRA_TXT_MASTER_V1_Copy(1)_000001" \
  -H "Cookie: khi_auth_token=$TOKEN" \
  -F 'data={"originalTitle":"Diwani Hasan Zirak","language":"Kurdish","pageCount":318};type=application/json' \
  -F "file=@./diwani-hasan-600dpi.pdf"
```

**Notes**

- When `file` is uploaded and `fileName` is blank in `data`, the record's `fileName` is set to
  the uploaded file's original filename.
- The superseded S3 object is deleted only when the new upload produced a different URL **and**
  `S3Service.isOurS3Url(...)` recognizes the old one; externally hosted URLs are left in place.
- Evicts `texts:all`, `tags:suggest` and `keywords:suggest`.
- Audits `UPDATE` with a `field: before -> after` diff across the full metadata set (including
  `textFileUrl` and `coverImageUrl`), or `Updated text record (no field changes detected)`.

---

### `PATCH /api/text/{textCode}/visibility`

Lightweight `isPublic` toggle for the list-row visibility switch. Consumes and produces
`application/json`.

**Authority:** `text:update` — deliberately the same authority as the full update, so anyone
allowed to edit a record can flip its public flag.

**Path parameters**

| Name | Type | Description |
|---|---|---|
| `textCode` | string | Business key of an active record |

**Request body** — `VisibilityUpdateRequest`

| Field | Type | Required | Description |
|---|---|---|---|
| `isPublic` | boolean | yes | `@NotNull` — a missing or null value is a validation error, never a silent `false` |

```json
{ "isPublic": false }
```

**Response** `200 OK`

The `TextResponseDTO` with the new `isPublic` value.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `VALIDATION_ERROR` | `isPublic` missing or null — `details.isPublic` = `isPublic is required` |
| `400` | `JSON_PARSE_ERROR` | Body is not valid JSON |
| `400` | `TEXT_VALIDATION_ERROR` | `textCode` blank after trimming |
| `401` | `TOKEN_MISSING` | No token presented |
| `403` | `ACCESS_DENIED` | Caller lacks `text:update` |
| `404` | `TEXT_NOT_FOUND` | No active record with that code — trashed records are not silently resurrected |
| `409` | `STALE_VERSION` | Concurrent edit detected via `@Version` |
| `415` | `UNSUPPORTED_MEDIA_TYPE` | Body was not sent as `application/json` |
| `500` | `DATABASE_ERROR` | The update failed |

**Example**

```bash
curl -s -X PATCH "{{BASE_URL}}/api/text/HASAZIRA_TXT_MASTER_V1_Copy(1)_000001/visibility" \
  -H "Cookie: khi_auth_token=$TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"isPublic": false}'
```

**Notes**

- Idempotent: setting the flag to its current value returns `200` with the record unchanged and
  performs no save, no `@Version` bump, no cache eviction and no audit row.
- On a real change: evicts `texts:all`, `tags:suggest`, `keywords:suggest`, and audits `UPDATE`
  with `details` = `Updated text record: isPublic: {previous} -> {new}`.
- `isPublic` controls visibility on the guest APIs only; staff always see the record.

---

### `DELETE /api/text/{textCode}`

Soft delete — sends the record to the trash. The S3 objects are preserved so the record can be
restored.

**Authority:** `text:delete`

**Path parameters**

| Name | Type | Description |
|---|---|---|
| `textCode` | string | Business key of an active record |

**Response** `204 No Content` — empty body.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `TEXT_VALIDATION_ERROR` | `textCode` blank after trimming |
| `401` | `TOKEN_MISSING` | No token presented |
| `403` | `ACCESS_DENIED` | Caller lacks `text:delete` |
| `404` | `TEXT_NOT_FOUND` | No active record with that code (already-trashed records return 404) |
| `409` | `STALE_VERSION` | Concurrent edit detected via `@Version` |
| `500` | `DATABASE_ERROR` | The update failed |

**Example**

```bash
curl -s -X DELETE "{{BASE_URL}}/api/text/HASAZIRA_TXT_MASTER_V1_Copy(1)_000001" \
  -H "Cookie: khi_auth_token=$TOKEN" \
  -i
```

**Notes**

- Sets `removedAt = now()` and `removedBy = <caller username>` (or `anonymous` when the
  `Authentication` is null).
- Evicts `texts:all`, `tags:suggest`, `keywords:suggest`.
- Audits `DELETE` with `details` = `Sent text record to trash`.

---

### `POST /api/text/{textCode}/restore`

Restore a trashed text record.

**Authority:** `text:delete`

The service additionally calls `requireAdminRole(...)`, which re-checks `text:delete` on the
`Authentication` and throws `AccessDeniedException` when absent or when there is no
authentication at all.

**Path parameters**

| Name | Type | Description |
|---|---|---|
| `textCode` | string | Business key. Looked up with `findByTextCode`, so trashed records resolve |

**Response** `200 OK`

The restored `TextResponseDTO`, with `removedAt`/`removedBy` cleared (and therefore omitted
from the JSON).

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `TEXT_VALIDATION_ERROR` | `textCode` blank after trimming; the record is not in trash; the parent project is itself in trash (restore the project first) |
| `401` | `TOKEN_MISSING` | No token presented |
| `403` | `ACCESS_DENIED` | Caller lacks `text:delete` |
| `404` | `TEXT_NOT_FOUND` | No record with that code at all |
| `409` | `STALE_VERSION` | Concurrent edit detected via `@Version` |
| `500` | `DATABASE_ERROR` | The update failed |

**Example**

```bash
curl -s -X POST "{{BASE_URL}}/api/text/HASAZIRA_TXT_MASTER_V1_Copy(1)_000001/restore" \
  -H "Cookie: khi_auth_token=$TOKEN"
```

**Notes**

- Clears `removedAt`/`removedBy` and stamps `updatedAt`/`updatedBy`.
- Evicts `texts:all`, `tags:suggest`, `keywords:suggest`.
- Audits `RESTORE` with `details` = `Restored text record from trash`.

---

### `DELETE /api/text/{textCode}/purge`

Permanently delete a trashed record, including its S3 book file and cover image. Irreversible.

**Authority:** `text:delete`

The service additionally calls `requireAdminRole(...)` (same re-check as restore).

**Path parameters**

| Name | Type | Description |
|---|---|---|
| `textCode` | string | Business key. Looked up with `findByTextCode`, so trashed records resolve |

**Response** `204 No Content` — empty body.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `TEXT_VALIDATION_ERROR` | `textCode` blank after trimming; the record is not in trash (`Text must be in trash before permanent deletion. Trash it first.`) |
| `401` | `TOKEN_MISSING` | No token presented |
| `403` | `ACCESS_DENIED` | Caller lacks `text:delete` |
| `404` | `TEXT_NOT_FOUND` | No record with that code at all |
| `409` | `CONFLICT` | A foreign-key constraint blocked the row deletion |
| `500` | `INTERNAL_SERVER_ERROR` | The S3 delete failed |
| `500` | `DATABASE_ERROR` | The row deletion failed |

**Example**

```bash
curl -s -X DELETE "{{BASE_URL}}/api/text/HASAZIRA_TXT_MASTER_V1_Copy(1)_000001/purge" \
  -H "Cookie: khi_auth_token=$TOKEN" \
  -i
```

**Notes**

- The `PURGE` audit row is written **before** the entity is deleted, so the log retains the
  record's id, code, title and project linkage.
- Only URLs that `S3Service.isOurS3Url(...)` recognizes are deleted from storage — externally
  hosted `textFileUrl`/`coverImageUrl` values (e.g. from a bulk import) are left alone.
- Evicts `texts:all`, `tags:suggest`, `keywords:suggest`.

---

### `GET /api/text/{textCode}/read`

Proxy the book file bytes through the API. The S3 URL is never sent to the browser. Supports
HTTP Range so PDF.js and browser PDF viewers can fetch individual pages.

**Authority:** none declared. `TextStreamAPI` has no `@PreAuthorize`; the endpoint is protected
only by `SecurityConfig`'s `.requestMatchers("/api/**").authenticated()`. Any signed-in account
can read it, including accounts with no `text:*` permission.

**Path parameters**

| Name | Type | Description |
|---|---|---|
| `textCode` | string | Business key. Looked up with `findByTextCode` — **trashed records are still served here** |

**Request headers**

| Name | Required | Description |
|---|---|---|
| `Range` | no | `bytes=start-end`. Either bound may be omitted. Out-of-range values are clamped to the object size; a malformed or non-`bytes=` value silently falls back to the whole file |

**Response** `200 OK` (no `Range` header) or `206 Partial Content` (any non-blank `Range`)

Body is the raw file. Response headers:

| Header | Value |
|---|---|
| `Content-Type` | Derived from the stored URL: `.pdf` → `application/pdf`, `.epub` → `application/epub+zip`, `.docx` → `application/vnd.openxmlformats-officedocument.wordprocessingml.document`, `.doc` → `application/msword`, `.txt` → `text/plain`, `.html`/`.htm` → `text/html`, otherwise `application/octet-stream` |
| `Accept-Ranges` | `bytes` |
| `Content-Range` | `bytes {start}-{end}/{total}` — only on `206` responses |
| `Content-Length` | Length of the returned slice |
| `Content-Disposition` | `inline; filename="<ascii fallback>"; filename*=UTF-8''<percent-encoded>` — RFC 5987, so Kurdish/Arabic filenames survive. Falls back to `book-{textCode}.{subtype}` when `fileName` is blank or sanitizes to nothing |
| `Cache-Control` | `no-store, private` |
| `X-Content-Type-Options` | `nosniff` |

**Errors**

Errors from this controller are raised as `ResponseStatusException` and translated by
`ApiExceptionHandler.handleResponseStatus`, so they carry the generic codes below rather than
`TEXT_NOT_FOUND`.

| Status | `error` code | When |
|---|---|---|
| `401` | `TOKEN_MISSING` | No token presented |
| `404` | `NOT_FOUND` | No record with that code (`message` = `Text not found`) |
| `404` | `NOT_FOUND` | The record has no `textFileUrl` (`message` = `Book file not available`) |
| `404` | `NOT_FOUND` | The S3 object is gone — the SDK reported 404 (`message` = `Book file not available for {textCode}`) |
| `500` | `INTERNAL_SERVER_ERROR` | The S3 key could not be extracted from the stored URL, or the byte read failed (`message` = `Book file not available` / `Failed to stream book file`) |

**Example**

```bash
# Whole file
curl -s "{{BASE_URL}}/api/text/HASAZIRA_TXT_MASTER_V1_Copy(1)_000001/read" \
  -H "Cookie: khi_auth_token=$TOKEN" \
  -o diwani.pdf

# First 64 KB only — expect 206 Partial Content
curl -s "{{BASE_URL}}/api/text/HASAZIRA_TXT_MASTER_V1_Copy(1)_000001/read" \
  -H "Cookie: khi_auth_token=$TOKEN" \
  -H "Range: bytes=0-65535" \
  -D - -o /dev/null
```

**Notes**

- Writes **no** audit row and touches **no** cache — it reads `TextRepository` directly.
- The requested byte range is buffered fully into a `byte[]` before the response is written.
- The public counterpart `GET /api/guest/text/{textCode}/read` lives in the same controller,
  filters on `removedAt IS NULL`, and sends `Cache-Control: public, max-age=3600`. It belongs to
  the external documentation set.

---

### `GET /api/text/{textCode}/cover`

Proxy the cover image bytes through the API.

**Authority:** none declared — same authentication-only gating as `/read` above.

**Path parameters**

| Name | Type | Description |
|---|---|---|
| `textCode` | string | Business key. Looked up with `findByTextCode` — **trashed records are still served here** |

**Request headers**

| Name | Required | Description |
|---|---|---|
| `If-None-Match` | no | When it equals the current ETag the server returns `304 Not Modified` with no body |

**Response** `200 OK` (or `304 Not Modified`)

Body is the raw image. Response headers:

| Header | Value |
|---|---|
| `Content-Type` | Derived from the stored cover URL: `.jpg`/`.jpeg` → `image/jpeg`, `.png` → `image/png`, `.webp` → `image/webp`, otherwise `image/jpeg` |
| `Content-Disposition` | `inline` |
| `ETag` | `"{12 hex chars}"` — first 6 bytes of `SHA-1(textCode + "-cover")`. Derived from the code only, so it does **not** change when the cover image is replaced |
| `Cache-Control` | `no-store, private` |
| `Content-Length` | Size of the image |
| `X-Content-Type-Options` | `nosniff` |

**Errors**

| Status | `error` code | When |
|---|---|---|
| `401` | `TOKEN_MISSING` | No token presented |
| `404` | `NOT_FOUND` | No record with that code (`message` = `Text not found`) |
| `404` | `NOT_FOUND` | The record has no `coverImageUrl` (`message` = `Cover image not available`) |
| `404` | `NOT_FOUND` | The S3 object is gone — the SDK reported 404 (`message` = `Cover image not available for {textCode}`) |
| `500` | `INTERNAL_SERVER_ERROR` | The S3 key could not be extracted, or the byte read failed (`message` = `Cover image not available` / `Failed to serve cover image`) |

**Example**

```bash
curl -s "{{BASE_URL}}/api/text/HASAZIRA_TXT_MASTER_V1_Copy(1)_000001/cover" \
  -H "Cookie: khi_auth_token=$TOKEN" \
  -o cover.jpg

# Conditional request — expect 304
curl -s "{{BASE_URL}}/api/text/HASAZIRA_TXT_MASTER_V1_Copy(1)_000001/cover" \
  -H "Cookie: khi_auth_token=$TOKEN" \
  -H 'If-None-Match: "3f1a9c7b2d40"' \
  -D - -o /dev/null
```

**Notes**

- The ETag check runs before S3 is contacted, so a `304` costs one database lookup and no
  storage call.
- No Range support here — the whole image is read into memory.
- Writes no audit row and touches no cache.
- The public counterpart `GET /api/guest/text/{textCode}/cover` filters on `removedAt IS NULL`
  and sends `Cache-Control: public, max-age=3600`. It belongs to the external documentation set.

---

## Audit actions

Every write and most reads land in `text_audit_logs` via `TextAuditService`, in a
`REQUIRES_NEW` transaction so the audit row survives a rollback of the business transaction.
`TextAuditAction` declares `CREATE, READ, LIST, SEARCH, UPDATE, REMOVE, DELETE, RESTORE, PURGE`;
`REMOVE` is never emitted by these endpoints.

| Endpoint | Action | `textId`/`textCode` on the row |
|---|---|---|
| `GET /api/text` | `LIST` | null |
| `GET /api/text/search` | `SEARCH` | null |
| `GET /api/text/trash` | `LIST` | null |
| `GET /api/text/{textCode}` | `READ` | set |
| `POST /api/text` | `CREATE` | set |
| `POST /api/text/bulk` | `CREATE` | null (one summary row) |
| `PATCH /api/text/{textCode}` | `UPDATE` | set |
| `PATCH /api/text/{textCode}/visibility` | `UPDATE` | set (skipped entirely on a no-op toggle) |
| `DELETE /api/text/{textCode}` | `DELETE` | set |
| `POST /api/text/{textCode}/restore` | `RESTORE` | set |
| `DELETE /api/text/{textCode}/purge` | `PURGE` | set |
| `GET /api/text/{textCode}/read` | none | — |
| `GET /api/text/{textCode}/cover` | none | — |

Each row also captures the actor (user id, username, display name, authority list, permission
list), the resolved session (id, device, IP, login timestamp, expiry, active flag), the request
method and URI, the parent project/person/category codes, an HTML-escaped `details` string, and
`occurredAt`.

## Caching

| Cache | Contents | Config |
|---|---|---|
| `texts:all` | The full active `List<TextResponseDTO>`, one entry | `maximumSize=1`, `expireAfterWrite=10m` |
| `tags:suggest` | Cross-entity tag autocomplete | `maximumSize=1000`, `expireAfterWrite=10m` |
| `keywords:suggest` | Cross-entity keyword autocomplete | `maximumSize=1000`, `expireAfterWrite=10m` |

`TextReadCache.evictAll()` clears **all three** in one `@Caching(evict = …)` block, because a
text record's tags and keywords feed the shared suggest caches. It is invoked after create,
bulk create, update, visibility change, delete, restore and purge.

`GET /api/text` is the only endpoint that reads through `texts:all`. `GET /api/text/{textCode}`
(`findByTextCodeAndRemovedAtIsNull`), `GET /api/text/search`, `GET /api/text/trash` and the two
byte endpoints all bypass the cache and read PostgreSQL directly.

## Related

- [Internal API index](../README.md)
- [Conventions — pagination envelope, timestamps, error shape](../01-conventions.md)
- [Audio API](./audio.md) — same list/filter/trash pattern for audio records
- [Image API](./image.md) — same list/filter/trash pattern for image records
- [Video API](./video.md) — same list/filter/trash pattern for video records
