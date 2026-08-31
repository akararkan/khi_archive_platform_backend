# Video API

> **Audience:** Staff (ADMIN / EMPLOYEE) · **Base path:** `/api/video` · **Source:** `platform/api/video/VideoAPI.java`, `platform/api/video/VideoStreamAPI.java`

Back-office CRUD for video records: paged listing with a large filter/sort catalog, fuzzy
search, single multipart create, JSON bulk create, partial update, a visibility toggle, the
trash lifecycle (soft delete → restore → purge), and a byte-range streaming proxy that keeps
the S3 URL off the wire.

## Access

| Requirement | Value |
|---|---|
| Authentication | Required (JWT in the `khi_auth_token` HttpOnly cookie, or `Authorization: Bearer`) |
| Authority | Per method — `video:read`, `video:create`, `video:update`, `video:delete` |
| Annotation placement | `@PreAuthorize` is on **each method** of `VideoAPI`; there is no class-level `@PreAuthorize` |
| Roles that hold `video:read` / `video:create` / `video:update` by default | ADMIN (via the role), EMPLOYEE (seeded into per-user grants from `EMPLOYEE_DEFAULT_PERMISSIONS`) |
| Roles that hold `video:delete` by default | ADMIN only |

Notes on the authority model:

- The trash endpoints (`DELETE`, `restore`, `trash`, `purge`) all require **`video:delete`**,
  not `video:remove`. `Permission.VIDEO_REMOVE` (`video:remove`) exists in the catalog but no
  video endpoint references it.
- `restore`, `purge` and `GET /api/video/trash` re-check `video:delete` a second time inside
  `VideoService.requireAdminRole(...)`. `DELETE /api/video/{videoCode}` relies on the
  `@PreAuthorize` alone.
- `GET /api/video/{videoCode}/stream` lives on `VideoStreamAPI` and carries **no
  `@PreAuthorize`** — it is gated only by the `/api/**` → `authenticated()` rule in
  `SecurityConfig`. Any signed-in account can stream, including a GUEST.

## Endpoints

| Method | Path | Authority | Purpose |
|---|---|---|---|
| `GET` | `/api/video` | `video:read` | Paged list of active records with filters + sort |
| `GET` | `/api/video/search` | `video:read` | Fuzzy multi-token search (flat array, not paged) |
| `GET` | `/api/video/{videoCode}` | `video:read` | Fetch one active record |
| `POST` | `/api/video` | `video:create` | Create one record from `multipart/form-data` |
| `POST` | `/api/video/bulk` | `video:create` | Create many records from a JSON array |
| `PATCH` | `/api/video/{videoCode}` | `video:update` | Partial update, optional file replacement |
| `PATCH` | `/api/video/{videoCode}/visibility` | `video:update` | Toggle the `isPublic` flag |
| `DELETE` | `/api/video/{videoCode}` | `video:delete` | Soft delete — send to trash |
| `POST` | `/api/video/{videoCode}/restore` | `video:delete` | Restore from trash |
| `GET` | `/api/video/trash` | `video:delete` | Paged list of trashed records |
| `DELETE` | `/api/video/{videoCode}/purge` | `video:delete` | Permanent delete + S3 object removal |
| `GET` | `/api/video/{videoCode}/stream` | none (`authenticated()`) | Range-streamed video bytes |
| `GET` | `/api/guest/video/{videoCode}/stream` | none (public) | Public range-streamed bytes — see [external streaming](../../external/07-streaming.md) |

Routing note: `/api/video/search` and `/api/video/trash` are literal path segments and win
over the `{videoCode}` template, so a record whose code is literally `search` or `trash`
could not be fetched through `GET /api/video/{videoCode}`. Real video codes never take that
form (see [Video code format](#video-code-format)).

---

### `GET /api/video`

Paged list of every video whose `removedAt IS NULL`, with optional filtering and sorting.

**Authority:** `video:read`

**Query parameters**

Pagination (standard Spring `Pageable`, `@PageableDefault(size = 100)`):

| Name | Type | Default | Description |
|---|---|---|---|
| `page` | int | `0` | Zero-based page index |
| `size` | int | `100` | Page size |
| `sort` | string | — | Spring's own sort parameter. Ignored by this endpoint: ordering is decided by `sortBy`/`sortDirection` below, applied in memory after filtering |

Sort (bound into `VideoFilterParams`):

| Name | Type | Default | Description |
|---|---|---|---|
| `sortBy` | string | — | Sort key. Unrecognized values leave the list in cache order (see the accepted values below) |
| `sortDirection` | string | `asc` | `desc` reverses the comparator; any other value (including absent) keeps ascending |

Accepted `sortBy` values, verbatim from `VideoFilterSupport.comparatorFor` (matching is
case-insensitive; every synonym on a row is equivalent):

| Sorts by | Accepted values |
|---|---|
| `videoCode` | `videoCode`, `code` |
| `originalTitle` | `originalTitle`, `title`, `name`, `alpha`, `alphabet`, `alphabetical` |
| `language` | `language`, `lang` |
| `createdAt` (audit) | `createdAt`, `created`, `added`, `dateAdded`, `date_added` |
| `updatedAt` (audit) | `updatedAt`, `updated`, `modified`, `dateModified`, `date_modified` |
| `dateCreated` (metadata) | `dateCreated`, `date_created` |
| `dateModified` (metadata) | `dateModifiedField`, `dateMod` |
| `datePublished` | `datePublished`, `date_published`, `published` |
| `dateCopyrighted` | `dateCopyrighted`, `copyrighted` |
| `versionNumber` | `versionNumber`, `version` |
| `copyNumber` | `copyNumber`, `copy` |

Trap worth repeating: `sortBy=dateModified` sorts by the **audit** `updatedAt` column. To sort
by the metadata field `dateModified`, pass `sortBy=dateModifiedField` (or `dateMod`). Nulls
always sort last before `sortDirection` is applied, so `desc` puts them first.

Categorical filters — case-insensitive **exact** match after Kurdish-aware normalization
(NFC, Yeh/Kaf folding, joiner + harakat stripping, whitespace collapse, lowercase):

| Name | Type | Matches DTO field |
|---|---|---|
| `videoVersion` | string | `videoVersion` |
| `videoStatus` | string | `videoStatus` |
| `audience` | string | `audience` |
| `extension` | string | `extension` |
| `orientation` | string | `orientation` |
| `dimension` | string | `dimension` |
| `resolution` | string | `resolution` |
| `duration` | string | `duration` |
| `bitDepth` | string | `bitDepth` |
| `frameRate` | string | `frameRate` |
| `overallBitRate` | string | `overallBitRate` |
| `videoCodec` | string | `videoCodec` |
| `audioCodec` | string | `audioCodec` |
| `audioChannels` | string | `audioChannels` |
| `language` | string | `language` |
| `dialect` | string | `dialect` |
| `subtitle` | string | `subtitle` |
| `accrualMethod` | string | `accrualMethod` |
| `lccClassification` | string | `lccClassification` |
| `availability` | string | `availability` |
| `licenseType` | string | `licenseType` |

Long-text filters — case-insensitive **substring** match, same normalization:

| Name | Type | Matches DTO field |
|---|---|---|
| `event` | string | `event` |
| `location` | string | `location` |
| `description` | string | `description` |
| `personShownInVideo` | string | `personShownInVideo` |
| `creatorArtistDirector` | string | `creatorArtistDirector` |
| `producer` | string | `producer` |
| `contributor` | string | `contributor` |
| `provenance` | string | `provenance` |
| `archiveCataloging` | string | `archiveCataloging` |
| `physicalLabel` | string | `physicalLabel` |
| `locationInArchiveRoom` | string | `locationInArchiveRoom` |
| `note` | string | `note` |
| `copyright` | string | `copyright` |
| `rightOwner` | string | `rightOwner` |
| `usageRights` | string | `usageRights` |
| `owner` | string | `owner` |
| `publisher` | string | `publisher` |

Collection filters — repeat the parameter (`?tags=a&tags=b`) or send a comma-separated value.
Each has a companion `*Match` parameter: `all` (case-insensitive) requires every listed value
to be present; anything else, including absent, means `any`. A record whose collection is
empty or null never matches a non-empty filter. These compare with plain
`trim().toLowerCase()` — the Kurdish-script folding applied to the scalar filters above is
**not** applied here.

| Name | Type | Companion | Matches DTO field |
|---|---|---|---|
| `subject` | string[] | `subjectMatch` | `subject` |
| `genre` | string[] | `genreMatch` | `genre` |
| `colorOfVideo` | string[] | `colorMatch` | `colorOfVideo` |
| `whereThisVideoUsed` | string[] | `usageMatch` | `whereThisVideoUsed` |
| `tags` | string[] | `tagMatch` | `tags` |
| `keywords` | string[] | `keywordMatch` | `keywords` |

Boolean, numeric and date filters:

| Name | Type | Description |
|---|---|---|
| `physicalAvailability` | boolean | Exact match against the record's flag |
| `versionNumberMin` | int | Inclusive lower bound on `versionNumber` |
| `versionNumberMax` | int | Inclusive upper bound on `versionNumber` |
| `copyNumberMin` | int | Inclusive lower bound on `copyNumber` |
| `copyNumberMax` | int | Inclusive upper bound on `copyNumber` |
| `dateCreatedFrom` | date `YYYY-MM-DD` | Inclusive lower bound on `dateCreated` |
| `dateCreatedTo` | date `YYYY-MM-DD` | Inclusive upper bound on `dateCreated` |
| `dateModifiedFrom` | date `YYYY-MM-DD` | Inclusive lower bound on `dateModified` |
| `dateModifiedTo` | date `YYYY-MM-DD` | Inclusive upper bound on `dateModified` |
| `datePublishedFrom` | date `YYYY-MM-DD` | Inclusive lower bound on `datePublished` |
| `datePublishedTo` | date `YYYY-MM-DD` | Inclusive upper bound on `datePublished` |
| `dateCopyrightedFrom` | date `YYYY-MM-DD` | Inclusive lower bound on `dateCopyrighted` |
| `dateCopyrightedTo` | date `YYYY-MM-DD` | Inclusive upper bound on `dateCopyrighted` |
| `createdFrom` | date `YYYY-MM-DD` | Inclusive lower bound on the audit `createdAt` |
| `createdTo` | date `YYYY-MM-DD` | Inclusive upper bound on the audit `createdAt` |
| `updatedFrom` | date `YYYY-MM-DD` | Inclusive lower bound on the audit `updatedAt` |
| `updatedTo` | date `YYYY-MM-DD` | Inclusive upper bound on the audit `updatedAt` |

Date bounds are declared `LocalDate` with `@DateTimeFormat(iso = ISO.DATE)` — send
`1980-01-01`, not a full ISO-8601 instant. Bounds are widened to whole days in the archive
zone `Asia/Baghdad` (`from` → 00:00:00, `to` → 23:59:59.999999999) before comparing against
the stored `Instant`. A record whose date field is null is excluded as soon as either bound
is supplied.

All filters combine with AND. When every parameter is absent the endpoint short-circuits and
returns the cached list untouched, in cache order.

**Response** `200 OK` — standard Spring `Page` envelope (see `../01-conventions.md`) whose
`content[]` elements are `VideoResponseDTO`:

```json
{
  "content": [
    {
      "id": 41,
      "videoCode": "HASAZIRA_VID_RAW_V1_Copy(1)_000001",
      "projectId": 7,
      "projectCode": "HASAZIRA-PROJ-000001",
      "projectName": "Hasa Zira concerts",
      "personId": 3,
      "personCode": "HASAZIRA",
      "personName": "Hasa Zira",
      "categoryCodes": ["CAT-000002"],
      "videoFileUrl": "/api/video/HASAZIRA_VID_RAW_V1_Copy(1)_000001/stream",
      "fileName": "hasa-zira-1991.mp4",
      "originalTitle": "Hasa Zira live, Sulaimani 1991",
      "subject": ["concert"],
      "genre": ["folk"],
      "location": "Sulaimani",
      "colorOfVideo": ["color"],
      "videoVersion": "RAW",
      "versionNumber": 1,
      "copyNumber": 1,
      "resolution": "1920x1080",
      "duration": "01:12:44",
      "frameRate": "25",
      "videoCodec": "H.264",
      "audioCodec": "AAC",
      "language": "Kurdish",
      "dialect": "Sorani",
      "physicalAvailability": true,
      "tags": ["interview", "1990s"],
      "keywords": ["sulaimani", "concert"],
      "dateCreated": "1991-06-12T00:00:00Z",
      "isPublic": true,
      "projectVisibleToPublic": true,
      "createdAt": "2026-02-03T09:14:22.481Z",
      "updatedAt": "2026-02-03T09:14:22.481Z",
      "createdBy": "aram",
      "updatedBy": "aram"
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

Full `VideoResponseDTO` field list — `spring.jackson.default-property-inclusion=non_null`
means any field left null is **omitted** from the JSON, so a sparse record produces a much
smaller object than the list below suggests:

| Group | Fields |
|---|---|
| Identity | `id` (Long), `videoCode` (String) |
| Project / person | `projectId` (Long), `projectCode`, `projectName`, `personId` (Long), `personCode`, `personName`, `categoryCodes` (String[]) |
| File URL | `videoFileUrl` (String) — always rewritten to `/api/video/{videoCode}/stream`; the S3 URL is never returned |
| File & path | `fileName`, `volumeName`, `directory`, `pathInExternalVolume`, `autoPath` |
| Titles | `originalTitle`, `alternativeTitle`, `titleInCentralKurdish`, `romanizedTitle` |
| Classification | `subject` (String[]), `genre` (String[]), `event`, `location`, `description` |
| Video details | `personShownInVideo`, `colorOfVideo` (String[]), `videoVersion`, `versionNumber` (Integer), `copyNumber` (Integer), `whereThisVideoUsed` (String[]) |
| Technical | `fileSize`, `extension`, `orientation`, `dimension`, `resolution`, `duration`, `bitDepth`, `frameRate`, `overallBitRate`, `videoCodec`, `audioCodec`, `audioChannels` — all String |
| Language | `language`, `dialect`, `subtitle` |
| People | `creatorArtistDirector`, `producer`, `contributor`, `audience` |
| Archival | `accrualMethod`, `provenance`, `videoStatus`, `archiveCataloging`, `physicalAvailability` (Boolean), `physicalLabel`, `locationInArchiveRoom`, `lccClassification`, `note` |
| Tags | `tags` (String[]), `keywords` (String[]) |
| Dates | `dateCreated`, `dateModified`, `datePublished` — Instant |
| Rights | `copyright`, `rightOwner`, `dateCopyrighted` (Instant), `licenseType`, `usageRights`, `availability`, `owner`, `publisher` |
| Visibility | `isPublic` (Boolean), `projectVisibleToPublic` (Boolean) |
| Audit | `createdAt`, `updatedAt`, `removedAt` (Instant), `createdBy`, `updatedBy`, `removedBy` (String) |

Fields that are effectively always present: `physicalAvailability` (mapped from a primitive
`boolean` column, so it serializes as `true`/`false` and never disappears), `isPublic` (NOT
NULL, defaults to `true`), and `projectVisibleToPublic` (falls back to `true` when the
project's own flag is null). `removedAt` / `removedBy` are present only on trashed records.

The entity column `region` is **not** exposed: no request or response DTO declares it, so it
can neither be set nor read through this API.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `VALIDATION_ERROR` | A filter parameter fails type conversion (e.g. `versionNumberMin=abc`, `dateCreatedFrom=1980`) — raised as a `BindException` on the `@ModelAttribute` |
| `401` | `TOKEN_MISSING` | No cookie and no `Authorization` header |
| `401` | `AUTHENTICATION_FAILED` | Credentials supplied but rejected |
| `403` | `ACCESS_DENIED` | Caller lacks `video:read`; `details.requiredAuthority` echoes it back |
| `500` | `DATABASE_ERROR` | Cache miss and the reload query fails |

**Example**

```bash
curl -s "{{BASE_URL}}/api/video?page=0&size=20&videoCodec=H.264&sortBy=originalTitle&sortDirection=asc" \
  -H "Cookie: khi_auth_token=$TOKEN"

curl -s -G "{{BASE_URL}}/api/video" \
  --data-urlencode "tags=interview" \
  --data-urlencode "tags=1990s" \
  --data-urlencode "tagMatch=all" \
  --data-urlencode "location=Sulaimani" \
  --data-urlencode "dateCreatedFrom=1980-01-01" \
  --data-urlencode "dateCreatedTo=2000-12-31" \
  --data-urlencode "versionNumberMin=1" \
  -H "Cookie: khi_auth_token=$TOKEN"
```

**Notes**

- Served from the Caffeine cache `videos:all` (one entry holding the whole active list,
  10-minute TTL). Filtering and sorting run in memory over that list.
- Writes a `LIST` row to `video_audit_logs` on every call, including page/size/returned/total
  and ` filtered=true` when any parameter was supplied.

---

### `GET /api/video/search`

Two-phase fuzzy search over video columns and their child collection tables, backed by
`pg_trgm` GIN indexes.

**Authority:** `video:read`

**Query parameters**

| Name | Type | Default | Description |
|---|---|---|---|
| `q` | string | — | **Required.** Free-text query. Split on whitespace; tokens with no letter or digit are dropped; duplicates removed, order preserved |
| `limit` | int | `20` | Max hits. Null, zero or negative falls back to `20`; values above `100` are clamped to `100` |

Every token must match somewhere — a column or a child row — for a record to survive
(`AND` across tokens, `OR` across fields). Each token is probed by prefix-LIKE,
substring-LIKE and trigram similarity, with a 2 000-row per-token prefilter. Ranking is a
three-tier sum: prefix hits on primary columns, then substring hits on primary columns, then
summed trigram similarity.

Columns searched: `video_code`, `file_name`, `volume_name`, `directory`,
`path_in_external_volume`, `auto_path`, `original_title`, `alternative_title`,
`title_in_central_kurdish`, `romanized_title`, `event`, `location`, `description`,
`person_shown_in_video`, `video_version`, `resolution`, `video_codec`, `audio_codec`,
`audio_channels`, `language`, `dialect`, `subtitle`, `creator_artist_director`, `producer`,
`contributor`, `audience`, `accrual_method`, `provenance`, `video_status`,
`archive_cataloging`, `physical_label`, `location_in_archive_room`, `lcc_classification`,
`note`, `copyright`, `right_owner`, `license_type`, `usage_rights`, `availability`, `owner`,
`publisher`. Child tables searched: `video_subjects`, `video_genres`, `video_colors`,
`video_usages`, `video_tags`, `video_keywords`. Primary (highest-weighted) columns are
`original_title`, `alternative_title`, `title_in_central_kurdish`, `romanized_title`,
`video_code`, `file_name`, `creator_artist_director`, `producer`, `person_shown_in_video`,
`event`, `location`.

**Response** `200 OK` — a flat JSON array of `VideoResponseDTO`, **not** a `Page` envelope.
Element shape is identical to `GET /api/video`. A blank `q`, or a `q` containing no
letter/digit token, returns `[]` without querying the database (and without an audit row).

```json
[
  {
    "id": 41,
    "videoCode": "HASAZIRA_VID_RAW_V1_Copy(1)_000001",
    "originalTitle": "Hasa Zira live, Sulaimani 1991",
    "videoFileUrl": "/api/video/HASAZIRA_VID_RAW_V1_Copy(1)_000001/stream",
    "physicalAvailability": true,
    "isPublic": true,
    "projectVisibleToPublic": true
  }
]
```

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `MISSING_PARAMETER` | `q` omitted; `details.parameter` is `q` |
| `400` | `TYPE_MISMATCH` | `limit` is not an integer |
| `401` | `TOKEN_MISSING` | No credentials supplied |
| `403` | `ACCESS_DENIED` | Caller lacks `video:read` |
| `500` | `DATABASE_ERROR` | The native query fails |
| `504` | `TIMEOUT` | The native query exceeds the statement timeout |

**Example**

```bash
curl -s -G "{{BASE_URL}}/api/video/search" \
  --data-urlencode "q=hasa zira sulaimani" \
  --data-urlencode "limit=25" \
  -H "Cookie: khi_auth_token=$TOKEN"
```

**Notes**

- Reads straight from PostgreSQL — the `videos:all` cache is not consulted.
- Only active records are returned; the generated SQL pins `removed_at IS NULL`.
- Writes a `SEARCH` row to `video_audit_logs` recording the query, tokens, limit and hit
  count.

---

### `GET /api/video/{videoCode}`

Fetch a single active video record.

**Authority:** `video:read`

**Path parameters**

| Name | Type | Description |
|---|---|---|
| `videoCode` | string | Business key, e.g. `HASAZIRA_VID_RAW_V1_Copy(1)_000001`. Trimmed before lookup |

**Response** `200 OK` — one `VideoResponseDTO` (same shape as the `content[]` element above).

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `VIDEO_VALIDATION_ERROR` | The path segment is whitespace-only after trimming ("Video code is required") |
| `401` | `TOKEN_MISSING` | No credentials supplied |
| `403` | `ACCESS_DENIED` | Caller lacks `video:read` |
| `404` | `VIDEO_NOT_FOUND` | No record with that code, or the record is in the trash |

**Example**

```bash
curl -s "{{BASE_URL}}/api/video/HASAZIRA_VID_RAW_V1_Copy(1)_000001" \
  -H "Cookie: khi_auth_token=$TOKEN"
```

**Notes**

- Reads from the database, not the cache.
- Writes a `READ` row to `video_audit_logs` ("Read video record").

---

### `POST /api/video`

Create one video record and upload its file to S3.

**Authority:** `video:create`
**Consumes:** `multipart/form-data` · **Produces:** `application/json`

**Request parts**

| Part | Type | Required | Description |
|---|---|---|---|
| `data` | string (JSON) | yes | A `VideoCreateRequestDTO` serialized as JSON. Sent as a plain string part and parsed by the controller, so its own `Content-Type` is not enforced |
| `file` | file | yes | The video file. The service hands `S3Service.upload` the folder `videos/{videoCode}`; the object key it builds is `{aws.s3.base-folder}/videos/{videoCode}/{uuid}-{sanitized-original-filename}` (base folder defaults to `khi-archive-platform-folders`) |

`data` fields (`VideoCreateRequestDTO` extends `VideoBaseRequestDTO`; unknown properties are
ignored):

| Group | Fields | Required |
|---|---|---|
| Link | `projectCode` (String) | **yes** — the video is attached to an existing, non-trashed project |
| Versioning | `videoVersion` (String), `versionNumber` (Integer), `copyNumber` (Integer) | **yes** — `videoVersion` must be one of `RAW`, `MASTER`, `RESTORED`, `ARCHIVE`, `ORIGINAL`, `4K_MASTER`, `PROFESSIONAL` (compared uppercased and stored uppercased); the two numbers must be ≥ 1 |
| File & path | `fileName`, `volumeName`, `directory`, `pathInExternalVolume`, `autoPath` | no |
| Titles | `originalTitle`, `alternativeTitle`, `titleInCentralKurdish`, `romanizedTitle` | no |
| Classification | `subject` (String[]), `genre` (String[]), `event`, `location`, `description` | no |
| Video details | `personShownInVideo`, `colorOfVideo` (String[]), `whereThisVideoUsed` (String[]) | no |
| Technical | `fileSize`, `extension`, `orientation`, `dimension`, `resolution`, `duration`, `bitDepth`, `frameRate`, `overallBitRate`, `videoCodec`, `audioCodec`, `audioChannels` | no |
| Language | `language`, `dialect`, `subtitle` | no |
| People | `creatorArtistDirector`, `producer`, `contributor`, `audience` | no |
| Archival | `accrualMethod`, `provenance`, `videoStatus`, `archiveCataloging`, `physicalAvailability` (Boolean), `physicalLabel`, `locationInArchiveRoom`, `lccClassification`, `note` | no |
| Tags | `tags` (String[]), `keywords` (String[]) | no — canonicalized and de-duplicated on save |
| Dates | `dateCreated`, `dateModified`, `datePublished` (Instant) | no |
| Rights | `copyright`, `rightOwner`, `dateCopyrighted` (Instant), `licenseType`, `usageRights`, `availability`, `owner`, `publisher` | no |

There is no `isPublic` in the create DTO — new records are public by default (the column
defaults to `true`); use the visibility endpoint to hide one.

**Request body**

```json
{
  "projectCode": "HASAZIRA-PROJ-000001",
  "videoVersion": "RAW",
  "versionNumber": 1,
  "copyNumber": 1,
  "originalTitle": "Hasa Zira live, Sulaimani 1991",
  "language": "Kurdish",
  "dialect": "Sorani",
  "location": "Sulaimani",
  "subject": ["concert"],
  "genre": ["folk"],
  "colorOfVideo": ["color"],
  "videoCodec": "H.264",
  "audioCodec": "AAC",
  "resolution": "1920x1080",
  "duration": "01:12:44",
  "physicalAvailability": true,
  "tags": ["interview", "1990s"],
  "keywords": ["sulaimani", "concert"]
}
```

**Response** `200 OK` (not `201`) — the saved `VideoResponseDTO`.

<a id="video-code-format"></a>
**Video code format**

Generated server-side; the client never supplies it:

```text
{PARENT}_VID_{VERSION}_V{versionNumber}_Copy({copyNumber})_{sequence:6 digits}
```

`PARENT` is the project's person code uppercased when the project has a person, otherwise
`ProjectCodeSupport.untitledMediaPrefix(project)`: the project code's prefix before `-PROJ-` /
`_PROJ_` (e.g. `DENG-PROJ-000004` → `DENG`), falling back to the project **name** uppercased
with every non-`[A-Z0-9]` run collapsed to `_` when the code carries neither marker.
`sequence` is `countByProject(project) + 1`, zero-padded to six digits. Concurrent creates on
the same project serialize through `CodeGenLock` keyed `video-code:{projectId}` so the
count-based sequence cannot collide.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `MISSING_REQUEST_PART` | The `data` or `file` part is absent from the multipart body — the parts are resolved before the controller body runs, so an absent `file` never reaches the service's own check |
| `400` | `VIDEO_VALIDATION_ERROR` | `data` is present but blank or unparseable; bean validation failed; `file` is present but empty; `projectCode` blank; `videoVersion` outside the seven allowed values; `versionNumber`/`copyNumber` null or < 1 |
| `400` | `BAD_REQUEST` | The multipart body itself could not be parsed |
| `401` | `TOKEN_MISSING` | No credentials supplied |
| `403` | `ACCESS_DENIED` | Caller lacks `video:create` |
| `404` | `PROJECT_NOT_FOUND` | `projectCode` matches no active project |
| `409` | `VIDEO_ALREADY_EXISTS` | The generated code already exists |
| `409` | `CONFLICT` | A database constraint rejected the insert (unique / FK / NOT NULL) |
| `413` | `UPLOAD_TOO_LARGE` | File over `spring.servlet.multipart.max-file-size` (**5 GB**) or request over `max-request-size` (**6 GB**); `details.maxBytes` carries the cap |
| `415` | `UNSUPPORTED_MEDIA_TYPE` | Request was not `multipart/form-data` |
| `500` | `INTERNAL_SERVER_ERROR` | The S3 upload failed (`UserStorageException` is not specifically mapped for platform controllers) |

Bean-validation failures land in `details` keyed by the constraint's property path — the
`@AssertTrue` checks report as `projectCodePresent`, `videoVersionValid`,
`versionNumberValid` and `copyNumberValid` rather than as the underlying field names:

```json
{
  "timestamp": "2026-02-03T09:14:22.481Z",
  "status": 400,
  "error": "VIDEO_VALIDATION_ERROR",
  "category": "VALIDATION",
  "message": "Validation failed for video data.",
  "hint": "Video submission rejected — fix the indicated fields and resubmit.",
  "path": "/api/video",
  "details": {
    "videoVersionValid": "Video version is required and must be one of: RAW, MASTER, RESTORED, ARCHIVE, ORIGINAL, 4K_MASTER, PROFESSIONAL."
  }
}
```

`ApiErrorResponse` also carries `traceId`, emitted only when a trace id is present in the MDC.
Like every response in this API it is serialized with `NON_NULL`, so `hint`, `traceId` and
`details` are omitted when unset.

**Example**

```bash
curl -s -X POST "{{BASE_URL}}/api/video" \
  -H "Cookie: khi_auth_token=$TOKEN" \
  -F 'data={"projectCode":"HASAZIRA-PROJ-000001","videoVersion":"RAW","versionNumber":1,"copyNumber":1,"originalTitle":"Hasa Zira live, Sulaimani 1991","language":"Kurdish"};type=application/json' \
  -F "file=@/path/to/hasa-zira-1991.mp4"
```

**Notes**

- Accepted container/codec types for the `file` part: _Not documented in source._ The service
  performs no extension or MIME check on upload — it rejects only an empty file. Note that
  the stream proxy can only map `.mp4`, `.webm`, `.ogg`, `.mov`, `.avi` and `.mkv` to a real
  `Content-Type`; anything else streams back as `application/octet-stream`.
- `fileName` defaults to the uploaded file's original filename when the payload leaves it
  blank.
- `duration` is taken from the payload when present (the browser probes it client-side); only
  when it is null/blank does the server attempt a best-effort extraction from the uploaded
  bytes. Unsupported containers simply leave it unset.
- Evicts the caches `videos:all`, `tags:suggest` and `keywords:suggest`.
- Writes a `CREATE` row to `video_audit_logs` recording the code, project and stored file URL.

---

### `POST /api/video/bulk`

Create many video records in one transaction from a JSON array. No multipart — each entry
carries a pre-uploaded `videoFileUrl`.

**Authority:** `video:create`
**Consumes:** `application/json` · **Produces:** `application/json`

**Request body** — an array of `VideoBulkCreateRequestDTO`. Same fields as the create DTO plus
`videoFileUrl` (String, S3 or external; may be null/blank).

```json
[
  {
    "projectCode": "HASAZIRA-PROJ-000001",
    "videoVersion": "MASTER",
    "versionNumber": 1,
    "copyNumber": 1,
    "originalTitle": "Reel 1",
    "videoFileUrl": "https://example-bucket.s3.amazonaws.com/videos/reel-1.mp4"
  },
  {
    "projectCode": "HASAZIRA-PROJ-000001",
    "videoVersion": "MASTER",
    "versionNumber": 1,
    "copyNumber": 2,
    "originalTitle": "Reel 2",
    "videoFileUrl": "https://example-bucket.s3.amazonaws.com/videos/reel-2.mp4"
  }
]
```

Entries are skipped, not rejected, when: the entry is null; `projectCode` is null/blank or
resolves to no active project; `videoVersion` is null or outside the seven allowed values;
`versionNumber` or `copyNumber` is null or < 1; or the generated code already exists. The
DTO's `@AssertTrue` constraints are **not** enforced here — the request body is bound with
`@RequestBody` without `@Valid`, so the service-side checks above are the only gate.

**Response** `200 OK` — a `BulkCreateResult`:

```json
{ "requested": 2, "inserted": 2, "skipped": 0, "elapsedMs": 118 }
```

| Field | Type | Description |
|---|---|---|
| `requested` | int | Size of the submitted array |
| `inserted` | int | Rows actually persisted |
| `skipped` | int | Entries rejected by the checks above |
| `elapsedMs` | long | Server-side wall time |

An empty or null array returns `{"requested":0,"inserted":0,"skipped":0,"elapsedMs":0}`
without touching the database, the cache or the audit log.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `JSON_PARSE_ERROR` | Body is not valid JSON or a field has the wrong type |
| `401` | `TOKEN_MISSING` | No credentials supplied |
| `403` | `ACCESS_DENIED` | Caller lacks `video:create` |
| `409` | `CONFLICT` | A database constraint rejected the batch (the whole transaction rolls back) |
| `415` | `UNSUPPORTED_MEDIA_TYPE` | `Content-Type` is not `application/json` |
| `500` | `DATABASE_ERROR` | The batch insert failed |

**Example**

```bash
curl -s -X POST "{{BASE_URL}}/api/video/bulk" \
  -H "Cookie: khi_auth_token=$TOKEN" \
  -H "Content-Type: application/json" \
  -d @videos.json
```

**Notes**

- Codes are generated with a per-project in-memory counter seeded from
  `countByProject(project) + 1`, using the same format as single create.
- Evicts `videos:all`, `tags:suggest` and `keywords:suggest`.
- Writes **one** `CREATE` summary row to `video_audit_logs` with no `videoId`/`videoCode`,
  detailing `requested`, `inserted`, `skipped` and `elapsedMs`.

---

### `PATCH /api/video/{videoCode}`

Partial update of an active record, optionally replacing the stored file.

**Authority:** `video:update`
**Consumes:** `multipart/form-data` · **Produces:** `application/json`

**Path parameters**

| Name | Type | Description |
|---|---|---|
| `videoCode` | string | Code of the record to update. Trimmed before lookup |

**Request parts**

| Part | Type | Required | Description |
|---|---|---|---|
| `data` | string (JSON) | yes | A `VideoUpdateRequestDTO` — the same field set as `VideoBaseRequestDTO`, all optional, no `@AssertTrue` constraints |
| `file` | file | no | Replacement video. When present and non-empty the new object is uploaded under the folder `videos/{videoCode}` (a fresh `{uuid}-{filename}` key, exactly as on create) and the previous S3 object is deleted if the URL changed and belongs to our bucket |

Semantics to know before calling:

- Fields are copied with `BeanUtils.copyProperties`, so **any field the DTO declares is
  written, including ones you left out of the JSON** — an omitted string deserializes to null
  and overwrites the stored value with null. Send the full object you want persisted, not just
  the changed keys. The exceptions are `physicalAvailability`, `videoVersion`, `tags` and
  `keywords`, which are applied only when non-null.
- `projectCode` may be sent but must equal the record's current project code — the project
  cannot be reassigned after creation.
- `videoVersion` is uppercased before storing. Unlike create, it is **not** re-validated
  against the seven allowed values on this path.
- Changing `videoVersion`, `versionNumber` or `copyNumber` does **not** regenerate
  `videoCode`; the code is fixed at creation.

**Request body**

```json
{
  "projectCode": "HASAZIRA-PROJ-000001",
  "originalTitle": "Hasa Zira live, Sulaimani 1991 (restored)",
  "videoStatus": "RESTORED",
  "note": "Color-corrected from the master reel",
  "tags": ["interview", "1990s", "restored"]
}
```

**Response** `200 OK` — the updated `VideoResponseDTO`.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `MISSING_REQUEST_PART` | The `data` part is absent |
| `400` | `VIDEO_VALIDATION_ERROR` | `data` blank or unparseable; the path code is whitespace-only; `projectCode` differs from the record's current project. `VideoUpdateRequestDTO` declares no constraints, so the shared `parseAndValidate` bean-validation branch never fires on this path |
| `400` | `BAD_REQUEST` | The multipart body could not be parsed |
| `401` | `TOKEN_MISSING` | No credentials supplied |
| `403` | `ACCESS_DENIED` | Caller lacks `video:update` |
| `404` | `VIDEO_NOT_FOUND` | No active record with that code (trashed records are not editable) |
| `409` | `STALE_VERSION` | Another user saved the same record first — `@Version` mismatch; `details.entity` is `Video` |
| `409` | `CONFLICT` | A database constraint rejected the update |
| `413` | `UPLOAD_TOO_LARGE` | Replacement file over the 5 GB / 6 GB multipart caps |
| `415` | `UNSUPPORTED_MEDIA_TYPE` | Request was not `multipart/form-data` |
| `500` | `INTERNAL_SERVER_ERROR` | The S3 upload or delete failed |

**Example**

```bash
curl -s -X PATCH "{{BASE_URL}}/api/video/HASAZIRA_VID_RAW_V1_Copy(1)_000001" \
  -H "Cookie: khi_auth_token=$TOKEN" \
  -F 'data={"projectCode":"HASAZIRA-PROJ-000001","originalTitle":"Hasa Zira live, Sulaimani 1991 (restored)","videoStatus":"RESTORED"};type=application/json'
```

**Notes**

- Evicts `videos:all`, `tags:suggest` and `keywords:suggest`.
- Writes an `UPDATE` row to `video_audit_logs` containing a `field: before -> after` diff over
  every tracked field (including `videoFileUrl`), or "no field changes detected" when nothing
  moved. Details are HTML-escaped before storage.

---

### `PATCH /api/video/{videoCode}/visibility`

Flip the record's public-visibility flag without sending the whole payload.

**Authority:** `video:update` — deliberately the same authority as a normal edit
**Consumes:** `application/json` · **Produces:** `application/json`

**Path parameters**

| Name | Type | Description |
|---|---|---|
| `videoCode` | string | Code of the record to toggle |

**Request body** — `VisibilityUpdateRequest`:

| Field | Type | Required | Description |
|---|---|---|---|
| `isPublic` | boolean | yes | `true` shows the record on the guest surface; `false` hides it. Boxed, so a missing or null value fails `@NotNull` |

```json
{ "isPublic": false }
```

**Response** `200 OK` — the record as `VideoResponseDTO`. Idempotent: when the flag already
holds the requested value the record is returned unchanged with no save, no version bump, no
cache eviction and no audit row.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `VALIDATION_ERROR` | `isPublic` missing or null; `details.isPublic` reads "isPublic is required" |
| `400` | `JSON_PARSE_ERROR` | Body is not valid JSON, or `isPublic` is not a boolean |
| `400` | `VIDEO_VALIDATION_ERROR` | The path code is whitespace-only |
| `401` | `TOKEN_MISSING` | No credentials supplied |
| `403` | `ACCESS_DENIED` | Caller lacks `video:update` |
| `404` | `VIDEO_NOT_FOUND` | No active record with that code — trashed records are not silently resurrected |
| `409` | `STALE_VERSION` | Concurrent edit detected via `@Version` |
| `415` | `UNSUPPORTED_MEDIA_TYPE` | `Content-Type` is not `application/json` |

**Example**

```bash
curl -s -X PATCH "{{BASE_URL}}/api/video/HASAZIRA_VID_RAW_V1_Copy(1)_000001/visibility" \
  -H "Cookie: khi_auth_token=$TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"isPublic": false}'
```

**Notes**

- `isPublic` on the video is independent of the project's `isVisibleToPublic`, which is
  mirrored into the response as `projectVisibleToPublic`.
- On an actual change: evicts `videos:all`, `tags:suggest` and `keywords:suggest`, and writes
  an `UPDATE` row reading `Updated video record: isPublic: {before} -> {after}`.

---

### `DELETE /api/video/{videoCode}`

Soft delete — move the record to the trash. The S3 object is preserved so the record can be
restored.

**Authority:** `video:delete`

**Path parameters**

| Name | Type | Description |
|---|---|---|
| `videoCode` | string | Code of the active record to trash |

**Response** `204 No Content` — empty body.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `VIDEO_VALIDATION_ERROR` | The path code is whitespace-only |
| `401` | `TOKEN_MISSING` | No credentials supplied |
| `403` | `ACCESS_DENIED` | Caller lacks `video:delete` |
| `404` | `VIDEO_NOT_FOUND` | No active record with that code (already trashed records 404) |
| `409` | `STALE_VERSION` | Concurrent edit detected via `@Version` |

**Example**

```bash
curl -s -i -X DELETE "{{BASE_URL}}/api/video/HASAZIRA_VID_RAW_V1_Copy(1)_000001" \
  -H "Cookie: khi_auth_token=$TOKEN"
```

**Notes**

- Sets `removedAt = now()` and `removedBy = <username>` (`anonymous` when no
  `Authentication`).
- Trashed records disappear from `GET /api/video`, `GET /api/video/search`,
  `GET /api/video/{videoCode}` and the guest stream — but remain streamable through the
  authenticated `GET /api/video/{videoCode}/stream`.
- Evicts `videos:all`, `tags:suggest` and `keywords:suggest`.
- Writes a `DELETE` row to `video_audit_logs` ("Sent video record to trash").

---

### `POST /api/video/{videoCode}/restore`

Bring a trashed record back to active.

**Authority:** `video:delete` (re-checked inside the service)

**Path parameters**

| Name | Type | Description |
|---|---|---|
| `videoCode` | string | Code of the trashed record |

**Response** `200 OK` — the restored `VideoResponseDTO`, with `removedAt` and `removedBy`
cleared (and therefore omitted from the JSON).

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `VIDEO_VALIDATION_ERROR` | The path code is whitespace-only; the record is not in trash; the parent project is itself in trash ("Restore the project first.") |
| `401` | `TOKEN_MISSING` | No credentials supplied |
| `403` | `ACCESS_DENIED` | Caller lacks `video:delete`, at the `@PreAuthorize` or at the service's own re-check ("Only ADMIN can permanently delete video records") |
| `404` | `VIDEO_NOT_FOUND` | No record with that code, active or trashed |
| `409` | `STALE_VERSION` | Concurrent edit detected via `@Version` |

**Example**

```bash
curl -s -X POST "{{BASE_URL}}/api/video/HASAZIRA_VID_RAW_V1_Copy(1)_000001/restore" \
  -H "Cookie: khi_auth_token=$TOKEN"
```

**Notes**

- Also refreshes `updatedAt` / `updatedBy` to the restoring actor.
- Evicts `videos:all`, `tags:suggest` and `keywords:suggest`.
- Writes a `RESTORE` row to `video_audit_logs`.

---

### `GET /api/video/trash`

Paged list of trashed video records (`removedAt IS NOT NULL`).

**Authority:** `video:delete` (re-checked inside the service)

**Query parameters**

| Name | Type | Default | Description |
|---|---|---|---|
| `page` | int | `0` | Zero-based page index |
| `size` | int | `100` | Page size |
| `sort` | string | — | Accepted by the `Pageable` resolver but not applied — rows come back in repository order |

No `VideoFilterParams` binding here: the trash listing takes **no** filter or sort parameters.

**Response** `200 OK` — a `Page` envelope whose `content[]` elements are `VideoResponseDTO`,
each carrying `removedAt` and `removedBy`:

```json
{
  "content": [
    {
      "id": 41,
      "videoCode": "HASAZIRA_VID_RAW_V1_Copy(1)_000001",
      "originalTitle": "Hasa Zira live, Sulaimani 1991",
      "videoFileUrl": "/api/video/HASAZIRA_VID_RAW_V1_Copy(1)_000001/stream",
      "physicalAvailability": true,
      "isPublic": true,
      "projectVisibleToPublic": true,
      "createdAt": "2026-02-03T09:14:22.481Z",
      "updatedAt": "2026-02-03T09:14:22.481Z",
      "removedAt": "2026-08-20T11:02:41.117Z",
      "createdBy": "aram",
      "updatedBy": "aram",
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
| `401` | `TOKEN_MISSING` | No credentials supplied |
| `403` | `ACCESS_DENIED` | Caller lacks `video:delete`, at the `@PreAuthorize` or at the service's re-check |
| `500` | `DATABASE_ERROR` | The trash query fails |

**Example**

```bash
curl -s "{{BASE_URL}}/api/video/trash?page=0&size=50" \
  -H "Cookie: khi_auth_token=$TOKEN"
```

**Notes**

- Reads from the database — the `videos:all` cache only holds active records.
- Writes a `LIST` row to `video_audit_logs` ("Listed video trash …").

---

### `DELETE /api/video/{videoCode}/purge`

Permanently delete a trashed record and its S3 object. Not reversible.

**Authority:** `video:delete` (re-checked inside the service)

**Path parameters**

| Name | Type | Description |
|---|---|---|
| `videoCode` | string | Code of a record that is already in the trash |

**Response** `204 No Content` — empty body.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `VIDEO_VALIDATION_ERROR` | The path code is whitespace-only; the record is still active ("Video must be in trash before permanent deletion. Trash it first.") |
| `401` | `TOKEN_MISSING` | No credentials supplied |
| `403` | `ACCESS_DENIED` | Caller lacks `video:delete`, at the `@PreAuthorize` or at the service's re-check |
| `404` | `VIDEO_NOT_FOUND` | No record with that code |
| `500` | `INTERNAL_SERVER_ERROR` | The S3 delete failed after the row was removed |

No `409` here: nothing in the schema holds a foreign key onto `videos`. The six
`@ElementCollection` child tables (`video_subjects`, `video_genres`, `video_colors`,
`video_usages`, `video_tags`, `video_keywords`) are deleted with the row, and
`video_audit_logs.video_id` is a plain `BIGINT` column rather than a relation — purged rows
leave their audit history behind, now pointing at an id that no longer exists.

**Example**

```bash
curl -s -i -X DELETE "{{BASE_URL}}/api/video/HASAZIRA_VID_RAW_V1_Copy(1)_000001/purge" \
  -H "Cookie: khi_auth_token=$TOKEN"
```

**Notes**

- The `PURGE` audit row is written **before** the row is deleted, so the log keeps the
  `videoId`, `videoCode` and title. Audit rows are written in a `REQUIRES_NEW` transaction and
  survive a rollback of the purge itself.
- The S3 object is deleted only when its URL belongs to our bucket; externally hosted URLs
  (common on bulk-created rows) are left alone.
- Evicts `videos:all`, `tags:suggest` and `keywords:suggest`.

---

### `GET /api/video/{videoCode}/stream`

Proxy the video bytes through the API. The S3 URL is never sent to the browser.

**Authority:** none declared — `VideoStreamAPI` has no `@PreAuthorize`, so the only gate is
the `/api/**` → `authenticated()` rule in `SecurityConfig`. A valid token of any role is
enough; `video:read` is **not** required.

**Path parameters**

| Name | Type | Description |
|---|---|---|
| `videoCode` | string | Code of the record. Looked up with `findByVideoCode` — **trashed records still stream on this endpoint** |

**Request headers**

| Name | Required | Description |
|---|---|---|
| `Range` | no | `bytes=start-end`, or the open-ended `bytes=start-`. When absent, the server returns the first 2 MB (`bytes=0-2097151`, clamped to the object size) so playback can start without downloading the whole file |

**Response** `206 Partial Content` — always, even for an unranged request, because players
need partial content to enable seeking. The body is the raw byte slice.

Response headers:

| Header | Value |
|---|---|
| `Content-Type` | Derived from the stored file URL by lowercasing it and taking the first `contains` hit, in this order: `.mp4` → `video/mp4`, `.webm` → `video/webm`, `.ogg` → `video/ogg`, `.mov` → `video/quicktime`, `.avi` → `video/x-msvideo`, `.mkv` → `video/x-matroska`, otherwise `application/octet-stream`. It is a substring test, not an extension test — the token may sit anywhere in the URL |
| `Content-Length` | Length of the returned slice |
| `Content-Range` | `bytes {start}-{end}/{total}` |
| `Accept-Ranges` | `bytes` |
| `Content-Disposition` | `inline; filename="<ascii>"; filename*=UTF-8''<percent-encoded>` — RFC 5987, so Kurdish/Arabic filenames survive. Falls back to `video-{videoCode}.{subtype}` when `fileName` is empty or reduces to nothing in ASCII |
| `Cache-Control` | `no-store, private` on this authenticated endpoint |
| `X-Content-Type-Options` | `nosniff` |

Range-parsing behavior, exactly as implemented: a header that does not start with `bytes=`,
has no `-`, or contains a non-numeric bound falls back to the first 2 MB window. `start` below
0 is clamped to 0, `end` at or beyond the object size is clamped to `total - 1`, and an `end`
below `start` is raised to `start`. Two edge cases are worth calling out:

- An **open-ended** range (`bytes=2097152-`, or anything blank after the dash) resolves `end`
  to `total - 1` — the whole remainder of the object comes back in one `206`, with no 2 MB
  cap. Only the *absent*-header case is capped at 2 MB.
- A **suffix** range such as `bytes=-500` is read as start `0`, end `500` — it returns the
  **first** 501 bytes, not the last 500, which departs from RFC 7233.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `401` | `TOKEN_MISSING` | No credentials supplied |
| `404` | `NOT_FOUND` | No record with that code ("Video not found"); the record has no `videoFileUrl` ("Video file not available"); or S3 reports the object missing ("Video not available for {code}") |
| `500` | `INTERNAL_SERVER_ERROR` | The S3 key could not be extracted from the stored URL ("Video file not available"); an S3 failure that is not a 404, or an `IOException` while reading the slice ("Failed to stream video") |

Stream errors are `ResponseStatusException`s translated by `ApiExceptionHandler`, so 404s
carry the generic `NOT_FOUND` code — not `VIDEO_NOT_FOUND`.

**Example**

```bash
# First window (server picks bytes 0-2097151)
curl -s -i "{{BASE_URL}}/api/video/HASAZIRA_VID_RAW_V1_Copy(1)_000001/stream" \
  -H "Cookie: khi_auth_token=$TOKEN" -o /dev/null

# Explicit range
curl -s -i "{{BASE_URL}}/api/video/HASAZIRA_VID_RAW_V1_Copy(1)_000001/stream" \
  -H "Cookie: khi_auth_token=$TOKEN" \
  -H "Range: bytes=2097152-4194303" -o /dev/null
```

**Notes**

- Every request issues an S3 `HEAD` for the object size plus a ranged `GET` for the slice; the
  full file is never held in JVM heap.
- No audit row is written — `VideoStreamAPI` does not use `VideoAuditService`.

---

### `GET /api/guest/video/{videoCode}/stream`

The public twin of the endpoint above, declared on the same controller. Listed here for
completeness; the visitor-facing contract belongs in the external docs.

**Authority:** none — permitted anonymously by the `/api/guest/**` rule in `SecurityConfig`.

Differences from the authenticated endpoint:

| Aspect | `/api/video/{code}/stream` | `/api/guest/video/{code}/stream` |
|---|---|---|
| Authentication | Required | Not required |
| Lookup | `findByVideoCode` — trashed records still stream | `findByVideoCodeAndRemovedAtIsNull` — trashed records 404 |
| `isPublic` check | Not consulted | Not consulted either — the flag is enforced by the guest catalog endpoints, not by this proxy |
| `Cache-Control` | `no-store, private` | `public, max-age=300` |

Everything else — the forced `206`, the 2 MB default window, the range parsing, the header
set and the error mapping — is identical.

**Example**

```bash
curl -s -i "{{BASE_URL}}/api/guest/video/HASAZIRA_VID_RAW_V1_Copy(1)_000001/stream" \
  -H "Range: bytes=0-1023" -o /dev/null
```

---

## Audit actions

Every `VideoAPI` endpoint writes to `video_audit_logs` through `VideoAuditService.record`,
which runs in a `REQUIRES_NEW` transaction so the audit row survives even if the business
transaction rolls back. Details text is HTML-escaped before storage.

| Endpoint | Action | Row scope |
|---|---|---|
| `GET /api/video` | `LIST` | No video reference (`videoId`/`videoCode` null) |
| `GET /api/video/search` | `SEARCH` | No video reference |
| `GET /api/video/{videoCode}` | `READ` | The fetched record |
| `POST /api/video` | `CREATE` | The created record |
| `POST /api/video/bulk` | `CREATE` | One summary row, no video reference |
| `PATCH /api/video/{videoCode}` | `UPDATE` | The updated record, with a field-level diff |
| `PATCH /api/video/{videoCode}/visibility` | `UPDATE` | The updated record — only when the flag actually changed |
| `DELETE /api/video/{videoCode}` | `DELETE` | The trashed record |
| `POST /api/video/{videoCode}/restore` | `RESTORE` | The restored record |
| `GET /api/video/trash` | `LIST` | No video reference |
| `DELETE /api/video/{videoCode}/purge` | `PURGE` | The record, captured before the row is deleted |
| `GET /api/video/{videoCode}/stream` | — | No audit row |
| `GET /api/guest/video/{videoCode}/stream` | — | No audit row |

`VideoAuditAction.REMOVE` exists in the enum but is never emitted by `VideoService`.

Each row also captures the actor (`actorUserId`, `actorUsername`, `actorDisplayName`,
`actorAuthorities`, `actorPermissions`), the session (`sessionId`, `deviceInfo`, `ipAddress`,
`sessionLoginTimestamp`, `sessionExpiresAt`, `sessionActive`), the request
(`requestMethod`, `requestPath`), `occurredAt`, and — when the video has a project — its
`projectId`/`projectCode`/`projectName`, the project's person, and the joined
`categoryCodes`.

## Caching

| Cache | Contents | Config |
|---|---|---|
| `videos:all` | The full list of active `VideoResponseDTO`s, as one entry | `maximumSize=1`, `expireAfterWrite=10m` (Caffeine, in-memory) |

`VideoReadCache.evictAll()` is called after **every** video mutation (create, bulk create,
update, visibility change, delete, restore, purge) and evicts three caches in one shot:

- `videos:all` — the video list itself
- `tags:suggest` — the cross-entity tag autocomplete
- `keywords:suggest` — the cross-entity keyword autocomplete

The tag/keyword suggest caches are evicted because video `tags` and `keywords` feed those
cross-entity autocompletes. Only `GET /api/video` reads the cache; every other read endpoint
(`/search`, `/{videoCode}`, `/trash`) queries PostgreSQL directly. The cache is per-JVM
(Caffeine, not Redis), so a multi-instance deployment evicts only on the node that handled the
write.

## Related

- [Internal API index](../README.md)
- [Conventions — page envelope, timestamps, error shape](../01-conventions.md)
- [Audio API](./audio.md) — same lifecycle and filter design over `/api/audio`
- [Image API](./image.md) — same lifecycle over `/api/image`
- [Text API](./text.md) — same lifecycle over `/api/text`
- [Project API](./project.md) — the parent entity `projectCode` points at, and the source of
  `projectVisibleToPublic`
