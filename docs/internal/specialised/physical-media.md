# Physical Media Inventory API

> **Audience:** Staff (ADMIN + EMPLOYEE) · **Base path:** `/api/physical-media`,
> `/api/physical-media/types`, `/api/admin/physical-media` ·
> **Source:** `platform/api/physicalmedia/PhysicalMediaAPI.java`,
> `platform/api/physicalmedia/PhysicalMediaTypeAPI.java`,
> `platform/api/physicalmedia/AdminPhysicalMediaAPI.java`

The physical-media inventory is the archive's register of physical artifacts — audio cassettes,
open reels, vinyl, VHS, MiniDV, CD/DVD. Each row mirrors one line of the source spreadsheet
(`All Final Archive Lists.xlsx`), all 29 columns of it, and carries no bytes: this entity tracks
what exists on a shelf and how far along digitization is, not the digital file itself. Three
controllers cover it — inventory CRUD plus `.xlsx` bulk ingest, the `physical_media_types` catalog
that supplies per-type technical defaults, and the admin-only trash/restore/purge surface.

## Access

| Requirement | Value |
|---|---|
| Authentication | required (all 17 endpoints; `/api/**` is authenticated in `SecurityConfig`) |
| Authority | per-method `@PreAuthorize`: `physical_media:read`, `physical_media:create`, `physical_media:update`, `physical_media:remove`, `physical_media:import`, `physical_media:delete`, `physical_media:type_manage` |
| Roles that hold it by default | ADMIN (all seven). EMPLOYEE: `read`, `create`, `update`, `import` only |

**None of the three controllers carries a class-level `@PreAuthorize`.** Every method declares its
own `hasAuthority('physical_media:<action>')`, and the exact authority is repeated in each endpoint
section below.

The seven authorities are declared in `user/enums/Permission.java` as `PHYSICAL_MEDIA_READ`,
`PHYSICAL_MEDIA_CREATE`, `PHYSICAL_MEDIA_UPDATE`, `PHYSICAL_MEDIA_REMOVE`,
`PHYSICAL_MEDIA_DELETE`, `PHYSICAL_MEDIA_IMPORT` and `PHYSICAL_MEDIA_TYPE_MANAGE`. ADMIN holds all
of them through the role itself. `EMPLOYEE_DEFAULT_PERMISSIONS` in `user/enums/Role.java` seeds
exactly four — `physical_media:read`, `:create`, `:update`, `:import` — so an employee can fill the
inventory and run an Excel import, but cannot trash, restore, purge, or edit the type catalog.
`TEACHER_DEFAULT_PERMISSIONS` contains none of them.

`user/configs/EmployeePhysicalMediaPermissionBackfillInitializer.java` is a boot-time
`ApplicationReadyEvent` listener that inserts those same four grants into `user_permissions` for
every existing `role = 'EMPLOYEE'` row (`ON CONFLICT DO NOTHING`), because role-default seeding
only fires on account creation or role transition.

Unauthenticated requests never reach these controllers: `user/exceptions/JwtAuthenticationEntryPoint.java`
answers `401` with `TOKEN_MISSING` (no credentials presented) or `AUTHENTICATION_FAILED`.

## Endpoints

| Method | Path | Authority | Purpose |
|---|---|---|---|
| `GET` | `/api/physical-media` | `physical_media:read` | Paged active inventory with the full filter/sort set |
| `GET` | `/api/physical-media/search` | `physical_media:read` | Free-text search, flat list, capped |
| `GET` | `/api/physical-media/next-number` | `physical_media:read` | Preview the per-type `Number` the server would assign |
| `GET` | `/api/physical-media/{pmCode}` | `physical_media:read` | One active row by business key |
| `POST` | `/api/physical-media` | `physical_media:create` | Create one inventory row |
| `PATCH` | `/api/physical-media/{pmCode}` | `physical_media:update` | Partial update of one row |
| `DELETE` | `/api/physical-media/{pmCode}` | `physical_media:remove` | Soft-trash one row |
| `POST` | `/api/physical-media/import` | `physical_media:import` | Bulk-ingest an `.xlsx` workbook |
| `POST` | `/api/physical-media/import/sheets` | `physical_media:import` | List a workbook's sheet names (no writes) |
| `GET` | `/api/physical-media/types` | `physical_media:read` | Type catalog, name-ordered |
| `GET` | `/api/physical-media/types/{id}` | `physical_media:read` | One catalog entry |
| `POST` | `/api/physical-media/types` | `physical_media:type_manage` | Add a type + its nine defaults |
| `PATCH` | `/api/physical-media/types/{id}` | `physical_media:type_manage` | Edit a type |
| `DELETE` | `/api/physical-media/types/{id}` | `physical_media:type_manage` | Delete an unused type |
| `GET` | `/api/admin/physical-media/trash` | `physical_media:delete` | Paged trash listing, same filters |
| `POST` | `/api/admin/physical-media/{pmCode}/restore` | `physical_media:delete` | Restore a trashed row |
| `DELETE` | `/api/admin/physical-media/{pmCode}/purge` | `physical_media:delete` | Permanent delete |

Route note: `/search`, `/next-number`, `/import`, `/import/sheets` and `/types` are literal path
segments and are matched ahead of the `/{pmCode}` variable pattern, so no `pmCode` can shadow them.

---

## Data model

### Identity and the two sequences

| Field | Meaning |
|---|---|
| `pmCode` | Internal unique business key, format `PM_000001` (`String.format("%s_%06d", "PM", repository.count() + 1)`), minted under a `CodeGenLock` advisory lock in namespace `physical-media-code-gen`. Immutable once assigned; every path variable in this document is a `pmCode`, never the numeric `id`. |
| `inventoryNumber` | The sheet's `Number` column — a **per-media-type** contiguous counter (Audio Cassette 1..N, VHS Cassette 1..N, …). Minted as `MAX(inventoryNumber) + 1` for that type under a per-type lock (`physical-media-inv-num:<type>`), computed over active **and** trashed rows so the sequence stays monotonic across the trash lifecycle. |

`inventoryNumber` is **always server-assigned on `POST /api/physical-media`** — any value the client
sends in the create body is discarded. Only the Excel importer round-trips a sheet-supplied value.
`PATCH` can set it explicitly.

### The 29 spreadsheet columns

Every column of `All Final Archive Lists.xlsx → Sheet1` maps 1:1 onto a field, so the importer can
round-trip the sheet without losing context. The "Excel header(s)" column lists exactly the header
strings the importer binds — `HEADER_BINDINGS` in `PhysicalMediaExcelImportService`, in Kurdish,
English, or both. `PhysicalMediaCreateRequestDTO` repeats each of them as a `@JsonAlias` so a row
pasted straight out of the sheet also binds on `POST`, and adds a few extra aliases the importer
does **not** recognize as headers — listed under the table.

| # | Field | Type | DB column | Excel header(s) | Meaning |
|---|---|---|---|---|---|
| 1 | `rowNumber` | int | `row_number` | `No.` | Row ordinal as recorded in the sheet |
| 2 | `inventoryNumber` | int | `inventory_number` | `Number` | Per-media-type contiguous counter |
| 3 | `physicalMediaType` | string(200) | `physical_media_type` | `Physical Media Type` | Artefact format — must match a `physical_media_types.name` on manual create |
| 4 | `mediaCategory` | string(200) | `media_category` | `جۆری بابەت(Media Category)`, `Media Category` | High-level bucket: Audio, Video, … |
| 5 | `title` | text | `title` | `ناوی بابەت (Title)`, `Title` | Display title, Sorani Kurdish / free text |
| 6 | `sizeGB` | string(200) | `size_gb` | `Size GB`, `Size in GB`, `Size (GB)` | Digital file size, free text (`4.7`, `700 MB`). Repurposed from the retired `sub_type` column |
| 7 | `physicalLabel` | string(200) | `physical_label` | `کۆد (Physical Label)`, `Physical Label` | Sticker/label on the artifact. **Not globally unique** — only meaningful within a media type |
| 8 | `physicalSize` | string(200) | `physical_size` | `قەبارە (Physical Size)`, `قەبارە (Size)`, `Physical Size`, `Size` | Material size of the artifact (big / medium / normal / small). Distinct from `sizeGB` |
| 9 | `content` | text | `content` | `ناوەڕۆک (Content)`, `Content` | Free-text content description |
| 10 | `archiveDepNote` | text | `archive_dep_note` | `تێبینی بەشی ئارشیڤ Archive Dep Note`, `Archive Dep Note` | Archive department note |
| 11 | `digitization` | enum | `digitization` | `دیجیتایز Digitization`, `Digitization` | Digitization state; see `DigitizationStatus` below |
| 12 | `owner` | text | `owner` | `بەرهەمهێن(خاوەن) Owner`, `Owner` | Owner / producer attribution |
| 13 | `year` | int | `year` | `ساڵ Year`, `Year` | Year printed on the artifact label |
| 14 | `durationMin` | int | `duration_min` | `درێژایی خولەک Duration Min`, `Duration Min` | Runtime in minutes |
| 15 | `trackNumbers` | int | `track_numbers` | `ژمارەی تراک Track Numbers`, `Track Numbers` | Count of tracks |
| 16 | `trackName` | text | `track_name` | `ناوی تراکەکان Track Name`, `Track Name` | Free-text track listing |
| 17 | `extension` | string(50) | `extension` | `Extension` | File extension produced on capture (wav, mp4, avi) |
| 18 | `bitOrColorDepth` | string(100) | `bit_or_color_depth` | `Bit Depth / Color Depth` | Capture bit depth (audio) or color depth (video) |
| 19 | `sampleOrFrameRate` | string(100) | `sample_or_frame_rate` | `Sample Rate kHz/Frame Rate fps` | Sample rate (audio) or frame rate (video) |
| 20 | `channelsOrResolution` | string(100) | `channels_or_resolution` | `Channels/Resolution` | Channel layout (audio) or pixel resolution (video) |
| 21 | `playbackModel` | text | `playback_model` | `Playback Model` | Playback hardware used during capture |
| 22 | `captureInterface` | text | `capture_interface` | `Capture Interface` | Audio/video interface in front of the playback deck |
| 23 | `signalInterface` | text | `signal_interface` | `Signal Interface(Cable Type)`, `Signal Interface` | Cable/signal type between deck and interface |
| 24 | `ingestSoftware` | text | `ingest_software` | `Ingest Software` | Application used during ingest |
| 25 | `formatCodec` | string(200) | `format_codec` | `Format/Codec` | Container/codec produced by the capture |
| 26 | `digitizeDate` | date | `digitize_date` | `ڕێکەوتی دیجیتایز/ گواستنەوە Digitize Date`, `Digitize Date` | Calendar date of capture — day precision only, no time zone invented |
| 27 | `tags` | text | `tags` | `تاگ Tags`, `Tags` | Free-text tag list, comma/slash-separated as found. A single string column, **not** the `List<String>` tag collection the media entities use |
| 28 | `needToClear` | boolean | `need_to_clear` | `خاوێنکردن Need to Clear`, `Need to Clear` | Whether the artifact still needs physical cleaning; `0`/`1` in Excel |
| 29 | `captureDepNote` | text | `capture_dep_note` | `تێبینی بەشی تیجیتایز Capture Dep Note`, `Capture Dep Note` | Capture (digitization) department note |

**`POST`-only aliases.** `PhysicalMediaCreateRequestDTO` carries ten further `@JsonAlias` names that
are **not** in the importer's header table, so they bind a JSON body but never a spreadsheet column:
`no` → `rowNumber`; `sizeGb`, `size_gb` → `sizeGB`; `Bit Depth` → `bitOrColorDepth`; `Sample Rate`,
`Frame Rate` → `sampleOrFrameRate`; `Channels`, `Resolution` → `channelsOrResolution`; `Format`,
`Codec` → `formatCodec`. The convenience fields `digitizationCode` and `needToClearCode` carry no
aliases at all, and `PhysicalMediaUpdateRequestDTO` declares none of any kind.

### Audit and lifecycle fields

These are server-owned and appear in every response; they are not settable through any request body.

| Field | Meaning |
|---|---|
| `id` | Numeric primary key. Present in responses; not used as a path variable |
| `source` | How the row first landed: `MANUAL` (created via `POST`) or `IMPORT` (created by the Excel importer). Defaults to `MANUAL` in `@PrePersist` |
| `createdBy` / `updatedBy` / `removedBy` | `Authentication.getName()` of the actor, or `system` when unauthenticated |
| `createdAt` / `updatedAt` / `removedAt` | Instants. `removedAt != null` means the row is trashed |
| `version` | JPA `@Version` optimistic-lock counter, starts at `0` |

### `DigitizationStatus`

`platform/enums/DigitizationStatus.java`. Stored as the enum **name** (`@Enumerated(EnumType.STRING)`,
`VARCHAR(20)`); the integer is the Excel encoding, exposed on the wire as `digitizationCode`.

| Name | Code | Meaning |
|---|---|---|
| `NOT_DIGITIZED` | `0` | Physical only, no digital copy yet |
| `DIGITIZED` | `1` | Captured at least once into the archive |
| `DUPLICATED` | `2` | Captured more than once (intentionally re-ingested) |

`DigitizationStatus.fromCode(null)` returns `null` — a blank cell or an omitted field means "unknown
status", which is a legal stored value. Any other integer throws `IllegalArgumentException`
(→ `400 BAD_REQUEST` on the API; silently dropped to `null` by the importer).

`platform/config/PhysicalMediaDigitizationConstraintInitializer.java` re-syncs the Postgres
`CHECK (digitization IS NULL OR digitization IN (...))` constraint from this enum on every boot,
because Hibernate's `ddl-auto=update` writes that CHECK once and never refreshes it.

`needToClear` follows the same dual-representation rule: the boolean is authoritative, and
`needToClearCode` carries `0`/`1`. On write, the typed field wins; the code is only consulted when
the typed field is absent. `needToClearCode` outside `{0, 1}` throws `IllegalArgumentException`
(→ `400 BAD_REQUEST`).

### Caching

There is **no** read-cache for this entity — `platform/config/CacheConfig.java` registers no
`physical_media` cache, and the service reads straight from Postgres on every request. Listing is
DB-paged whenever it can be; see the filter path notes on `GET /api/physical-media`.

---

## Inventory endpoints — `PhysicalMediaAPI`

Class-level `@RequestMapping("/api/physical-media")`, no class-level `@PreAuthorize`.

### `GET /api/physical-media`

Paged listing of active (non-trashed) rows with the full filter + sort set.

**Authority:** `physical_media:read`

**Paging parameters**

| Name | Type | Default | Description |
|---|---|---|---|
| `page` | int | `0` | Zero-based page index |
| `size` | int | `50` | Page size (`@PageableDefault(size = 50)`) |
| `sort` | string | `id,ASC` | Bound, but **never applied**. Use `sortBy`/`sortDirection` below |

The raw `sort` parameter has no effect on this endpoint on any path: `effectivePageable` keeps only
the page number and size and rebuilds the order from `sortBy`, pinning `id ASC` when `sortBy` is
absent, while the in-memory path orders in `PhysicalMediaFilterSupport` before `sliceList` ever sees
the `Pageable`. So `?sort=createdAt,desc` silently does nothing. That default of `id ASC` is
deliberate — rows come back in insertion order, so the table reads "1, 2, 3, …" the way the source
spreadsheet does, and paging stays stable across requests instead of trusting Postgres row order.

No `spring.data.web.pageable.max-page-size` override is set in `application.yaml`, so Spring Data's
built-in cap applies. The response is the standard Spring `Page` envelope — see
[../01-conventions.md](../01-conventions.md); `content[]` elements have the shape shown below.

**Query parameters** — bound from the query string into `PhysicalMediaFilterParams` via
`@ModelAttribute`. Every field it binds is listed; all are optional.

_Sort_

| Name | Type | Default | Description |
|---|---|---|---|
| `sortBy` | string | — | Sort key, case-insensitive; see the synonym table below. Unknown keys are ignored and the order falls back to `id ASC` |
| `sortDirection` | string | `asc` | `asc` or `desc` (`desc` matched case-insensitively) |

_Free text_

| Name | Type | Default | Description |
|---|---|---|---|
| `q` | string | — | Case-insensitive substring across `pmCode`, `physicalLabel`, `physicalMediaType`, `mediaCategory`, `title`, `physicalSize`, `content`, `owner`, `tags`, `trackName`. Unlike `/search`, `q` composes with every filter and sort below |

_Categorical equals (case-insensitive exact match)_

| Name | Type | Default | Description |
|---|---|---|---|
| `physicalMediaType` | string | — | Exact type name, e.g. `Audio Cassette` |
| `mediaCategory` | string | — | Exact category, e.g. `Video` |
| `physicalSize` | string | — | Exact material size |
| `extension` | string | — | Exact capture extension |
| `formatCodec` | string | — | Exact format/codec |
| `source` | string | — | `MANUAL` or `IMPORT` |

_Enum / boolean_

| Name | Type | Default | Description |
|---|---|---|---|
| `digitization` | string | — | Enum name, case-insensitive: `NOT_DIGITIZED`, `DIGITIZED`, `DUPLICATED`. A row with a null status never matches |
| `digitizationCode` | int | — | Numeric form of the same field: `0`, `1`, `2` |
| `needToClear` | boolean | — | `true` / `false` |
| `needToClearCode` | int | — | Numeric form: `0`, `1` |

_Long-text contains (case-insensitive substring)_

| Name | Type | Default | Description |
|---|---|---|---|
| `pmCode` | string | — | Substring of the business key |
| `title` | string | — | Substring of the title |
| `physicalLabel` | string | — | Substring of the physical label |
| `content` | string | — | Substring of the content description |
| `archiveDepNote` | string | — | Substring of the archive department note |
| `owner` | string | — | Substring of the owner/producer |
| `tags` | string | — | Substring of the free-text tag column |
| `trackName` | string | — | Substring of the track listing |
| `captureDepNote` | string | — | Substring of the capture department note |
| `sizeGB` | string | — | Substring of the digital-size text |
| `playbackModel` | string | — | Substring of the playback hardware |
| `captureInterface` | string | — | Substring of the capture interface |
| `signalInterface` | string | — | Substring of the signal/cable type |
| `ingestSoftware` | string | — | Substring of the ingest software |
| `bitOrColorDepth` | string | — | Substring of the bit/color depth |
| `sampleOrFrameRate` | string | — | Substring of the sample/frame rate |
| `channelsOrResolution` | string | — | Substring of the channels/resolution |
| `createdBy` | string | — | Substring of the creating actor |
| `updatedBy` | string | — | Substring of the last-updating actor |
| `removedBy` | string | — | Substring of who trashed the row. Meaningful on `/api/admin/physical-media/trash`; inert here, since active rows have a null `removedBy` and a null never matches |

_Numeric ranges (inclusive; a null field value never matches a bounded range)_

| Name | Type | Default | Description |
|---|---|---|---|
| `yearMin` / `yearMax` | int | — | Range over `year` |
| `durationMinutesMin` / `durationMinutesMax` | int | — | Range over `durationMin` |
| `trackNumbersMin` / `trackNumbersMax` | int | — | Range over `trackNumbers` |
| `inventoryNumberMin` / `inventoryNumberMax` | int | — | Range over `inventoryNumber` |
| `rowNumberMin` / `rowNumberMax` | int | — | Range over `rowNumber` |

_Date ranges (all `YYYY-MM-DD`, inclusive)_

| Name | Type | Default | Description |
|---|---|---|---|
| `digitizeDateFrom` / `digitizeDateTo` | date | — | Range over `digitizeDate`, a date-only column compared as-is |
| `createdFrom` / `createdTo` | date | — | Range over `createdAt`; day bounds resolved in `Asia/Baghdad` via `ArchiveTime` |
| `updatedFrom` / `updatedTo` | date | — | Range over `updatedAt`; same zone handling |
| `removedFrom` / `removedTo` | date | — | Range over `removedAt`; same zone handling. Meaningful on the trash listing |

**`sortBy` synonyms** (`PhysicalMediaFilterSupport`)

| Accepted values | Orders by |
|---|---|
| `pmcode`, `code` | `pmCode`, case-insensitive |
| `inventorynumber`, `number`, `inventory` | `inventoryNumber` |
| `rownumber`, `row`, `no` | `rowNumber` |
| `physicalmediatype`, `type`, `mediatype` | `physicalMediaType`, case-insensitive |
| `mediacategory`, `category` | `mediaCategory`, case-insensitive |
| `title`, `name`, `alpha`, `alphabet`, `alphabetical` | `title`, case-insensitive |
| `physicallabel`, `label` | `physicalLabel`, case-insensitive |
| `owner` | `owner`, case-insensitive |
| `year` | `year` |
| `duration`, `durationmin`, `durationminutes` | `durationMin` |
| `tracknumbers`, `tracks` | `trackNumbers` |
| `digitization`, `digitizationcode` | derived `digitizationCode` — **in-memory only** |
| `digitizedate`, `digitized` | `digitizeDate` |
| `createdat`, `created`, `added`, `dateadded`, `date_added` | `createdAt` |
| `updatedat`, `updated`, `modified`, `datemodified`, `date_modified` | `updatedAt` |

Null values sort last on `asc` and first on `desc` — the in-memory comparators use
`Comparator.nullsLast(...)` and reverse it for `desc`, and PostgreSQL's native null handling
(`NULLS LAST` for ASC, `NULLS FIRST` for DESC) matches, so both execution paths agree. `id ASC` is
always appended as the tiebreaker so paging is stable across requests.

**Execution paths** — three, cheapest first:

1. **No params at all** (`filter.isEmpty()`): the plain DB-paged query
   `findAllByRemovedAtIsNull(pageable)`, ordered `id ASC`. One page loaded.
2. **Sort only**, on a DB-mappable key: still DB-paged, with the order pushed into SQL
   (`LOWER()` for text keys). One page loaded.
3. **Any non-sort filter present, or `sortBy=digitization`**: the full active set is loaded
   (`findAllByRemovedAtIsNullOrderByIdAsc`), mapped to DTOs, filtered and sorted in memory by
   `PhysicalMediaFilterSupport`, then sliced by `PaginationSupport.sliceList`. `totalElements`
   reflects the filtered count.

`sortBy=digitization` is the one sort key with a cost. It orders by the derived `0`/`1`/`2` code,
which has no backing column, so `resolveDbSort` returns `Sort.unsorted()` and the request drops to
path 3 — a full active-set load, map and scan — **even with no filters set**. Every other key in the
synonym table above stays on the single-page DB query. The inventory is a few thousand rows, so this
is milliseconds rather than a problem, but it is not the cheap request the other two paths describe:
worth knowing before wiring `digitization` up as a default column sort on a grid that reloads on
every keystroke.

String comparisons on the in-memory path run both sides through
`platform/service/common/KurdishText.normalize` — NFC, Arabic Yeh/Kaf folding, joiner and
tashkeel stripping, whitespace collapse, then lower-case — so Sorani text matches across codepoint
variants. The `/search` endpoint does **not** use this; it uses SQL `LOWER()`.

**Response** `200 OK`

```json
{
  "content": [
    {
      "id": 812,
      "pmCode": "PM_000812",
      "rowNumber": 812,
      "inventoryNumber": 56,
      "physicalMediaType": "VHS Cassette",
      "mediaCategory": "Video",
      "title": "ئاهەنگی زەماوەند",
      "sizeGB": "4.7",
      "physicalLabel": "VHS-0056",
      "physicalSize": "normal",
      "content": "Wedding footage, Sulaymaniyah",
      "archiveDepNote": "Tape shows mold on the leader",
      "digitization": "NOT_DIGITIZED",
      "digitizationCode": 0,
      "owner": "Kaka Hama",
      "year": 1994,
      "durationMin": 118,
      "trackNumbers": 1,
      "extension": "avi",
      "bitOrColorDepth": "8",
      "sampleOrFrameRate": "25",
      "channelsOrResolution": "720X576",
      "playbackModel": "Sony DVD Player / Video Cassette Recorder SLV-D985P ME",
      "captureInterface": "Blackmagic Intensity Pro 4K",
      "signalInterface": "Composite",
      "ingestSoftware": "Blackmagic Media Express",
      "formatCodec": "Uncompressed avi 8-bit YUV, 625i50 PAL",
      "tags": "wedding, 1990s",
      "needToClear": true,
      "needToClearCode": 1,
      "source": "IMPORT",
      "createdBy": "employee@example.com",
      "updatedBy": "employee@example.com",
      "createdAt": "2026-08-20T07:11:03.221Z",
      "updatedAt": "2026-08-20T07:11:03.221Z",
      "version": 0
    }
  ],
  "pageable": { "pageNumber": 0, "pageSize": 50 },
  "totalElements": 4412,
  "totalPages": 89,
  "number": 0,
  "size": 50,
  "first": true,
  "last": false,
  "numberOfElements": 50,
  "empty": false
}
```

`spring.jackson.default-property-inclusion=non_null` — the example row omits `trackName`,
`digitizeDate`, `captureDepNote`, `removedBy` and `removedAt` because they are null. An active row
never carries `removedBy` / `removedAt`.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `VALIDATION_ERROR` | A filter parameter fails to bind — `yearMin=abc`, `createdFrom=2026-13-01`, `needToClear=maybe` (`BindException`) |
| `401` | `TOKEN_MISSING` / `AUTHENTICATION_FAILED` | No token on the request — neither an `Authorization: Bearer` header nor the cookie — or the token is invalid/revoked |
| `403` | `ACCESS_DENIED` | Caller lacks `physical_media:read`; `details.requiredAuthority` names it |
| `500` | `DATABASE_ERROR` | `DataAccessException` from the query |

**Example**

```bash
curl -s "{{BASE_URL}}/api/physical-media?physicalMediaType=VHS%20Cassette&digitization=NOT_DIGITIZED&yearMin=1980&yearMax=1999&sortBy=inventoryNumber&sortDirection=asc&page=0&size=50" \
  -H "Cookie: khi_auth_token=$TOKEN"
```

**Notes** — writes one `LIST` row to `physical_media_audit_logs` with
`details = "size=<n> total=<n>"`, suffixed `" filtered=true"` on the in-memory path or
`" sorted=true"` on the DB fast-sort path.

---

### `GET /api/physical-media/search`

Free-text search across the columns end-users care about. Returns a flat array, not a `Page`.

**Authority:** `physical_media:read`

**Query parameters**

| Name | Type | Default | Description |
|---|---|---|---|
| `q` | string | — | **Required.** Search term. Blank/whitespace-only returns `[]` without touching the DB |
| `limit` | int | `20` | Max rows. Clamped to `[1, 100]` server-side — `limit=5000` yields 100, `limit=0` yields 1 |

Matching is a native SQL `LIKE '%' || LOWER(:q) || '%'` over `pm_code`, `physical_label`,
`physical_media_type`, `media_category`, `title`, `physical_size`, `content`, `owner`, `tags`,
`track_name`, restricted to `removed_at IS NULL`, ordered `id ASC`. Because it is SQL `LOWER()`
rather than `KurdishText.normalize`, it does not fold Arabic-script codepoint variants — use the
`q` filter on `GET /api/physical-media` when that matters.

**Response** `200 OK` — array of the same object shape as `content[]` above.

```json
[
  {
    "id": 812,
    "pmCode": "PM_000812",
    "physicalMediaType": "VHS Cassette",
    "title": "ئاهەنگی زەماوەند",
    "physicalLabel": "VHS-0056",
    "digitization": "NOT_DIGITIZED",
    "digitizationCode": 0,
    "source": "IMPORT",
    "version": 0
  }
]
```

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `MISSING_PARAMETER` | `q` omitted entirely |
| `400` | `TYPE_MISMATCH` | `limit` is not an integer |
| `401` | `TOKEN_MISSING` / `AUTHENTICATION_FAILED` | No token on the request — neither an `Authorization: Bearer` header nor the cookie — or the token is invalid/revoked |
| `403` | `ACCESS_DENIED` | Caller lacks `physical_media:read` |
| `500` | `DATABASE_ERROR` | `DataAccessException` from the native query |

**Example**

```bash
curl -s "{{BASE_URL}}/api/physical-media/search?q=wedding&limit=25" \
  -H "Cookie: khi_auth_token=$TOKEN"
```

**Notes** — writes a `SEARCH` audit row with `details = "q=<q> hits=<n>"`. A blank or
whitespace-only `q` returns `[]` before the audit call, so that request leaves no row.

---

### `GET /api/physical-media/next-number`

Previews the `Number` the server would assign to the next record of a given media type, so the
create form can show "this row will be VHS Cassette #56" without the user computing it.

**Authority:** `physical_media:read`

**Query parameters**

| Name | Type | Default | Description |
|---|---|---|---|
| `type` | string | — | **Required.** The `physicalMediaType` to preview. Trimmed before lookup |

**Response** `200 OK`

```json
{
  "physicalMediaType": "VHS Cassette",
  "nextInventoryNumber": 56
}
```

`nextInventoryNumber` is a primitive `int`, so it is always present. The value is
`MAX(inventoryNumber) + 1` over every row of that type — active and trashed — or `1` when the type
has no rows yet. The type does not have to exist in the catalog for this preview to answer.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `MISSING_PARAMETER` | `type` omitted entirely |
| `400` | `PHYSICAL_MEDIA_VALIDATION_ERROR` | `type` present but blank — `"type query parameter is required"` |
| `401` | `TOKEN_MISSING` / `AUTHENTICATION_FAILED` | No token on the request — neither an `Authorization: Bearer` header nor the cookie — or the token is invalid/revoked |
| `403` | `ACCESS_DENIED` | Caller lacks `physical_media:read` |

**Example**

```bash
curl -s "{{BASE_URL}}/api/physical-media/next-number?type=VHS%20Cassette" \
  -H "Cookie: khi_auth_token=$TOKEN"
```

**Notes** — best-effort preview, **not a reservation**: no advisory lock is taken and no audit row
is written. `POST /api/physical-media` re-mints the number under the per-type lock, so a stale
preview cannot cause a collision — it can only be shown as a slightly wrong hint.

---

### `GET /api/physical-media/{pmCode}`

Fetch one active row by business key.

**Authority:** `physical_media:read`

**Path parameters**

| Name | Type | Description |
|---|---|---|
| `pmCode` | string | Business key, e.g. `PM_000812` |

**Response** `200 OK` — one object with the shape shown under `GET /api/physical-media`.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `401` | `TOKEN_MISSING` / `AUTHENTICATION_FAILED` | No token on the request — neither an `Authorization: Bearer` header nor the cookie — or the token is invalid/revoked |
| `403` | `ACCESS_DENIED` | Caller lacks `physical_media:read` |
| `404` | `PHYSICAL_MEDIA_NOT_FOUND` | No row with that `pmCode`, **or** the row is trashed — this endpoint only sees `removedAt IS NULL` |

**Example**

```bash
curl -s "{{BASE_URL}}/api/physical-media/PM_000812" \
  -H "Cookie: khi_auth_token=$TOKEN"
```

**Notes** — writes a `READ` audit row (with a null `details`) on every hit.

---

### `POST /api/physical-media`

Create one inventory row. `Content-Type: application/json` is required (`consumes`); the response
is `application/json` (`produces`).

**Authority:** `physical_media:create`

**Request body** — `PhysicalMediaCreateRequestDTO`. Every one of the 29 columns is accepted; each
also accepts its Excel header as a `@JsonAlias`, so a row pasted straight out of the sheet binds
without renaming. Two extra convenience fields exist: `digitizationCode` (`0`/`1`/`2`) and
`needToClearCode` (`0`/`1`), each consulted only when the typed field is absent.

Validation, from `PhysicalMediaService.validateRequiredOnCreate`:

| Rule | Failure |
|---|---|
| `physicalMediaType` must not be blank | `details.physicalMediaType = "must not be blank"` |
| `title` or `physicalLabel` must be provided | `details.title = "title or physicalLabel must be provided"` |
| `physicalMediaType` must already exist in the catalog | `details.physicalMediaType = "not in the type catalog — add it via /api/physical-media/types first"` |

Bean-validation length caps also apply: `physicalMediaType`, `mediaCategory`, `sizeGB`,
`physicalLabel`, `physicalSize`, `formatCodec` ≤ 200; `bitOrColorDepth`, `sampleOrFrameRate`,
`channelsOrResolution` ≤ 100; `extension` ≤ 50.

`rowNumber` is accepted and stored. **`inventoryNumber` is ignored** — the server always mints
`MAX + 1` for the chosen type.

```json
{
  "physicalMediaType": "VHS Cassette",
  "mediaCategory": "Video",
  "title": "ئاهەنگی زەماوەند",
  "physicalLabel": "VHS-0056",
  "physicalSize": "normal",
  "content": "Wedding footage, Sulaymaniyah",
  "owner": "Kaka Hama",
  "year": 1994,
  "durationMin": 118,
  "digitizationCode": 0,
  "needToClearCode": 1,
  "tags": "wedding, 1990s",
  "archiveDepNote": "Tape shows mold on the leader"
}
```

**Response** `200 OK` (not `201`) — the created row, same shape as `GET /api/physical-media/{pmCode}`,
with `pmCode`, `inventoryNumber`, `source: "MANUAL"`, `createdBy`, `updatedBy`, `createdAt`,
`updatedAt` and `version: 0` filled in by the server.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `PHYSICAL_MEDIA_VALIDATION_ERROR` | A `validateRequiredOnCreate` rule fails; `details` carries the per-field reasons |
| `400` | `VALIDATION_ERROR` | A `@Size` cap is exceeded; `details` maps field → message |
| `400` | `JSON_PARSE_ERROR` | Malformed JSON, or `digitization` sent as a string that is not an enum name |
| `400` | `BAD_REQUEST` | `digitizationCode` outside `{0,1,2}`, or `needToClearCode` outside `{0,1}` (`IllegalArgumentException`) |
| `401` | `TOKEN_MISSING` / `AUTHENTICATION_FAILED` | No token on the request — neither an `Authorization: Bearer` header nor the cookie — or the token is invalid/revoked |
| `403` | `ACCESS_DENIED` | Caller lacks `physical_media:create` |
| `409` | `CONFLICT` | `DataIntegrityViolationException` — e.g. a `pm_code` uniqueness collision |
| `415` | `UNSUPPORTED_MEDIA_TYPE` | Body sent as anything other than `application/json` |

**Example**

```bash
curl -s -X POST "{{BASE_URL}}/api/physical-media" \
  -H "Cookie: khi_auth_token=$TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
        "physicalMediaType": "VHS Cassette",
        "mediaCategory": "Video",
        "title": "Wedding, Sulaymaniyah 1994",
        "physicalLabel": "VHS-0056",
        "year": 1994,
        "digitizationCode": 0
      }'
```

**Notes** — writes a `CREATE` audit row with `details = "type=<type> label=<label>"`.

---

### `PATCH /api/physical-media/{pmCode}`

Partial update of one active row. `Content-Type: application/json` required.

**Authority:** `physical_media:update`

**Path parameters**

| Name | Type | Description |
|---|---|---|
| `pmCode` | string | Business key of an **active** row |

**Request body** — `PhysicalMediaUpdateRequestDTO`. All fields optional; `null` (or absent) means
"leave alone". The same 29 columns are settable, plus `digitizationCode` / `needToClearCode`.
Unlike the create DTO this one declares no `@JsonAlias`, so Excel header names are not accepted
here. Sending `digitization` **or** `digitizationCode` counts as touching `digitization`; likewise
for `needToClear` / `needToClearCode`. Strings are trimmed, and a whitespace-only string is stored
as `null` — that is the supported way to clear a text field.

```json
{
  "digitizationCode": 1,
  "digitizeDate": "2026-08-24",
  "extension": "avi",
  "captureDepNote": "Captured on the Blackmagic chain, no dropouts"
}
```

**Response** `200 OK` — the updated row.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `VALIDATION_ERROR` | A `@Size` cap is exceeded |
| `400` | `JSON_PARSE_ERROR` | Malformed JSON, an unparseable `digitizeDate`, or a `digitization` string that is not an enum name |
| `400` | `BAD_REQUEST` | `digitizationCode` outside `{0,1,2}`, or `needToClearCode` outside `{0,1}` |
| `401` | `TOKEN_MISSING` / `AUTHENTICATION_FAILED` | No token on the request — neither an `Authorization: Bearer` header nor the cookie — or the token is invalid/revoked |
| `403` | `ACCESS_DENIED` | Caller lacks `physical_media:update` |
| `404` | `PHYSICAL_MEDIA_NOT_FOUND` | No active row with that `pmCode` (trashed rows are invisible here) |
| `409` | `STALE_VERSION` | `ObjectOptimisticLockingFailureException` — a concurrent write bumped `version` first |
| `409` | `CONFLICT` | `DataIntegrityViolationException` |
| `415` | `UNSUPPORTED_MEDIA_TYPE` | Body sent as anything other than `application/json` |

**Example**

```bash
curl -s -X PATCH "{{BASE_URL}}/api/physical-media/PM_000812" \
  -H "Cookie: khi_auth_token=$TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"digitizationCode": 1, "digitizeDate": "2026-08-24"}'
```

**Notes** — writes an `UPDATE` audit row naming the touched fields,
`details = "fields=digitization,digitizeDate"` (or `"fields=<none>"` when the body changed nothing).
There is no `physicalMediaType` catalog check on `PATCH` — only `POST` enforces it.

---

### `DELETE /api/physical-media/{pmCode}`

Soft-trash: sets `removedAt` and `removedBy`. The row disappears from `GET /api/physical-media`
and appears in `GET /api/admin/physical-media/trash`. Nothing is deleted.

**Authority:** `physical_media:remove`

**Path parameters**

| Name | Type | Description |
|---|---|---|
| `pmCode` | string | Business key of an **active** row |

**Response** `204 No Content` — empty body.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `401` | `TOKEN_MISSING` / `AUTHENTICATION_FAILED` | No token on the request — neither an `Authorization: Bearer` header nor the cookie — or the token is invalid/revoked |
| `403` | `ACCESS_DENIED` | Caller lacks `physical_media:remove` — EMPLOYEE never holds it by default |
| `404` | `PHYSICAL_MEDIA_NOT_FOUND` | No active row with that `pmCode` (including one already trashed) |
| `409` | `STALE_VERSION` | Concurrent write bumped `version` first |

**Example**

```bash
curl -s -X DELETE "{{BASE_URL}}/api/physical-media/PM_000812" \
  -H "Cookie: khi_auth_token=$TOKEN" -i
```

**Notes** — writes a `REMOVE` audit row with `details = "soft-trashed"`. The trashed row keeps its
`inventoryNumber` and still counts toward `MAX(inventoryNumber)`, so trashing never causes a later
create to reuse a number.

---

## Excel import

Bulk ingest of `.xlsx` workbooks through Apache POI 5.3.0 (`XSSFWorkbook`). Both import endpoints
require `physical_media:import`, which EMPLOYEE holds by default.

### Header-name resolution

The importer reads the chosen sheet's **first defined row** (`sheet.getRow(sheet.getFirstRowNum())`)
as the header row and matches each cell against a static binding table of canonical header texts —
Kurdish, English, or both. Matching is by *name*, never by column position, so columns may be
reordered, and columns the archive does not model may sit anywhere in the sheet.

Both the sheet's header text and the binding key are passed through the same normalizer before
comparison:

1. Strip zero-width characters — ZWSP `U+200B`, ZWNJ `U+200C`, ZWJ `U+200D`, BOM `U+FEFF`.
2. Collapse every whitespace run to a single space.
3. `trim()`.
4. `toLowerCase(Locale.ROOT)`.

So `"  Physical   Media Type "` and `"physical media type"` both bind to `physicalMediaType`.

Every accepted header string is listed in the "Excel header(s)" column of the
[29-column table](#the-29-spreadsheet-columns) above. Headers that match nothing are collected into
`unknownHeaders` in the report and their columns are ignored; matched header labels (the sheet's
original, trimmed text) come back in `matchedHeaders`. Both are accumulated in a `HashSet` and
copied into a `List<String>` for the wire, so each label appears once and the order is unspecified.

If **no** header cell matches anything, the whole request fails with
`"No recognisable columns found in header row"`.

### Row handling

Rows where every mapped column is blank are skipped silently and do not count toward
`totalDataRows` — they are treated as visual spacers. Every other row counts and is inserted.

Cell coercion is deliberately forgiving:

| Situation | Behavior |
|---|---|
| Numeric cell flagged as a date | Read as `LocalDate` |
| Numeric cell with an integral value | Trailing `.0` dropped (`60.0` → `60`) |
| `"12.0"` in an integer column | Parsed via `Integer.parseInt`, then `Double.parseDouble` → `12` |
| Unparseable integer | `null` |
| `Digitization` outside `{0,1,2}` | Left `null` — the row is not failed |
| `Need to Clear` outside `{0,1}` | Left `null` |
| Unparseable `Digitize Date` | `null` |

If a row still fails to persist, the importer retries it once with a **stripped** DTO that drops
`digitization`, `needToClear`, `digitizeDate` and `tags`, keeps the text and numeric columns, and
appends `"[Imported with stripped fields — original encoded or date columns failed to parse]"` to
`archiveDepNote`. A successful retry counts as `inserted` **and** adds an entry to `errors[]`, so
staff can find the degraded rows. Only if the stripped retry also fails is the row counted as
`skipped`.

Each row is persisted in its own `REQUIRES_NEW` transaction, so one bad row cannot roll the batch
back. Rows carrying a `physicalMediaType` the catalog does not know are **not** rejected: the type
is auto-created with blank defaults and `description = "Auto-created during Excel import."`. Rows
that carry the sheet's own `Number` keep it; rows without one get the per-type `MAX + 1`.

**Create-time validation does not apply to imported rows.** `insertFromImport` never calls
`validateRequiredOnCreate`, so a sheet row missing `Physical Media Type`, `Title` **and**
`Physical Label` is still inserted — it lands as a near-empty record carrying whatever else the row
had. "Type must be in the catalog" and "title or physicalLabel required" are constraints on the
create API, not invariants of the table: do not write a query, a report or a UI that assumes every
row satisfies them.

That is the deliberate trade. Staff would rather have every artifact in the database and patch it
afterwards than have the importer refuse a sheet — a rejected row is an artifact nobody remembers to
re-enter, whereas a half-empty row surfaces in the listing and gets fixed.

### Dedupe

**There is none.** Every data row of the sheet becomes a new `physical_media` record with its own
`pmCode`. The importer calls `PhysicalMediaService.insertFromImport`, which is insert-only — there
is no lookup, no update branch, and the import report has no "updated" counter.

The `(physicalMediaType, physicalLabel)` pair is explicitly **not** a unique key. `physical_label`
is only meaningful within a media type and is not unique even there, so two artifacts that share a
label stay two artifacts. `PhysicalMediaService.insertFromImport`'s javadoc records that the
importer used to upsert on that pair and that the behavior was removed because it silently merged
distinct physical tapes. The only uniqueness in the table is `uk_pm_code` on `pm_code`.

Practical consequence: re-uploading the same workbook duplicates every row. There is no
idempotency key and no "replace existing" mode.

> Two javadoc comments are stale on this point and contradict the code —
> `PhysicalMediaAPI.importExcel` ("Dedupe key inside the importer is
> `(physicalMediaType, physicalLabel)`") and the `PhysicalMediaExcelImportService` class comment
> ("rows are upserted by `(physicalMediaType, physicalLabel)`"). The implementation and
> `PhysicalMediaService.insertFromImport` / `PhysicalMediaImportReportDTO` are authoritative:
> insert-only, no dedupe.

### Scale and throughput

The reference workbook is a single sheet of roughly 4,400 rows and imports in about ten seconds
against a local Postgres. Each row commits in its own `REQUIRES_NEW` transaction — that is what
makes "skip the bad row and continue" work, but it also puts a commit in the per-row hot path, so
there is never a batch to fill. Two independent reasons the import is unbatched, then:

1. The per-row `REQUIRES_NEW` commit, described above.
2. **JDBC batching is not configured after all.** `application.yaml` writes `batch_size: 100`,
   `order_inserts: true` and `order_updates: true` under `spring.jpa.jdbc.*` — a property path
   Spring Boot does not bind. All three are verified inert; Hibernate never receives them. See
   [Indexes and performance](../database/indexes-and-performance.md#eight-hibernate-keys-are-inert-verified).

Fixing only the YAML nesting would not speed this import up, because reason 1 still holds. The cost
scales roughly linearly, so a sheet of tens of thousands of rows means a synchronous request
measured in minutes.

If a sheet ever gets that big, the fix is to stop committing per row: run the batch in one
transaction with an injected `EntityManager`, `flush()` and `clear()` every ~200 rows, and collect
failed rows for a second, per-row pass instead of relying on `REQUIRES_NEW` for isolation. Nothing
on the wire has to change — `PhysicalMediaImportReportDTO` already carries the per-row outcome
either way.

There is no asynchronous or job-based import path, and no cap on the upload beyond the
`spring.servlet.multipart` limits (`max-file-size: 5GB`, `max-request-size: 6GB`).

---

### `POST /api/physical-media/import`

Bulk-insert every data row of an uploaded `.xlsx`. Consumes `multipart/form-data`.

**Authority:** `physical_media:import`

**Request parts**

| Part / param | Kind | Required | Description |
|---|---|---|---|
| `file` | `@RequestPart` multipart file | yes | The workbook. The multipart **field name must be `file`**. Rejected unless the original filename ends in `.xlsx` (case-insensitive); a file with no filename at all passes the extension check |
| `sheet` | `@RequestParam` query or form field | no | Sheet name. Omitted or blank → the workbook's first sheet. Resolution tries an exact POI lookup first, then a normalized match (whitespace-collapsed, lower-cased), so `sheet=archive physical list` finds a tab literally named `"Archive Physical List "` |

Upload size is bounded by `spring.servlet.multipart` in `application.yaml`: `max-file-size: 5GB`,
`max-request-size: 6GB`.

**Response** `200 OK` — `PhysicalMediaImportReportDTO`

```json
{
  "sheetName": "Sheet1",
  "matchedHeaders": [
    "No.",
    "Number",
    "Physical Media Type",
    "جۆری بابەت(Media Category)",
    "ناوی بابەت (Title)",
    "کۆد (Physical Label)",
    "دیجیتایز Digitization",
    "ساڵ Year"
  ],
  "unknownHeaders": ["Remarks", "Shelf"],
  "totalDataRows": 4412,
  "inserted": 4411,
  "skipped": 1,
  "errors": [
    {
      "rowNumber": 118,
      "message": "saved with stripped fields after error: could not execute statement"
    },
    {
      "rowNumber": 2044,
      "message": "could not save even stripped row: value too long for type character varying(200)"
    }
  ],
  "finishedAt": "2026-08-26T09:14:02.771Z"
}
```

| Field | Type | Meaning |
|---|---|---|
| `sheetName` | string | The sheet actually parsed, as POI reports it |
| `matchedHeaders` | string[] | Sheet header labels (trimmed originals) that bound to a field |
| `unknownHeaders` | string[] | Sheet header labels that bound to nothing and were ignored |
| `totalDataRows` | int | Non-empty data rows encountered below the header |
| `inserted` | int | Rows persisted — including stripped-fallback retries. There is no `updated` counter |
| `skipped` | int | Rows that failed even the stripped retry and are not in the database |
| `errors[]` | object[] | One entry per problem row, in sheet order |
| `errors[].rowNumber` | int | **1-based sheet row number** (POI's 0-based index + 1), so it matches what the user sees in Excel |
| `errors[].message` | string | `"saved with stripped fields after error: …"` (row is in the DB, degraded) or `"could not save even stripped row: …"` (row is not in the DB) |
| `finishedAt` | Instant | Server clock when the report was built |

`inserted`, `skipped` and `totalDataRows` are primitive `int`s, so they serialize even at `0`.
`errors[]`, `matchedHeaders` and `unknownHeaders` are always present, possibly as empty arrays.

Read `errors[]` together with `skipped`: a non-empty `errors[]` with `skipped == 0` means every row
landed, some of them with stripped fields that a human should review.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `PHYSICAL_MEDIA_VALIDATION_ERROR` | Missing/empty file; filename not `.xlsx`; named sheet not found (the message lists the available sheets); empty header row; no recognizable columns; `IOException` while reading the workbook |
| `400` | `MISSING_REQUEST_PART` | The `file` part is absent from the multipart body |
| `400` | `BAD_REQUEST` | `MultipartException` — malformed multipart body |
| `401` | `TOKEN_MISSING` / `AUTHENTICATION_FAILED` | No token on the request — neither an `Authorization: Bearer` header nor the cookie — or the token is invalid/revoked |
| `403` | `ACCESS_DENIED` | Caller lacks `physical_media:import` |
| `413` | `UPLOAD_TOO_LARGE` | Beyond `max-file-size` / `max-request-size` |
| `415` | `UNSUPPORTED_MEDIA_TYPE` | Not sent as `multipart/form-data` |
| `500` | `INTERNAL_SERVER_ERROR` | A non-`IOException` failure while parsing the workbook (e.g. a corrupt zip container) |

Note that per-row failures never produce an HTTP error — they surface in `errors[]` with a `200`.

**Example**

```bash
curl -s -X POST "{{BASE_URL}}/api/physical-media/import" \
  -H "Cookie: khi_auth_token=$TOKEN" \
  -F "file=@All Final Archive Lists.xlsx" \
  -F "sheet=Sheet1"
```

**Notes** — writes exactly **one** `IMPORT` audit row per batch,
`details = "inserted=<n> skipped=<n>"`; individual rows get no `CREATE` audit entry. Every row it
creates carries `source: "IMPORT"`, which is filterable via `?source=IMPORT` on the listing.

---

### `POST /api/physical-media/import/sheets`

Peek at a workbook's sheet names so the UI can offer a dropdown before committing to an import.

**Authority:** `physical_media:import`

**Request parts**

| Part | Kind | Required | Description |
|---|---|---|---|
| `file` | `@RequestPart` multipart file | yes | The workbook. Field name must be `file`; same `.xlsx` extension check as `/import` |

**Response** `200 OK` — sheet names in workbook order.

```json
["Sheet1", "Audio Cassettes", "VHS"]
```

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `PHYSICAL_MEDIA_VALIDATION_ERROR` | Missing/empty file, filename not `.xlsx`, or `IOException` while reading the workbook |
| `400` | `MISSING_REQUEST_PART` | The `file` part is absent |
| `400` | `BAD_REQUEST` | `MultipartException` — malformed multipart body |
| `401` | `TOKEN_MISSING` / `AUTHENTICATION_FAILED` | No token on the request — neither an `Authorization: Bearer` header nor the cookie — or the token is invalid/revoked |
| `403` | `ACCESS_DENIED` | Caller lacks `physical_media:import` |
| `413` | `UPLOAD_TOO_LARGE` | Beyond the multipart size limits |
| `415` | `UNSUPPORTED_MEDIA_TYPE` | Not sent as `multipart/form-data` |

**Example**

```bash
curl -s -X POST "{{BASE_URL}}/api/physical-media/import/sheets" \
  -H "Cookie: khi_auth_token=$TOKEN" \
  -F "file=@All Final Archive Lists.xlsx"
```

**Notes** — no DB writes, no audit row, no file retained. The controller method takes no
`Authentication` or `HttpServletRequest` at all.

---

## Type catalog — `PhysicalMediaTypeAPI`

Class-level `@RequestMapping("/api/physical-media/types")`, no class-level `@PreAuthorize`.

`physical_media_types` is the controlled vocabulary behind `PhysicalMedia.physicalMediaType`. It
exists as a table rather than a Java enum because the team adds an unfamiliar format roughly once a
year, and a table row avoids a redeploy. `name` is unique (`uk_pmt_name`) and is referenced **by
value** from `physical_media.physical_media_type` — there is no foreign key.

### The nine technical defaults

Each catalog row carries nine capture-chain fields whose names mirror the inventory columns exactly.
The frontend copies them into the create/edit form when the user picks a type; they are **defaults,
not constraints** — any row may override any of them, and editing a catalog row never rewrites
values already stamped on existing inventory rows.

| # | Field | Group |
|---|---|---|
| 1 | `extension` | Capture format |
| 2 | `bitOrColorDepth` | Capture format |
| 3 | `sampleOrFrameRate` | Capture format |
| 4 | `channelsOrResolution` | Capture format |
| 5 | `playbackModel` | Capture chain |
| 6 | `captureInterface` | Capture chain |
| 7 | `signalInterface` | Capture chain |
| 8 | `ingestSoftware` | Capture chain |
| 9 | `formatCodec` | Capture chain |

### Seeded types

`platform/config/PhysicalMediaTypeSeeder.java` runs on `ApplicationReadyEvent` and inserts six
types when absent. It is idempotent and non-destructive: existing rows are left untouched, so an
admin edit survives every restart. To force a reset to the shipped values, delete the row and
restart.

| Name | extension | bitOrColorDepth | sampleOrFrameRate | channelsOrResolution | playbackModel | captureInterface | signalInterface | ingestSoftware | formatCodec |
|---|---|---|---|---|---|---|---|---|---|
| Audio Cassette | `wav` | `24` | `48000` | `Stereo` | Pioneer Stereo Double Cassette Deck CT-W2O8R | MOTO 896mk3 hybrid | RCA | Adobe Audition | PCM |
| Reel | `wav` | `24` | `48000` | `Stereo` | AKAI X-201D | MOTO 896mk3 hybrid | RCA | Adobe Audition | PCM |
| Vinyl Record | `wav` | `24` | `48000` | `Stereo` | Audio-Technica AT-LP60 | MOTO 896mk3 hybrid | RCA | Adobe Audition | PCM |
| VHS Cassette | `avi` | `8` | `25` | `720X576` | Sony DVD Player / Video Cassette Recorder SLV-D985P ME | Blackmagic Intensity Pro 4K | Composite | Blackmagic Media Express | Uncompressed avi 8-bit YUV, 625i50 PAL |
| MiniDV | `avi` | `8` | `25` | `720x576` | Sony HVR M10 | FireWire 400 | FireWire IEEE 1394 | Adobe Premiere | DV(Native) |
| CD/DVD | — | — | — | — | — | — | — | — | — |

Seeded descriptions: "Compact audio cassette tape; 4-track stereo, ~1.875 ips.", "Open-reel
magnetic tape, typically 1/4-inch.", "LP / 7-inch / 12-inch vinyl record.", "VHS video cassette;
PAL 625i.", "MiniDV digital video cassette captured over FireWire.", and for CD/DVD "Compact disc
/ DVD optical media. Capture defaults to be filled when the team picks the ingest chain." CD/DVD
ships with all nine defaults empty by design.

Besides the seeder and `POST /api/physical-media/types`, a third source of catalog rows is the Excel
importer's `ensureExists`, which auto-creates any unknown type with blank defaults, the description
"Auto-created during Excel import." and `createdBy`/`updatedBy` set to the importing actor (or
`system-import` when unauthenticated).

---

### `GET /api/physical-media/types`

Full catalog, ordered by name. Not paged, not cached.

**Authority:** `physical_media:read`

**Response** `200 OK`

```json
[
  {
    "id": 1,
    "name": "Audio Cassette",
    "description": "Compact audio cassette tape; 4-track stereo, ~1.875 ips.",
    "extension": "wav",
    "bitOrColorDepth": "24",
    "sampleOrFrameRate": "48000",
    "channelsOrResolution": "Stereo",
    "playbackModel": "Pioneer Stereo Double Cassette Deck CT-W2O8R",
    "captureInterface": "MOTO 896mk3 hybrid",
    "signalInterface": "RCA",
    "ingestSoftware": "Adobe Audition",
    "formatCodec": "PCM",
    "createdBy": "system-seed",
    "updatedBy": "system-seed",
    "createdAt": "2026-07-02T05:00:11.004Z",
    "updatedAt": "2026-07-02T05:00:11.004Z",
    "version": 0
  },
  {
    "id": 6,
    "name": "CD/DVD",
    "description": "Compact disc / DVD optical media. Capture defaults to be filled when the team picks the ingest chain.",
    "createdBy": "system-seed",
    "updatedBy": "system-seed",
    "createdAt": "2026-07-02T05:00:11.061Z",
    "updatedAt": "2026-07-02T05:00:11.061Z",
    "version": 0
  }
]
```

The CD/DVD entry shows the `non_null` behavior: all nine defaults are null, so all nine keys are
absent rather than present-and-null. Clients must treat a missing key as "no default".

**Errors**

| Status | `error` code | When |
|---|---|---|
| `401` | `TOKEN_MISSING` / `AUTHENTICATION_FAILED` | No token on the request — neither an `Authorization: Bearer` header nor the cookie — or the token is invalid/revoked |
| `403` | `ACCESS_DENIED` | Caller lacks `physical_media:read` |
| `500` | `DATABASE_ERROR` | `DataAccessException` from the query |

**Example**

```bash
curl -s "{{BASE_URL}}/api/physical-media/types" \
  -H "Cookie: khi_auth_token=$TOKEN"
```

**Notes** — no audit row is written for catalog reads.

---

### `GET /api/physical-media/types/{id}`

One catalog entry by numeric id.

**Authority:** `physical_media:read`

**Path parameters**

| Name | Type | Description |
|---|---|---|
| `id` | long | Catalog primary key. Note this is the numeric `id`, not a name |

**Response** `200 OK` — one object with the shape shown above.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `TYPE_MISMATCH` | `id` is not a number |
| `401` | `TOKEN_MISSING` / `AUTHENTICATION_FAILED` | No token on the request — neither an `Authorization: Bearer` header nor the cookie — or the token is invalid/revoked |
| `403` | `ACCESS_DENIED` | Caller lacks `physical_media:read` |
| `404` | `PHYSICAL_MEDIA_NOT_FOUND` | No catalog row with that id — `"Physical-media type not found: <id>"` |

**Example**

```bash
curl -s "{{BASE_URL}}/api/physical-media/types/4" \
  -H "Cookie: khi_auth_token=$TOKEN"
```

---

### `POST /api/physical-media/types`

Register a new type. `Content-Type: application/json` required.

**Authority:** `physical_media:type_manage` — ADMIN only by default; not in
`EMPLOYEE_DEFAULT_PERMISSIONS`.

**Request body** — `PhysicalMediaTypeCreateRequestDTO`. `name` is `@NotBlank` and ≤ 200 chars;
everything else is optional so an admin can register the type now and fill the capture chain later.
Caps: `extension` ≤ 50; `bitOrColorDepth`, `sampleOrFrameRate`, `channelsOrResolution` ≤ 100;
`formatCodec` ≤ 200; `description`, `playbackModel`, `captureInterface`, `signalInterface`,
`ingestSoftware` uncapped (TEXT). Every string is trimmed, and a whitespace-only value is stored as
`null`.

```json
{
  "name": "Betamax",
  "description": "Sony Betamax video cassette.",
  "extension": "avi",
  "bitOrColorDepth": "8",
  "sampleOrFrameRate": "25",
  "channelsOrResolution": "720X576",
  "playbackModel": "Sony SL-HF950",
  "captureInterface": "Blackmagic Intensity Pro 4K",
  "signalInterface": "Composite",
  "ingestSoftware": "Blackmagic Media Express",
  "formatCodec": "Uncompressed avi 8-bit YUV, 625i50 PAL"
}
```

**Response** `200 OK` (not `201`) — the created catalog entry, with `id`, `createdBy`, `updatedBy`,
`createdAt`, `updatedAt` and `version: 0`.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `VALIDATION_ERROR` | `name` blank/missing (`@NotBlank`) or a `@Size` cap exceeded |
| `400` | `PHYSICAL_MEDIA_VALIDATION_ERROR` | `name` whitespace-only after trim (`"Name is required"`), or a type with that name already exists (`"Type already exists: <name>"`) |
| `400` | `JSON_PARSE_ERROR` | Malformed JSON |
| `401` | `TOKEN_MISSING` / `AUTHENTICATION_FAILED` | No token on the request — neither an `Authorization: Bearer` header nor the cookie — or the token is invalid/revoked |
| `403` | `ACCESS_DENIED` | Caller lacks `physical_media:type_manage` |
| `409` | `CONFLICT` | `DataIntegrityViolationException` — `uk_pmt_name` violated by a concurrent insert |
| `415` | `UNSUPPORTED_MEDIA_TYPE` | Body sent as anything other than `application/json` |

**Example**

```bash
curl -s -X POST "{{BASE_URL}}/api/physical-media/types" \
  -H "Cookie: khi_auth_token=$TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name": "Betamax", "extension": "avi", "signalInterface": "Composite"}'
```

**Notes** — writes a `TYPE_CREATE` audit row into `physical_media_audit_logs` with
`details = "added type '<name>'"`. Catalog audit rows reuse the inventory columns: `physical_media_id`
holds the catalog id and `physical_label` holds the type name, so the analytics feed can render
"`<actor>` added type 'Betamax'" without a join.

---

### `PATCH /api/physical-media/types/{id}`

Edit a type's name, description, or any of the nine defaults. `Content-Type: application/json`
required.

**Authority:** `physical_media:type_manage`

**Path parameters**

| Name | Type | Description |
|---|---|---|
| `id` | long | Catalog primary key |

**Request body** — `PhysicalMediaTypeUpdateRequestDTO`. All fields optional; `null`/absent means
"leave alone". Same `@Size` caps as create, but no `@NotBlank` — an explicitly empty `name` is
rejected in the service instead. Renaming is allowed but collides loudly.

```json
{
  "playbackModel": "Sony SL-HF950 (replaced 2026-08)",
  "ingestSoftware": "Blackmagic Media Express 3.9"
}
```

**Response** `200 OK` — the updated catalog entry with `version` bumped.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `TYPE_MISMATCH` | `id` is not a number |
| `400` | `VALIDATION_ERROR` | A `@Size` cap is exceeded |
| `400` | `PHYSICAL_MEDIA_VALIDATION_ERROR` | `name` sent but blank after trim (`"Name must not be blank"`), or renamed onto an existing different type (`"Type already exists: <name>"`) |
| `400` | `JSON_PARSE_ERROR` | Malformed JSON |
| `401` | `TOKEN_MISSING` / `AUTHENTICATION_FAILED` | No token on the request — neither an `Authorization: Bearer` header nor the cookie — or the token is invalid/revoked |
| `403` | `ACCESS_DENIED` | Caller lacks `physical_media:type_manage` |
| `404` | `PHYSICAL_MEDIA_NOT_FOUND` | No catalog row with that id |
| `409` | `STALE_VERSION` | Concurrent write bumped `version` first |
| `409` | `CONFLICT` | `DataIntegrityViolationException` on `uk_pmt_name` |
| `415` | `UNSUPPORTED_MEDIA_TYPE` | Body sent as anything other than `application/json` |

**Example**

```bash
curl -s -X PATCH "{{BASE_URL}}/api/physical-media/types/4" \
  -H "Cookie: khi_auth_token=$TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"ingestSoftware": "Blackmagic Media Express 3.9"}'
```

**Notes** — writes a `TYPE_UPDATE` audit row, `details = "fields=playbackModel,ingestSoftware"`
(or `"fields=<none>"`). Renaming a type does **not** cascade: `physical_media.physical_media_type`
holds the old name as a plain string and there is no foreign key, so existing inventory rows keep
pointing at a name the catalog no longer has. Re-tag those rows via `PATCH /api/physical-media/{pmCode}`.

---

### `DELETE /api/physical-media/types/{id}`

Hard-delete an unused type. There is no trash for the catalog.

**Authority:** `physical_media:type_manage`

**Path parameters**

| Name | Type | Description |
|---|---|---|
| `id` | long | Catalog primary key |

**Response** `204 No Content` — empty body.

Deletion is refused when any `physical_media` row still names the type. The in-use check scans
`mediaRepository.findAll()` and counts rows whose `physicalMediaType` equals the catalog name —
it therefore counts **trashed rows too**, so a type used only by trashed inventory cannot be
deleted until those rows are purged or re-tagged.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `TYPE_MISMATCH` | `id` is not a number |
| `400` | `PHYSICAL_MEDIA_VALIDATION_ERROR` | Type still in use — `"Type '<name>' is still used by <n> record(s)"` |
| `401` | `TOKEN_MISSING` / `AUTHENTICATION_FAILED` | No token on the request — neither an `Authorization: Bearer` header nor the cookie — or the token is invalid/revoked |
| `403` | `ACCESS_DENIED` | Caller lacks `physical_media:type_manage` |
| `404` | `PHYSICAL_MEDIA_NOT_FOUND` | No catalog row with that id |
| `409` | `CONFLICT` | `DataIntegrityViolationException` |

**Example**

```bash
curl -s -X DELETE "{{BASE_URL}}/api/physical-media/types/7" \
  -H "Cookie: khi_auth_token=$TOKEN" -i
```

**Notes** — writes a `TYPE_DELETE` audit row, `details = "deleted type '<name>'"`. A deleted type
that still appears in a sheet will simply be auto-recreated by the next Excel import.

---

## Admin trash — `AdminPhysicalMediaAPI`

Class-level `@RequestMapping("/api/admin/physical-media")`, no class-level `@PreAuthorize`. All
three methods require `physical_media:delete`, which only ADMIN holds by default. Soft-trash itself
lives on the non-admin controller (`DELETE /api/physical-media/{pmCode}`, `physical_media:remove`)
so a holder of that authority can trash a row; inspecting the trash, restoring, and purging stay
admin-only because they touch records that may belong to other people.

### `GET /api/admin/physical-media/trash`

Paged listing of trashed rows (`removedAt IS NOT NULL`).

**Authority:** `physical_media:delete`

**Paging parameters**

| Name | Type | Default | Description |
|---|---|---|---|
| `page` | int | `0` | Zero-based page index |
| `size` | int | `100` | Page size (`@PageableDefault(size = 100)`) — larger than the active listing's 50 |
| `sort` | string | `id,ASC` | Bound, but **never applied** — same `effectivePageable` handling as the active listing. The pinned `id ASC` reads as "earliest trashed first" |

**Query parameters** — the **same** `PhysicalMediaFilterParams` object as `GET /api/physical-media`,
with every field behaving identically. See that endpoint's tables for the complete list. Two
filters are only meaningful here:

| Name | Type | Description |
|---|---|---|
| `removedBy` | string | Contains-match on who trashed the row |
| `removedFrom` / `removedTo` | date | Inclusive `YYYY-MM-DD` range over `removedAt`, day bounds in `Asia/Baghdad` |

Same three execution paths as the active listing, over the trashed set.

**Response** `200 OK` — standard `Page` envelope; `content[]` items add the two trash fields:

```json
{
  "content": [
    {
      "id": 812,
      "pmCode": "PM_000812",
      "physicalMediaType": "VHS Cassette",
      "title": "ئاهەنگی زەماوەند",
      "physicalLabel": "VHS-0056",
      "inventoryNumber": 56,
      "digitization": "NOT_DIGITIZED",
      "digitizationCode": 0,
      "source": "IMPORT",
      "createdBy": "employee@example.com",
      "updatedBy": "employee@example.com",
      "removedBy": "admin@example.com",
      "createdAt": "2026-08-20T07:11:03.221Z",
      "updatedAt": "2026-08-20T07:11:03.221Z",
      "removedAt": "2026-08-25T13:40:59.812Z",
      "version": 1
    }
  ],
  "totalElements": 3,
  "totalPages": 1,
  "number": 0,
  "size": 100,
  "first": true,
  "last": true,
  "numberOfElements": 3,
  "empty": false
}
```

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `VALIDATION_ERROR` | A filter parameter fails to bind |
| `401` | `TOKEN_MISSING` / `AUTHENTICATION_FAILED` | No token on the request — neither an `Authorization: Bearer` header nor the cookie — or the token is invalid/revoked |
| `403` | `ACCESS_DENIED` | Caller lacks `physical_media:delete` |
| `500` | `DATABASE_ERROR` | `DataAccessException` from the query |

**Example**

```bash
curl -s "{{BASE_URL}}/api/admin/physical-media/trash?removedFrom=2026-08-01&removedTo=2026-08-31&sortBy=updatedAt&sortDirection=desc" \
  -H "Cookie: khi_auth_token=$TOKEN"
```

**Notes** — writes a `LIST` audit row prefixed `trash`,
`details = "trash size=<n> total=<n>"` plus the same `filtered=`/`sorted=` suffix.

---

### `POST /api/admin/physical-media/{pmCode}/restore`

Move a trashed row back to the active inventory: clears `removedAt` and `removedBy`, and stamps
`updatedBy` with the restoring actor.

**Authority:** `physical_media:delete`

**Path parameters**

| Name | Type | Description |
|---|---|---|
| `pmCode` | string | Business key of a **trashed** row |

**Request body** — none.

**Response** `200 OK` — the restored row, with `removedAt` and `removedBy` now absent from the JSON
(`non_null`).

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `PHYSICAL_MEDIA_VALIDATION_ERROR` | The row exists but is not trashed — `"Physical media is not in trash: <pmCode>"` |
| `401` | `TOKEN_MISSING` / `AUTHENTICATION_FAILED` | No token on the request — neither an `Authorization: Bearer` header nor the cookie — or the token is invalid/revoked |
| `403` | `ACCESS_DENIED` | Caller lacks `physical_media:delete` |
| `404` | `PHYSICAL_MEDIA_NOT_FOUND` | No row with that `pmCode` at all |
| `409` | `STALE_VERSION` | Concurrent write bumped `version` first |

**Example**

```bash
curl -s -X POST "{{BASE_URL}}/api/admin/physical-media/PM_000812/restore" \
  -H "Cookie: khi_auth_token=$TOKEN"
```

**Notes** — writes a `RESTORE` audit row, `details = "restored from trash"`. The row keeps its
original `pmCode` and `inventoryNumber`.

---

### `DELETE /api/admin/physical-media/{pmCode}/purge`

Permanently delete a trashed row. Irreversible.

**Authority:** `physical_media:delete`

**Path parameters**

| Name | Type | Description |
|---|---|---|
| `pmCode` | string | Business key of a **trashed** row |

**Response** `204 No Content` — empty body.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `PHYSICAL_MEDIA_VALIDATION_ERROR` | The row is still active — `"Physical media must be trashed before purge: <pmCode>"`. Purge requires a soft-delete first |
| `401` | `TOKEN_MISSING` / `AUTHENTICATION_FAILED` | No token on the request — neither an `Authorization: Bearer` header nor the cookie — or the token is invalid/revoked |
| `403` | `ACCESS_DENIED` | Caller lacks `physical_media:delete` |
| `404` | `PHYSICAL_MEDIA_NOT_FOUND` | No row with that `pmCode` at all |
| `409` | `CONFLICT` | `DataIntegrityViolationException` |

**Example**

```bash
curl -s -X DELETE "{{BASE_URL}}/api/admin/physical-media/PM_000812/purge" \
  -H "Cookie: khi_auth_token=$TOKEN" -i
```

**Notes** — the `PURGE` audit row (`details = "permanent deletion"`) is written **before** the
delete, so the trail survives the record. The audit row keeps `physical_media_code`,
`physical_label`, `title` and `physical_media_type` snapshots, so the feed stays readable with no
row left to join against.

Purging changes what `MAX(inventoryNumber)` sees: purging the highest-numbered row of a type makes
the next create of that type reuse that number.

---

## Frontend integration

The API deliberately leaves several conveniences to the client. This section records the UI contract
the inventory screens were built against, so a rewrite lands in the same place rather than
rediscovering it.

### Type dropdown and autofill

**The server never autofills the nine capture defaults.** `PhysicalMediaService.create` stores
exactly what the request body carried and never reads the catalog row for the chosen type — it
consults the catalog only to check that the name exists. If the form does not copy the defaults
across, the row is saved with those nine columns empty. That is what makes them *defaults* rather
than constraints: the copy happens once, at form time, and the value is then the record's own.

1. Load `GET /api/physical-media/types` when the create/edit form mounts and hold the array in page
   state. The catalog is neither paged nor server-cached, so one fetch per form is enough.
2. Render the type field as a dropdown over that list. Display `name`, and send `name` — **not**
   `id` — as `physicalMediaType`, because `physical_media.physical_media_type` stores the name as a
   plain string.
3. On selection, copy the catalog row's nine technical fields into the matching form inputs. The
   field names are identical on both sides, so this is a straight field-for-field copy. Because
   `non_null` omits null defaults entirely, treat a missing key as "no default" and *clear* the
   input — do not leave the previous type's value sitting there.
4. Offer a "+ Add new type" entry at the bottom of the dropdown, opening a small modal over `name`,
   `description` and the nine defaults that `POST`s to `/api/physical-media/types`. On success,
   refresh the cached list and pre-select the new type. Gate the entry on
   `physical_media:type_manage`, or an EMPLOYEE finds a button that can only ever return `403`.
5. Re-selecting a different type re-runs the autofill. Prompt before overwriting only when the user
   has already hand-edited one of the nine fields; a straight type swap should feel free.

### Permission-gated controls

The three controllers enforce authority per method. The UI hides what the caller cannot use rather
than letting it fail with a `403`.

| Show | When the caller holds |
|---|---|
| "Physical media" nav entry, list and detail screens | `physical_media:read` |
| "+ New" button and the create form | `physical_media:create` |
| Inline edit / save on the detail screen | `physical_media:update` |
| "Import Excel" button | `physical_media:import` |
| Row-level "Move to trash" | `physical_media:remove` |
| Trash bin, plus the restore and purge controls | `physical_media:delete` |
| "+ Add type" entry and the catalog admin screen | `physical_media:type_manage` |

A default EMPLOYEE holds the first four and nothing else, so the ordinary staff view is browse,
create, edit, import — with no destructive control on screen at all. Everything below that line is
ADMIN-by-default.

### Listing screen

Rows arrive in insertion order unless asked otherwise; change the order with `sortBy` +
`sortDirection`, never with the raw `sort` parameter, which is ignored on every path. Only one sort
key is supported — there is no multi-key sort.

- Newest first: `?sortBy=createdAt&sortDirection=desc`
- One type's sequence in order: `?physicalMediaType=VHS%20Cassette&sortBy=inventoryNumber`

Each row carries both forms of the two encoded fields (`digitization` / `digitizationCode`,
`needToClear` / `needToClearCode`). Render the typed form for humans and keep the numeric form in
filter URL state, so a shared link is not tied to the spelling of an enum constant.

### Create form

Two of the create rules are worth enforcing client-side so the user sees them before the round trip:

- `physicalMediaType` must already exist in the catalog. That is why the field is a dropdown bound
  to the catalog and not a free-text input: the strictness exists to catch the typo that would
  otherwise leave a one-record "VHS Casette" type in the inventory that nobody ever finds again. The
  importer is lenient on the same field precisely because a sheet must never be refused wholesale; a
  hand-typed row has no such excuse.
- `title` **or** `physicalLabel` must be present. Either one alone is enough.

Do not send `inventoryNumber` at all. Call `GET /api/physical-media/next-number?type=<chosen>` when
the user picks a type and show the answer as a read-only hint — "this will be VHS Cassette #56" —
never as an editable input; the create re-mints the number under the per-type lock and returns the
authoritative value.

Because every column also accepts its Excel header as a `@JsonAlias`, a "paste a row" affordance is
cheap: accept the sheet's own column names verbatim in the JSON body and let Jackson bind them.

### Edit form

`PATCH` is field-by-field: send only what the user actually changed. Never round-trip the whole row
back — a stale form state overwrites an edit somebody else made between load and save, which is the
"save wiped my notes" bug the PATCH semantics exist to avoid.

To **clear** a text field, send the empty string (`""`). `PhysicalMediaMapper.trimOrNull` turns any
blank value into `null`, and the field still counts as touched in the `UPDATE` audit details. There
is no other way to null a column through the API.

A `409 STALE_VERSION` means someone else saved first: re-fetch the row, re-apply the user's edits on
top, and submit again.

### Excel upload flow

1. Restrict the file picker to `.xlsx` —
   `accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"`. The server
   rejects anything else on the filename extension anyway.
2. Offer an optional sheet picker. `POST /api/physical-media/import/sheets` writes nothing and
   records no audit row, so it is safe to call as soon as the user picks a file. Default to the
   first sheet.
3. Show a progress indicator and block navigation during `POST /api/physical-media/import` — a full
   sheet is thousands of rows and the request is synchronous.
4. Render `PhysicalMediaImportReportDTO` as four blocks:
   - a summary card — `inserted` and `skipped` out of `totalDataRows`;
   - a "matched columns" chip list from `matchedHeaders`;
   - an "unrecognized columns — ignored" chip list from `unknownHeaders`, which is where a renamed
     or newly added sheet header shows up;
   - a collapsible row table from `errors[]`, **split by message prefix**: rows reading
     `saved with stripped fields …` are in the database and need a human to restore the stripped
     columns, while `could not save even stripped row …` rows are *not* in the database and must be
     re-entered by hand.

Warn before the upload that the importer has no dedupe: re-uploading the same workbook adds every
row a second time, and cleaning that up means soft-trashing and purging the duplicates from the
admin trash list.

---

## Audit trail

Every mutation and every read path writes one row to `physical_media_audit_logs` through
`PhysicalMediaAuditService`, in its own `REQUIRES_NEW` transaction so a failed business commit still
leaves the forensic record. The table is column-aligned with the other `*_audit_logs` tables so the
analytics `UNION ALL` sees one shape, and adds `physical_label` + `physical_media_type` snapshots.

Four endpoints write nothing at all: the two catalog `GET`s, `GET /api/physical-media/next-number`,
and `POST /api/physical-media/import/sheets`. `GET /api/physical-media/search` also skips its row
when `q` is blank, because the service short-circuits to `[]` before recording.

| Action | Written by |
|---|---|
| `CREATE` | `POST /api/physical-media` |
| `READ` | `GET /api/physical-media/{pmCode}` |
| `LIST` | `GET /api/physical-media`, `GET /api/admin/physical-media/trash` |
| `SEARCH` | `GET /api/physical-media/search` |
| `UPDATE` | `PATCH /api/physical-media/{pmCode}` |
| `REMOVE` | `DELETE /api/physical-media/{pmCode}` |
| `RESTORE` | `POST /api/admin/physical-media/{pmCode}/restore` |
| `PURGE` | `DELETE /api/admin/physical-media/{pmCode}/purge` |
| `IMPORT` | `POST /api/physical-media/import` — one row per batch |
| `TYPE_CREATE` / `TYPE_UPDATE` / `TYPE_DELETE` | The three `/types` mutations |

`DELETE` is declared in `PhysicalMediaAuditAction` but no endpoint in these three controllers emits
it — soft-trash records `REMOVE` and hard delete records `PURGE`.

`platform/config/PhysicalMediaAuditActionConstraintInitializer.java` re-syncs the Postgres CHECK
constraint on `physical_media_audit_logs.action` from this enum at boot, for the same
`ddl-auto=update` reason as the digitization constraint.

## Error envelope

Every failure returns the standard `ApiErrorResponse`. `traceId` appears only when an MDC
correlation id is present, and `details` only when the handler attaches structured data.

```json
{
  "timestamp": "2026-08-26T09:12:44.118Z",
  "status": 400,
  "error": "PHYSICAL_MEDIA_VALIDATION_ERROR",
  "category": "VALIDATION",
  "message": "Validation failed",
  "hint": "Physical-media row invalid — see field-level reasons in 'details'.",
  "path": "/api/physical-media",
  "details": {
    "physicalMediaType": "not in the type catalog — add it via /api/physical-media/types first"
  }
}
```

On a `403`, `details` carries `requiredAuthority` (parsed out of the method's `@PreAuthorize`),
`actor`, `actorAuthorities` and `requestMethod` — enough for the UI to say "you have X, you need
`physical_media:type_manage`".

## Related

- [Internal API index](../README.md)
- [Conventions](../01-conventions.md) — the Spring `Page` envelope, timestamp formats
  (`Asia/Baghdad`), and the shared error codes referenced throughout this document
