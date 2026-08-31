# Image API

> **Audience:** Staff (ADMIN / EMPLOYEE) · **Base path:** `/api/image` · **Source:** `src/main/java/ak/dev/khi_archive_platform/platform/api/image/ImageAPI.java`, `src/main/java/ak/dev/khi_archive_platform/platform/api/image/ImageStreamAPI.java`

The back-office surface for image records: paged listing with a large filter/sort catalog,
typo-tolerant search, single multipart create, JSON bulk create, whole-document update,
visibility toggle, trash / restore / purge, and the authenticated byte-proxy that serves the
image itself. Image bytes live in S3 but the S3 URL is never returned — every response rewrites
`imageFileUrl` to the proxy path `/api/image/{imageCode}/view`.

## Access

| Requirement | Value |
|---|---|
| Authentication | required (JWT in the `khi_auth_token` HttpOnly cookie, or `Authorization: Bearer`) |
| Authority | per-method `@PreAuthorize` — `image:read`, `image:create`, `image:update`, `image:delete` |
| Roles that hold it by default | ADMIN (all four, via the role). EMPLOYEE is seeded with `image:read`, `image:create`, `image:update` from `Role.EMPLOYEE_DEFAULT_PERMISSIONS` — **not** `image:delete` |

There is **no class-level `@PreAuthorize`** on `ImageAPI`; every method carries its own
annotation, and the authority is repeated per endpoint below. `ImageStreamAPI` carries no
`@PreAuthorize` at all — `GET /api/image/{imageCode}/view` is gated only by
`SecurityConfig`'s `requestMatchers("/api/**").authenticated()`, so **any** signed-in
principal can fetch image bytes, including a GUEST account.

`image:remove` exists in `user/enums/Permission.java` (`IMAGE_REMOVE`) but no image endpoint
references it — soft delete is gated on `image:delete`.

Authentication failures are produced by the JWT filter and entry point, not by the platform
exception advice, and are identical on every endpoint here: `401` with
`TOKEN_MISSING`, `TOKEN_EXPIRED`, `TOKEN_MALFORMED`, `TOKEN_INVALID`,
`TOKEN_INVALID_SIGNATURE`, `TOKEN_REVOKED`, or `AUTHENTICATION_FAILED`. Per-endpoint error
tables below list only the `401 TOKEN_MISSING` row as representative and otherwise cover
what the endpoint's own handlers produce.

## Endpoints

| Method | Path | Authority | Purpose |
|---|---|---|---|
| `GET` | `/api/image` | `image:read` | Paged list of active records with filters + sort |
| `GET` | `/api/image/search` | `image:read` | Fuzzy multi-token search (non-paged list) |
| `GET` | `/api/image/{imageCode}` | `image:read` | Fetch one active record |
| `GET` | `/api/image/{imageCode}/view` | none (authenticated only) | Proxy the image bytes, with ETag/304 |
| `POST` | `/api/image` | `image:create` | Create one record from multipart `data` + `file` |
| `POST` | `/api/image/bulk` | `image:create` | Bulk create from a JSON array of pre-uploaded URLs |
| `PATCH` | `/api/image/{imageCode}` | `image:update` | Whole-document update (omitted keys are nulled), optionally replacing the file |
| `PATCH` | `/api/image/{imageCode}/visibility` | `image:update` | Toggle `isPublic` |
| `DELETE` | `/api/image/{imageCode}` | `image:delete` | Soft delete (send to trash) |
| `POST` | `/api/image/{imageCode}/restore` | `image:delete` | Restore from trash |
| `GET` | `/api/image/trash` | `image:delete` | Paged list of trashed records |
| `DELETE` | `/api/image/{imageCode}/purge` | `image:delete` | Permanent delete, including the S3 object |
| `GET` | `/api/guest/image/{imageCode}/view` | none (public) | Public byte proxy — declared in the same controller |

---

## Shared shapes

### `ImageResponseDTO`

Returned by every JSON endpoint below (as the element type of the `Page.content[]` array for
the two paged endpoints). `spring.jackson.default-property-inclusion=non_null` means **null
fields are omitted entirely** — a record with no `lens` simply has no `lens` key.

| Field | Type | Notes |
|---|---|---|
| `id` | number | Database primary key |
| `imageCode` | string | Business key, e.g. `HASAZIRA_IMG_RAW_V1_Copy(1)_000001` |
| `projectId` | number | From the parent project |
| `projectCode` | string | From the parent project |
| `projectName` | string | From the parent project |
| `personId` | number | From `project.person`, when the project has one |
| `personCode` | string | From `project.person` |
| `personName` | string | `project.person.fullName` |
| `categoryCodes` | string[] | Category codes of the parent project |
| `imageFileUrl` | string | Always rewritten to `/api/image/{imageCode}/view`; never the S3 URL |
| `fileName` | string | |
| `volumeName` | string | |
| `directory` | string | |
| `pathInExternalVolume` | string | |
| `autoPath` | string | |
| `originalTitle` | string | |
| `alternativeTitle` | string | |
| `titleInCentralKurdish` | string | |
| `romanizedTitle` | string | |
| `subject` | string[] | `image_subjects` collection table |
| `form` | string | |
| `genre` | string[] | `image_genres` |
| `event` | string | |
| `location` | string | |
| `description` | string | |
| `personShownInImage` | string | |
| `colorOfImage` | string[] | `image_colors` |
| `imageVersion` | string | Stored upper-cased |
| `versionNumber` | number | |
| `copyNumber` | number | |
| `whereThisImageUsed` | string[] | `image_usages` |
| `fileSize` | string | Free text, not a number |
| `extension` | string | |
| `orientation` | string | |
| `dimension` | string | |
| `bitDepth` | string | |
| `dpi` | string | |
| `manufacturer` | string | |
| `model` | string | |
| `lens` | string | |
| `creatorArtistPhotographer` | string | |
| `contributor` | string | |
| `audience` | string | |
| `accrualMethod` | string | |
| `provenance` | string | |
| `photostory` | string | |
| `imageStatus` | string | |
| `archiveCataloging` | string | |
| `physicalAvailability` | boolean | Primitive `boolean` on the entity — always present |
| `physicalLabel` | string | |
| `locationInArchiveRoom` | string | |
| `lccClassification` | string | |
| `note` | string | |
| `tags` | string[] | Canonicalized on save |
| `keywords` | string[] | Canonicalized on save |
| `dateCreated` | string | Instant |
| `dateModified` | string | Instant |
| `datePublished` | string | Instant |
| `copyright` | string | |
| `rightOwner` | string | |
| `dateCopyrighted` | string | Instant |
| `licenseType` | string | |
| `usageRights` | string | |
| `availability` | string | |
| `owner` | string | |
| `publisher` | string | |
| `isPublic` | boolean | Visibility of this image record; column defaults to `true` |
| `projectVisibleToPublic` | boolean | Parent project's flag, mirrored; `true` when the project's flag is null |
| `createdAt` | string | Instant |
| `updatedAt` | string | Instant |
| `removedAt` | string | Instant; present only for trashed records |
| `createdBy` | string | Username, or `anonymous` |
| `updatedBy` | string | Username |
| `removedBy` | string | Username; present only for trashed records |

The entity also has `language`, `dialect`, `region` and `version` (optimistic-lock counter)
columns that no request or response DTO exposes.

Example (a densely populated record; sparse records simply omit keys):

```json
{
  "id": 412,
  "imageCode": "HASAZIRA_IMG_MASTER_V1_Copy(1)_000007",
  "projectId": 18,
  "projectCode": "PRJ_HASAZIRA_0003",
  "projectName": "Hasa Zira family album",
  "personId": 6,
  "personCode": "HASAZIRA",
  "personName": "Hasa Zira",
  "categoryCodes": ["CAT_PHOTO", "CAT_FAMILY"],
  "imageFileUrl": "/api/image/HASAZIRA_IMG_MASTER_V1_Copy(1)_000007/view",
  "fileName": "hasa-zira-1978.tif",
  "volumeName": "ARCHIVE_VOL_02",
  "directory": "photographs/1978",
  "originalTitle": "Hasa Zira in Sulaimani, 1978",
  "titleInCentralKurdish": "حەسە زیرا لە سلێمانی",
  "subject": ["portrait", "family"],
  "form": "photograph",
  "genre": ["documentary"],
  "event": "Newroz gathering",
  "location": "Sulaimani",
  "description": "Group portrait taken outside the family home.",
  "personShownInImage": "Hasa Zira; Kawa Zira",
  "colorOfImage": ["black and white"],
  "imageVersion": "MASTER",
  "versionNumber": 1,
  "copyNumber": 1,
  "whereThisImageUsed": ["exhibition-2021"],
  "fileSize": "48.2 MB",
  "extension": "tif",
  "orientation": "landscape",
  "dimension": "6000x4000",
  "bitDepth": "16",
  "dpi": "600",
  "manufacturer": "Epson",
  "model": "Perfection V850",
  "creatorArtistPhotographer": "Unknown",
  "audience": "public",
  "accrualMethod": "donation",
  "provenance": "Donated by the Zira family, 2019",
  "imageStatus": "cataloged",
  "physicalAvailability": true,
  "physicalLabel": "BOX-14/PH-007",
  "locationInArchiveRoom": "Shelf B, drawer 3",
  "tags": ["family", "1980s"],
  "keywords": ["sulaimani", "newroz"],
  "dateCreated": "1978-03-21T00:00:00Z",
  "datePublished": "2021-05-02T00:00:00Z",
  "copyright": "KHI Archive",
  "rightOwner": "Zira family",
  "licenseType": "CC BY-NC 4.0",
  "availability": "public",
  "isPublic": true,
  "projectVisibleToPublic": true,
  "createdAt": "2025-11-04T09:12:33.512Z",
  "updatedAt": "2026-02-18T14:03:01.884Z",
  "createdBy": "sara.k",
  "updatedBy": "sara.k"
}
```

### `ImageBaseRequestDTO` — the write payload

`ImageCreateRequestDTO`, `ImageUpdateRequestDTO` and `ImageBulkCreateRequestDTO` all extend
`ImageBaseRequestDTO` and share exactly these fields. All three are annotated
`@JsonIgnoreProperties(ignoreUnknown = true)` — unknown keys are silently dropped rather than
rejected.

| Field | Type | Notes |
|---|---|---|
| `projectCode` | string | Required on create; on update it must equal the record's current project code or the request is rejected |
| `fileName` | string | On create/update with a file part, defaults to the uploaded file's original name when left blank |
| `volumeName` | string | |
| `directory` | string | |
| `pathInExternalVolume` | string | |
| `autoPath` | string | |
| `originalTitle` | string | |
| `alternativeTitle` | string | |
| `titleInCentralKurdish` | string | |
| `romanizedTitle` | string | |
| `subject` | string[] | |
| `form` | string | |
| `genre` | string[] | |
| `event` | string | |
| `location` | string | |
| `description` | string | |
| `personShownInImage` | string | |
| `colorOfImage` | string[] | |
| `imageVersion` | string | Required on create; upper-cased before storage |
| `versionNumber` | integer | Required on create, minimum `1` |
| `copyNumber` | integer | Required on create, minimum `1` |
| `whereThisImageUsed` | string[] | |
| `fileSize` | string | |
| `extension` | string | |
| `orientation` | string | |
| `dimension` | string | |
| `bitDepth` | string | |
| `dpi` | string | |
| `manufacturer` | string | |
| `model` | string | |
| `lens` | string | |
| `creatorArtistPhotographer` | string | |
| `contributor` | string | |
| `audience` | string | |
| `accrualMethod` | string | |
| `provenance` | string | |
| `photostory` | string | |
| `imageStatus` | string | |
| `archiveCataloging` | string | |
| `physicalAvailability` | boolean | Applied only when non-null |
| `physicalLabel` | string | |
| `locationInArchiveRoom` | string | |
| `lccClassification` | string | |
| `note` | string | |
| `tags` | string[] | Canonicalized + deduped via `platform.service.common.Tags.canonical` |
| `keywords` | string[] | Canonicalized + deduped via `platform.service.common.Keywords.canonical` |
| `dateCreated` | string | Instant |
| `dateModified` | string | Instant |
| `datePublished` | string | Instant |
| `copyright` | string | |
| `rightOwner` | string | |
| `dateCopyrighted` | string | Instant |
| `licenseType` | string | |
| `usageRights` | string | |
| `availability` | string | |
| `owner` | string | |
| `publisher` | string | |

`ImageBulkCreateRequestDTO` adds one field:

| Field | Type | Notes |
|---|---|---|
| `imageFileUrl` | string | Pre-uploaded image URL (S3 or external). Stored verbatim. May be null/blank |

`imageVersion` must be one of `RAW`, `MASTER`, `RESTORED`, `ARCHIVE`, `ORIGINAL`, `HIGH_RES`,
`PROFESSIONAL` (compared case-insensitively).

There is no request field for `isPublic` — use `PATCH /api/image/{imageCode}/visibility`.

---

### `GET /api/image`

Paged list of active (non-trashed) image records with an in-memory filter + sort pass over the
`images:all` read cache.

**Authority:** `image:read`

**Query parameters — paging**

| Name | Type | Default | Description |
|---|---|---|---|
| `page` | int | `0` | Zero-based page index |
| `size` | int | `100` | Page size (`@PageableDefault(size = 100)`) |
| `sort` | string | — | Bound by Spring but **not applied**: ordering comes from `sortBy`/`sortDirection` because the list is sliced in memory after `ImageFilterSupport` has already sorted it |

**Query parameters — sort** (`ImageFilterParams`)

| Name | Type | Default | Description |
|---|---|---|---|
| `sortBy` | string | — | Sort key; unrecognized values leave the cache order untouched. See the accepted values below |
| `sortDirection` | string | ascending | `desc` (case-insensitive) reverses; any other value, including absent, sorts ascending |

Accepted `sortBy` values, matched case-insensitively (synonyms on the same row are equivalent):

| Sorts by | Accepted values |
|---|---|
| `imageCode` | `imageCode`, `code` |
| `originalTitle` | `originalTitle`, `title`, `name`, `alpha`, `alphabet`, `alphabetical` |
| `createdAt` | `createdAt`, `created`, `added`, `dateAdded`, `date_added` |
| `updatedAt` | `updatedAt`, `updated`, `modified`, `dateModified`, `date_modified` |
| `dateCreated` | `dateCreated`, `date_created` |
| `dateModified` | `dateModifiedField`, `dateMod` |
| `datePublished` | `datePublished`, `date_published`, `published` |
| `dateCopyrighted` | `dateCopyrighted`, `copyrighted` |
| `versionNumber` | `versionNumber`, `version` |
| `copyNumber` | `copyNumber`, `copy` |

Nulls sort last in ascending order (`Comparator.nullsLast`) and therefore first when reversed.
Note the collision: `dateModified` as a `sortBy` value sorts by the **audit** `updatedAt`
column; the entity's own `dateModified` field is reachable only through
`dateModifiedField` / `dateMod`.

**Query parameters — categorical filters** (case-insensitive exact match after
Kurdish/Arabic normalization; a null field on the record never matches)

| Name | Type | Default | Description |
|---|---|---|---|
| `form` | string | — | Equals `form` |
| `imageStatus` | string | — | Equals `imageStatus` |
| `imageVersion` | string | — | Equals `imageVersion` |
| `audience` | string | — | Equals `audience` |
| `extension` | string | — | Equals `extension` |
| `orientation` | string | — | Equals `orientation` |
| `dimension` | string | — | Equals `dimension` |
| `bitDepth` | string | — | Equals `bitDepth` |
| `dpi` | string | — | Equals `dpi` |
| `manufacturer` | string | — | Equals `manufacturer` |
| `model` | string | — | Equals `model` |
| `lens` | string | — | Equals `lens` |
| `accrualMethod` | string | — | Equals `accrualMethod` |
| `lccClassification` | string | — | Equals `lccClassification` |
| `availability` | string | — | Equals `availability` |
| `licenseType` | string | — | Equals `licenseType` |

**Query parameters — substring filters** (case-insensitive `contains` after the same
normalization)

| Name | Type | Default | Description |
|---|---|---|---|
| `event` | string | — | Substring of `event` |
| `location` | string | — | Substring of `location` |
| `description` | string | — | Substring of `description` |
| `personShownInImage` | string | — | Substring of `personShownInImage` |
| `creatorArtistPhotographer` | string | — | Substring of `creatorArtistPhotographer` |
| `contributor` | string | — | Substring of `contributor` |
| `provenance` | string | — | Substring of `provenance` |
| `photostory` | string | — | Substring of `photostory` |
| `archiveCataloging` | string | — | Substring of `archiveCataloging` |
| `physicalLabel` | string | — | Substring of `physicalLabel` |
| `locationInArchiveRoom` | string | — | Substring of `locationInArchiveRoom` |
| `note` | string | — | Substring of `note` |
| `copyright` | string | — | Substring of `copyright` |
| `rightOwner` | string | — | Substring of `rightOwner` |
| `usageRights` | string | — | Substring of `usageRights` |
| `owner` | string | — | Substring of `owner` |
| `publisher` | string | — | Substring of `publisher` |

**Query parameters — collection filters**

Repeat the parameter (`?subject=portrait&subject=landscape`) or pass a comma-separated value —
both bind to `List<String>`. Values are trimmed and lower-cased before comparison. A record
with an empty or absent collection never matches an active collection filter.

| Name | Type | Default | Description |
|---|---|---|---|
| `subject` | string[] | — | Matches `subject` |
| `subjectMatch` | string | `any` | `all` (case-insensitive) requires every value; anything else means any |
| `genre` | string[] | — | Matches `genre` |
| `genreMatch` | string | `any` | As above |
| `colorOfImage` | string[] | — | Matches `colorOfImage` |
| `colorMatch` | string | `any` | As above |
| `whereThisImageUsed` | string[] | — | Matches `whereThisImageUsed` |
| `usageMatch` | string | `any` | As above |
| `tags` | string[] | — | Matches `tags` |
| `tagMatch` | string | `any` | As above |
| `keywords` | string[] | — | Matches `keywords` |
| `keywordMatch` | string | `any` | As above |

**Query parameters — boolean, numeric and date ranges**

| Name | Type | Default | Description |
|---|---|---|---|
| `physicalAvailability` | boolean | — | Exact match on `physicalAvailability` |
| `versionNumberMin` | integer | — | Inclusive lower bound on `versionNumber` |
| `versionNumberMax` | integer | — | Inclusive upper bound on `versionNumber` |
| `copyNumberMin` | integer | — | Inclusive lower bound on `copyNumber` |
| `copyNumberMax` | integer | — | Inclusive upper bound on `copyNumber` |
| `dateCreatedFrom` | date `yyyy-MM-dd` | — | Inclusive lower bound on `dateCreated` |
| `dateCreatedTo` | date `yyyy-MM-dd` | — | Inclusive upper bound on `dateCreated` |
| `dateModifiedFrom` | date `yyyy-MM-dd` | — | Inclusive lower bound on `dateModified` |
| `dateModifiedTo` | date `yyyy-MM-dd` | — | Inclusive upper bound on `dateModified` |
| `datePublishedFrom` | date `yyyy-MM-dd` | — | Inclusive lower bound on `datePublished` |
| `datePublishedTo` | date `yyyy-MM-dd` | — | Inclusive upper bound on `datePublished` |
| `dateCopyrightedFrom` | date `yyyy-MM-dd` | — | Inclusive lower bound on `dateCopyrighted` |
| `dateCopyrightedTo` | date `yyyy-MM-dd` | — | Inclusive upper bound on `dateCopyrighted` |
| `createdFrom` | date `yyyy-MM-dd` | — | Inclusive lower bound on the audit `createdAt` |
| `createdTo` | date `yyyy-MM-dd` | — | Inclusive upper bound on the audit `createdAt` |
| `updatedFrom` | date `yyyy-MM-dd` | — | Inclusive lower bound on the audit `updatedAt` |
| `updatedTo` | date `yyyy-MM-dd` | — | Inclusive upper bound on the audit `updatedAt` |

Every date parameter binds as a `LocalDate` (`@DateTimeFormat(iso = ISO.DATE)`) and is expanded
to Asia/Baghdad day bounds by `ArchiveTime` before being compared against the stored `Instant`.
A record whose target date is null never matches an active date range. Numeric ranges behave
the same way — a null `versionNumber` fails any `versionNumberMin`/`Max` filter. Send plain
calendar dates: the `getAll` javadoc on `ImageAPI` shows full-instant examples such as
`dateCreatedFrom=1980-01-01T00:00:00Z`, and `ImageFilterParams`' own javadoc calls the ranges
"ISO-8601 instants", but the fields are `LocalDate` and such a value fails to bind.

All active filters are ANDed together.

**Response** `200 OK` — standard Spring `Page` envelope (see `../01-conventions.md`) whose
`content[]` elements are `ImageResponseDTO` objects as described above.

```json
{
  "content": [
    {
      "id": 412,
      "imageCode": "HASAZIRA_IMG_MASTER_V1_Copy(1)_000007",
      "projectCode": "PRJ_HASAZIRA_0003",
      "imageFileUrl": "/api/image/HASAZIRA_IMG_MASTER_V1_Copy(1)_000007/view",
      "originalTitle": "Hasa Zira in Sulaimani, 1978",
      "form": "photograph",
      "orientation": "landscape",
      "imageVersion": "MASTER",
      "versionNumber": 1,
      "copyNumber": 1,
      "physicalAvailability": true,
      "isPublic": true,
      "projectVisibleToPublic": true,
      "createdAt": "2025-11-04T09:12:33.512Z",
      "updatedAt": "2026-02-18T14:03:01.884Z",
      "createdBy": "sara.k",
      "updatedBy": "sara.k"
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
| `400` | `VALIDATION_ERROR` | A filter parameter fails to bind — e.g. `dateCreatedFrom=1980` or `copyNumberMin=abc` |
| `401` | `TOKEN_MISSING` | No/invalid token (see the Access section for the full 401 set) |
| `403` | `ACCESS_DENIED` | Caller lacks `image:read`; `details.requiredAuthority` is `image:read` |
| `500` | `DATABASE_ERROR` | Cache miss forces a reload and the query fails |

**Example**

```bash
curl -s "{{BASE_URL}}/api/image?page=0&size=20&form=photograph&orientation=landscape&sortBy=originalTitle&sortDirection=asc" \
  -H "Cookie: khi_auth_token=$TOKEN"

curl -s "{{BASE_URL}}/api/image?tags=family&tags=1980s&tagMatch=all&versionNumberMin=1&dateCreatedFrom=1970-01-01&dateCreatedTo=1999-12-31" \
  -H "Cookie: khi_auth_token=$TOKEN"
```

**Notes** — Served from the Caffeine cache `images:all` (one entry, 10-minute TTL). No
mutation happens, so nothing is evicted. Every call writes one `image_audit_logs` row with
action `LIST` and details `Listed active image records (page=… size=… returned=… total=…
[filtered=true])`.

---

### `GET /api/image/search`

Two-phase fuzzy search across image text columns and their child collections. Not paged —
returns a plain JSON array.

**Authority:** `image:read`

**Query parameters**

| Name | Type | Default | Description |
|---|---|---|---|
| `q` | string | — | **Required.** Tokenized on whitespace, deduplicated, and tokens without at least one letter or digit are dropped; every surviving token must match somewhere on the row (prefix, substring, or trigram similarity). A blank `q` — or one that tokenizes to nothing, such as `q=---` — returns `[]` |
| `limit` | integer | `20` | Max results. Null or `<= 0` falls back to `20`; values above `100` are clamped to `100` |

Searched columns: `image_code`, `file_name`, `volume_name`, `directory`,
`path_in_external_volume`, `auto_path`, `original_title`, `alternative_title`,
`title_in_central_kurdish`, `romanized_title`, `form`, `event`, `location`, `description`,
`person_shown_in_image`, `image_version`, `manufacturer`, `model`, `lens`,
`creator_artist_photographer`, `contributor`, `audience`, `accrual_method`, `provenance`,
`photostory`, `image_status`, `archive_cataloging`, `physical_label`,
`location_in_archive_room`, `lcc_classification`, `note`, `copyright`, `right_owner`,
`license_type`, `usage_rights`, `availability`, `owner`, `publisher` — plus the child tables
`image_subjects`, `image_genres`, `image_colors`, `image_usages`, `image_tags`,
`image_keywords`.

Ranking is boosted for the primary columns `original_title`, `alternative_title`,
`title_in_central_kurdish`, `romanized_title`, `image_code`, `file_name`,
`creator_artist_photographer`, `person_shown_in_image`, `event`, `location`. Trashed rows are
excluded (`removed_at IS NULL`).

**Response** `200 OK` — a JSON array of `ImageResponseDTO`.

```json
[
  {
    "id": 412,
    "imageCode": "HASAZIRA_IMG_MASTER_V1_Copy(1)_000007",
    "imageFileUrl": "/api/image/HASAZIRA_IMG_MASTER_V1_Copy(1)_000007/view",
    "originalTitle": "Hasa Zira in Sulaimani, 1978",
    "location": "Sulaimani",
    "physicalAvailability": true,
    "isPublic": true,
    "projectVisibleToPublic": true,
    "createdAt": "2025-11-04T09:12:33.512Z",
    "updatedAt": "2026-02-18T14:03:01.884Z",
    "createdBy": "sara.k",
    "updatedBy": "sara.k"
  }
]
```

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `MISSING_PARAMETER` | `q` omitted entirely |
| `400` | `TYPE_MISMATCH` | `limit` is not an integer — `@RequestParam` conversion failure, not bean validation |
| `401` | `TOKEN_MISSING` | No/invalid token |
| `403` | `ACCESS_DENIED` | Caller lacks `image:read` |
| `500` | `DATABASE_ERROR` | The native search query fails |

**Example**

```bash
curl -s "{{BASE_URL}}/api/image/search?q=sulaimani%201978&limit=25" \
  -H "Cookie: khi_auth_token=$TOKEN"
```

**Notes** — Hits PostgreSQL directly (pg_trgm GIN indexes), not the read cache. Writes one
audit row with action `SEARCH` and details `Searched images q="…" tokens=[…] limit=… hits=…`.

---

### `GET /api/image/{imageCode}`

Fetch one active image record.

**Authority:** `image:read`

**Path parameters**

| Name | Type | Description |
|---|---|---|
| `imageCode` | string | Business key. Trimmed before lookup; trashed records are not returned |

**Response** `200 OK` — a single `ImageResponseDTO` (see the shared shape above).

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `IMAGE_VALIDATION_ERROR` | The code is blank after trimming (`Image code is required`) |
| `401` | `TOKEN_MISSING` | No/invalid token |
| `403` | `ACCESS_DENIED` | Caller lacks `image:read` |
| `404` | `IMAGE_NOT_FOUND` | No active record with that code (a trashed record also 404s here) |
| `500` | `DATABASE_ERROR` | Query failure |

**Example**

```bash
curl -s "{{BASE_URL}}/api/image/HASAZIRA_IMG_MASTER_V1_Copy(1)_000007" \
  -H "Cookie: khi_auth_token=$TOKEN"
```

**Notes** — Reads straight from the database (not the cache). Writes one audit row with action
`READ` and details `Read image record`.

---

### `GET /api/image/{imageCode}/view`

Streams the image bytes through the backend. Declared in `ImageStreamAPI`, which has no
class-level `@RequestMapping` — the full path is on the method.

**Authority:** none. `ImageStreamAPI` carries **no `@PreAuthorize`**; the endpoint is protected
only by `SecurityConfig`'s `requestMatchers("/api/**").authenticated()`, so any authenticated
principal may call it regardless of `image:read`.

**Path parameters**

| Name | Type | Description |
|---|---|---|
| `imageCode` | string | Business key. Looked up with `findByImageCode` — **trashed records are still served here**, so admins can preview what they are about to restore |

**Request headers**

| Name | Description |
|---|---|
| `If-None-Match` | When it equals the current `ETag`, the endpoint returns `304` immediately with no S3 round-trip |

**Response** `200 OK` — the raw image bytes.

| Response header | Value |
|---|---|
| `Content-Type` | First substring hit anywhere in the lower-cased stored file URL, checked in this order: `image/jpeg` (`.jpg`/`.jpeg`), `image/png`, `image/gif`, `image/webp`, `image/tiff` (`.tif`/`.tiff`), `image/bmp`, `image/svg+xml`; otherwise `application/octet-stream`. It is a `contains` test, not an extension parse, so a `.png` earlier in the key wins over the real suffix |
| `Content-Disposition` | `inline; filename="<ascii-sanitized>"; filename*=UTF-8''<percent-encoded>` — RFC 5987, so Kurdish/Arabic filenames survive. Falls back to `image-<imageCode>.<subtype>` when `fileName` is blank or sanitizes to nothing |
| `ETag` | `"<first 6 bytes of SHA-1(imageCode), hex>"` — stable for the life of the record |
| `Cache-Control` | `no-store, private` (the guest endpoint uses `public, max-age=3600` instead) |
| `X-Content-Type-Options` | `nosniff` |
| `Content-Length` | Byte length of the response body |

**Response** `304 Not Modified` — empty body, `ETag` header only, when `If-None-Match` matches.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `401` | `TOKEN_MISSING` | No/invalid token |
| `404` | `NOT_FOUND` | No record with that code (`Image not found`), the record has a blank `imageFileUrl` (`Image file not available`), or S3 answers 404 for the stored key (`Image not available for <imageCode>`) |
| `500` | `INTERNAL_SERVER_ERROR` | The S3 key cannot be extracted from the stored URL, the object cannot be read, or any non-404 S3 failure |

`ResponseStatusException`s raised here are translated by `ApiExceptionHandler.handleResponseStatus`,
which maps `404` to the generic `NOT_FOUND` code — not `IMAGE_NOT_FOUND`.

**Example**

```bash
# Full fetch
curl -s "{{BASE_URL}}/api/image/HASAZIRA_IMG_MASTER_V1_Copy(1)_000007/view" \
  -H "Cookie: khi_auth_token=$TOKEN" \
  -o image.tif

# Conditional fetch — expect 304
curl -s -o /dev/null -w '%{http_code}\n' \
  "{{BASE_URL}}/api/image/HASAZIRA_IMG_MASTER_V1_Copy(1)_000007/view" \
  -H "Cookie: khi_auth_token=$TOKEN" \
  -H 'If-None-Match: "8f2a1c0b4d5e"'
```

**Notes** — The whole object is read into memory (`readAllBytes`) — there is no `Range` support
on this endpoint. No audit row is written and no cache is touched: `ImageStreamAPI` talks to
`ImageRepository` and `S3Service` only.

---

### `POST /api/image`

Create one image record and upload its file. `multipart/form-data` only.

**Authority:** `image:create`

**Request parts**

| Part | Type | Required | Description |
|---|---|---|---|
| `data` | string (JSON) | yes | An `ImageCreateRequestDTO` document — the fields listed under `ImageBaseRequestDTO`. Parsed and bean-validated manually by the controller |
| `file` | file | yes | The image binary. Uploaded to S3 under `images/<imageCode>` |

**Request body** — the `data` part:

```json
{
  "projectCode": "PRJ_HASAZIRA_0003",
  "imageVersion": "MASTER",
  "versionNumber": 1,
  "copyNumber": 1,
  "originalTitle": "Hasa Zira in Sulaimani, 1978",
  "titleInCentralKurdish": "حەسە زیرا لە سلێمانی",
  "form": "photograph",
  "subject": ["portrait", "family"],
  "genre": ["documentary"],
  "colorOfImage": ["black and white"],
  "location": "Sulaimani",
  "orientation": "landscape",
  "dimension": "6000x4000",
  "physicalAvailability": true,
  "physicalLabel": "BOX-14/PH-007",
  "tags": ["family", "1980s"],
  "keywords": ["sulaimani", "newroz"],
  "dateCreated": "1978-03-21T00:00:00Z"
}
```

`imageCode` is generated, never supplied:
`<PERSON_CODE or project media prefix>_IMG_<IMAGE_VERSION>_V<versionNumber>_Copy(<copyNumber>)_<6-digit sequence>`,
where the sequence is `count of images in the project + 1`. Concurrent creates for the same
project are serialized by `CodeGenLock` on `image-code:<projectId>`.

**Response** `200 OK` — the created `ImageResponseDTO`. Note the status is `200`, not `201`.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `MISSING_REQUEST_PART` | Either part is absent — both `data` and `file` are declared required by `@RequestPart` |
| `400` | `IMAGE_VALIDATION_ERROR` | A present-but-blank `data` part; unparseable `data` JSON; bean-validation failure — `details` carries the violated property paths `projectCodePresent`, `imageVersionValid`, `versionNumberValid`, `copyNumberValid`; or a service-side check: `Image file is required`, `Project code is required`, `Image version must be one of: …`, `Version number is required and must be at least 1`, `Copy number is required and must be at least 1` |
| `401` | `TOKEN_MISSING` | No/invalid token |
| `403` | `ACCESS_DENIED` | Caller lacks `image:create` |
| `404` | `PROJECT_NOT_FOUND` | `projectCode` does not resolve to an active project |
| `409` | `IMAGE_ALREADY_EXISTS` | The generated `imageCode` already exists |
| `409` | `CONFLICT` | A database constraint is violated on insert |
| `413` | `UPLOAD_TOO_LARGE` | Beyond `spring.servlet.multipart.max-file-size` (5GB) / `max-request-size` (6GB) |
| `415` | `UNSUPPORTED_MEDIA_TYPE` | The request is not `multipart/form-data` |
| `500` | `INTERNAL_SERVER_ERROR` | The S3 upload fails (`UserStorageException` has no dedicated handler in the platform advice) |
| `500` | `DATABASE_ERROR` | Insert failure |

**Example**

```bash
curl -s -X POST "{{BASE_URL}}/api/image" \
  -H "Cookie: khi_auth_token=$TOKEN" \
  -F 'data={"projectCode":"PRJ_HASAZIRA_0003","imageVersion":"MASTER","versionNumber":1,"copyNumber":1,"originalTitle":"Hasa Zira in Sulaimani, 1978","form":"photograph","tags":["family","1980s"]};type=application/json' \
  -F "file=@./hasa-zira-1978.tif"
```

**Notes** — On success calls `ImageReadCache.evictAll()`, which clears **all** entries of
`images:all`, `tags:suggest` and `keywords:suggest`. Writes one audit row with action `CREATE`
and details `Created image record with code=… project=… imageFileUrl=…` (the S3 URL appears in
the audit detail, not in the API response). When `fileName` is left blank in `data`, the
uploaded file's original name is stored.

---

### `POST /api/image/bulk`

Insert many image records in one transaction. JSON only — each entry carries an already-uploaded
`imageFileUrl` instead of a multipart part.

**Authority:** `image:create`

**Request body** — a JSON array of `ImageBulkCreateRequestDTO`:

```json
[
  {
    "projectCode": "PRJ_HASAZIRA_0003",
    "imageVersion": "MASTER",
    "versionNumber": 1,
    "copyNumber": 1,
    "imageFileUrl": "https://example-bucket.s3.amazonaws.com/images/legacy/0001.tif",
    "originalTitle": "Album page 1",
    "form": "photograph",
    "tags": ["family"]
  },
  {
    "projectCode": "PRJ_HASAZIRA_0003",
    "imageVersion": "ARCHIVE",
    "versionNumber": 2,
    "copyNumber": 1,
    "imageFileUrl": "https://example-bucket.s3.amazonaws.com/images/legacy/0002.tif",
    "originalTitle": "Album page 2"
  }
]
```

The `@AssertTrue` constraints on the DTO are **not** enforced here — this endpoint binds with
`@RequestBody` and no `@Valid`. Instead the service silently skips any entry that is null, has a
blank `projectCode`, a null `imageVersion` or one outside the seven allowed values, a null
`versionNumber`/`copyNumber` below `1`, an unresolvable project, or a generated code that
already exists. Skipped entries are counted, never reported individually.

**Response** `200 OK` — an `ImageService.BulkCreateResult` record:

| Field | Type | Description |
|---|---|---|
| `requested` | number | Size of the submitted array |
| `inserted` | number | Rows actually persisted |
| `skipped` | number | Entries rejected by the per-row checks above |
| `elapsedMs` | number | Wall-clock duration of the batch |

```json
{ "requested": 2, "inserted": 2, "skipped": 0, "elapsedMs": 143 }
```

An empty or null array short-circuits to `{"requested":0,"inserted":0,"skipped":0,"elapsedMs":0}`
without touching the cache or writing an audit row.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `JSON_PARSE_ERROR` | The body is not a readable JSON array |
| `401` | `TOKEN_MISSING` | No/invalid token |
| `403` | `ACCESS_DENIED` | Caller lacks `image:create` |
| `409` | `CONFLICT` | A database constraint is violated during `saveAll` |
| `415` | `UNSUPPORTED_MEDIA_TYPE` | The request is not `application/json` |
| `500` | `DATABASE_ERROR` | Batch insert failure |

**Example**

```bash
curl -s -X POST "{{BASE_URL}}/api/image/bulk" \
  -H "Cookie: khi_auth_token=$TOKEN" \
  -H "Content-Type: application/json" \
  -d '[{"projectCode":"PRJ_HASAZIRA_0003","imageVersion":"MASTER","versionNumber":1,"copyNumber":1,"imageFileUrl":"https://example-bucket.s3.amazonaws.com/images/legacy/0001.tif","originalTitle":"Album page 1"}]'
```

**Notes** — Codes are generated from an in-memory per-project counter seeded once with
`countByProject + 1`, so no `count()` runs per row. `CodeGenLock` is taken once per project.
Calls `ImageReadCache.evictAll()` (evicts `images:all`, `tags:suggest`, `keywords:suggest`) and
writes exactly one audit row with action `CREATE` and details
`Bulk created images: requested=… inserted=… skipped=… elapsedMs=…`; that row has no
`imageId`/`imageCode`.

---

### `PATCH /api/image/{imageCode}`

Update an active record, optionally replacing the stored file. `multipart/form-data` only.
Despite the verb the `data` part is applied as a whole-document replacement — see the copy
semantics below before sending a sparse body.

**Authority:** `image:update`

**Path parameters**

| Name | Type | Description |
|---|---|---|
| `imageCode` | string | Business key; trimmed. Trashed records 404 |

**Request parts**

| Part | Type | Required | Description |
|---|---|---|---|
| `data` | string (JSON) | yes | An `ImageUpdateRequestDTO` — the same fields as `ImageBaseRequestDTO`, with no constraints of its own. Treat it as a full replacement document; see the copy semantics below |
| `file` | file | no | Replacement image. When present, it is uploaded to `images/<imageCode>` and the previous S3 object is deleted if the URL changed and belongs to this bucket |

Despite the `PATCH` verb this is **not** a per-key merge. The service runs

```java
BeanUtils.copyProperties(dto, image,
        "projectCode", "physicalAvailability", "imageVersion", "tags", "keywords");
```

and Spring's `BeanUtils` copies *every* remaining property off the deserialized DTO — including
the `null` that a key you omitted left behind. **A key absent from `data` is written back as
`NULL`, not left untouched.** Read the record first and send its full field set, or accept that
everything you leave out is cleared.

The five excluded names are the exceptions:

| Field | Behavior when omitted / null |
|---|---|
| `projectCode` | Never copied at all. Only compared against the record's current project — see the error table |
| `physicalAvailability` | Applied only when non-null; the stored value survives an omission |
| `imageVersion` | Applied only when non-null, upper-cased before storage |
| `tags` | Applied only when non-null, canonicalized + deduped |
| `keywords` | Applied only when non-null, canonicalized + deduped |

The body below therefore sets `originalTitle`, `note`, `dpi` and `tags`, and clears every other
writable column — `volumeName`, `location`, `description`, `copyright` and the rest all become
`NULL`. Only `imageVersion` and `physicalAvailability` survive the omission, because they are on
the exclusion list:

```json
{
  "originalTitle": "Hasa Zira in Sulaimani, spring 1978",
  "note": "Rescanned at 600 dpi.",
  "dpi": "600",
  "tags": ["family", "1980s", "rescan"]
}
```

**Response** `200 OK` — the updated `ImageResponseDTO`.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `MISSING_REQUEST_PART` | The `data` part is declared required by `@RequestPart` |
| `400` | `IMAGE_VALIDATION_ERROR` | Blank/unparseable `data`; blank `imageCode`; or `Image project cannot be changed after creation. Create a new image record instead.` when `data.projectCode` differs from the record's current project code |
| `401` | `TOKEN_MISSING` | No/invalid token |
| `403` | `ACCESS_DENIED` | Caller lacks `image:update` |
| `404` | `IMAGE_NOT_FOUND` | No active record with that code |
| `409` | `STALE_VERSION` | Optimistic-lock failure — another writer saved first |
| `409` | `CONFLICT` | A database constraint is violated on update |
| `413` | `UPLOAD_TOO_LARGE` | Replacement file exceeds the multipart limits |
| `415` | `UNSUPPORTED_MEDIA_TYPE` | The request is not `multipart/form-data` |
| `500` | `INTERNAL_SERVER_ERROR` | The S3 upload or delete fails |
| `500` | `DATABASE_ERROR` | Update failure |

**Example**

```bash
# NOTE: this two-key data part clears every other writable column on the record.
# For a real edit, GET the record first and resend its full field set.
curl -s -X PATCH "{{BASE_URL}}/api/image/HASAZIRA_IMG_MASTER_V1_Copy(1)_000007" \
  -H "Cookie: khi_auth_token=$TOKEN" \
  -F 'data={"note":"Rescanned at 600 dpi.","dpi":"600"};type=application/json' \
  -F "file=@./hasa-zira-1978-600dpi.tif"
```

**Notes** — Calls `ImageReadCache.evictAll()` (evicts `images:all`, `tags:suggest`,
`keywords:suggest`). Writes one audit row with action `UPDATE` whose details enumerate every
changed field as `field: before -> after`, joined by ` | `, or
`Updated image record (no field changes detected)` when nothing differed. `buildUpdateDetails`
covers the writable columns plus `imageFileUrl`, with three gaps: `dateCreated`, `dateModified`
and `datePublished` are **not** diffed, so a change to any of them leaves no trace in the audit
detail (and, on its own, produces the `no field changes detected` message).

---

### `PATCH /api/image/{imageCode}/visibility`

Flip the record's public-visibility flag. Deliberately reuses `image:update` so anyone who can
edit the record can also hide or publish it.

**Authority:** `image:update`

**Path parameters**

| Name | Type | Description |
|---|---|---|
| `imageCode` | string | Business key; trimmed. Trashed records 404 |

**Request body** — `VisibilityUpdateRequest`, validated with `@Valid`:

| Field | Type | Required | Description |
|---|---|---|---|
| `isPublic` | boolean | yes | `@NotNull` — a missing or null value is a validation error, never a silent `false` |

```json
{ "isPublic": false }
```

**Response** `200 OK` — the `ImageResponseDTO` with the new `isPublic`. Idempotent: when the
flag already holds the requested value the current record is returned unchanged — no save, no
version bump, no cache eviction, no audit row.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `VALIDATION_ERROR` | `isPublic` missing or null; `details.isPublic` is `isPublic is required` |
| `400` | `JSON_PARSE_ERROR` | Body is not readable JSON |
| `400` | `IMAGE_VALIDATION_ERROR` | Blank `imageCode` |
| `401` | `TOKEN_MISSING` | No/invalid token |
| `403` | `ACCESS_DENIED` | Caller lacks `image:update` |
| `404` | `IMAGE_NOT_FOUND` | No active record with that code |
| `409` | `STALE_VERSION` | Optimistic-lock failure |
| `415` | `UNSUPPORTED_MEDIA_TYPE` | The request is not `application/json` |
| `500` | `DATABASE_ERROR` | Update failure |

**Example**

```bash
curl -s -X PATCH "{{BASE_URL}}/api/image/HASAZIRA_IMG_MASTER_V1_Copy(1)_000007/visibility" \
  -H "Cookie: khi_auth_token=$TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"isPublic": false}'
```

**Notes** — On an actual change, calls `ImageReadCache.evictAll()` (evicts `images:all`,
`tags:suggest`, `keywords:suggest`) and writes one audit row with action `UPDATE` and details
`Updated image record: isPublic: <before> -> <after>`. This flag is independent of the parent
project's `isVisibleToPublic`, which is mirrored into the response as `projectVisibleToPublic`.

---

### `DELETE /api/image/{imageCode}`

Soft delete — sets `removedAt` / `removedBy` and sends the record to the trash. The S3 object is
**preserved** so the record can be restored.

**Authority:** `image:delete`

**Path parameters**

| Name | Type | Description |
|---|---|---|
| `imageCode` | string | Business key; trimmed. Must currently be active |

**Response** `204 No Content` — empty body.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `IMAGE_VALIDATION_ERROR` | Blank `imageCode` |
| `401` | `TOKEN_MISSING` | No/invalid token |
| `403` | `ACCESS_DENIED` | Caller lacks `image:delete` |
| `404` | `IMAGE_NOT_FOUND` | No active record with that code (already-trashed records 404) |
| `409` | `STALE_VERSION` | Optimistic-lock failure |
| `500` | `DATABASE_ERROR` | Update failure |

**Example**

```bash
curl -s -X DELETE "{{BASE_URL}}/api/image/HASAZIRA_IMG_MASTER_V1_Copy(1)_000007" \
  -H "Cookie: khi_auth_token=$TOKEN" -i
```

**Notes** — Calls `ImageReadCache.evictAll()` (evicts `images:all`, `tags:suggest`,
`keywords:suggest`). Writes one audit row with action `DELETE` and details
`Sent image record to trash`. Trashing a whole project cascades to its images and writes the
same `DELETE` action per image from `ProjectService`.

---

### `POST /api/image/{imageCode}/restore`

Bring a trashed record back.

**Authority:** `image:delete`

**Path parameters**

| Name | Type | Description |
|---|---|---|
| `imageCode` | string | Business key; trimmed. Looked up regardless of trash state |

**Response** `200 OK` — the restored `ImageResponseDTO` with `removedAt` / `removedBy` cleared
(and therefore omitted from the JSON).

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `IMAGE_VALIDATION_ERROR` | Blank `imageCode`; `Image is not in trash: <code>`; or `Cannot restore image while its project is in trash. Restore the project first.` |
| `401` | `TOKEN_MISSING` | No/invalid token |
| `403` | `ACCESS_DENIED` | Caller lacks `image:delete` — enforced twice, by `@PreAuthorize` and again by the service's `requireAdminRole`, which checks for the same `image:delete` authority |
| `404` | `IMAGE_NOT_FOUND` | No record with that code at all |
| `409` | `STALE_VERSION` | Optimistic-lock failure |
| `500` | `DATABASE_ERROR` | Update failure |

**Example**

```bash
curl -s -X POST "{{BASE_URL}}/api/image/HASAZIRA_IMG_MASTER_V1_Copy(1)_000007/restore" \
  -H "Cookie: khi_auth_token=$TOKEN"
```

**Notes** — Also refreshes `updatedAt` / `updatedBy`. Calls `ImageReadCache.evictAll()` (evicts
`images:all`, `tags:suggest`, `keywords:suggest`) and writes one audit row with action `RESTORE`
and details `Restored image record from trash`.

---

### `GET /api/image/trash`

Paged list of trashed image records.

**Authority:** `image:delete`

**Query parameters**

| Name | Type | Default | Description |
|---|---|---|---|
| `page` | int | `0` | Zero-based page index |
| `size` | int | `100` | Page size (`@PageableDefault(size = 100)`) |
| `sort` | string | — | Bound by Spring but not applied — the list is sliced in memory in repository order |

No `ImageFilterParams` binding on this endpoint: the filter catalog documented for
`GET /api/image` is not available here.

**Response** `200 OK` — the standard `Page` envelope with `ImageResponseDTO` elements; every
element has `removedAt` and `removedBy` populated.

```json
{
  "content": [
    {
      "id": 399,
      "imageCode": "HASAZIRA_IMG_RAW_V1_Copy(1)_000004",
      "imageFileUrl": "/api/image/HASAZIRA_IMG_RAW_V1_Copy(1)_000004/view",
      "originalTitle": "Contact sheet, roll 4",
      "physicalAvailability": false,
      "isPublic": true,
      "projectVisibleToPublic": true,
      "createdAt": "2025-09-30T11:41:02.100Z",
      "updatedAt": "2026-01-12T08:55:19.740Z",
      "removedAt": "2026-01-12T08:55:19.740Z",
      "createdBy": "sara.k",
      "updatedBy": "sara.k",
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
| `401` | `TOKEN_MISSING` | No/invalid token |
| `403` | `ACCESS_DENIED` | Caller lacks `image:delete`; also enforced by the service's `requireAdminRole` |
| `500` | `DATABASE_ERROR` | Query failure |

Unparseable `page`/`size` values are absorbed by Spring's `Pageable` resolver and fall back to
the defaults rather than producing an error.

**Example**

```bash
curl -s "{{BASE_URL}}/api/image/trash?page=0&size=50" \
  -H "Cookie: khi_auth_token=$TOKEN"
```

**Notes** — Queried from the database with `findAllByRemovedAtIsNotNull()`, not the cache.
Writes one audit row with action `LIST` and details
`Listed image trash (page=… size=… returned=… total=…)`.

---

### `DELETE /api/image/{imageCode}/purge`

Permanently delete a trashed record and its S3 object. Irreversible.

**Authority:** `image:delete`

**Path parameters**

| Name | Type | Description |
|---|---|---|
| `imageCode` | string | Business key; trimmed. Must already be in trash |

**Response** `204 No Content` — empty body.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `IMAGE_VALIDATION_ERROR` | Blank `imageCode`, or `Image must be in trash before permanent deletion. Trash it first.` |
| `401` | `TOKEN_MISSING` | No/invalid token |
| `403` | `ACCESS_DENIED` | Caller lacks `image:delete`; also enforced by `requireAdminRole` |
| `404` | `IMAGE_NOT_FOUND` | No record with that code at all |
| `409` | `CONFLICT` | A foreign-key constraint blocks the row delete |
| `500` | `INTERNAL_SERVER_ERROR` | The S3 delete fails |
| `500` | `DATABASE_ERROR` | Delete failure |

**Example**

```bash
curl -s -X DELETE "{{BASE_URL}}/api/image/HASAZIRA_IMG_RAW_V1_Copy(1)_000004/purge" \
  -H "Cookie: khi_auth_token=$TOKEN" -i
```

**Notes** — Ordering matters: the audit row (action `PURGE`, details
`Permanently deleted image record from trash`) is written **before** the row is deleted, so the
log keeps the image's id, code, title, project and person. Then
`ImageReadCache.evictAll()` runs (evicts `images:all`, `tags:suggest`, `keywords:suggest`) and
the S3 object is removed last, and only when the stored URL belongs to this bucket
(`S3Service.isOurS3Url`).

---

### `GET /api/guest/image/{imageCode}/view`

The public counterpart of `/api/image/{imageCode}/view`, declared in the same `ImageStreamAPI`
class. It belongs to the external (visitor-facing) surface and is listed here only because it
lives in a controller in this document's scope.

**Authority:** none — `SecurityConfig` permits `/api/guest/**` without a token, and the method
carries no `@PreAuthorize`.

Identical behavior to the authenticated view with two differences:

| Aspect | `/api/image/{code}/view` | `/api/guest/image/{code}/view` |
|---|---|---|
| Record lookup | `findByImageCode` — trashed records are served | `findByImageCodeAndRemovedAtIsNull` — trashed records `404` |
| `Cache-Control` | `no-store, private` | `public, max-age=3600` |

The `ETag`, `Content-Type` resolution, `Content-Disposition`, `X-Content-Type-Options` and the
`304` short-circuit are the same. This endpoint does **not** check `isPublic` or the parent
project's `projectVisibleToPublic` — visibility filtering happens in the guest listing/feed
endpoints, not in the byte proxy.

**Path parameters**

| Name | Type | Description |
|---|---|---|
| `imageCode` | string | Business key. Looked up with `findByImageCodeAndRemovedAtIsNull` |

**Request headers**

| Name | Description |
|---|---|
| `If-None-Match` | When it equals the current `ETag`, returns `304` with no S3 round-trip |

**Response** `200 OK` — the raw image bytes, with the header set described above.

**Response** `304 Not Modified` — empty body, `ETag` header only.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `404` | `NOT_FOUND` | No active record with that code, blank `imageFileUrl`, or the S3 object is missing |
| `500` | `INTERNAL_SERVER_ERROR` | S3 key extraction or read failure |

**Example**

```bash
curl -s "{{BASE_URL}}/api/guest/image/HASAZIRA_IMG_MASTER_V1_Copy(1)_000007/view" -o image.tif
```

---

## Caching and audit summary

| Endpoint | Cache read | Cache evicted | Audit action |
|---|---|---|---|
| `GET /api/image` | `images:all` | — | `LIST` |
| `GET /api/image/search` | — (direct SQL) | — | `SEARCH` |
| `GET /api/image/{imageCode}` | — (direct DB) | — | `READ` |
| `GET /api/image/{imageCode}/view` | — | — | none |
| `POST /api/image` | — | `images:all`, `tags:suggest`, `keywords:suggest` | `CREATE` |
| `POST /api/image/bulk` | — | `images:all`, `tags:suggest`, `keywords:suggest` | `CREATE` (one summary row) |
| `PATCH /api/image/{imageCode}` | — | `images:all`, `tags:suggest`, `keywords:suggest` | `UPDATE` |
| `PATCH /api/image/{imageCode}/visibility` | — | same, only when the flag actually changes | `UPDATE`, only when it changes |
| `DELETE /api/image/{imageCode}` | — | `images:all`, `tags:suggest`, `keywords:suggest` | `DELETE` |
| `POST /api/image/{imageCode}/restore` | — | `images:all`, `tags:suggest`, `keywords:suggest` | `RESTORE` |
| `GET /api/image/trash` | — (direct DB) | — | `LIST` |
| `DELETE /api/image/{imageCode}/purge` | — | `images:all`, `tags:suggest`, `keywords:suggest` | `PURGE` |
| `GET /api/guest/image/{imageCode}/view` | — | — | none |

All three caches are Caffeine, declared in `platform/config/CacheConfig.java`: `images:all`
holds one entry with a 10-minute TTL; `tags:suggest` and `keywords:suggest` hold up to 1 000
entries each, also with a 10-minute TTL. Eviction is always `allEntries = true` — there is no
per-record invalidation.

`ImageAuditAction` also declares a `REMOVE` constant, but no image endpoint emits it; soft
delete is logged as `DELETE`. Every audit row is written in a `REQUIRES_NEW` transaction, so it
survives a rollback of the surrounding operation, and its `details` string is HTML-escaped
before storage.

## Related

- [Internal docs index](../README.md)
- [Conventions — the `Page` envelope, timestamps, error format](../01-conventions.md)
- [Audio API](./audio.md) — the sibling media type with the same CRUD, trash and filter shape
