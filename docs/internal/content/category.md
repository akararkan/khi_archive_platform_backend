# Category API

> **Audience:** Staff (EMPLOYEE / ADMIN) · **Base path:** `/api/category` · **Source:** `src/main/java/ak/dev/khi_archive_platform/platform/api/category/CategoryAPI.java`

Categories are the archive's shared classification vocabulary: every project is filed under one or
more of them. This API is the back-office CRUD surface for that vocabulary — listing, fuzzy search,
create (single and bulk), partial update, trash / restore / purge — plus the per-category
`keywords` list that keeps staff from creating near-duplicate categories.

## Access

| Requirement | Value |
|---|---|
| Authentication | Required — JWT via `Authorization: Bearer <jwt>` (read first) or the `khi_auth_token` HttpOnly cookie (fallback) |
| Authority | Per method (no class-level `@PreAuthorize`): `category:read`, `category:create`, `category:update`, `category:delete` |
| Roles that hold it by default | ADMIN holds all four. EMPLOYEE is seeded with `category:read`, `category:create`, `category:update` (`Role.EMPLOYEE_DEFAULT_PERMISSIONS`) — **not** `category:delete` |

Notes on the authority model:

- `@PreAuthorize` is declared on **each handler method**, not on the controller class. The exact
  authority is repeated in every endpoint section below.
- The three trash-side operations (`/trash`, `/{categoryCode}/restore`, `/{categoryCode}/purge`)
  are additionally re-checked inside `CategoryService` via `requireAdminRole(...)`, which asserts
  the caller holds `category:delete`. In practice this makes them ADMIN-only, because
  `category:delete` is not part of the EMPLOYEE seed set.
- `Permission.CATEGORY_REMOVE` (`category:remove`) exists in `user/enums/Permission.java` but is
  not referenced by any endpoint in this controller — soft delete is gated by `category:delete`.

## Endpoints

| Method | Path | Authority | Purpose |
|---|---|---|---|
| `GET` | `/api/category` | `category:read` | Paged list of active categories with filter + sort |
| `GET` | `/api/category/search` | `category:read` | Typo-tolerant search over name, description, code, keywords |
| `GET` | `/api/category/trash` | `category:delete` | Paged list of trashed categories |
| `GET` | `/api/category/{categoryCode}` | `category:read` | Single active category by code |
| `POST` | `/api/category` | `category:create` | Create one category |
| `POST` | `/api/category/bulk` | `category:create` | Bulk-create from a JSON array |
| `PATCH` | `/api/category/{categoryCode}` | `category:update` | Partial update of name / description / keywords |
| `DELETE` | `/api/category/{categoryCode}` | `category:delete` | Soft delete — send to trash |
| `POST` | `/api/category/{categoryCode}/restore` | `category:delete` | Restore from trash |
| `DELETE` | `/api/category/{categoryCode}/purge` | `category:delete` | Permanent delete from trash |

## Data model

`CategoryResponseDTO` — the element shape returned by every endpoint in this file except
`POST /api/category/bulk` (which returns `BulkCreateResult`) and the two `204 No Content`
endpoints, `DELETE /api/category/{categoryCode}` and `DELETE /api/category/{categoryCode}/purge`,
which return no body at all.

| Field | Type | Description |
|---|---|---|
| `id` | long | Database identity |
| `categoryCode` | string | Unique code, max 120 chars, `^[A-Za-z0-9_-]+$` |
| `name` | string | Display name (TEXT column, required) |
| `description` | string | Free text; omitted from the response when null |
| `keywords` | string[] | Canonicalized alternative names / search hints (`category_keywords` table) |
| `createdAt` | timestamp | Set on insert |
| `updatedAt` | timestamp | Refreshed on every save (`@PreUpdate`) |
| `removedAt` | timestamp | Trash marker; present only while the category is in trash |
| `createdBy` | string | Username of the creating actor |
| `updatedBy` | string | Username of the last actor to save the row |
| `removedBy` | string | Username of the actor who trashed it; cleared on restore |

`spring.jackson.default-property-inclusion=non_null` — null fields are omitted entirely, so an
active category never carries `removedAt` / `removedBy`, and a category without a description
never carries `description`. `keywords` is always present (an empty list serializes as `[]`).
The entity also carries an optimistic-locking `version` column, which is **not** exposed in the
DTO; concurrent edits surface as `409 STALE_VERSION`.

### Keyword canonicalization

`keywords` on create and update are run through `Keywords.canonical(...)`
(`platform/service/common/Keywords.java` → `Tags.TextListCanonicalizer`) before persisting:

1. Unicode NFKC normalization; zero-width joiners replaced with a space.
2. Trim, then collapse internal whitespace runs to a single space.
3. Lower-case with `Locale.ROOT`.
4. Deduplicate, first occurrence wins.
5. Blank entries dropped; entries longer than **200 characters** after normalization are dropped
   silently (not truncated).

The value you send back in the response is the canonical form, not the raw input.

---

### `GET /api/category`

Paged list of active categories (`removed_at IS NULL`) with optional in-memory filter and sort.

**Authority:** `category:read`

**Query parameters**

| Name | Type | Default | Description |
|---|---|---|---|
| `page` | int | `0` | Zero-based page index (standard `Pageable` binding) |
| `size` | int | `100` | Page size — `@PageableDefault(size = 100)` |
| `sortBy` | string | none | `name` \| `createdAt` \| `updatedAt`. Synonyms accepted: `alpha`, `alphabet`, `alphabetical` → name; `created`, `added`, `dateAdded`, `date_added` → createdAt; `updated`, `modified`, `dateModified`, `date_modified` → updatedAt. Case-insensitive; an unrecognized value leaves the order untouched |
| `sortDirection` | string | `asc` | `desc` (case-insensitive) reverses the comparator; anything else is ascending |
| `createdFrom` | date `yyyy-MM-dd` | none | Inclusive lower bound on `createdAt` |
| `createdTo` | date `yyyy-MM-dd` | none | Inclusive upper bound on `createdAt` |
| `updatedFrom` | date `yyyy-MM-dd` | none | Inclusive lower bound on `updatedAt` |
| `updatedTo` | date `yyyy-MM-dd` | none | Inclusive upper bound on `updatedAt` |
| `tags` | string (repeatable) | none | Matched against `keywords`, case-insensitive and trimmed. Repeat the parameter or comma-separate |
| `tagMatch` | string | `any` | `all` requires every supplied tag to be present; any other value means `any` |

These eight filter/sort fields are exactly the components of the
`CategoryFilterParams` record (`platform/dto/category/CategoryFilterParams.java`), which the
controller assembles from the individual `@RequestParam` values.

Behavior details:

- Date bounds are resolved to day boundaries in the archive zone `Asia/Baghdad` by `ArchiveTime`
  — `createdFrom=2026-01-05` means `2026-01-05T00:00:00+03:00`, `createdTo=2026-01-05` means
  `2026-01-05T23:59:59.999999999+03:00`.
- When `createdFrom/To` and `updatedFrom/To` are both supplied, a row must satisfy **both** ranges.
- A row with no keywords never matches a `tags` filter.
- With no filter parameters at all the cached active list is returned as-is; its base order is
  `name ASC` from `CategoryRepository.findAllActiveWithKeywords()`.
- The standard `Pageable` `sort` parameter is **not** applied — paging is a plain slice over the
  in-memory list (`PaginationSupport.sliceList`). Use `sortBy` / `sortDirection` for ordering.
- A non-numeric `page` or `size` does **not** fail the request: Spring Data's `Pageable` resolver
  swallows the parse error and falls back to `page=0` / `size=100`.

**Response** `200 OK` — standard Spring `Page` envelope (see [`../01-conventions.md`](../01-conventions.md)),
`content[]` elements are `CategoryResponseDTO`.

```json
{
  "content": [
    {
      "id": 12,
      "categoryCode": "FOLK_MUSIC",
      "name": "Folk Music",
      "description": "Recordings of traditional Kurdish folk music.",
      "keywords": ["folk", "traditional music"],
      "createdAt": "2026-01-05T07:12:44.318Z",
      "updatedAt": "2026-02-19T10:41:02.907Z",
      "createdBy": "aland",
      "updatedBy": "aland"
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
| `400` | `TYPE_MISMATCH` | `createdFrom` / `createdTo` / `updatedFrom` / `updatedTo` is not a valid `yyyy-MM-dd` date |
| `401` | `TOKEN_MISSING`, `TOKEN_EXPIRED`, `TOKEN_REVOKED`, `TOKEN_INVALID` | No token on the request — neither an `Authorization: Bearer` header nor the cookie — or the JWT is expired / revoked / unparseable |
| `403` | `ACCESS_DENIED` | Caller lacks `category:read` |
| `500` | `DATABASE_ERROR` | The read-cache miss query failed |

**Notes** — Served from the Caffeine cache `categories:all` (one entry, 10-minute TTL,
`platform/config/CacheConfig.java`); filtering and sorting run in memory over the cached DTO list.
Audit action `LIST` is written on every call, cache hit or miss, with the active filters appended
to `details`.

---

### `GET /api/category/search`

Typo-tolerant, multilingual search across category name, description, code, and keywords.

**Authority:** `category:read`

**Query parameters**

| Name | Type | Default | Description |
|---|---|---|---|
| `q` | string | — | **Required.** Search text; trimmed before use. A blank value returns `[]` without touching the database and without writing an audit row |
| `limit` | int | `20` | Maximum hits. Values `<= 0` (and a missing value) fall back to `20`; values above `100` are clamped to `100` |

Matching combines case-insensitive substring hits (`LOWER(col) LIKE LOWER('%q%')`) on name,
description, `category_code` and keywords with trigram-similarity hits on **name and keywords
only** (`pg_trgm`, `similarity(...) > 0.3`; the GIN indexes are created at startup by
`platform/config/CategorySearchIndexInitializer.java`). Results are ordered by the best similarity
score across name and keywords, then `name ASC`. Only active categories (`removed_at IS NULL`) are
searched.

**Response** `200 OK` — a plain JSON array (not a `Page`) of `CategoryResponseDTO`.

```json
[
  {
    "id": 12,
    "categoryCode": "FOLK_MUSIC",
    "name": "Folk Music",
    "keywords": ["folk", "traditional music"],
    "createdAt": "2026-01-05T07:12:44.318Z",
    "updatedAt": "2026-02-19T10:41:02.907Z",
    "createdBy": "aland",
    "updatedBy": "aland"
  }
]
```

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `MISSING_PARAMETER` | `q` was not supplied |
| `400` | `TYPE_MISMATCH` | `limit` is not an integer |
| `401` | `TOKEN_MISSING`, `TOKEN_EXPIRED`, `TOKEN_REVOKED`, `TOKEN_INVALID` | No token on the request — neither an `Authorization: Bearer` header nor the cookie — or the JWT is expired / revoked / unparseable |
| `403` | `ACCESS_DENIED` | Caller lacks `category:read` |
| `500` | `DATABASE_ERROR` | The search query failed (for example the `pg_trgm` extension is unavailable) |
| `504` | `TIMEOUT` | The search query exceeded the database timeout |

**Notes** — Not cached; every call hits PostgreSQL. Audit action `SEARCH` records the normalized
query, the effective limit, and the hit count. A blank `q` short-circuits before both the query and
the audit write.

---

### `GET /api/category/trash`

Paged list of soft-deleted categories (`removed_at IS NOT NULL`).

**Authority:** `category:delete` (re-checked in the service by `requireAdminRole`)

**Query parameters**

| Name | Type | Default | Description |
|---|---|---|---|
| `page` | int | `0` | Zero-based page index |
| `size` | int | `100` | Page size — `@PageableDefault(size = 100)` |

No filter parameters are bound on this endpoint, and a non-numeric `page` or `size` is swallowed by
the `Pageable` resolver and replaced by the defaults rather than rejected. Ordering:
_Not documented in source._ — `findAllByRemovedAtIsNotNull()` declares no `ORDER BY` and the
`Pageable` sort is not applied.

**Response** `200 OK` — `Page<CategoryResponseDTO>`. Trashed rows carry `removedAt` and
`removedBy`.

```json
{
  "content": [
    {
      "id": 41,
      "categoryCode": "OBSOLETE_TAG",
      "name": "Obsolete Tag",
      "keywords": [],
      "createdAt": "2025-11-02T06:00:11.004Z",
      "updatedAt": "2026-03-01T09:22:35.610Z",
      "removedAt": "2026-03-01T09:22:35.610Z",
      "createdBy": "aland",
      "updatedBy": "aland",
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
| `401` | `TOKEN_MISSING`, `TOKEN_EXPIRED`, `TOKEN_REVOKED`, `TOKEN_INVALID` | No token on the request — neither an `Authorization: Bearer` header nor the cookie — or the JWT is expired / revoked / unparseable |
| `403` | `ACCESS_DENIED` | Caller lacks `category:delete` — from `@PreAuthorize` or from the service's `requireAdminRole` check |
| `500` | `DATABASE_ERROR` | The trash query failed |

**Notes** — Reads the database directly (the `categories:all` cache holds active rows only).
Audit action `LIST` is written with `details` prefixed `Listed category trash`.
`/trash` is a literal path segment, so it is matched ahead of `GET /api/category/{categoryCode}`;
a category whose code is literally `trash` is therefore unreachable through the by-code endpoint.

---

### `GET /api/category/{categoryCode}`

Fetch a single active category by its code.

**Authority:** `category:read`

**Path parameters**

| Name | Type | Description |
|---|---|---|
| `categoryCode` | string | Category code. Trimmed, then validated against `^[A-Za-z0-9_-]+$`. Case-sensitive — the code is not lower-cased |

**Response** `200 OK`

```json
{
  "id": 12,
  "categoryCode": "FOLK_MUSIC",
  "name": "Folk Music",
  "description": "Recordings of traditional Kurdish folk music.",
  "keywords": ["folk", "traditional music"],
  "createdAt": "2026-01-05T07:12:44.318Z",
  "updatedAt": "2026-02-19T10:41:02.907Z",
  "createdBy": "aland",
  "updatedBy": "aland"
}
```

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `BAD_REQUEST` | Code is blank or contains characters outside `A-Z a-z 0-9 _ -` |
| `401` | `TOKEN_MISSING`, `TOKEN_EXPIRED`, `TOKEN_REVOKED`, `TOKEN_INVALID` | No token on the request — neither an `Authorization: Bearer` header nor the cookie — or the JWT is expired / revoked / unparseable |
| `403` | `ACCESS_DENIED` | Caller lacks `category:read` |
| `404` | `CATEGORY_NOT_FOUND` | No active category with that code (trashed categories are invisible here) |
| `500` | `DATABASE_ERROR` | Lookup failed |

**Notes** — Bypasses the list cache and reads the row directly. Audit action `READ`.

**Example (read group)**

```bash
# list, filtered and sorted
curl -s "{{BASE_URL}}/api/category?page=0&size=20&sortBy=name&sortDirection=asc&tags=folk&tagMatch=any" \
  -H "Cookie: khi_auth_token=$TOKEN"

# fuzzy search
curl -s "{{BASE_URL}}/api/category/search?q=folkk&limit=10" \
  -H "Cookie: khi_auth_token=$TOKEN"

# single category
curl -s "{{BASE_URL}}/api/category/FOLK_MUSIC" \
  -H "Cookie: khi_auth_token=$TOKEN"
```

---

### `POST /api/category`

Create a single category.

**Authority:** `category:create`

**Request body** — `CategoryCreateRequestDTO` (`application/json`; unknown properties ignored)

| Field | Type | Required | Rules |
|---|---|---|---|
| `categoryCode` | string | Yes | `@NotBlank`; must match `^[A-Za-z0-9_-]+$` (column length 120). Trimmed server-side |
| `name` | string | Yes | `@NotBlank` |
| `description` | string | No | Free text |
| `keywords` | string[] | No | Canonicalized and deduplicated before saving |

```json
{
  "categoryCode": "FOLK_MUSIC",
  "name": "Folk Music",
  "description": "Recordings of traditional Kurdish folk music.",
  "keywords": ["Folk", "Traditional Music", "folk"]
}
```

**Response** `200 OK` — the created `CategoryResponseDTO` (note: `200`, not `201`).

```json
{
  "id": 12,
  "categoryCode": "FOLK_MUSIC",
  "name": "Folk Music",
  "description": "Recordings of traditional Kurdish folk music.",
  "keywords": ["folk", "traditional music"],
  "createdAt": "2026-01-05T07:12:44.318Z",
  "updatedAt": "2026-01-05T07:12:44.318Z",
  "createdBy": "aland",
  "updatedBy": "aland"
}
```

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `VALIDATION_ERROR` | `categoryCode` or `name` blank, or `categoryCode` fails the pattern — per-field reasons in `details` |
| `400` | `JSON_PARSE_ERROR` | Body is not valid JSON, or a field has the wrong JSON type |
| `400` | `BAD_REQUEST` | Code rejected by `CategoryCodeHelper` (blank after trimming, or illegal characters) |
| `401` | `TOKEN_MISSING`, `TOKEN_EXPIRED`, `TOKEN_REVOKED`, `TOKEN_INVALID` | No token on the request — neither an `Authorization: Bearer` header nor the cookie — or the JWT is expired / revoked / unparseable |
| `403` | `ACCESS_DENIED` | Caller lacks `category:create` |
| `409` | `CATEGORY_ALREADY_EXISTS` | An **active** category already uses that code |
| `409` | `CONFLICT` | A database constraint blocked the insert — for example the unique `category_code` index still holding a trashed row with the same code |
| `500` | `DATABASE_ERROR` | Insert failed |

**Notes** — `createdAt` / `updatedAt` / `createdBy` / `updatedBy` are set server-side from the
authenticated principal (`anonymous` when there is no `Authentication`). Evicts the
`categories:all` and `keywords:suggest` caches. Audit action `CREATE`.

---

### `POST /api/category/bulk`

Bulk-create categories in one transaction with a single cache eviction at the end.

**Authority:** `category:create`

**Request body** — a JSON array of `CategoryCreateRequestDTO` (same field rules as
`POST /api/category`).

```json
[
  { "categoryCode": "FOLK_MUSIC", "name": "Folk Music", "keywords": ["folk"] },
  { "categoryCode": "POETRY", "name": "Poetry", "description": "Recited and written poetry." }
]
```

Rows whose `categoryCode` already exists as an **active** category are skipped, not rejected — the
rest of the batch still commits. An empty array (or `null`) short-circuits and returns all zeros
without writing an audit row.

**Response** `200 OK` — `CategoryService.BulkCreateResult`, not a category list.

| Field | Type | Description |
|---|---|---|
| `requested` | int | Number of elements in the submitted array |
| `inserted` | int | Rows actually saved |
| `skippedDuplicates` | int | Rows skipped because an active category already held the code |
| `elapsedMs` | long | Server-side wall time for the batch |

```json
{
  "requested": 1000,
  "inserted": 987,
  "skippedDuplicates": 13,
  "elapsedMs": 2411
}
```

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `JSON_PARSE_ERROR` | Body is not a valid JSON array, or an element has the wrong field types |
| `400` | `BAD_REQUEST` | Any element's `categoryCode` is blank or fails `^[A-Za-z0-9_-]+$` — the whole batch is rejected |
| `401` | `TOKEN_MISSING`, `TOKEN_EXPIRED`, `TOKEN_REVOKED`, `TOKEN_INVALID` | No token on the request — neither an `Authorization: Bearer` header nor the cookie — or the JWT is expired / revoked / unparseable |
| `403` | `ACCESS_DENIED` | Caller lacks `category:create` |
| `409` | `CONFLICT` | A database constraint blocked the flush — most commonly two elements of the same payload carrying the same `categoryCode` (the duplicate check runs against the database, not within the batch) |
| `500` | `DATABASE_ERROR` | The batch insert failed |

**Notes** — Duplicate detection is per row against existing active rows; the entire call is one
transaction, so a constraint failure rolls the whole batch back. One audit row is written with
action `CREATE` and a batch summary in `details`
(`Bulk created categories: requested=… inserted=… skippedDuplicates=… elapsedMs=…`); the row has
no `categoryId` / `categoryCode` / `categoryName`.

**Example (create group)**

```bash
# single create
curl -s -X POST "{{BASE_URL}}/api/category" \
  -H "Cookie: khi_auth_token=$TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
        "categoryCode": "FOLK_MUSIC",
        "name": "Folk Music",
        "description": "Recordings of traditional Kurdish folk music.",
        "keywords": ["folk", "traditional music"]
      }'

# bulk create from a file holding a JSON array
curl -s -X POST "{{BASE_URL}}/api/category/bulk" \
  -H "Cookie: khi_auth_token=$TOKEN" \
  -H "Content-Type: application/json" \
  --data-binary @test-categories-1000.json
```

---

### `PATCH /api/category/{categoryCode}`

Partial update of an active category. `categoryCode` itself cannot be changed.

**Authority:** `category:update`

**Path parameters**

| Name | Type | Description |
|---|---|---|
| `categoryCode` | string | Code of the active category to update; trimmed and pattern-validated |

**Request body** — `CategoryUpdateRequestDTO` (no bean validation; unknown properties ignored)

| Field | Type | Description |
|---|---|---|
| `name` | string | Applied only when non-null **and** different from the current value |
| `description` | string | Applied only when non-null **and** different from the current value |
| `keywords` | string[] | Applied whenever non-null — the list is **replaced** wholesale with the canonicalized version. Send `[]` to clear all keywords |

Omitted (or `null`) fields are left untouched; there is no way to null out `name` or
`description` through this endpoint.

```json
{
  "name": "Folk Music (Kurdish)",
  "keywords": ["folk", "traditional music", "hawrami"]
}
```

**Response** `200 OK` — the updated `CategoryResponseDTO`.

```json
{
  "id": 12,
  "categoryCode": "FOLK_MUSIC",
  "name": "Folk Music (Kurdish)",
  "description": "Recordings of traditional Kurdish folk music.",
  "keywords": ["folk", "traditional music", "hawrami"],
  "createdAt": "2026-01-05T07:12:44.318Z",
  "updatedAt": "2026-04-11T13:05:59.220Z",
  "createdBy": "aland",
  "updatedBy": "shilan"
}
```

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `BAD_REQUEST` | Path code blank or failing `^[A-Za-z0-9_-]+$` |
| `400` | `JSON_PARSE_ERROR` | Body is not valid JSON, or a field has the wrong JSON type |
| `401` | `TOKEN_MISSING`, `TOKEN_EXPIRED`, `TOKEN_REVOKED`, `TOKEN_INVALID` | No token on the request — neither an `Authorization: Bearer` header nor the cookie — or the JWT is expired / revoked / unparseable |
| `403` | `ACCESS_DENIED` | Caller lacks `category:update` |
| `404` | `CATEGORY_NOT_FOUND` | No active category with that code |
| `409` | `STALE_VERSION` | Someone else saved the same category concurrently (`@Version` conflict) — reload and re-apply |
| `409` | `CONFLICT` | A database constraint blocked the update |
| `500` | `DATABASE_ERROR` | Update failed |

**Notes** — `updatedAt` / `updatedBy` are refreshed even when no field actually changed; in that
case the audit `details` reads `Updated category (no field changes detected)`. Otherwise `details`
is `Updated category: ` followed by the ` | `-separated changes (`name: old -> new`,
`description changed`, `keywords: [...] -> [...]`). Evicts `categories:all` and `keywords:suggest`.
Audit action `UPDATE`.

**Example**

```bash
curl -s -X PATCH "{{BASE_URL}}/api/category/FOLK_MUSIC" \
  -H "Cookie: khi_auth_token=$TOKEN" \
  -H "Content-Type: application/json" \
  -d '{ "name": "Folk Music (Kurdish)", "keywords": ["folk", "traditional music", "hawrami"] }'
```

---

### `DELETE /api/category/{categoryCode}`

Soft delete — stamps `removedAt` / `removedBy` and moves the category to the trash. Nothing is
erased.

**Authority:** `category:delete` (EMPLOYEE does not hold it by default)

**Path parameters**

| Name | Type | Description |
|---|---|---|
| `categoryCode` | string | Code of the active category to trash |

**Response** `204 No Content` — empty body.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `BAD_REQUEST` | Path code blank or failing `^[A-Za-z0-9_-]+$` |
| `401` | `TOKEN_MISSING`, `TOKEN_EXPIRED`, `TOKEN_REVOKED`, `TOKEN_INVALID` | No token on the request — neither an `Authorization: Bearer` header nor the cookie — or the JWT is expired / revoked / unparseable |
| `403` | `ACCESS_DENIED` | Caller lacks `category:delete` |
| `404` | `CATEGORY_NOT_FOUND` | No active category with that code (already trashed counts as not found) |
| `409` | `CATEGORY_IN_USE` | At least one **active** project still references this category — see [In-use rule](#the-category_in_use-rule) |
| `409` | `STALE_VERSION` | Concurrent save on the same row |
| `500` | `DATABASE_ERROR` | Update failed |

**Notes** — Evicts `categories:all` and `keywords:suggest`. Audit action `DELETE` with `details`
`Sent category to trash`. The enum value `REMOVE` exists in `CategoryAuditAction` but is not
written by this API.

---

### `POST /api/category/{categoryCode}/restore`

Bring a trashed category back — clears `removedAt` / `removedBy` and re-stamps `updatedAt` /
`updatedBy`.

**Authority:** `category:delete` (re-checked in the service by `requireAdminRole`)

**Path parameters**

| Name | Type | Description |
|---|---|---|
| `categoryCode` | string | Code of the trashed category |

**Request body** — none.

**Response** `200 OK` — the restored `CategoryResponseDTO` (no `removedAt` / `removedBy` any more).

```json
{
  "id": 41,
  "categoryCode": "OBSOLETE_TAG",
  "name": "Obsolete Tag",
  "keywords": [],
  "createdAt": "2025-11-02T06:00:11.004Z",
  "updatedAt": "2026-05-04T08:30:12.556Z",
  "createdBy": "aland",
  "updatedBy": "admin"
}
```

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `BAD_REQUEST` | Path code blank or failing `^[A-Za-z0-9_-]+$` |
| `401` | `TOKEN_MISSING`, `TOKEN_EXPIRED`, `TOKEN_REVOKED`, `TOKEN_INVALID` | No token on the request — neither an `Authorization: Bearer` header nor the cookie — or the JWT is expired / revoked / unparseable |
| `403` | `ACCESS_DENIED` | Caller lacks `category:delete` (from `@PreAuthorize` or `requireAdminRole`) |
| `404` | `CATEGORY_NOT_FOUND` | No category with that code at all, **or** the category exists but is not in trash (`Category is not in trash: …`) |
| `409` | `CATEGORY_ALREADY_EXISTS` | An active category already occupies that code |
| `409` | `STALE_VERSION` | Concurrent save on the same row |
| `500` | `DATABASE_ERROR` | Update failed |

**Notes** — Evicts `categories:all` and `keywords:suggest`. Audit action `RESTORE`.

---

### `DELETE /api/category/{categoryCode}/purge`

Permanently delete a trashed category. Irreversible.

**Authority:** `category:delete` (re-checked in the service by `requireAdminRole`)

**Path parameters**

| Name | Type | Description |
|---|---|---|
| `categoryCode` | string | Code of the category to purge; it must already be in trash |

**Response** `204 No Content` — empty body.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `BAD_REQUEST` | Path code blank or failing `^[A-Za-z0-9_-]+$` |
| `401` | `TOKEN_MISSING`, `TOKEN_EXPIRED`, `TOKEN_REVOKED`, `TOKEN_INVALID` | No token on the request — neither an `Authorization: Bearer` header nor the cookie — or the JWT is expired / revoked / unparseable |
| `403` | `ACCESS_DENIED` | Caller lacks `category:delete` (from `@PreAuthorize` or `requireAdminRole`) |
| `404` | `CATEGORY_NOT_FOUND` | No category with that code, **or** the category is still active (`Category must be in trash before permanent deletion. Trash it first.`) |
| `409` | `CATEGORY_IN_USE` | Any project — active **or** trashed — still references the category |
| `409` | `CONFLICT` | A foreign-key constraint blocked the delete |
| `500` | `DATABASE_ERROR` | Delete failed |

**Notes** — The audit row is written **before** the delete, so the purge is recorded even though
the category row disappears. Audit action `PURGE` with `details`
`Permanently deleted category from trash`. Evicts `categories:all` and `keywords:suggest`.

**Example (trash group)**

```bash
# soft delete
curl -s -X DELETE "{{BASE_URL}}/api/category/OBSOLETE_TAG" \
  -H "Cookie: khi_auth_token=$TOKEN" -i

# list trash
curl -s "{{BASE_URL}}/api/category/trash?page=0&size=50" \
  -H "Cookie: khi_auth_token=$TOKEN"

# restore
curl -s -X POST "{{BASE_URL}}/api/category/OBSOLETE_TAG/restore" \
  -H "Cookie: khi_auth_token=$TOKEN"

# permanent delete
curl -s -X DELETE "{{BASE_URL}}/api/category/OBSOLETE_TAG/purge" \
  -H "Cookie: khi_auth_token=$TOKEN" -i
```

---

## The `CATEGORY_IN_USE` rule

A category is "in use" when a **project** joins it through the `project_categories` join table
(`Project.categories` is a `@ManyToMany`). Media records never reference a category directly, so
only projects can block a deletion.

| Operation | Guard | Repository check | Result when blocked |
|---|---|---|---|
| `DELETE /api/category/{categoryCode}` (trash) | Any **active** project (`removed_at IS NULL`) joins the category | `ProjectRepository.existsByCategoryAndRemovedAtIsNull` | `409` · `CATEGORY_IN_USE` · "Category is used by active projects and cannot be sent to trash" |
| `DELETE /api/category/{categoryCode}/purge` | **Any** project joins the category, active or trashed | `ProjectRepository.existsByCategory` | `409` · `CATEGORY_IN_USE` · "Category is still referenced by projects (active or trashed). Purge those projects first." |

Both map through `CategoryInUseException` → `ApiExceptionHandler.handleCategoryInUse`, which
returns HTTP `409 Conflict`, `error: "CATEGORY_IN_USE"`, `category: "CONFLICT"`, and the hint
"Reassign the linked media to another category before deleting this one."

Recovery path: retarget or trash the linked projects (for trash), or purge them (for purge), then
retry. Trashing a project releases the trash-side block but not the purge-side one — the join row
survives soft deletion.

```json
{
  "timestamp": "2026-05-04T08:30:12.556Z",
  "status": 409,
  "error": "CATEGORY_IN_USE",
  "category": "CONFLICT",
  "message": "Category is used by active projects and cannot be sent to trash",
  "hint": "Reassign the linked media to another category before deleting this one.",
  "path": "/api/category/FOLK_MUSIC"
}
```

## Audit trail

Every endpoint writes exactly one row to `category_audit_logs` through `CategoryAuditService`
(`REQUIRES_NEW` transaction, so the audit row survives a rolled-back business transaction). Two
short-circuit paths are the exception and write nothing: `GET /api/category/search` with a blank
`q`, and `POST /api/category/bulk` with an empty or `null` array.

| Endpoint | `action` | `details` (summary) |
|---|---|---|
| `GET /api/category` | `LIST` | `Listed active categories (page=… size=… returned=… total=… <active filters>)` |
| `GET /api/category/search` | `SEARCH` | `Searched categories q="…" limit=… hits=…` |
| `GET /api/category/trash` | `LIST` | `Listed category trash (page=… size=… returned=… total=…)` |
| `GET /api/category/{categoryCode}` | `READ` | `Read category` |
| `POST /api/category` | `CREATE` | `Created category with code=…` |
| `POST /api/category/bulk` | `CREATE` | `Bulk created categories: requested=… inserted=… skippedDuplicates=… elapsedMs=…` |
| `PATCH /api/category/{categoryCode}` | `UPDATE` | `Updated category: <field-level diff>`, or `Updated category (no field changes detected)` |
| `DELETE /api/category/{categoryCode}` | `DELETE` | `Sent category to trash` |
| `POST /api/category/{categoryCode}/restore` | `RESTORE` | `Restored category from trash` |
| `DELETE /api/category/{categoryCode}/purge` | `PURGE` | `Permanently deleted category from trash` |

`CategoryAuditAction` also declares `REMOVE`, which no endpoint in this controller writes.

Each row records: `categoryId`, `categoryCode`, `categoryName` (all null for the list, search and
bulk rows, which are not tied to a single category), `action`, `actorUserId`, `actorUsername`,
`actorDisplayName`, `actorAuthorities`, `actorPermissions` (authorities minus `ROLE_*`),
`deviceInfo`, `ipAddress`, `sessionId`, `sessionLoginTimestamp`, `sessionExpiresAt`,
`sessionActive`, `requestMethod`, `requestPath`, `details` (HTML-escaped) and `occurredAt`.
Session columns are filled from the `sessions` row resolved from the JWT; when no session can be
resolved, `deviceInfo` falls back to the `User-Agent` header and `ipAddress` to the remote address.

## Caching

| Cache | Holds | Size / TTL |
|---|---|---|
| `categories:all` | The full active category list as DTOs (`CategoryReadCache.getAllActive`) | 1 entry, 10 minutes |
| `keywords:suggest` | Cross-entity keyword autocomplete (`KeywordSuggestService.CACHE`) | 1 000 entries, 10 minutes |

Both are Caffeine caches declared in `platform/config/CacheConfig.java`. Every mutating category
endpoint (create, bulk create, update, delete, restore, purge) calls `CategoryReadCache.evictAll()`,
which clears both caches — category keywords feed the keyword autocomplete, so a category change
must invalidate it too. `GET /api/category/{categoryCode}`, `GET /api/category/search` and
`GET /api/category/trash` do not use the cache.

## Related

- [Internal API index](../README.md)
- [Conventions — page envelope, error envelope, timestamps](../01-conventions.md)
- [Project API](./project.md) — projects hold the `project_categories` links that drive the
  `CATEGORY_IN_USE` rule
