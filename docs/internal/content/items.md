# Unified Items API

> **Audience:** Staff (back-office) · **Base path:** `/api/items` · **Source:** `src/main/java/ak/dev/khi_archive_platform/platform/api/items/ItemsAPI.java`

`GET /api/items` merges the four media read-caches (audio, video, image, text) into a single
paged list, so the back-office grid can search, filter and sort across every media type in one
call. Each row carries a flat summary for the card view **plus** the full original per-type DTO
under the matching slot, so a detail panel needs no follow-up request.
`PATCH /api/items/{type}/{code}/visibility` is the matching lightweight write: it flips one row's
`isPublic` flag without re-sending the full edit payload.

## Access

| Requirement | Value |
|---|---|
| Authentication | Required — JWT via `Authorization: Bearer <jwt>` (read first) or the `khi_auth_token` HttpOnly cookie (fallback) |
| Class-level `@PreAuthorize` | None. `ItemsAPI` carries no class-level annotation; authority is decided per endpoint |
| Authority — `GET /api/items` | `hasAuthority('audio:read') and hasAuthority('video:read') and hasAuthority('image:read') and hasAuthority('text:read')` — all four are required because a page can contain rows of any type and there is no per-type filtering at the security layer |
| Authority — `PATCH /api/items/{type}/{code}/visibility` | `{type}:update` — one of `audio:update`, `video:update`, `image:update`, `text:update`. Not declarative: the value depends on the `{type}` path variable, so `ItemVisibilityService` performs the check in code |
| Roles that hold them by default | ADMIN (holds every `Permission` through the role). EMPLOYEE (`audio/video/image/text` `:read` and `:update` are all in `EMPLOYEE_DEFAULT_PERMISSIONS`) |
| Roles that do **not** | GUEST, and TEACHER — `TEACHER_DEFAULT_PERMISSIONS` is only `maqam:read` + `maqam:vote` |

## Endpoints

| Method | Path | Authority | Purpose |
|---|---|---|---|
| `GET` | `/api/items` | `audio:read` **and** `video:read` **and** `image:read` **and** `text:read` | Paged, searchable, filterable, sortable union of all active media |
| `PATCH` | `/api/items/{type}/{code}/visibility` | `audio:update` / `video:update` / `image:update` / `text:update`, selected by `{type}` | Toggle one media row's `isPublic` flag |

---

### `GET /api/items`

Lists every active (non-trashed) audio, video, image and text row as one merged page.

**Authority:** `hasAuthority('audio:read') and hasAuthority('video:read') and hasAuthority('image:read') and hasAuthority('text:read')`

**Query parameters**

Every parameter is optional; with none supplied the endpoint returns the union of all four
caches sorted by `updatedAt` descending. Each one is bound as an individual `@RequestParam` and
copied into `ItemFilterParams` (`platform/dto/items/ItemFilterParams.java`), whose fields map 1:1
to the names below.

| Name | Type | Default | Description |
|---|---|---|---|
| `q` | string | – | Free-text search. Trimmed, lower-cased, matched as a case-insensitive **substring**. Blank/whitespace is treated as absent. See "Searchable fields" below |
| `types` | enum list — `AUDIO`, `VIDEO`, `IMAGE`, `TEXT` | all four | Repeat the parameter: `types=AUDIO&types=VIDEO`. Buckets outside the set are skipped entirely — no cache read, no mapping |
| `projectCodes` | string list | – | Repeat. Keeps rows whose `projectCode` is in the set. Case-insensitive, values trimmed |
| `personCodes` | string list | – | Repeat. Case-insensitive. A row whose `personCode` is null or blank is matched by the literal value `UNTITLED` |
| `categoryCodes` | string list | – | Repeat. Keeps rows whose project joins **any** of these category codes. Case-insensitive. A row with no categories never matches |
| `languages` | string list | – | Repeat. Case-insensitive equality against the row's `language`. `IMAGE` rows have no language field and are dropped whenever this filter is set |
| `isPublic` | boolean | – | Exact match on the media row's own `isPublic` flag |
| `projectVisibleToPublic` | boolean | – | Exact match on the parent project's `isVisibleToPublic` flag, mirrored onto each row |
| `createdFrom` | date `yyyy-MM-dd` | – | Inclusive lower bound on `createdAt`, resolved to `00:00:00.000000000` in the archive zone (`Asia/Baghdad`, UTC+3, no DST) |
| `createdTo` | date `yyyy-MM-dd` | – | Inclusive upper bound on `createdAt`, resolved to `23:59:59.999999999` in the archive zone |
| `updatedFrom` | date `yyyy-MM-dd` | – | Inclusive lower bound on `updatedAt`, same day-bound resolution |
| `updatedTo` | date `yyyy-MM-dd` | – | Inclusive upper bound on `updatedAt`, same day-bound resolution |
| `sortBy` | string | `updatedAt` | Sort field. Trimmed and lower-cased before matching. Unrecognized values fall back to sorting on `updatedAt`. See "Sort fields" |
| `sortDirection` | string | `desc` only when `sortBy` is omitted or is exactly `createdAt`/`updatedAt`; `asc` for every other value | Trimmed; `desc` (any casing) sorts descending, any other non-null value sorts ascending |
| `page` | int | `0` | Zero-based page index (Spring `Pageable`) |
| `size` | int | `50` | Page size — `@PageableDefault(size = 50)` |
| `sort` | string | – | Accepted by Spring's `Pageable` resolver but **ignored**: the merged list is sorted only from `sortBy`/`sortDirection`, and the page slice is cut in memory |

When a date-range filter is set, a row whose corresponding timestamp is null is excluded.

Two things to know about the parameter contract:

- The list parameters (`types`, `projectCodes`, `personCodes`, `categoryCodes`, `languages`) are
  documented in the controller as repeated parameters, and that is the form shown here. Whether a
  single comma-separated value is also accepted: _Not documented in source._
- The controller Javadoc describes `updatedFrom`/`updatedTo` as "ISO-8601 instants", but the
  method signature binds them as `LocalDate` with `@DateTimeFormat(iso = ISO.DATE)`, exactly like
  `createdFrom`/`createdTo`. The signature is what runs: send `yyyy-MM-dd`.

**Sort fields** (`sortBy`, trimmed and case-insensitive; aliases in the same row select the same
sort key)

| Accepted values | Sorts on | Null handling |
|---|---|---|
| `createdAt`, `created`, `added` | `createdAt` | Nulls last |
| `updatedAt`, `updated`, `modified` | `updatedAt` | Nulls last |
| `title`, `name`, `alpha`, `alphabetical` | `title`, case-insensitive | Nulls last |
| `code` | `code`, case-insensitive | Nulls last |
| `projectName`, `project` | `projectName`, case-insensitive | Nulls last |
| `personName`, `person` | `personName`, case-insensitive | Nulls last |
| `type` | `type` name, case-insensitive | Nulls last |

`Comparator.reversed()` is applied for a descending sort, which also flips the nulls-last
ordering to nulls-first.

The aliases pick the same sort key but **not** the same default direction: the "is this a date
sort?" test compares the raw `sortBy` token against `createdat`/`updatedat` only. So with
`sortDirection` omitted, `sortBy=createdAt` sorts descending while `sortBy=created`,
`sortBy=added`, `sortBy=updated`, `sortBy=modified` — and any unrecognized value, which still
sorts on `updatedAt` — sort ascending. Send `sortDirection` explicitly to avoid the difference.

**Searchable fields** (`q`, matched per type, first hit wins)

| Type | Fields scanned |
|---|---|
| `AUDIO` | `audioCode`, `originTitle`, `alterTitle`, `centralKurdishTitle`, `romanizedTitle`, `fileName`, `projectCode`, `projectName`, `personCode`, `personName`, `speaker`, `composer`, `poet`, `producer`, `city`, `region`, `description`, `abstractText`, `tags[]`, `keywords[]`, `genre[]`, `categoryCodes[]` |
| `VIDEO` | `videoCode`, `originalTitle`, `alternativeTitle`, `titleInCentralKurdish`, `romanizedTitle`, `fileName`, `projectCode`, `projectName`, `personCode`, `personName`, `creatorArtistDirector`, `producer`, `event`, `location`, `personShownInVideo`, `description`, `tags[]`, `keywords[]`, `subject[]`, `genre[]`, `categoryCodes[]` |
| `IMAGE` | `imageCode`, `originalTitle`, `alternativeTitle`, `titleInCentralKurdish`, `romanizedTitle`, `fileName`, `projectCode`, `projectName`, `personCode`, `personName`, `creatorArtistPhotographer`, `event`, `location`, `personShownInImage`, `description`, `tags[]`, `keywords[]`, `subject[]`, `genre[]`, `categoryCodes[]` |
| `TEXT` | `textCode`, `originalTitle`, `alternativeTitle`, `titleInCentralKurdish`, `romanizedTitle`, `fileName`, `projectCode`, `projectName`, `personCode`, `personName`, `author`, `documentType`, `description`, `tags[]`, `keywords[]`, `subject[]`, `genre[]`, `categoryCodes[]` |

**Response** `200 OK`

Standard Spring `Page` envelope — see [`../01-conventions.md`](../01-conventions.md) for the
envelope fields. The `content[]` element is `ItemDTO`
(`platform/dto/items/ItemDTO.java`):

| Field | Type | Notes |
|---|---|---|
| `type` | enum | Discriminator: `AUDIO`, `VIDEO`, `IMAGE` or `TEXT`. Tells you which payload slot is populated |
| `id` | number | Primary key of the underlying media row |
| `code` | string | Business code — `audioCode` / `videoCode` / `imageCode` / `textCode` by type |
| `title` | string | First non-blank of the per-type title chain, then `fileName`, then the code. Audio chain: `originTitle` → `alterTitle` → `centralKurdishTitle` → `romanizedTitle`. Video/image/text chain: `originalTitle` → `alternativeTitle` → `titleInCentralKurdish` → `romanizedTitle` |
| `projectId` | number | Parent project (collection) id |
| `projectCode` | string | Parent project code |
| `projectName` | string | Parent project name |
| `projectVisibleToPublic` | boolean | Project-level public flag, mirrored onto the row |
| `personId` | number | Person linked through the project |
| `personCode` | string | Omitted for untitled projects (no person) |
| `personName` | string | Omitted for untitled projects (no person) |
| `categoryCodes` | string[] | Category codes joined through the project |
| `fileUrl` | string | Proxy URL of the media bytes: `/api/audio/{code}/stream`, `/api/video/{code}/stream`, `/api/image/{code}/view`, `/api/text/{code}/read`. Never an S3 URL, and always the authenticated form — see below |
| `coverImageUrl` | string | `TEXT` only, and only when a cover was uploaded: `/api/text/{code}/cover`. Omitted for audio/video/image |
| `fileExtension` | string | From `fileExtension` (audio) or `extension` (video/image/text) |
| `fileSize` | string | Stored as text, as on the source DTO |
| `language` | string | Not set for `IMAGE` rows — the image DTO has no language field |
| `dialect` | string | Set for `AUDIO`, `VIDEO`, `TEXT`. Not set for `IMAGE` |
| `tags` | string[] | Canonicalized tag list of the media row |
| `keywords` | string[] | Canonicalized keyword list of the media row |
| `isPublic` | boolean | The media row's own public flag |
| `createdAt` | timestamp | |
| `updatedAt` | timestamp | |
| `removedAt` | timestamp | Always null here (the caches hold only `removedAt IS NULL` rows), so it is omitted |
| `createdBy` | string | Username of the creating actor |
| `updatedBy` | string | Username of the last updating actor |
| `removedBy` | string | Always null here, so it is omitted |
| `audio` | object | Full `AudioResponseDTO`. Present only when `type` is `AUDIO` |
| `video` | object | Full `VideoResponseDTO`. Present only when `type` is `VIDEO` |
| `image` | object | Full `ImageResponseDTO`. Present only when `type` is `IMAGE` |
| `text` | object | Full `TextResponseDTO`. Present only when `type` is `TEXT` |

Exactly one of `audio` / `video` / `image` / `text` is populated per row; the other three are
null and therefore omitted (`ItemDTO` is annotated `@JsonInclude(NON_NULL)` on top of the global
`spring.jackson.default-property-inclusion=non_null`).

**`fileUrl` and `coverImageUrl` are staff-only paths**

Both always carry the authenticated form of the byte proxy — `/api/audio/{code}/stream`,
`/api/video/{code}/stream`, `/api/image/{code}/view`, `/api/text/{code}/read`,
`/api/text/{code}/cover` — on **every** row, including rows whose `isPublic` and
`projectVisibleToPublic` are both `true`. `ItemsService` copies the value straight off the
per-type response DTO (`AudioResponseDTO.audioFileUrl` and its siblings), and
`AudioService`/`VideoService`/`ImageService`/`TextService` build that string unconditionally. The
URL family follows the endpoint that produced the row, never the visibility of the record.

So these values cannot be forwarded to an anonymous surface. `/api/**` is `authenticated()` while
only `/api/guest/**` is `permitAll()`, so a logged-out page that renders them gets `401` /
`TOKEN_MISSING`. A public page must read the record from `GET /api/guest/audios/{audioCode}`,
`/api/guest/videos/{videoCode}`, `/api/guest/texts/{textCode}` or `/api/guest/images/{imageCode}`
and use the `/api/guest/…` path the guest DTO carries — see
[`../../external/06-media.md`](../../external/06-media.md). Splicing `/guest` into an `/api/items`
URL by hand happens to produce a working string today, but nothing in the API guarantees the two
path shapes stay in step.

The nested payloads are the same authenticated paths, not a public alternative:
`item.audio.audioFileUrl`, `item.video.videoFileUrl`, `item.image.imageFileUrl`,
`item.text.textFileUrl` and `item.text.coverImageUrl`.

```json
{
  "content": [
    {
      "type": "AUDIO",
      "id": 412,
      "code": "MAHMUD_AUD_MASTER_V1_Copy(1)_000001",
      "title": "Bangî Sibê",
      "projectId": 57,
      "projectCode": "MAHMUD-PROJ-000001",
      "projectName": "Mahmud field recordings",
      "projectVisibleToPublic": true,
      "personId": 19,
      "personCode": "MAHMUD",
      "personName": "Mahmud Ahmad",
      "categoryCodes": ["MUSIC", "FIELD"],
      "fileUrl": "/api/audio/MAHMUD_AUD_MASTER_V1_Copy(1)_000001/stream",
      "fileExtension": "wav",
      "fileSize": "84.2 MB",
      "language": "Kurdish",
      "dialect": "Sorani",
      "tags": ["maqam", "field recording"],
      "keywords": ["bangi sibe", "morning song"],
      "isPublic": false,
      "createdAt": "2026-07-29 11:04:12",
      "updatedAt": "2026-08-25 09:31:58",
      "createdBy": "hana",
      "updatedBy": "akar",
      "audio": {
        "id": 412,
        "audioCode": "MAHMUD_AUD_MASTER_V1_Copy(1)_000001",
        "projectCode": "MAHMUD-PROJ-000001",
        "audioFileUrl": "/api/audio/MAHMUD_AUD_MASTER_V1_Copy(1)_000001/stream",
        "fileName": "bangi_sibe_master.wav",
        "originTitle": "Bangî Sibê",
        "speaker": "Mahmud Ahmad",
        "language": "Kurdish",
        "dialect": "Sorani",
        "duration": "00:07:41",
        "sampleRate": "96000",
        "isPublic": false,
        "projectVisibleToPublic": true
      }
    },
    {
      "type": "TEXT",
      "id": 88,
      "code": "MAHMUD_TXT_MASTER_V1_Copy(1)_000003",
      "title": "Notebook of songs",
      "projectId": 57,
      "projectCode": "MAHMUD-PROJ-000001",
      "projectName": "Mahmud field recordings",
      "projectVisibleToPublic": true,
      "personId": 19,
      "personCode": "MAHMUD",
      "personName": "Mahmud Ahmad",
      "categoryCodes": ["MUSIC"],
      "fileUrl": "/api/text/MAHMUD_TXT_MASTER_V1_Copy(1)_000003/read",
      "coverImageUrl": "/api/text/MAHMUD_TXT_MASTER_V1_Copy(1)_000003/cover",
      "fileExtension": "pdf",
      "fileSize": "3.1 MB",
      "language": "Kurdish",
      "dialect": "Sorani",
      "tags": ["notebook"],
      "keywords": ["lyrics"],
      "isPublic": true,
      "createdAt": "2026-08-02 15:22:40",
      "updatedAt": "2026-08-20 12:00:03",
      "createdBy": "hana",
      "updatedBy": "hana",
      "text": {
        "id": 88,
        "textCode": "MAHMUD_TXT_MASTER_V1_Copy(1)_000003",
        "projectCode": "MAHMUD-PROJ-000001",
        "textFileUrl": "/api/text/MAHMUD_TXT_MASTER_V1_Copy(1)_000003/read",
        "coverImageUrl": "/api/text/MAHMUD_TXT_MASTER_V1_Copy(1)_000003/cover",
        "originalTitle": "Notebook of songs",
        "author": "Mahmud Ahmad",
        "documentType": "Manuscript",
        "pageCount": 64,
        "isPublic": true,
        "projectVisibleToPublic": true
      }
    }
  ],
  "totalElements": 2,
  "totalPages": 1,
  "number": 0,
  "size": 50,
  "first": true,
  "last": true,
  "numberOfElements": 2,
  "empty": false
}
```

The embedded `audio` / `text` objects above are abbreviated for readability — the real response
carries every non-null field of `AudioResponseDTO` / `TextResponseDTO`.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `TYPE_MISMATCH` | `types` contains a value that is not `AUDIO`/`VIDEO`/`IMAGE`/`TEXT`, a date parameter is not `yyyy-MM-dd`, or `isPublic`/`projectVisibleToPublic` is not a boolean. `details` carries `parameter`, `rejectedValue`, `expectedType` |
| `401` | `TOKEN_MISSING` | No `khi_auth_token` cookie on the request |
| `401` | `TOKEN_EXPIRED` | Token past its expiry |
| `401` | `TOKEN_REVOKED` | Token blacklisted by logout or a forced session kill |
| `401` | `TOKEN_MALFORMED` / `TOKEN_INVALID_SIGNATURE` / `TOKEN_INVALID` | Unparseable, tampered, or otherwise unverifiable token |
| `403` | `ACCESS_DENIED` | Caller is missing **any** of the four `*:read` authorities. `details.requiredAuthority` reports only the first authority in the expression (`audio:read`), because the handler regex extracts a single `hasAuthority('…')`; `details.actorAuthorities` lists what the caller actually holds |
| `500` | `DATABASE_ERROR` | A cold cache had to reload from PostgreSQL and the query failed |
| `504` | `TIMEOUT` | The cold-cache reload query timed out |
| `500` | `INTERNAL_SERVER_ERROR` | Unhandled failure; correlate with `traceId` |

**Example**

```bash
# Everything, newest first (defaults)
curl -s "{{BASE_URL}}/api/items?page=0&size=50" \
  -H "Cookie: khi_auth_token=$TOKEN"

# Audio + video only, hidden rows, in a project, searched, sorted by title ascending
curl -s -G "{{BASE_URL}}/api/items" \
  --data-urlencode "q=maqam" \
  --data-urlencode "types=AUDIO" \
  --data-urlencode "types=VIDEO" \
  --data-urlencode "projectCodes=MAHMUD-PROJ-000001" \
  --data-urlencode "isPublic=false" \
  --data-urlencode "createdFrom=2026-01-01" \
  --data-urlencode "createdTo=2026-08-26" \
  --data-urlencode "sortBy=title" \
  --data-urlencode "sortDirection=asc" \
  --data-urlencode "page=0" \
  --data-urlencode "size=25" \
  -H "Cookie: khi_auth_token=$TOKEN"

# Rows whose project has no linked person
curl -s "{{BASE_URL}}/api/items?personCodes=UNTITLED" \
  -H "Cookie: khi_auth_token=$TOKEN"
```

**Notes**

- **Cache-backed, no DB round-trip when warm.** The four buckets are read from the Caffeine
  caches `audios:all`, `videos:all`, `images:all`, `texts:all` (each `maximumSize=1`, 10-minute
  TTL — see `platform/config/CacheConfig.java`). A miss runs
  `findAllByRemovedAtIsNull()` on that one table. The endpoint response itself is **not** cached.
- Filtering, mapping, sorting and the page slice all happen in memory: one linear pass per
  bucket, one merge, one sort, then `PaginationSupport.sliceList`. Out-of-range pages return
  empty `content` with the correct `totalElements`.
- Trashed records never appear — the caches hold only rows with `removedAt IS NULL`.
- No audit row is written for reads.

---

### `PATCH /api/items/{type}/{code}/visibility`

Flips a single media row's `isPublic` flag. Designed for the list-row toggle, so the UI can
reuse the `type` + `code` it already has instead of re-submitting the full edit payload.

**Authority:** `{type}:update` — `audio:update`, `video:update`, `image:update` or `text:update`,
resolved from the `{type}` path variable. There is **no** `@PreAuthorize` on this method or on
`ItemsAPI`; `ItemVisibilityService.setVisibility` lower-cases the parsed `ItemType`, appends
`:update`, and compares it against the caller's granted authorities, throwing
`AccessDeniedException` when absent.

**Path parameters**

| Name | Type | Description |
|---|---|---|
| `type` | string | Trimmed and upper-cased, then parsed as `ItemType`. Accepted values: `AUDIO`, `VIDEO`, `IMAGE`, `TEXT` (so `audio`, `Audio` and `AUDIO` all work). Anything else is rejected |
| `code` | string | Business code of the media row — `audioCode` / `videoCode` / `imageCode` / `textCode`, matching `{type}`. Trimmed before lookup |

**Request body**

`VisibilityUpdateRequest` — a record with one boxed `Boolean`, so a missing or null value fails
`@NotNull` instead of silently defaulting to `false`.

```json
{ "isPublic": true }
```

**Response** `200 OK`

The full, freshly mapped response DTO of the targeted media type — not an `ItemDTO`:

| `{type}` | Delegates to | Response body |
|---|---|---|
| `AUDIO` | `AudioService.setVisibility` | `AudioResponseDTO` |
| `VIDEO` | `VideoService.setVisibility` | `VideoResponseDTO` |
| `IMAGE` | `ImageService.setVisibility` | `ImageResponseDTO` |
| `TEXT` | `TextService.setVisibility` | `TextResponseDTO` |

```json
{
  "id": 412,
  "audioCode": "MAHMUD_AUD_MASTER_V1_Copy(1)_000001",
  "projectId": 57,
  "projectCode": "MAHMUD-PROJ-000001",
  "projectName": "Mahmud field recordings",
  "personId": 19,
  "personCode": "MAHMUD",
  "personName": "Mahmud Ahmad",
  "categoryCodes": ["MUSIC", "FIELD"],
  "audioFileUrl": "/api/audio/MAHMUD_AUD_MASTER_V1_Copy(1)_000001/stream",
  "fileName": "bangi_sibe_master.wav",
  "originTitle": "Bangî Sibê",
  "language": "Kurdish",
  "dialect": "Sorani",
  "fileExtension": "wav",
  "fileSize": "84.2 MB",
  "isPublic": true,
  "projectVisibleToPublic": true,
  "createdAt": "2026-07-29 11:04:12",
  "updatedAt": "2026-08-26 10:15:07",
  "createdBy": "hana",
  "updatedBy": "akar"
}
```

Abbreviated for readability — the real response carries every non-null field of the matching
per-type DTO.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `VALIDATION_ERROR` | Body omits `isPublic` or sends it as `null`. `details` is `{"isPublic": "isPublic is required"}` |
| `400` | `JSON_PARSE_ERROR` | Body is not valid JSON, or `isPublic` is not a boolean |
| `400` | `AUDIO_VALIDATION_ERROR` | `{type}` is blank or not one of the four values — `ItemVisibilityService.parseType` throws `AudioValidationException` for **every** unknown type, so the code is `AUDIO_VALIDATION_ERROR` even when the caller meant a video/image/text. Message: `Unknown item type: <value>`. Also raised for an `AUDIO` request whose `{code}` is whitespace-only |
| `400` | `VIDEO_VALIDATION_ERROR` / `IMAGE_VALIDATION_ERROR` / `TEXT_VALIDATION_ERROR` | `{code}` is whitespace-only for that type (`<Type> code is required`) |
| `401` | `TOKEN_MISSING` | No `khi_auth_token` cookie on the request |
| `401` | `TOKEN_EXPIRED` / `TOKEN_REVOKED` / `TOKEN_MALFORMED` / `TOKEN_INVALID_SIGNATURE` / `TOKEN_INVALID` | Token rejected by the JWT filter |
| `403` | `ACCESS_DENIED` | Caller lacks `{type}:update`, or the request reached the service with no authenticated principal. Because the method has no `@PreAuthorize`, the handler cannot extract a `requiredAuthority`, so `details` omits it and the message is the generic "You don't have permission to perform this action." |
| `404` | `AUDIO_NOT_FOUND` | No active audio with that code (trashed rows 404 — they are never silently resurrected) |
| `404` | `VIDEO_NOT_FOUND` | No active video with that code |
| `404` | `IMAGE_NOT_FOUND` | No active image with that code |
| `404` | `TEXT_NOT_FOUND` | No active text with that code |
| `405` | `METHOD_NOT_ALLOWED` | Any method other than `PATCH` on this path |
| `415` | `UNSUPPORTED_MEDIA_TYPE` | `Content-Type` is not `application/json` |
| `500` | `DATABASE_ERROR` | The save failed at the database layer |
| `500` | `INTERNAL_SERVER_ERROR` | Unhandled failure; correlate with `traceId` |

**Example**

```bash
# Publish an audio row
curl -s -X PATCH "{{BASE_URL}}/api/items/AUDIO/MAHMUD_AUD_MASTER_V1_Copy(1)_000001/visibility" \
  -H "Content-Type: application/json" \
  -H "Cookie: khi_auth_token=$TOKEN" \
  -d '{"isPublic": true}'

# Hide a text row (lower-case type is accepted)
curl -s -X PATCH "{{BASE_URL}}/api/items/text/MAHMUD_TXT_MASTER_V1_Copy(1)_000003/visibility" \
  -H "Content-Type: application/json" \
  -H "Cookie: khi_auth_token=$TOKEN" \
  -d '{"isPublic": false}'
```

Codes contain `(` and `)`; percent-encode them (`%28`, `%29`) if your HTTP client does not do it
for you.

**Notes**

- Idempotent. Setting the flag to the value it already has is a complete no-op: the current DTO
  is returned, with **no** save, **no** `updatedAt`/`updatedBy` touch, **no** cache eviction and
  **no** audit row.
- The previous value is read as `Boolean.TRUE.equals(record.getIsPublic())`, so a row whose
  `isPublic` column is still `NULL` counts as `false`: sending `{"isPublic": false}` against it is
  treated as no change and leaves the column `NULL`. Send `true` to materialize the flag.
- On a real change the service sets `isPublic`, touches the update-audit fields, saves, evicts
  that media type's read cache (`{type}s:all`, plus `tags:suggest` and `keywords:suggest`), and
  records an `UPDATE` audit entry whose detail reads
  `Updated <type> record: isPublic: <previous> -> <new>`.
- This endpoint changes only the media row. It does not touch the parent project's
  `isVisibleToPublic` flag, and it is not the project-level cascade.

## Related

- [Internal API index](../README.md)
- [Conventions — page envelope, timestamps, error shape](../01-conventions.md)
- [Audio API](./audio.md) — per-type CRUD and the equivalent
  `PATCH /api/audio/{audioCode}/visibility`
- [Video API](./video.md) — per-type CRUD and `PATCH /api/video/{videoCode}/visibility`
- [Image API](./image.md) — per-type CRUD and `PATCH /api/image/{imageCode}/visibility`
- [Text API](./text.md) — per-type CRUD and `PATCH /api/text/{textCode}/visibility`
- [Project API](./project.md) — project-level `isVisibleToPublic` and its optional cascade
  to media
