# Physical Media — feature spec

The **physical-media inventory** is the 9th entity domain in the archive
platform. It tracks every physical artefact (cassette, reel, VHS, DVD, …)
the team is preparing for digitisation, with a 1:1 column mapping to the
source spreadsheet `/Users/khi/Documents/All Final Archive Lists.xlsx →
Sheet1`. The same endpoints accept hand-entered rows and `.xlsx` bulk
imports of the source sheet.

---

## 1. Entity shape

`platform.model.physicalmedia.PhysicalMedia` (table `physical_media`).

| Sheet column                                   | Java field             | DB column                | Type / notes                                                       |
| ---------------------------------------------- | ---------------------- | ------------------------ | ------------------------------------------------------------------ |
| _internal_                                     | `id`                   | `id`                     | PK                                                                 |
| _internal_                                     | `pmCode`               | `pm_code`                | `PM_NNNNNN`, unique, generated atomically via `CodeGenLock`        |
| `No.`                                          | `rowNumber`            | `row_number`             | int                                                                |
| `Number`                                       | `inventoryNumber`      | `inventory_number`       | int                                                                |
| `Physical Media Type`                          | `physicalMediaType`    | `physical_media_type`    | varchar(200), indexed                                              |
| `جۆری بابەت(Media Category)`                  | `mediaCategory`        | `media_category`         | varchar(200), indexed                                              |
| `ناوی بابەت (Title)`                          | `title`                | `title`                  | TEXT                                                               |
| `جۆر(Type)`                                    | `subType`              | `sub_type`               | varchar(200)                                                       |
| `کۆد (Physical Label)`                         | `physicalLabel`        | `physical_label`         | varchar(200), indexed                                              |
| `قەبارە (Size)`                                | `size`                 | `size`                   | varchar(200)                                                       |
| `ناوەڕۆک (Content)`                            | `content`              | `content`                | TEXT                                                               |
| `تێبینی بەشی ئارشیڤ Archive Dep Note`         | `archiveDepNote`       | `archive_dep_note`       | TEXT                                                               |
| `دیجیتایز Digitization`                        | `digitization`         | `digitization`           | `DigitizationStatus` enum, indexed (see §3)                        |
| `بەرهەمهێن(خاوەن) Owner`                       | `owner`                | `owner`                  | TEXT                                                               |
| `ساڵ Year`                                     | `year`                 | `year`                   | int                                                                |
| `درێژایی خولەک Duration Min`                  | `durationMin`          | `duration_min`           | int (minutes)                                                      |
| `ژمارەی تراک Track Numbers`                    | `trackNumbers`         | `track_numbers`          | int                                                                |
| `ناوی تراکەکان Track Name`                    | `trackName`            | `track_name`             | TEXT                                                               |
| `Extension`                                    | `extension`            | `extension`              | varchar(50)                                                        |
| `Bit Depth / Color Depth`                      | `bitOrColorDepth`      | `bit_or_color_depth`     | varchar(100)                                                       |
| `Sample Rate kHz/Frame Rate fps`               | `sampleOrFrameRate`    | `sample_or_frame_rate`   | varchar(100)                                                       |
| `Channels/Resolution`                          | `channelsOrResolution` | `channels_or_resolution` | varchar(100)                                                       |
| `Playback Model`                               | `playbackModel`        | `playback_model`         | TEXT                                                               |
| `Capture Interface`                            | `captureInterface`     | `capture_interface`      | TEXT                                                               |
| `Signal Interface(Cable Type)`                 | `signalInterface`      | `signal_interface`       | TEXT                                                               |
| `Ingest Software`                              | `ingestSoftware`       | `ingest_software`        | TEXT                                                               |
| `Format/Codec`                                 | `formatCodec`          | `format_codec`           | varchar(200)                                                       |
| `ڕێکەوتی دیجیتایز/ گواستنەوە Digitize Date`   | `digitizeDate`         | `digitize_date`          | `LocalDate` (no time-zone — sheet only carries day precision)      |
| `تاگ Tags`                                     | `tags`                 | `tags`                   | TEXT                                                               |
| `خاوێنکردن Need to Clear`                      | `needToClear`          | `need_to_clear`          | boolean, indexed (see §3)                                          |
| `تێبینی بەشی تیجیتایز Capture Dep Note`        | `captureDepNote`       | `capture_dep_note`       | TEXT                                                               |

**Plus the standard envelope** every entity in the platform carries:
`source` (`MANUAL`/`IMPORT`), `created_at`, `updated_at`, `removed_at`,
`created_by`, `updated_by`, `removed_by`, `version` (optimistic locking).

### Indexes

`pm_code` (unique), `physical_label`, `physical_media_type`,
`media_category`, `digitization`, `need_to_clear`, `removed_at` — wired
up via `@Index` on the entity. The boot-time
`AuditLogIndexInitializer` also creates analytics indexes on
`physical_media_audit_logs (actor_username, occurred_at DESC)` and
friends so the cross-entity analytics UNION stays fast.

---

## 2. Audit log

`PhysicalMediaAuditLog` → table `physical_media_audit_logs`. Column
layout matches every other `*_audit_logs` table so the analytics UNION
ALL CTE in `AnalyticsService` works unchanged after wiring in the new
`UNION ALL SELECT 'physical_media', …` branch.

Actions recorded (`PhysicalMediaAuditAction`):
`CREATE`, `READ`, `LIST`, `SEARCH`, `UPDATE`, `REMOVE`, `RESTORE`,
`DELETE`, `PURGE`, `IMPORT`, plus the catalog actions `TYPE_CREATE`,
`TYPE_UPDATE`, `TYPE_DELETE`. The CHECK constraint is kept in sync at
boot by `PhysicalMediaAuditActionConstraintInitializer` (same recipe as
the maqam and user-role initializers).

### `details` field per action

What the activity feed shows in the details column:

| Action        | `details` content                                          |
| ------------- | ---------------------------------------------------------- |
| `CREATE`      | `type=<physicalMediaType> label=<physicalLabel>`           |
| `UPDATE`      | `fields=<csv of touched field names>` (e.g. `fields=title,owner,year`) |
| `REMOVE`      | `soft-trashed`                                             |
| `RESTORE`     | `restored from trash`                                      |
| `PURGE`       | `permanent deletion`                                       |
| `READ`        | `null` (the act itself is enough)                          |
| `LIST`        | `size=<n>` (or `trash size=<n>` for the admin trash view)  |
| `SEARCH`      | `q=<query> hits=<count>`                                   |
| `IMPORT`      | `inserted=<X> skipped=<Y>`                                 |
| `TYPE_CREATE` | `added type '<name>'`                                      |
| `TYPE_UPDATE` | `fields=<csv of touched field names on the catalog row>`   |
| `TYPE_DELETE` | `deleted type '<name>'`                                    |

Catalog audit rows store the catalog id in `physical_media_id` and the
type name in `physical_label` so the analytics feed surfaces them
without an extra join: `<actor> added type 'CD/DVD'`.

### Wired into analytics

The whole user-activity stack picks up physical-media work without any
new code paths in `AnalyticsService`:

- `ALL_LOGS_CTE` (UNION ALL) — added a 9th branch emitting
  `entity='physical_media', entity_id=physical_media_id,
  entity_code=physical_media_code`.
- `ENTITY_KEYS` — added `"physical_media"` so the `entities=` query
  filter accepts it and `loadEntityStats` pre-seeds a zero-stats row for
  it in the `byEntity` map even on an empty window.
- `ACTION_KEYS` — added `IMPORT` so the `actions=` filter accepts it.
- `AuditLogIndexInitializer` — added `physical_media_audit_logs` so the
  per-actor / per-day indexes get built at boot.

What admins see:

- `/api/analytics/overview` → `byEntity.physical_media` carries
  CREATE/UPDATE/REMOVE/READ/SEARCH/IMPORT counts; the top-users panel
  surfaces whoever has been editing or importing inventory.
- `/api/analytics/users/{username}` → the same `byEntity` shape per
  user, plus the daily / monthly buckets include physical-media rows.
- `/api/analytics/feed` → each `RecentActivityItemDTO` from a
  physical-media action arrives with `entity="physical_media"`,
  `entityCode="PM_NNNNNN"`, the actor envelope, and the request
  envelope just like every other entity.
- `/api/analytics/entities` → physical-media counts ship in the per-
  entity stats map.

Cache note: `Cacheable` keys are derived from the filter — the new
`"physical_media"` entity key changes `toCacheKey()` outputs, so cached
overview / user-activity responses computed before this deploy expire
naturally on TTL. Nothing to flush by hand.

---

## 2.4 Physical-media type catalog

The `physicalMediaType` column on every record points by name at a row
of the `physical_media_types` catalog. The catalog is editable — admins
can add a new type when an unfamiliar tape format arrives — and each
catalog row carries the **nine technical-capture defaults** that travel
with that type, so the frontend can autofill those nine fields when the
user picks a type from the dropdown.

### Seeded baseline (six known types)

Pre-populated at boot by `PhysicalMediaTypeSeeder` (idempotent, won't
overwrite admin edits):

| Type            | Extension | Bit/Color Depth | Sample/Frame Rate | Channels/Resolution | Playback Model                                       | Capture Interface          | Signal Interface     | Ingest Software           | Format/Codec                          |
| --------------- | --------- | --------------- | ----------------- | ------------------- | ---------------------------------------------------- | -------------------------- | -------------------- | ------------------------- | ------------------------------------- |
| Audio Cassette  | wav       | 24              | 48000             | Stereo              | Pioneer Stereo Double Cassette Deck CT-W2O8R         | MOTO 896mk3 hybrid         | RCA                  | Adobe Audition            | PCM                                   |
| Reel            | wav       | 24              | 48000             | Stereo              | AKAI X-201D                                          | MOTO 896mk3 hybrid         | RCA                  | Adobe Audition            | PCM                                   |
| Vinyl Record    | wav       | 24              | 48000             | Stereo              | Audio-Technica AT-LP60                               | MOTO 896mk3 hybrid         | RCA                  | Adobe Audition            | PCM                                   |
| VHS Cassette    | avi       | 8               | 25                | 720X576             | Sony DVD Player / Video Cassette Recorder SLV-D985P ME | Blackmagic Intensity Pro 4K | Composite           | Blackmagic Media Express  | Uncompressed avi 8-bit YUV, 625i50 PAL |
| MiniDV          | avi       | 8               | 25                | 720x576             | Sony HVR M10                                         | FireWire 400               | FireWire IEEE 1394   | Adobe Premiere            | DV(Native)                            |
| CD/DVD          | —         | —               | —                 | —                   | —                                                    | —                          | —                    | —                         | —                                     |

(CD/DVD is intentionally blank — the team hasn't standardised the
optical-ingest chain yet; admins fill it in when they do.)

### Catalog REST surface — `/api/physical-media/types`

| Method  | Path        | Auth                            | Notes                                                                 |
| ------- | ----------- | ------------------------------- | --------------------------------------------------------------------- |
| `GET`   | `/`         | `physical_media:read`           | Returns the full catalog, sorted by name. The frontend caches it.    |
| `GET`   | `/{id}`     | `physical_media:read`           | One catalog row.                                                      |
| `POST`  | `/`         | `physical_media:type_manage`    | Create a new type. JSON body — see `PhysicalMediaTypeCreateRequestDTO`. Rejects on duplicate name. |
| `PATCH` | `/{id}`     | `physical_media:type_manage`    | Edit name, description, or any of the nine defaults. PATCH semantics. |
| `DELETE`| `/{id}`     | `physical_media:type_manage`    | Refused with 400 if any `physical_media` row still references it.    |

### Validation

- **Manual create** (`POST /api/physical-media`) is strict: if
  `physicalMediaType` is not in the catalog, the request fails with
  `physicalMediaType: not in the type catalog — add it via /api/physical-media/types first`.
  This catches typos before they pollute the inventory.
- **Excel import** is lenient: an unknown type encountered during
  ingest is auto-created in the catalog with blank defaults, then the
  row is upserted. Admins can fill in the technical defaults later.

### Frontend: dropdown + autofill + "+ Add type"

1. On the create / edit form, GET `/api/physical-media/types` once and
   cache the response in the page state.
2. Render the type field as a dropdown bound to that list. Show the
   `name` to the user; pass the `name` (not the id) into
   `PhysicalMediaCreateRequestDTO.physicalMediaType`.
3. **On selection**, copy the catalog row's nine technical defaults
   into the corresponding form inputs:
   `extension`, `bitOrColorDepth`, `sampleOrFrameRate`,
   `channelsOrResolution`, `playbackModel`, `captureInterface`,
   `signalInterface`, `ingestSoftware`, `formatCodec`. The user can
   override any of them before submitting — the autofill is a starting
   point, not a lock.
4. Show a "+ Add new type" entry at the bottom of the dropdown. The
   user clicks it → small modal with the same 10 fields (name +
   nine defaults) → POST `/api/physical-media/types` → on 200, refresh
   the cached list and pre-select the new type. Gate the modal on
   `physical_media:type_manage` so non-admins don't see the button.
5. Re-selecting a different type re-autofills; the form should ask "the
   technical fields will be overwritten — continue?" only if the user
   already typed in custom values for them.

### Editing the catalog later

If an admin updates a type's defaults (say, the team buys a new
turntable), **existing physical-media rows are not touched** — they
keep whatever values were stamped at creation. Only future records
benefit from the new defaults. This is intentional: historical data
should reflect the equipment used when the digitisation was actually
performed.

---

## 2.5 Per-type Number counter (`inventoryNumber`)

The `Number` column on the sheet is **a contiguous 1..N counter scoped
per `Physical Media Type`** — not a global inventory number. Sheet1
breakdown today:

| Physical Media Type | Count | Number range |
| ------------------- | ----- | ------------ |
| Audio Cassette      | 1893  | 1..1893      |
| CD/DVD              | 1743  | 1..1743      |
| Reel                | 382   | 1..382       |
| Vinyl Record        | 353   | 1..353       |
| VHS Cassette        | 55    | 1..55        |
| MiniDV              | 1     | 1            |

Semantics enforced by the service:

- **Import path**: if the sheet row carries `Number`, the importer
  preserves it verbatim — round-trips the spreadsheet exactly.
- **Create path** (`POST /api/physical-media`) **and import rows missing
  `Number`**: the service mints
  `max(Number where physical_media_type = X) + 1` for that media type
  and assigns it. The 56th VHS cassette uploaded after today gets
  `Number = 56`; the 1894th Audio Cassette gets `Number = 1894`.
- **Concurrency**: serialised on a per-type advisory lock
  (`physical-media-inv-num:<type>`) so two employees adding two new VHS
  rows at the same time don't collide on the same `Number`. Different
  types stay parallel.
- **Trash**: `MAX(Number)` looks at every row regardless of trash state,
  so the counter is monotonic — restoring a trashed `VHS:#55` won't
  collide with the `VHS:#56` minted while it was trashed.

`Number` is **not** unique at the DB level (the same value can appear
across different types), and the importer doesn't dedupe on it.
Dedupe still uses `(physical_media_type, physical_label)`.

---

## 3. Encoded fields

### `digitization` — `DigitizationStatus`

| Excel value | Enum constant     | Meaning                  |
| ----------- | ----------------- | ------------------------ |
| `0`         | `NOT_DIGITIZED`   | physical-only, never ingested |
| `1`         | `DIGITIZED`       | captured once into the archive |
| `2`         | `DUPLICATED`      | captured more than once (intentional re-ingest) |
| _blank_     | `null`            | unknown                  |

Stored as a Postgres enum string (`@Enumerated(EnumType.STRING)`) and
indexed. The DB CHECK constraint is resynced at boot by
`PhysicalMediaDigitizationConstraintInitializer`.

### `needToClear` — boolean

| Excel value | Java value | Meaning                  |
| ----------- | ---------- | ------------------------ |
| `0`         | `false`    | no cleaning needed       |
| `1`         | `true`     | needs cleaning           |
| _blank_     | `null`     | unknown                  |

No `2` in the sheet — bolean is the right type.

Both DTOs accept either the typed value (enum / boolean) **or** the
raw integer code from the sheet (`digitizationCode`, `needToClearCode`)
so clients pasting straight from a row do not need to translate.

---

## 4. Permissions

Six new permissions in `Permission`:

| Permission                       | Granted to                                | Used by                            |
| -------------------------------- | ----------------------------------------- | ---------------------------------- |
| `physical_media:read`            | ADMIN + EMPLOYEE (seeded)                 | list / get / search                |
| `physical_media:create`          | ADMIN + EMPLOYEE (seeded)                 | `POST /api/physical-media`         |
| `physical_media:update`          | ADMIN + EMPLOYEE (seeded)                 | `PATCH /api/physical-media/{code}` |
| `physical_media:import`          | ADMIN + EMPLOYEE (seeded)                 | `POST /api/physical-media/import`  |
| `physical_media:remove`          | ADMIN only                                | `DELETE /api/physical-media/{code}` |
| `physical_media:delete`          | ADMIN only                                | trash / restore / purge            |

`EmployeePhysicalMediaPermissionBackfillInitializer` runs at boot and
inserts the four EMPLOYEE-default rows into `user_permissions` for every
existing EMPLOYEE — so the next deploy lights up the inventory for the
whole team without needing per-user grants. Re-runs are no-ops (the
unique constraint catches duplicates via `ON CONFLICT DO NOTHING`).

---

## 5. REST surface

### `PhysicalMediaAPI` — `/api/physical-media`

| Method  | Path                       | Auth                          | Body / params                                  |
| ------- | -------------------------- | ----------------------------- | ---------------------------------------------- |
| `GET`   | `/`                        | `physical_media:read`         | `?page=&size=&sort=` (Spring Pageable, default size 50) |
| `GET`   | `/search`                  | `physical_media:read`         | `?q=&limit=` (limit clamped to 1–100, default 20) |
| `GET`   | `/{pmCode}`                | `physical_media:read`         | —                                              |
| `GET`   | `/next-number?type=<type>` | `physical_media:read`         | Returns `{ physicalMediaType, nextInventoryNumber }`. Drives the create-form hint so the user sees the auto-assigned `Number` before they submit. Best-effort preview; the actual create re-mints under a lock. |
| `POST`  | `/`                        | `physical_media:create`       | `PhysicalMediaCreateRequestDTO` (JSON)         |
| `PATCH` | `/{pmCode}`                | `physical_media:update`       | `PhysicalMediaUpdateRequestDTO` (JSON, PATCH semantics) |
| `DELETE`| `/{pmCode}`                | `physical_media:remove`       | soft-trash                                     |
| `POST`  | `/import`                  | `physical_media:import`       | `multipart/form-data` — `file` (.xlsx), optional `sheet` name |
| `POST`  | `/import/sheets`           | `physical_media:import`       | `multipart/form-data` — `file` (.xlsx). Returns `List<String>` of sheet names so the UI can render a dropdown before kicking off the import. No DB writes. |

### `AdminPhysicalMediaAPI` — `/api/admin/physical-media`

| Method  | Path                        | Auth                          |
| ------- | --------------------------- | ----------------------------- |
| `GET`   | `/trash`                    | `physical_media:delete`       |
| `POST`  | `/{pmCode}/restore`         | `physical_media:delete`       |
| `DELETE`| `/{pmCode}/purge`           | `physical_media:delete`       |

---

## 6. Excel import contract

`POST /api/physical-media/import` — `multipart/form-data`, `file` part
must be a `.xlsx`. Optional `sheet` query param to pick a non-default
sheet.

**Header resolution** — row 1 of the chosen sheet is scanned and each
column is matched against the canonical English + Kurdish header text
(whitespace + zero-width-space normalised, case-insensitive). Both
language variants are accepted simultaneously, so a sheet exported as
English-only or as the original bilingual headers both import.

**No dedupe** — every sheet row becomes a new `physical_media` record
with its own `pmCode`. Two artefacts that happen to share
`(physical_media_type, physical_label)` are still two distinct
artefacts; merging them would silently lose inventory. Re-running the
same workbook therefore creates duplicates — the import is "append
every row," not "sync the sheet to the DB." Cleanup of accidental
re-imports is via soft-trash + purge from the admin trash list.

**Cell coercion** — maximally lenient. Anything that can't be parsed
becomes `null` instead of failing the row:
- Integer columns: numeric cells → int; text like `"60"` → 60; `"60.0"` → 60; junk → `null`.
- `digitizeDate`: date-formatted cell → `LocalDate`; ISO-8601 text → parsed; anything else → `null`.
- `digitization`: 0 / 1 / 2 → `DigitizationStatus` enum; blank or anything else → `null` (no row failure).
- `needToClear`: 0 → false; 1 → true; blank or anything else → `null`.

**Leniency contract** — the importer **never refuses a row for missing
or bad data**. Staff want every artefact in the DB so they can patch
it up afterwards:
- Rows missing media type, title, AND physical label still go in — they
  surface in the list as a near-empty record with the row's other data.
- A row whose original DTO trips a persistence error is retried with a
  stripped-down DTO (the four encoded/temporal fields removed). The
  archive note is annotated with `[Imported with stripped fields …]`
  so staff can find and fix it. These rows land as inserted but get an
  informational entry in `errors[]`.
- The only rows actually skipped are physically empty rows (every data
  cell blank — usually visual separators). They do not appear in
  `errors[]` either.
- Unknown `physicalMediaType` values still auto-create a blank-defaults
  catalog entry — the row goes in either way.

**Response — `PhysicalMediaImportReportDTO`**:

```json
{
  "sheetName": "Sheet1",
  "matchedHeaders": ["No.", "Number", "Physical Media Type", "..."],
  "unknownHeaders": [],
  "totalDataRows": 4427,
  "inserted": 4427,
  "skipped": 0,
  "errors": [],
  "finishedAt": "2026-06-03T11:24:35Z"
}
```

`errors[]` is a list of `{rowNumber, message}`. With the importer's
maximum-leniency contract, `skipped` should be 0 for any reasonable
sheet — entries in `errors[]` with `skipped == 0` are informational
"saved with stripped fields" notes; entries with `skipped > 0` mean
even the stripped-fallback retry failed.

**One IMPORT audit row** is written per upload via
`physical_media_audit_logs.action = 'IMPORT'`, with
`details = "inserted=X updated=Y skipped=Z"`. Per-row audit is deliberately
skipped to avoid swamping the audit table with 4000+ entries per import.

---

## 7. Frontend integration guide

### Permissions

- Show the "Physical media" nav entry when the user has `physical_media:read`.
- Show the "+ New" button when the user has `physical_media:create`.
- Show the "Import Excel" button when the user has `physical_media:import`.
- Show the trash bin + restore / purge controls when the user has `physical_media:delete`.

### Listing page

`GET /api/physical-media` returns a Spring `Page<PhysicalMediaResponseDTO>`
— use the standard `content`, `number`, `size`, `totalElements`,
`totalPages` envelope.

**Sort defaults to `id ASC`** — rows render top-to-bottom in insertion
order so the table reads "1, 2, 3, …" the way the Excel sheet does.
Override with `?sort=createdAt,desc` for a "newest first" view, or
`?sort=physicalMediaType,asc&sort=inventoryNumber,asc` for a
grouped-by-type listing.

The DTO has both the typed (`digitization: "DIGITIZED"`) and numeric
(`digitizationCode: 1`) forms — render the typed one for humans and
use the numeric one for filter URL state if you want stable links.

### Create form

POST `application/json` matching `PhysicalMediaCreateRequestDTO`. The
DTO declares `@JsonAlias` for every Kurdish/English Excel header, so a
quick "paste a row" UI can send the literal `{ "Physical Media Type":
"Audio Cassette", ... }` payload and it'll deserialise correctly.

Required fields enforced server-side:
- `physicalMediaType` (or you'll get `PHYSICAL_MEDIA_VALIDATION_ERROR`).
- `title` **or** `physicalLabel` (at least one).

**`Number` autofill** — `inventoryNumber` is **never required**: leave
it blank and the server assigns the next available value for that
media type (VHS Cassette #56 if 55 already exist). To give the user
visibility before they submit, hit `GET /api/physical-media/next-number?type=<chosen>`
when they pick a type and display the response value as a placeholder
or pre-fill in the `Number` input. The user can still type a custom
value to override; if they leave it blank, the server picks the same
number (concurrency-safe via per-type advisory lock).

### Update form (PATCH)

Send only the fields the user actually changed. `null` means "leave
alone" — that avoids the classic "save wiped my notes" bug. If you
need to clear a field, send the empty string (`""`) — the mapper's
`trimOrNull` converts it to `null` server-side.

### Excel upload UI

1. File picker that accepts `.xlsx` only (`accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"`).
2. Optional dropdown to pick a sheet (after a first parse, or
   pre-populate from the workbook the user just downloaded). Default:
   first sheet.
3. `POST /api/physical-media/import` as `multipart/form-data`.
4. Render the returned report: a "Summary" card (`inserted / updated /
   skipped`), a "Matched columns" chip list (`matchedHeaders`), a
   "Unknown columns — these were ignored" chip list
   (`unknownHeaders`), and a collapsible "Skipped rows" table built
   from `errors[]`.

### Search

`GET /api/physical-media/search?q=…&limit=…` returns a plain
`List<PhysicalMediaResponseDTO>` (not paged). Use it for the global
search box and for "find while you type" autocompletes — cap `limit`
to ~20 client-side.

### Error shape

The platform's `ApiExceptionHandler` returns the standard
`ApiErrorResponse`:

```json
{
  "timestamp": "...",
  "path": "/api/physical-media",
  "code": "PHYSICAL_MEDIA_VALIDATION_ERROR",
  "message": "Validation failed",
  "details": { "physicalMediaType": "must not be blank" }
}
```

Codes you may see: `PHYSICAL_MEDIA_VALIDATION_ERROR` (400),
`PHYSICAL_MEDIA_NOT_FOUND` (404), `CONFLICT` (409 — uniqueness or
optimistic locking).

---

## 8. Files added / changed

**Added**

- `platform/enums/DigitizationStatus.java`
- `platform/enums/PhysicalMediaAuditAction.java`
- `platform/model/physicalmedia/PhysicalMedia.java`
- `platform/model/physicalmedia/PhysicalMediaAuditLog.java`
- `platform/repo/physicalmedia/PhysicalMediaRepository.java`
- `platform/repo/physicalmedia/PhysicalMediaAuditLogRepository.java`
- `platform/dto/physicalmedia/PhysicalMediaCreateRequestDTO.java`
- `platform/dto/physicalmedia/PhysicalMediaUpdateRequestDTO.java`
- `platform/dto/physicalmedia/PhysicalMediaResponseDTO.java`
- `platform/dto/physicalmedia/PhysicalMediaImportReportDTO.java`
- `platform/exceptions/PhysicalMediaNotFoundException.java`
- `platform/exceptions/PhysicalMediaValidationException.java`
- `platform/service/physicalmedia/PhysicalMediaMapper.java`
- `platform/service/physicalmedia/PhysicalMediaAuditService.java`
- `platform/service/physicalmedia/PhysicalMediaService.java`
- `platform/service/physicalmedia/PhysicalMediaExcelImportService.java`
- `platform/api/physicalmedia/PhysicalMediaAPI.java`
- `platform/api/physicalmedia/AdminPhysicalMediaAPI.java`
- `platform/config/PhysicalMediaAuditActionConstraintInitializer.java`
- `platform/config/PhysicalMediaDigitizationConstraintInitializer.java`
- `user/configs/EmployeePhysicalMediaPermissionBackfillInitializer.java`

**Changed**

- `pom.xml` — added Apache POI `poi` + `poi-ooxml` 5.3.0.
- `user/enums/Permission.java` — six new `PHYSICAL_MEDIA_*` constants.
- `user/enums/Role.java` — four of them added to
  `EMPLOYEE_DEFAULT_PERMISSIONS`.
- `platform/service/analytics/AnalyticsService.java` — added a
  `physical_media_audit_logs` branch to the `ALL_LOGS_CTE` UNION.
- `platform/config/AuditLogIndexInitializer.java` — added
  `physical_media_audit_logs` to the index-init loop.
- `platform/exceptions/ApiExceptionHandler.java` — registered handlers
  for the two new exception types.

---

## 9. Operational notes

- **Boot order**: Hibernate auto-creates `physical_media` and
  `physical_media_audit_logs` on first start (under `ddl-auto=update`).
  The three boot initializers
  (`PhysicalMediaDigitizationConstraintInitializer`,
  `PhysicalMediaAuditActionConstraintInitializer`,
  `EmployeePhysicalMediaPermissionBackfillInitializer`) all fire on
  `ApplicationReadyEvent` so they run after Hibernate finishes its
  schema work.
- **Large imports**: the source sheet has ~4400 rows. Each row goes
  through one INSERT or UPDATE under one transaction — measured at
  ~10s on a local Postgres. If you bump this past tens of thousands
  of rows, switch the importer to batch flushes (`@PersistenceContext
  EntityManager em.flush()/em.clear()` every 200 rows).
- **Sheet variants**: the source workbook has three sheets. The
  importer defaults to sheet 1 but accepts `?sheet=Sheet2` etc.
- **Re-imports** are safe: dedupe is keyed on `(media type, physical
  label)`, so re-running the same workbook updates existing rows in
  place. Use this to ship spreadsheet edits — change a row, re-upload.
