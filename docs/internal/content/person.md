# Person API

> **Audience:** Staff (back-office) · **Base path:** `/api/person` · **Source:** `src/main/java/ak/dev/khi_archive_platform/platform/api/person/PersonAPI.java`

The person register: the archive's authority list of the people that projects and media are
attributed to. Each record carries names (full / nickname / romanized), classification
(`gender`, `personType`, `region`), fuzzy-precision birth and death dates, a portrait image, and
discovery fields (`tag`, `keywords`). Deletion is soft (trash) and cascades to every project
linked to the person.

## Access

| Requirement | Value |
|---|---|
| Authentication | required (JWT in the `Authorization: Bearer` header, read first, or the `khi_auth_token` HttpOnly cookie) |
| Authority | per-method — `person:read`, `person:create`, `person:update`, `person:delete` |
| Roles that hold `person:read` / `person:create` / `person:update` by default | ADMIN (role), EMPLOYEE (seeded per-user grants) |
| Roles that hold `person:delete` by default | ADMIN only |

`@PreAuthorize` is declared **on each handler method**, not on the class — there is no
class-level authority. Every endpoint below repeats its own authority.

`person:remove` exists in `user/enums/Permission.java` but no Person endpoint uses it. Trash,
restore, trash-listing and purge are all gated on `person:delete`. Restore, trash-listing and
purge additionally re-check `person:delete` inside `PersonService` (`requireAdminRole`), so a
caller that somehow passes the annotation still gets a 403; the soft-delete handler
(`DELETE /api/person/{personCode}`) relies on the annotation alone.

Two failures are produced by the security filter chain rather than by the platform exception
handler, and can therefore occur on any endpoint in this file:

| Status | `error` code | When |
|---|---|---|
| `401` | `TOKEN_MISSING` | No `Authorization` header and no cookie on the request |
| `401` | `AUTHENTICATION_FAILED` | Token present but rejected (expired, revoked, malformed) |

They are not repeated in the per-endpoint tables.

## Endpoints

| Method | Path | Authority | Purpose |
|---|---|---|---|
| `GET` | `/api/person` | `person:read` | Paged list of active persons with filters and sort |
| `GET` | `/api/person/search` | `person:read` | Typo-tolerant fuzzy search (pg_trgm) |
| `GET` | `/api/person/{personCode}` | `person:read` | Fetch one active person by code |
| `POST` | `/api/person` | `person:create` | Create a person (multipart, portrait required) |
| `PATCH` | `/api/person/{personCode}` | `person:update` | Partial update (multipart, portrait optional) |
| `DELETE` | `/api/person/{personCode}` | `person:delete` | Soft-delete to trash, cascades to linked projects |
| `POST` | `/api/person/{personCode}/restore` | `person:delete` | Restore from trash, cascades to trashed projects |
| `GET` | `/api/person/trash` | `person:delete` | Paged list of trashed persons |
| `DELETE` | `/api/person/{personCode}/purge` | `person:delete` | Permanent delete, removes the portrait from S3 |

## Enums

### `Gender`

Source: `platform/enums/Gender.java`.

| Value | Meaning |
|---|---|
| `MALE` | Male |
| `FEMALE` | Female |

There is no third value and no "unknown" member — leave `gender` out of the payload when it is
not known, and the field is then omitted from the response.

### `DatePrecision`

Source: `platform/enums/DatePrecision.java`. The archive stores a real `LocalDate` for both
birth and death plus a precision marker, so a "born 1943, month unknown" record is not
mistaken for "born 1 January 1943".

| Value | Set when the request supplies | Stored `dateOfBirth` / `dateOfDeath` |
|---|---|---|
| `YEAR_ONLY` | year only | `year-01-01` |
| `MONTH_ONLY` | year + month | first day of that month |
| `FULL` | year + month + day | exactly that date |

Clients never send `dateOfBirthPrecision` / `dateOfDeathPrecision`; the server derives them from
which of the `…Year` / `…Month` / `…Day` parts were supplied. Supplying a day without a month
is rejected, and supplying a month or day without a year is rejected — see the create/update
error tables.

## Portrait image — upload and serving

- **Upload part name:** `mediaPortrait`, sent as a `multipart/form-data` file part alongside the
  JSON `data` part. Required on `POST /api/person`, optional on `PATCH /api/person/{personCode}`.
- **Where it goes:** `S3Service.uploadPersonPortrait` writes the bytes to the configured bucket
  under `${aws.s3.base-folder}/${aws.s3.person-folder}/{personCode}/{uuid}-{sanitized-filename}`
  (`base-folder` defaults to `khi-archive-platform-folders`, `person-folder` defaults to
  `persons`).
- **What is stored and returned:** the resulting public object URL
  (`https://{bucket}.s3.{region}.amazonaws.com/{key}`) is persisted in `person.media_portrait`
  and returned verbatim as the `mediaPortrait` field of `PersonResponseDTO`.
- **Serving:** there is no proxy or stream endpoint for person portraits in `PersonAPI` — unlike
  audio/video/image/text bytes, the portrait is consumed by the client directly from the URL in
  the response. Nothing in this controller serves the image bytes.
- **Replacement:** on `PATCH`, uploading a new `mediaPortrait` deletes the previous object from
  S3 once the new key differs. Sending `"removeMediaPortrait": true` in the `data` part deletes
  the object and sets the column to `null`; when that flag is true any uploaded file part is
  ignored.
- **Deletion:** soft-delete (trash) deliberately keeps the portrait on S3 so a restore is
  lossless. Only `DELETE /api/person/{personCode}/purge` removes it. In every case the delete
  only fires when `S3Service.isOurS3Url` recognizes the URL as belonging to the configured
  bucket, so externally-hosted portrait URLs (e.g. seeded ones) are left alone.
- **Size limits:** `spring.servlet.multipart.max-file-size=5GB`,
  `max-request-size=6GB`. Exceeding them yields `413 UPLOAD_TOO_LARGE`.

## Response object — `PersonResponseDTO`

Source: `platform/dto/person/PersonResponseDTO.java`, built by `PersonMapper.toResponse`.
`spring.jackson.default-property-inclusion=non_null`, so any field that is null is **omitted**
from the JSON.

| Field | Type | Notes |
|---|---|---|
| `id` | number | Database primary key |
| `personCode` | string | Business key, max 50 chars. `person.person_code` is declared `unique = true`, so the index counts trashed rows as well as active ones |
| `mediaPortrait` | string | Public S3 URL of the portrait; omitted when there is none |
| `fullName` | string | Always present (non-null column) |
| `nickname` | string | |
| `romanizedName` | string | Latin-script rendering of the name |
| `gender` | string | `MALE` or `FEMALE` |
| `personType` | string[] | Always present — the mapper emits `[]` rather than null |
| `region` | string | |
| `dateOfBirth` | string (`yyyy-MM-dd`) | |
| `dateOfBirthPrecision` | string | `FULL` / `MONTH_ONLY` / `YEAR_ONLY` |
| `placeOfBirth` | string | |
| `dateOfDeath` | string (`yyyy-MM-dd`) | |
| `dateOfDeathPrecision` | string | `FULL` / `MONTH_ONLY` / `YEAR_ONLY` |
| `placeOfDeath` | string | |
| `description` | string | |
| `tag` | string[] | Stored as one comma-joined column, split back into a list; always present, `[]` when empty |
| `keywords` | string[] | Same storage and splitting rule as `tag`; always present |
| `note` | string | Internal note |
| `createdAt` | timestamp | |
| `updatedAt` | timestamp | |
| `removedAt` | timestamp | Set only for trashed records; omitted for active ones |
| `createdBy` | string | Actor username |
| `updatedBy` | string | Actor username |
| `removedBy` | string | Actor username; omitted for active records |

Timestamps serialize in `Asia/Baghdad` (`spring.jackson.time-zone`).

---

### `GET /api/person`

Paged list of every **active** person, with optional filtering and sorting.

**Authority:** `person:read`

**Query parameters**

Paging comes from Spring's `Pageable` (`@PageableDefault(size = 100)`). Every other value is
declared as its own `@RequestParam` on the handler method and then assembled into a
`PersonFilterParams` record (`platform/dto/person/PersonFilterParams.java`) before it reaches the
service — the table below lists every component of that record.

| Name | Type | Default | Description |
|---|---|---|---|
| `page` | int | `0` | Zero-based page index |
| `size` | int | `100` | Page size, capped at Spring Data's `max-page-size` (`2000`) |
| `sortBy` | string | — | `fullName` \| `createdAt` \| `updatedAt` \| `dateOfBirth` \| `dateOfDeath`. Synonyms accepted, see below. Unrecognized values leave the order untouched |
| `sortDirection` | string | `asc` | `asc` \| `desc`; anything other than `desc` (case-insensitive) is treated as ascending |
| `gender` | enum | — | Exact match: `MALE` or `FEMALE` |
| `personType` | string (repeatable) | — | Repeat the parameter or comma-separate. Matched case-insensitively against the person's `personType` list |
| `personTypeMatch` | string | `any` | `any` — at least one value matches; `all` — every value must match |
| `region` | string | — | Case-insensitive "contains" match |
| `dobFrom` | date (`yyyy-MM-dd`) | — | Inclusive lower bound on `dateOfBirth` |
| `dobTo` | date (`yyyy-MM-dd`) | — | Inclusive upper bound on `dateOfBirth` |
| `dodFrom` | date (`yyyy-MM-dd`) | — | Inclusive lower bound on `dateOfDeath` |
| `dodTo` | date (`yyyy-MM-dd`) | — | Inclusive upper bound on `dateOfDeath` |
| `placeOfBirth` | string | — | Case-insensitive "contains" match |
| `placeOfDeath` | string | — | Case-insensitive "contains" match |
| `tags` | string (repeatable) | — | Repeat or comma-separate; matched against the person's `tag` list |
| `tagMatch` | string | `any` | `any` \| `all` |
| `keywords` | string (repeatable) | — | Repeat or comma-separate; matched against the person's `keywords` list |
| `keywordMatch` | string | `any` | `any` \| `all` |
| `createdFrom` | date (`yyyy-MM-dd`) | — | Inclusive lower bound on `createdAt`, resolved to the start of that day in `Asia/Baghdad` |
| `createdTo` | date (`yyyy-MM-dd`) | — | Inclusive upper bound on `createdAt`, resolved to the end of that day in `Asia/Baghdad` |
| `updatedFrom` | date (`yyyy-MM-dd`) | — | Inclusive lower bound on `updatedAt`, same day-bound rule |
| `updatedTo` | date (`yyyy-MM-dd`) | — | Inclusive upper bound on `updatedAt`, same day-bound rule |

Accepted `sortBy` synonyms (case-insensitive):

| Canonical | Also accepted |
|---|---|
| `fullName` | `name`, `alpha`, `alphabet`, `alphabetical` |
| `createdAt` | `created`, `added`, `dateAdded`, `date_added` |
| `updatedAt` | `updated`, `modified`, `dateModified`, `date_modified` |
| `dateOfBirth` | `dob`, `birth`, `date_of_birth` |
| `dateOfDeath` | `dod`, `death`, `date_of_death` |

Notes on filter semantics:

- Range filters exclude records whose value is null. A person with no `dateOfDeath` never
  appears in a `dodFrom`/`dodTo` result.
- List filters (`personType`, `tags`, `keywords`) exclude records whose list is empty.
- String "contains" filters (`region`, `placeOfBirth`, `placeOfDeath`) canonicalize both sides
  through `KurdishText.normalize` before comparing, so Arabic/Kurdish Yeh and Kaf variants,
  tashkeel, ZWNJ and repeated whitespace do not cause false misses.
- Spring's own `sort` request parameter is bound into the `Pageable` but not used — the page is
  sliced from an in-memory list by offset and size only. Use `sortBy` / `sortDirection`.
- Unparseable `page` / `size` values are absorbed by Spring's `Pageable` resolver and fall back to
  the defaults rather than producing an error.

**Response** `200 OK` — standard Spring `Page` envelope (see [`../01-conventions.md`](../01-conventions.md))
with `content[]` elements shaped as `PersonResponseDTO` above.

```json
{
  "content": [
    {
      "id": 12,
      "personCode": "HZI",
      "mediaPortrait": "https://khi-archive.s3.eu-central-1.amazonaws.com/khi-archive-platform-folders/persons/HZI/6f1c2b7e-9a30-4c11-bb02-2f5d7c0a1e44-hazhar.jpg",
      "fullName": "عەبدولڕەحمان شەرەفکەندی",
      "nickname": "هەژار",
      "romanizedName": "Hazhar Mukriyani",
      "gender": "MALE",
      "personType": ["poet", "translator"],
      "region": "Mukriyan",
      "dateOfBirth": "1921-01-01",
      "dateOfBirthPrecision": "YEAR_ONLY",
      "placeOfBirth": "Mahabad",
      "dateOfDeath": "1991-02-22",
      "dateOfDeathPrecision": "FULL",
      "placeOfDeath": "Karaj",
      "description": "Kurdish poet, lexicographer and translator.",
      "tag": ["poetry", "lexicography"],
      "keywords": ["hazhar", "mukriyani"],
      "createdAt": "2026-03-04 11:12:08",
      "updatedAt": "2026-06-18 09:41:55",
      "createdBy": "aram",
      "updatedBy": "shilan"
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
| `400` | `TYPE_MISMATCH` | `gender` is not `MALE`/`FEMALE`, or a date parameter is not `yyyy-MM-dd` |
| `403` | `ACCESS_DENIED` | Caller lacks `person:read` |
| `500` | `DATABASE_ERROR` | The cache was cold and the backing query failed |
| `504` | `TIMEOUT` | The backing query timed out |

**Example**

```bash
curl -s "{{BASE_URL}}/api/person?page=0&size=20&sortBy=fullName&sortDirection=asc" \
  -H "Cookie: khi_auth_token=$TOKEN"

curl -s "{{BASE_URL}}/api/person?gender=MALE&personType=poet&personType=translator&personTypeMatch=all&dobFrom=1900-01-01&dobTo=1950-12-31&sortBy=dob&sortDirection=desc" \
  -H "Cookie: khi_auth_token=$TOKEN"
```

**Notes**

- Served from the Caffeine cache `persons:all` (one entry holding the full active list, TTL 10
  minutes, configured in `platform/config/CacheConfig.java`). Filtering, sorting and paging all
  run in memory over that list. Create, update, delete, restore and purge each evict it.
- With no filter parameters at all the cached list is passed straight through — the fast path.
- Writes one `LIST` row to `person_audit_logs` on every call, including the applied filters, no
  matter whether the cache was hit.

---

### `GET /api/person/search`

Typo-tolerant fuzzy search across the person register.

**Authority:** `person:read`

**Query parameters**

| Name | Type | Default | Description |
|---|---|---|---|
| `q` | string | — | **Required.** Search text. Trimmed before use; a blank value returns `[]` without querying |
| `limit` | int | `20` | Maximum hits. Values `<= 0` fall back to `20`; values above `100` are clamped to `100` |

The query matches active persons only (`removed_at IS NULL`). It unions a case-insensitive
substring match over `full_name`, `nickname`, `romanized_name`, `description`, `tag`,
`keywords`, `region`, `place_of_birth`, `place_of_death`, `person_code` and the person's
`person_type` values, with a PostgreSQL `pg_trgm` similarity match (threshold `0.3`) over
`full_name`, `nickname` and `romanized_name`. Results are ordered by the best similarity score
among those three name fields, then by `full_name` ascending.

**Response** `200 OK` — a plain JSON array of `PersonResponseDTO`, **not** a `Page`.

```json
[
  {
    "id": 12,
    "personCode": "HZI",
    "fullName": "عەبدولڕەحمان شەرەفکەندی",
    "romanizedName": "Hazhar Mukriyani",
    "gender": "MALE",
    "personType": ["poet"],
    "tag": [],
    "keywords": ["hazhar"],
    "createdAt": "2026-03-04 11:12:08",
    "updatedAt": "2026-06-18 09:41:55",
    "createdBy": "aram",
    "updatedBy": "shilan"
  }
]
```

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `MISSING_PARAMETER` | `q` was not supplied at all |
| `400` | `TYPE_MISMATCH` | `limit` is not an integer |
| `403` | `ACCESS_DENIED` | Caller lacks `person:read` |
| `500` | `DATABASE_ERROR` | Search query failed (for example the `pg_trgm` extension is not installed) |
| `504` | `TIMEOUT` | Search query timed out |

**Example**

```bash
curl -s "{{BASE_URL}}/api/person/search?q=hazar&limit=10" \
  -H "Cookie: khi_auth_token=$TOKEN"
```

**Notes**

- Always hits the database — the `persons:all` cache does not front this endpoint.
- Writes one `SEARCH` row to `person_audit_logs` with the normalized query, effective limit and
  hit count.

---

### `GET /api/person/{personCode}`

Fetch a single active person by business code.

**Authority:** `person:read`

**Path parameters**

| Name | Type | Description |
|---|---|---|
| `personCode` | string | Business key. Trimmed before lookup. Trashed records are not found here |

**Response** `200 OK` — a single `PersonResponseDTO` (same shape as the `content[]` element
above).

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `BAD_REQUEST` | `personCode` is blank after trimming |
| `403` | `ACCESS_DENIED` | Caller lacks `person:read` |
| `404` | `PERSON_NOT_FOUND` | No active person with that code (it may be in trash) |

**Example**

```bash
curl -s "{{BASE_URL}}/api/person/HZI" \
  -H "Cookie: khi_auth_token=$TOKEN"
```

**Notes** — writes one `READ` row to `person_audit_logs`.

---

### `POST /api/person`

Create a person record. `multipart/form-data` only; produces `application/json`.

**Authority:** `person:create`

**Request parts**

| Part | Type | Required | Description |
|---|---|---|---|
| `data` | JSON string | yes | `PersonCreateRequestDTO`, parsed and bean-validated by the controller |
| `mediaPortrait` | file | yes | Portrait image. The part is declared without `required = false`, so it must be present |

**Request body** — the `data` part (`platform/dto/person/PersonCreateRequestDTO.java`)

| Field | Type | Required | Constraints |
|---|---|---|---|
| `personCode` | string | yes | Not blank, max 50 chars, matches `^[A-Za-z0-9_-]+$` |
| `fullName` | string | yes | Not blank |
| `nickname` | string | no | |
| `romanizedName` | string | no | |
| `gender` | enum | no | `MALE` \| `FEMALE` |
| `personType` | string[] | no | Stored as a separate `person_person_type` collection table |
| `region` | string | no | |
| `dateOfBirthYear` | int | no | Required if `dateOfBirthMonth` or `dateOfBirthDay` is given |
| `dateOfBirthMonth` | int | no | Required if `dateOfBirthDay` is given |
| `dateOfBirthDay` | int | no | |
| `placeOfBirth` | string | no | |
| `dateOfDeathYear` | int | no | Required if `dateOfDeathMonth` or `dateOfDeathDay` is given |
| `dateOfDeathMonth` | int | no | Required if `dateOfDeathDay` is given |
| `dateOfDeathDay` | int | no | |
| `placeOfDeath` | string | no | |
| `description` | string | no | |
| `tag` | string[] | no | Blank entries dropped, remaining values trimmed and comma-joined into one column. An empty or all-blank list stores `null` |
| `keywords` | string[] | no | Same normalization as `tag` |
| `note` | string | no | |

```json
{
  "personCode": "HZI",
  "fullName": "عەبدولڕەحمان شەرەفکەندی",
  "nickname": "هەژار",
  "romanizedName": "Hazhar Mukriyani",
  "gender": "MALE",
  "personType": ["poet", "translator"],
  "region": "Mukriyan",
  "dateOfBirthYear": 1921,
  "placeOfBirth": "Mahabad",
  "dateOfDeathYear": 1991,
  "dateOfDeathMonth": 2,
  "dateOfDeathDay": 22,
  "placeOfDeath": "Karaj",
  "description": "Kurdish poet, lexicographer and translator.",
  "tag": ["poetry", "lexicography"],
  "keywords": ["hazhar", "mukriyani"],
  "note": "Portrait sourced from the family archive."
}
```

**Response** `200 OK` — the created `PersonResponseDTO`. Note this is `200`, not `201`; no
`Location` header is set. `createdAt`, `updatedAt`, `createdBy` and `updatedBy` are filled by the
server, and `dateOfBirthPrecision` / `dateOfDeathPrecision` are derived from the date parts that
were supplied (here `YEAR_ONLY` and `FULL`).

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `MISSING_REQUEST_PART` | The `data` or `mediaPortrait` part is absent from the multipart body |
| `400` | `PERSON_VALIDATION_ERROR` | `data` is blank, is not parseable JSON, or fails bean validation — `details` carries one entry per offending field |
| `400` | `BAD_REQUEST` | A date part combination is illegal (`Year is required when date parts are provided`, `Month is required when day is provided`), or the multipart body could not be parsed |
| `403` | `ACCESS_DENIED` | Caller lacks `person:create` |
| `409` | `PERSON_ALREADY_EXISTS` | An active person already uses that `personCode` |
| `409` | `CONFLICT` | A database constraint rejected the insert (for example the unique `person_code` index, counting trashed rows) |
| `413` | `UPLOAD_TOO_LARGE` | The portrait exceeds the configured multipart limit |
| `415` | `UNSUPPORTED_MEDIA_TYPE` | Request was not sent as `multipart/form-data` |
| `500` | `INTERNAL_SERVER_ERROR` | The portrait could not be read or uploaded to S3 |

**Example**

```bash
curl -s -X POST "{{BASE_URL}}/api/person" \
  -H "Cookie: khi_auth_token=$TOKEN" \
  -F 'data={"personCode":"HZI","fullName":"عەبدولڕەحمان شەرەفکەندی","romanizedName":"Hazhar Mukriyani","gender":"MALE","personType":["poet"],"dateOfBirthYear":1921};type=application/json' \
  -F "mediaPortrait=@./hazhar.jpg"
```

**Notes**

- Evicts the `persons:all` cache.
- Writes one `CREATE` row to `person_audit_logs` with details
  `Created person record with code=<personCode>`.

---

### `PATCH /api/person/{personCode}`

Partial update of an active person. `multipart/form-data` only; produces `application/json`.

**Authority:** `person:update`

**Path parameters**

| Name | Type | Description |
|---|---|---|
| `personCode` | string | Business key of the active record. The code itself cannot be changed through this endpoint |

**Request parts**

| Part | Type | Required | Description |
|---|---|---|---|
| `data` | JSON string | yes | `PersonUpdateRequestDTO`; unknown properties are ignored |
| `mediaPortrait` | file | no | Replacement portrait |

**Request body** — the `data` part (`platform/dto/person/PersonUpdateRequestDTO.java`)

Every field is optional and every field is null-means-untouched: omit a field and the stored
value is left alone. There is no bean-validation annotation on this DTO, so it carries no
`@NotBlank` / `@Size` / `@Pattern` rules of its own.

| Field | Type | Description |
|---|---|---|
| `fullName` | string | |
| `nickname` | string | |
| `romanizedName` | string | |
| `gender` | enum | `MALE` \| `FEMALE` |
| `personType` | string[] | Replaces the whole list |
| `region` | string | |
| `dateOfBirthYear` | int | Sending any birth part re-derives `dateOfBirth` **and** `dateOfBirthPrecision` |
| `dateOfBirthMonth` | int | |
| `dateOfBirthDay` | int | |
| `placeOfBirth` | string | |
| `dateOfDeathYear` | int | Sending any death part re-derives `dateOfDeath` **and** `dateOfDeathPrecision` |
| `dateOfDeathMonth` | int | |
| `dateOfDeathDay` | int | |
| `placeOfDeath` | string | |
| `description` | string | |
| `tag` | string[] | Replaces the whole list; blank entries dropped, rest trimmed and comma-joined. Sending `[]` clears the column |
| `keywords` | string[] | Same rule as `tag` |
| `note` | string | |
| `removeMediaPortrait` | boolean | `true` deletes the current portrait from S3 and clears the column; any uploaded `mediaPortrait` part is then ignored |

Leaving all three parts of a date null leaves that date untouched — a date that has already been
set cannot be cleared through this endpoint.

```json
{
  "region": "Mukriyan",
  "dateOfDeathYear": 1991,
  "dateOfDeathMonth": 2,
  "dateOfDeathDay": 22,
  "tag": ["poetry", "lexicography", "dictionary"],
  "removeMediaPortrait": false
}
```

**Response** `200 OK` — the updated `PersonResponseDTO`.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `MISSING_REQUEST_PART` | The `data` part is absent |
| `400` | `PERSON_VALIDATION_ERROR` | `data` is blank or is not parseable JSON |
| `400` | `BAD_REQUEST` | A date part combination is illegal, `personCode` is blank after trimming, or the multipart body could not be parsed |
| `403` | `ACCESS_DENIED` | Caller lacks `person:update` |
| `404` | `PERSON_NOT_FOUND` | No active person with that code |
| `409` | `STALE_VERSION` | Someone else saved the record between your read and your write (optimistic locking on `person.version`) |
| `409` | `CONFLICT` | A database constraint rejected the update |
| `413` | `UPLOAD_TOO_LARGE` | The replacement portrait exceeds the multipart limit |
| `415` | `UNSUPPORTED_MEDIA_TYPE` | Request was not sent as `multipart/form-data` |
| `500` | `INTERNAL_SERVER_ERROR` | The replacement portrait could not be read or uploaded to S3 |

**Example**

```bash
curl -s -X PATCH "{{BASE_URL}}/api/person/HZI" \
  -H "Cookie: khi_auth_token=$TOKEN" \
  -F 'data={"region":"Mukriyan","tag":["poetry","dictionary"]};type=application/json' \
  -F "mediaPortrait=@./hazhar-new.jpg"
```

Clearing the portrait without uploading a replacement:

```bash
curl -s -X PATCH "{{BASE_URL}}/api/person/HZI" \
  -H "Cookie: khi_auth_token=$TOKEN" \
  -F 'data={"removeMediaPortrait":true};type=application/json'
```

**Notes**

- Evicts the `persons:all` cache.
- Writes one `UPDATE` row to `person_audit_logs` containing a field-by-field
  `field: old -> new` diff (including `dateOfBirthPrecision` / `dateOfDeathPrecision` changes and
  a `mediaPortrait removed:` / `mediaPortrait replaced:` line). When nothing actually changed the
  details read `Updated person record (no field changes detected)` — the audit row is still
  written.

---

### `DELETE /api/person/{personCode}`

Soft delete: sends the person to trash. The row and the portrait survive.

**Authority:** `person:delete`

**Path parameters**

| Name | Type | Description |
|---|---|---|
| `personCode` | string | Business key of the active record |

**Cascade** — every **active** project linked to the person is trashed first, and each of those
projects cascades to its own audio, video, image and text records. The linked categories are not
touched. The response tells the caller exactly which project collections moved.

**Response** `200 OK` — `PersonService.DeleteResult`

```json
{
  "personCode": "HZI",
  "trashedProjectsCount": 2,
  "trashedProjectCodes": ["HZI-POEMS-1968", "HZI-LETTERS"]
}
```

`trashedProjectsCount` is `0` and `trashedProjectCodes` is `[]` when the person had no active
projects.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `BAD_REQUEST` | `personCode` is blank after trimming |
| `403` | `ACCESS_DENIED` | Caller lacks `person:delete` |
| `404` | `PERSON_NOT_FOUND` | No active person with that code |
| `409` | `STALE_VERSION` | The person or one of the cascaded projects was modified concurrently |
| `409` | `CONFLICT` | A database constraint rejected the change |

**Example**

```bash
curl -s -X DELETE "{{BASE_URL}}/api/person/HZI" \
  -H "Cookie: khi_auth_token=$TOKEN"
```

**Notes**

- Evicts the `persons:all` cache; each cascaded project evicts the project and media caches
  itself.
- Writes one `DELETE` row to `person_audit_logs`. Each cascaded project writes its own audit row
  in `project_audit_logs`, and the cascaded media write theirs.
- The portrait is deliberately **not** deleted from S3 — only `purge` removes it.

---

### `POST /api/person/{personCode}/restore`

Bring a person back from trash.

**Authority:** `person:delete`

**Path parameters**

| Name | Type | Description |
|---|---|---|
| `personCode` | string | Business key of the trashed record |

**Cascade** — the person is reactivated first, then every project currently in trash that links
to them is restored, and each restored project cascade-restores its own audio, video, image and
text. This mirrors the delete cascade so a delete/restore round-trip is reversible.

The cascade runs through `ProjectService.restore`, whose own `requireAdminRole` demands
`project:delete`, not `person:delete`. A caller holding only `person:delete` gets a `403` as soon
as the cascade reaches the first trashed project, and the whole restore rolls back with it. ADMIN
holds both authorities, so this only affects hand-edited per-user grant sets.
`DELETE /api/person/{personCode}` has no equivalent requirement — `ProjectService.delete` does
not re-check any authority.

**Request body** — none.

**Response** `200 OK` — `PersonService.RestoreResult`

```json
{
  "person": {
    "id": 12,
    "personCode": "HZI",
    "fullName": "عەبدولڕەحمان شەرەفکەندی",
    "gender": "MALE",
    "personType": ["poet"],
    "tag": [],
    "keywords": [],
    "createdAt": "2026-03-04 11:12:08",
    "updatedAt": "2026-08-26 14:02:31",
    "createdBy": "aram",
    "updatedBy": "admin"
  },
  "restoredProjectsCount": 2,
  "restoredProjectCodes": ["HZI-POEMS-1968", "HZI-LETTERS"]
}
```

`removedAt` and `removedBy` are cleared by the restore, so — being null — they are omitted from
the embedded `person` object.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `BAD_REQUEST` | `personCode` is blank after trimming |
| `403` | `ACCESS_DENIED` | Caller lacks `person:delete` (checked by `@PreAuthorize` and again inside the service), or lacks `project:delete` once the project cascade starts |
| `404` | `PERSON_NOT_FOUND` | No person with that code at all, or the record is not in trash |
| `409` | `PERSON_ALREADY_EXISTS` | The service re-checks for an active person on that `personCode` before reactivating. Defensive only — the table-wide unique index on `person_code` normally stops the collision from existing |
| `409` | `PROJECT_ALREADY_EXISTS` | A cascaded project's code is already taken by an active project |
| `409` | `STALE_VERSION` | The person or a cascaded project was modified concurrently |

**Example**

```bash
curl -s -X POST "{{BASE_URL}}/api/person/HZI/restore" \
  -H "Cookie: khi_auth_token=$TOKEN"
```

**Notes**

- Evicts the `persons:all` cache; cascaded projects evict their own caches.
- Writes one `RESTORE` row to `person_audit_logs` naming the restored project codes. Each
  cascaded project writes its own audit row.

---

### `GET /api/person/trash`

Paged list of trashed persons.

**Authority:** `person:delete`

**Query parameters**

| Name | Type | Default | Description |
|---|---|---|---|
| `page` | int | `0` | Zero-based page index |
| `size` | int | `100` | Page size, capped at Spring Data's `max-page-size` (`2000`) |

There are no filter or sort parameters on this endpoint — unlike `GET /api/person`, the handler
takes only a `Pageable` (`@PageableDefault(size = 100)`), no `PersonFilterParams`. Unparseable
`page` / `size` values are absorbed by Spring's `Pageable` resolver and fall back to the defaults
rather than producing an error.

**Response** `200 OK` — standard `Page` envelope with `PersonResponseDTO` elements. Every element
here carries `removedAt` and `removedBy`.

```json
{
  "content": [
    {
      "id": 12,
      "personCode": "HZI",
      "fullName": "عەبدولڕەحمان شەرەفکەندی",
      "gender": "MALE",
      "personType": ["poet"],
      "tag": [],
      "keywords": [],
      "createdAt": "2026-03-04 11:12:08",
      "updatedAt": "2026-06-18 09:41:55",
      "removedAt": "2026-08-26 13:55:02",
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
| `403` | `ACCESS_DENIED` | Caller lacks `person:delete` (checked by `@PreAuthorize` and again inside the service) |
| `500` | `DATABASE_ERROR` | The trash query failed |
| `504` | `TIMEOUT` | The trash query timed out |

**Example**

```bash
curl -s "{{BASE_URL}}/api/person/trash?page=0&size=50" \
  -H "Cookie: khi_auth_token=$TOKEN"
```

**Notes**

- Not cached — always queried from the database (`persons:all` holds active records only).
- Writes one `LIST` row to `person_audit_logs` with details `Listed person trash (...)`, which is
  how it is told apart from the active-list `LIST` rows.

---

### `DELETE /api/person/{personCode}/purge`

Permanently delete a trashed person and their portrait. Irreversible.

**Authority:** `person:delete`

**Path parameters**

| Name | Type | Description |
|---|---|---|
| `personCode` | string | Business key of the trashed record |

**Preconditions**

1. The person must already be in trash — purge does not trash first.
2. No project may still reference the person, **active or trashed**. Purge those projects first.

**Response** `204 No Content` — empty body.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `BAD_REQUEST` | `personCode` is blank after trimming |
| `403` | `ACCESS_DENIED` | Caller lacks `person:delete` (checked by `@PreAuthorize` and again inside the service) |
| `404` | `PERSON_NOT_FOUND` | No person with that code |
| `409` | `CONFLICT` | A database constraint rejected the row deletion |
| `500` | `INTERNAL_SERVER_ERROR` | The person is not in trash, or a project still references them — both guards throw `IllegalStateException`, which no handler in `ApiExceptionHandler` maps, so the catch-all turns them into a generic 500 whose `message` is `An unexpected error occurred.` The specific reason (`Person must be in trash before permanent deletion. Trash it first.` / `Person is still referenced by projects (active or trashed). Purge those projects first.`) appears only in the server log |

**Example**

```bash
curl -s -X DELETE "{{BASE_URL}}/api/person/HZI/purge" \
  -H "Cookie: khi_auth_token=$TOKEN" -i
```

**Notes**

- Deletes the portrait object from S3 before deleting the row, but only when the stored URL
  belongs to the configured bucket.
- Writes the `PURGE` row to `person_audit_logs` **before** the row is deleted, so the log keeps
  the person's id, code and name. Evicts the `persons:all` cache afterwards.

---

## Audit trail — `person_audit_logs`

Every endpoint in this file writes exactly one row through `PersonAuditService.record`, in a
`REQUIRES_NEW` transaction so the audit survives even if the surrounding business transaction
rolls back.

| Action | Written by | `person_id` / `person_code` / `person_name` |
|---|---|---|
| `CREATE` | `POST /api/person` | populated |
| `READ` | `GET /api/person/{personCode}` | populated |
| `LIST` | `GET /api/person` and `GET /api/person/trash` | null — the details string distinguishes the two |
| `SEARCH` | `GET /api/person/search` | null |
| `UPDATE` | `PATCH /api/person/{personCode}` | populated |
| `DELETE` | `DELETE /api/person/{personCode}` | populated |
| `RESTORE` | `POST /api/person/{personCode}/restore` | populated |
| `PURGE` | `DELETE /api/person/{personCode}/purge` | populated |

`PersonAuditAction` also declares a `REMOVE` value, but no Person endpoint writes it.

Columns recorded per row (`platform/model/person/PersonAuditLog.java`): `id`, `person_id`,
`person_code`, `person_name`, `action`, `actor_user_id`, `actor_username`, `actor_display_name`,
`actor_authorities`, `actor_permissions`, `device_info`, `ip_address`, `session_id`,
`session_login_timestamp`, `session_expires_at`, `session_is_active`, `request_method`,
`request_path`, `details`, `occurred_at`.

Session columns are resolved from the JWT on the request (`Authorization: Bearer …` first, then
the `khi_auth_token` cookie) against the `sessions` table; when no session can be resolved,
`device_info` falls back to the `User-Agent` header and `ip_address` to the remote address.
`details` is HTML-escaped before it is stored.

## Related

- [Internal API index](../README.md)
- [Conventions — page envelope, timestamps, error shape](../01-conventions.md)
- [Project API](./project.md) — the collection entity that delete and restore cascade through
