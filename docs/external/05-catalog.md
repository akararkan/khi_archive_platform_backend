# Public Catalog — Projects, Categories, Persons

> **Audience:** anonymous public website / third-party client · **Base path:** `/api/guest` ·
> **Source:** `src/main/java/ak/dev/khi_archive_platform/platform/api/guest/GuestSearchAPI.java`

The catalog endpoints expose the archive's three organizing entities — **projects** (collections),
**categories**, and **persons** — plus the media held inside a project. They are the read-only
browse surface behind the public site: list, filter, open one record by its business code, and
walk the relationships between them.

Everything here is served from `Guest…DTO` shapes, which deliberately omit storage paths,
file-technical metadata (bit rate, file size, volume/directory), version internals, and
audit/trash bookkeeping (`createdBy`, `removedAt`, `version`).

## Access

| Requirement | Value |
|---|---|
| Authentication | not required |
| Authority | none — `SecurityConfig` permits `/api/guest/**` for everyone |
| Roles that hold it by default | all callers, including anonymous |

`GuestSearchAPI` carries no `@PreAuthorize` annotation on the class or on any method. Access is
granted purely by `.requestMatchers("/api/guest/**").permitAll()` in
`user/configs/SecurityConfig.java`. Sending a `khi_auth_token` cookie is allowed but changes
nothing about the response.

## Visibility gate

Two independent flags decide what an anonymous caller may see. Both are evaluated in
`GuestSearchService`.

| Entity | Trash gate | Visibility gate | Predicate in source |
|---|---|---|---|
| Project | `removedAt IS NULL` | `isVisibleToPublic` is not `false` | `isProjectPubliclyVisible(Project)` |
| Audio / Video / Text / Image | `removedAt IS NULL` | `isPublic` is not `false` **and** the owning project passes `isProjectPubliclyVisible` | `isPubliclyVisible(...)` |
| Category | `removedAt IS NULL` | none — categories have no visibility column | repository `findBy…AndRemovedAtIsNull` |
| Person | `removedAt IS NULL` | none — persons have no visibility column | repository `findBy…AndRemovedAtIsNull` |

Notes on the exact semantics:

- The guard is `!Boolean.FALSE.equals(flag)`, so a `NULL` flag counts as **visible**. Both columns
  are declared `NOT NULL DEFAULT TRUE`, so in practice only an explicit `false` hides a record.
- Media visibility **cascades from the project**: a public audio inside a hidden project is not
  reachable through any guest endpoint.
- Categories and persons stay listable even when every project attached to them is hidden. Their
  `projectCount` then reports `0`, because the counts are computed with
  `ProjectRepository.countPublicByCategory` / `countPublicByPerson`, which both require
  `removedAt IS NULL AND (isVisibleToPublic IS NULL OR isVisibleToPublic = true)`.

What a caller gets when a code exists but is not public:

| Situation | Result |
|---|---|
| `GET /api/guest/projects/{projectCode}` — project trashed or `isVisibleToPublic = false` | `404 Not Found`, **empty body** (the controller returns `ResponseEntity.notFound().build()`) |
| `GET /api/guest/projects/{projectCode}/media` — same | `404 Not Found`, empty body |
| `GET /api/guest/categories/{categoryCode}` / `persons/{personCode}` — record trashed | `404 Not Found`, empty body |
| `GET /api/guest/categories/{categoryCode}/projects` / `persons/{personCode}/projects` — code unknown or trashed | `200 OK` with an **empty page**, not a 404 (`Page.empty(pageable)`) |
| A hidden project inside any listing | silently skipped; it is absent from `content` and is not counted in `totalElements` |

There is no "hidden" marker in any guest response — a non-public record is indistinguishable from
one that never existed.

## Endpoints

| Method | Path | Authority | Purpose |
|---|---|---|---|
| `GET` | `/api/guest/projects` | none | Paged, filterable list of public projects |
| `GET` | `/api/guest/projects/{projectCode}` | none | One public project by code |
| `GET` | `/api/guest/projects/{projectCode}/media` | none | All public media inside a project, grouped by kind |
| `GET` | `/api/guest/categories` | none | Paged list of active categories |
| `GET` | `/api/guest/categories/{categoryCode}` | none | One active category by code |
| `GET` | `/api/guest/categories/{categoryCode}/projects` | none | Public projects filed under a category |
| `GET` | `/api/guest/persons` | none | Paged, filterable list of active persons |
| `GET` | `/api/guest/persons/{personCode}` | none | One active person by code |
| `GET` | `/api/guest/persons/{personCode}/projects` | none | Public projects owned by a person |

All nine are `@GetMapping` methods on `GuestSearchAPI`, a `@RestController` annotated
`@RequestMapping("/api/guest")`. The same class also serves the discovery endpoints
([`./04-discovery.md`](./04-discovery.md)) and the per-kind media endpoints
([`./06-media.md`](./06-media.md)); none of its methods carries `@PreAuthorize`.

---

### `GET /api/guest/projects`

Paged list of publicly visible projects, with optional free-text search and structured filters.

**Authority:** none (public)

**Query parameters**

| Name | Type | Default | Description |
|---|---|---|---|
| `q` | string | — | Free text. Blank/absent makes the candidate set every active project, before the visibility gate is applied. Matched by `ProjectRepository.searchByText` against `project_name`, `project_code`, `description`, `project_tags.tag` and `project_keywords.keyword` — substring `LIKE` plus `pg_trgm` similarity above `0.2` — ordered by best similarity, then `project_name ASC`, capped at 500 rows |
| `categoryCode` | string | — | Keep only projects that carry this category code. Exact match, case-insensitive |
| `personCode` | string | — | Keep only projects whose owning person has this code. Exact match, case-insensitive |
| `tag` | string, repeatable | — | Keep projects having **any** of the given tags. Whole-value match, case-insensitive, trimmed — no comma splitting, no substring matching |
| `keyword` | string, repeatable | — | Same rule as `tag`, against the project's keywords |
| `sortBy` | string | — | One of `name` \| `alpha` \| `alphabet` \| `alphabetical` \| `projectname`, `code` \| `projectcode`, `createdat` \| `created` \| `added`, `updatedat` \| `updated` \| `modified` (case-insensitive). Any other value is ignored and leaves the order untouched |
| `sortDirection` | string | ascending | `desc` (case-insensitive) reverses the comparator. Any other value keeps ascending order. Ignored when `sortBy` is absent or unrecognized |
| `page` | int | `0` | Zero-based page index |
| `size` | int | `50` | Page size (`@PageableDefault(size = 50)`) |

**Ordering when `sortBy` is not supplied**

| `q` | Resulting order |
|---|---|
| present | relevance order from the search query (similarity DESC, then `project_name ASC`) |
| absent | the database's natural order — `findAllByRemovedAtIsNull()` has no `ORDER BY` |

**Response** `200 OK` — standard Spring `Page` envelope; see
[`./01-conventions.md`](./01-conventions.md). `content[]` holds `GuestProjectDTO`:

| Field | Type | Notes |
|---|---|---|
| `id` | number | Database id |
| `projectCode` | string | Business key, unique |
| `projectName` | string | |
| `description` | string | Omitted when null |
| `tags` | string[] | Always present; `[]` when the project has none |
| `keywords` | string[] | Always present; `[]` when the project has none |
| `person` | object | Owning person summary; omitted when the project has no person |
| `person.id` | number | |
| `person.personCode` | string | |
| `person.fullName` | string | |
| `person.nickname` | string | Omitted when null |
| `person.romanizedName` | string | Omitted when null |
| `person.mediaPortrait` | string | Portrait URL; omitted when null |
| `categories` | object[] | Always present; `[]` when the project has none |
| `categories[].id` | number | |
| `categories[].categoryCode` | string | |
| `categories[].name` | string | |
| `mediaCounts` | object | Always present |
| `mediaCounts.audios` | number | Public + active audios in this project |
| `mediaCounts.videos` | number | Public + active videos |
| `mediaCounts.texts` | number | Public + active texts |
| `mediaCounts.images` | number | Public + active images |
| `createdAt` | timestamp | Omitted when null |
| `updatedAt` | timestamp | Omitted when null |
| `trending` | boolean | Always present. The Java field is `isTrending`; the JSON property is `trending` |
| `trendingRank` | number | 1-based rank; present only when the project is in the cached trending snapshot |
| `trendingScore` | number | Decay-weighted score; present only when trending |

Each `mediaCounts.*` value comes from a dedicated `countPublicByProject` query on the matching
repository — `removedAt IS NULL AND (isPublic IS NULL OR isPublic = true)`. Trashed and hidden
media are therefore excluded from the badge counts as well as from the listings.

**Response** `200 OK`

```json
{
  "content": [
    {
      "id": 12,
      "projectCode": "PRJ-HZI-001",
      "projectName": "Hasan Zirak — Radio Recordings",
      "description": "Reel-to-reel radio sessions recovered from the Sulaymaniyah studio archive.",
      "tags": ["radio", "reel-to-reel"],
      "keywords": ["hasan zirak", "maqam"],
      "person": {
        "id": 3,
        "personCode": "HZI",
        "fullName": "حەسەن زیرەک",
        "nickname": "Hasan Zirak",
        "romanizedName": "Hasan Zirak",
        "mediaPortrait": "https://s3.example/khi-archive-platform-folders/persons/HZI.jpg"
      },
      "categories": [
        { "id": 2, "categoryCode": "MUS", "name": "Music" }
      ],
      "mediaCounts": { "audios": 42, "videos": 0, "texts": 3, "images": 11 },
      "createdAt": "2024-11-03T08:41:12Z",
      "updatedAt": "2025-02-18T13:07:55Z",
      "trending": true,
      "trendingRank": 2,
      "trendingScore": 87.0
    },
    {
      "id": 19,
      "projectCode": "PRJ-FLK-004",
      "projectName": "Village Weddings 1978–1984",
      "tags": [],
      "keywords": ["folklore"],
      "categories": [],
      "mediaCounts": { "audios": 0, "videos": 6, "texts": 0, "images": 120 },
      "createdAt": "2025-01-09T10:22:03Z",
      "trending": false
    }
  ],
  "totalElements": 2,
  "totalPages": 1,
  "number": 0,
  "size": 50,
  "numberOfElements": 2,
  "first": true,
  "last": true,
  "empty": false
}
```

The `pageable` object is omitted from the example for brevity — its shape is described in
[`./01-conventions.md`](./01-conventions.md). The second element shows `non_null` inclusion in
action: `description`, `person`, `updatedAt`, `trendingRank` and `trendingScore` are simply absent.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `500` | `DATABASE_ERROR` | A project, count or search query fails (`DataAccessException`) |
| `500` | `INTERNAL_SERVER_ERROR` | Any other unhandled failure |

No `4xx` is reachable here. Every declared parameter is a `String`, a `List<String>` or part of the
`Pageable`, and Spring Data's resolver swallows the `NumberFormatException` rather than raising
`TYPE_MISMATCH` — a non-numeric `page` becomes `0` and a non-numeric `size` falls back to the
`@PageableDefault`. An unknown `sortBy`, an unknown `categoryCode`, or a tag nobody uses is not an
error either — the response is a normally-shaped page, possibly empty.

**Example**

```bash
curl -s "{{BASE_URL}}/api/guest/projects?categoryCode=MUS&tag=radio&sortBy=name&sortDirection=asc&page=0&size=20"
```

**Notes**

- Filtering, sorting and paging all happen in memory in `GuestSearchService.searchProjects`; the
  Spring `sort` request parameter is parsed by the `Pageable` resolver but has **no effect**, since
  the pagination helper only reads `offset` and `pageSize`. Use `sortBy` / `sortDirection`.
- When `q` is present the candidate set is capped at 500 rows *before* filtering and paging, so
  matches beyond that cap are unreachable for that query.
- No view is logged for list calls; only the by-code detail endpoints record guest interactions.

---

### `GET /api/guest/projects/{projectCode}`

Fetch one publicly visible project by its business code.

**Authority:** none (public)

**Path parameters**

| Name | Type | Description |
|---|---|---|
| `projectCode` | string | Exact `projects.project_code` value. Matched by SQL equality, so it is **case-sensitive** |

**Query parameters** — none.

**Response** `200 OK` — a single `GuestProjectDTO`, the same shape as a `content[]` element of
`GET /api/guest/projects`, `mediaCounts` included. The one difference is the trending fields, which
are never populated here — see the note under the example.

```json
{
  "id": 12,
  "projectCode": "PRJ-HZI-001",
  "projectName": "Hasan Zirak — Radio Recordings",
  "description": "Reel-to-reel radio sessions recovered from the Sulaymaniyah studio archive.",
  "tags": ["radio", "reel-to-reel"],
  "keywords": ["hasan zirak", "maqam"],
  "person": {
    "id": 3,
    "personCode": "HZI",
    "fullName": "حەسەن زیرەک",
    "nickname": "Hasan Zirak",
    "romanizedName": "Hasan Zirak",
    "mediaPortrait": "https://s3.example/khi-archive-platform-folders/persons/HZI.jpg"
  },
  "categories": [
    { "id": 2, "categoryCode": "MUS", "name": "Music" }
  ],
  "mediaCounts": { "audios": 42, "videos": 0, "texts": 3, "images": 11 },
  "createdAt": "2024-11-03T08:41:12Z",
  "updatedAt": "2025-02-18T13:07:55Z",
  "trending": false
}
```

The by-code path never consults the trending snapshot — only `stampPage` does, and that
runs on the paged listings — so this endpoint always reports `trending: false` and always
omits `trendingRank` and `trendingScore`. Use the list endpoints (or `/api/guest/trending`)
for trending metadata.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `404` | _no body_ | No project with that code, or it is trashed (`removedAt` set), or `isVisibleToPublic = false` |
| `500` | `DATABASE_ERROR` | The project or count query fails (`DataAccessException`) |
| `500` | `INTERNAL_SERVER_ERROR` | Any other unhandled failure |

The 404 produced here is `ResponseEntity.notFound().build()` — an empty body, **not** the
`ApiErrorResponse` envelope that `ApiExceptionHandler` returns for thrown exceptions.

**Example**

```bash
curl -s "{{BASE_URL}}/api/guest/projects/PRJ-HZI-001"
```

**Notes** — a successful call fires `GuestTrendingService.logView("project", projectCode)` on the
`trendingLogExecutor` pool (`@Async`, fire-and-forget), which feeds the trending computation. A
failed lookup logs nothing.

---

### `GET /api/guest/projects/{projectCode}/media`

Every publicly visible media record inside one project, grouped by kind. Not paginated.

**Authority:** none (public)

**Path parameters**

| Name | Type | Description |
|---|---|---|
| `projectCode` | string | Exact, case-sensitive project code |

**Query parameters**

| Name | Type | Default | Description |
|---|---|---|---|
| `type` | string | all four kinds | Restrict the response to one media kind. Accepted (case-insensitive): `all`, `audio`, `audios`, `video`, `videos`, `text`, `texts`, `image`, `images`. Absent or blank behaves like `all` |

An unrecognized `type` (for example `type=maqam`) is **not** an error and does not fall back to
`all`: the response contains only `projectCode` and `projectName`, with no media arrays at all.

**Response** `200 OK` — a plain JSON object (a `LinkedHashMap`, so key order is stable), not a
`Page`:

| Key | Type | Notes |
|---|---|---|
| `projectCode` | string | Always present |
| `projectName` | string | Always present |
| `audios` | object[] | Present when `type` selects audio; `GuestAudioDTO` elements |
| `videos` | object[] | Present when `type` selects video; `GuestVideoDTO` elements |
| `texts` | object[] | Present when `type` selects text; `GuestTextDTO` elements |
| `images` | object[] | Present when `type` selects image; `GuestImageDTO` elements |

Keys always appear in the order above. A selected kind with no visible rows serializes as an empty
array (`[]`), which is how you distinguish "kind not requested" (key absent) from "kind requested,
nothing public" (key present, empty).

The array elements are the **full** media DTOs — field-for-field the same shapes returned by
`GET /api/guest/audios/{audioCode}`, `/videos/{videoCode}`, `/texts/{textCode}` and
`/images/{imageCode}`, including the proxy URLs (`audioFileUrl`, `videoFileUrl`, `textFileUrl`,
`coverImageUrl`, `imageFileUrl`). No S3 URL for the media bytes is ever exposed; every one of those
fields points back at a `/api/guest/...` proxy path (`coverImageUrl` is the one exception to
"always present": `GuestMapper` leaves it null when the text has no stored cover). The per-media
field tables are documented with their own endpoints — see [`./06-media.md`](./06-media.md).

**Response** `200 OK` (abridged — each media object carries its full field set)

```json
{
  "projectCode": "PRJ-HZI-001",
  "projectName": "Hasan Zirak — Radio Recordings",
  "audios": [
    {
      "id": 501,
      "audioCode": "AUD-042",
      "projectCode": "PRJ-HZI-001",
      "projectName": "Hasan Zirak — Radio Recordings",
      "person": {
        "id": 3,
        "personCode": "HZI",
        "fullName": "حەسەن زیرەک",
        "romanizedName": "Hasan Zirak"
      },
      "categories": [
        { "id": 2, "categoryCode": "MUS", "name": "Music" }
      ],
      "originTitle": "گوڵی باخ",
      "romanizedTitle": "Gulî Bax",
      "typeOfMaqam": "Bayat",
      "subject": ["love song"],
      "genre": ["folk"],
      "singer": "Hasan Zirak",
      "language": "Kurdish",
      "dialect": "Sorani",
      "tags": ["radio"],
      "keywords": ["maqam"],
      "duration": "00:04:12",
      "dateCreated": "1972-05-01T00:00:00Z",
      "audioFileUrl": "/api/guest/audio/AUD-042/stream",
      "trending": false
    }
  ],
  "videos": [],
  "texts": [],
  "images": [
    {
      "id": 880,
      "imageCode": "IMG-1104",
      "projectCode": "PRJ-HZI-001",
      "projectName": "Hasan Zirak — Radio Recordings",
      "originalTitle": "Studio session, Sulaymaniyah",
      "subject": ["portrait"],
      "genre": [],
      "colorOfImage": ["black and white"],
      "tags": [],
      "keywords": [],
      "whereThisImageUsed": [],
      "dateCreated": "1972-05-01T00:00:00Z",
      "imageFileUrl": "/api/guest/image/IMG-1104/view",
      "trending": false
    }
  ]
}
```

**Errors**

| Status | `error` code | When |
|---|---|---|
| `404` | _no body_ | No project with that code, or it is trashed, or `isVisibleToPublic = false` |
| `500` | `DATABASE_ERROR` | One of the four per-kind reads fails (`DataAccessException`) |
| `500` | `INTERNAL_SERVER_ERROR` | Any other unhandled failure |

**Example**

```bash
# Everything in the project
curl -s "{{BASE_URL}}/api/guest/projects/PRJ-HZI-001/media"

# Just the images
curl -s "{{BASE_URL}}/api/guest/projects/PRJ-HZI-001/media?type=image"
```

**Notes**

- Every returned media row passes `isPubliclyVisible`: `removedAt IS NULL`, `isPublic` not `false`,
  and the parent project publicly visible. The project-level check has already succeeded by the
  time the arrays are built.
- No pagination and no cap: a project with thousands of images returns all of them in one payload.
  Prefer the per-kind endpoints (or `/api/guest/feed`) with `projectCode` when you need paging.
- This endpoint does not stamp trending metadata, so every media object here reports
  `trending: false` and omits `trendingRank` / `trendingScore`.
- No view is logged for this call.

---

### `GET /api/guest/categories`

Paged list of active categories.

**Authority:** none (public)

**Query parameters**

| Name | Type | Default | Description |
|---|---|---|---|
| `q` | string | — | Free text. Matched by `CategoryRepository.searchByText` against `name`, `description`, `category_code` and `category_keywords.keyword` — substring `LIKE` plus `pg_trgm` similarity above `0.2` — ordered by best similarity, then `name ASC`, capped at 500 rows |
| `page` | int | `0` | Zero-based page index |
| `size` | int | `100` | Page size (`@PageableDefault(size = 100)`) |

There is no `sortBy` / `sortDirection` on this endpoint. When `q` is absent the full active set is
sorted by `name`, case-insensitive, nulls last; when `q` is present the search relevance order is
kept as-is.

**Response** `200 OK` — standard `Page` envelope; `content[]` holds `GuestCategoryDTO`:

| Field | Type | Notes |
|---|---|---|
| `id` | number | Database id |
| `categoryCode` | string | Business key, unique |
| `name` | string | |
| `description` | string | Omitted when null |
| `keywords` | string[] | Always present; `[]` when the category has none |
| `projectCount` | number | Always present. Active **and** publicly visible projects filed under this category (`countPublicByCategory`) |
| `createdAt` | timestamp | Omitted when null |
| `trending` | boolean | Always present; JSON name is `trending`, Java field is `isTrending` |
| `trendingRank` | number | Present only when the category is in the trending snapshot |
| `trendingScore` | number | Present only when trending |

`updatedAt` exists on the entity but is deliberately **not** part of the guest category shape.

```json
{
  "content": [
    {
      "id": 2,
      "categoryCode": "MUS",
      "name": "Music",
      "description": "Songs, maqam, instrumental and radio recordings.",
      "keywords": ["music", "maqam", "song"],
      "projectCount": 37,
      "createdAt": "2024-06-14T09:00:00Z",
      "trending": true,
      "trendingRank": 5,
      "trendingScore": 41.0
    },
    {
      "id": 7,
      "categoryCode": "ORAL",
      "name": "Oral History",
      "keywords": [],
      "projectCount": 0,
      "createdAt": "2024-06-14T09:00:00Z",
      "trending": false
    }
  ],
  "totalElements": 2,
  "totalPages": 1,
  "number": 0,
  "size": 100,
  "numberOfElements": 2,
  "first": true,
  "last": true,
  "empty": false
}
```

**Errors**

| Status | `error` code | When |
|---|---|---|
| `500` | `DATABASE_ERROR` | The category or count query fails (`DataAccessException`) |
| `500` | `INTERNAL_SERVER_ERROR` | Any other unhandled failure |

As on `GET /api/guest/projects`, no `4xx` is reachable: `q` is a `String` and `page` / `size` fall
back to their defaults rather than being rejected.

**Example**

```bash
curl -s "{{BASE_URL}}/api/guest/categories?q=music&page=0&size=25"
```

**Notes** — the Spring `sort` parameter is accepted by the resolver but ignored; ordering is fixed
as described above.

---

### `GET /api/guest/categories/{categoryCode}`

Fetch one active category by its code.

**Authority:** none (public)

**Path parameters**

| Name | Type | Description |
|---|---|---|
| `categoryCode` | string | Exact `categories.category_code` value; case-sensitive |

**Query parameters** — none.

**Response** `200 OK` — a single `GuestCategoryDTO` (same fields as the list `content[]` element).

```json
{
  "id": 2,
  "categoryCode": "MUS",
  "name": "Music",
  "description": "Songs, maqam, instrumental and radio recordings.",
  "keywords": ["music", "maqam", "song"],
  "projectCount": 37,
  "createdAt": "2024-06-14T09:00:00Z",
  "trending": false
}
```

As with the project detail endpoint, the by-code path does not consult the trending snapshot, so
`trending` is always `false` here and `trendingRank` / `trendingScore` are always omitted.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `404` | _no body_ | No category with that code, or it is trashed (`removedAt` set) |
| `500` | `DATABASE_ERROR` | The category or count query fails (`DataAccessException`) |
| `500` | `INTERNAL_SERVER_ERROR` | Any other unhandled failure |

Categories carry no visibility flag, so trash is the only reason a known code returns 404.

**Example**

```bash
curl -s "{{BASE_URL}}/api/guest/categories/MUS"
```

**Notes** — a successful call fires `logView("category", categoryCode)` asynchronously.

---

### `GET /api/guest/categories/{categoryCode}/projects`

Publicly visible projects filed under one category.

**Authority:** none (public)

**Path parameters**

| Name | Type | Description |
|---|---|---|
| `categoryCode` | string | Exact, case-sensitive category code |

**Query parameters**

| Name | Type | Default | Description |
|---|---|---|---|
| `page` | int | `0` | Zero-based page index |
| `size` | int | `50` | Page size (`@PageableDefault(size = 50)`) |

No `q`, no filters, no sort parameters. Results are always ordered by `projectName`,
case-insensitive, nulls last, ascending.

**Response** `200 OK` — standard `Page` envelope whose `content[]` holds `GuestProjectDTO`,
exactly the shape documented under [`GET /api/guest/projects`](#get-apiguestprojects),
trending stamp included.

```json
{
  "content": [
    {
      "id": 12,
      "projectCode": "PRJ-HZI-001",
      "projectName": "Hasan Zirak — Radio Recordings",
      "tags": ["radio", "reel-to-reel"],
      "keywords": ["hasan zirak", "maqam"],
      "person": {
        "id": 3,
        "personCode": "HZI",
        "fullName": "حەسەن زیرەک",
        "romanizedName": "Hasan Zirak"
      },
      "categories": [
        { "id": 2, "categoryCode": "MUS", "name": "Music" }
      ],
      "mediaCounts": { "audios": 42, "videos": 0, "texts": 3, "images": 11 },
      "createdAt": "2024-11-03T08:41:12Z",
      "trending": false
    }
  ],
  "totalElements": 1,
  "totalPages": 1,
  "number": 0,
  "size": 50,
  "numberOfElements": 1,
  "first": true,
  "last": true,
  "empty": false
}
```

**Errors**

| Status | `error` code | When |
|---|---|---|
| `500` | `DATABASE_ERROR` | The category, project or count query fails (`DataAccessException`) |
| `500` | `INTERNAL_SERVER_ERROR` | Any other unhandled failure |

This endpoint never returns 404. An unknown or trashed `categoryCode` yields
`Page.empty(pageable)` — `200 OK`, `content: []`, `totalElements: 0`, `empty: true`. `page` and
`size` are the only parameters and neither can be rejected, so no `4xx` is reachable.

**Example**

```bash
curl -s "{{BASE_URL}}/api/guest/categories/MUS/projects?page=0&size=20"
```

**Notes** — matching is by category **id** after resolving the code, so a project qualifies if the
resolved category appears anywhere in its `categories` list. Hidden and trashed projects are
excluded before paging, so `totalElements` reflects only what a guest may see. No view is logged.

---

### `GET /api/guest/persons`

Paged list of active persons, with optional free-text search and structured filters.

**Authority:** none (public)

**Query parameters**

| Name | Type | Default | Description |
|---|---|---|---|
| `q` | string | — | Free text. Matched by `PersonRepository.searchByText` against `full_name`, `nickname`, `romanized_name`, `description`, `tag`, `keywords`, `region`, `place_of_birth`, `place_of_death`, `person_code` and `person_person_type.person_type` — substring `LIKE` plus `pg_trgm` similarity above `0.2` on the three name columns — ordered by best similarity, then `full_name ASC`, capped at 500 rows |
| `region` | string | — | Exact match on the person's `region`, case-insensitive |
| `gender` | enum | — | `MALE` or `FEMALE`. Bound directly to the `Gender` enum, so any other value is a binding failure |
| `personType` | string, repeatable | — | Keep persons having **any** of the given types. Whole-value match, case-insensitive, trimmed — no comma splitting |
| `page` | int | `0` | Zero-based page index |
| `size` | int | `50` | Page size (`@PageableDefault(size = 50)`) |

There is no `sortBy` / `sortDirection`. When `q` is absent the filtered set is sorted by
`fullName`, case-insensitive, nulls last; when `q` is present the search relevance order is kept.

**Response** `200 OK` — standard `Page` envelope; `content[]` holds `GuestPersonDTO`:

| Field | Type | Notes |
|---|---|---|
| `id` | number | Database id |
| `personCode` | string | Business key, unique |
| `mediaPortrait` | string | Portrait URL stored on the person record; omitted when null |
| `fullName` | string | |
| `nickname` | string | Omitted when null |
| `romanizedName` | string | Omitted when null |
| `gender` | string | `MALE` \| `FEMALE`; omitted when null |
| `personType` | string[] | Always present; `[]` when the person has none |
| `region` | string | Omitted when null |
| `dateOfBirth` | date | ISO `yyyy-MM-dd`; omitted when null |
| `dateOfBirthPrecision` | string | `FULL` \| `MONTH_ONLY` \| `YEAR_ONLY`; omitted when null |
| `placeOfBirth` | string | Omitted when null |
| `dateOfDeath` | date | ISO `yyyy-MM-dd`; omitted when null |
| `dateOfDeathPrecision` | string | `FULL` \| `MONTH_ONLY` \| `YEAR_ONLY`; omitted when null |
| `placeOfDeath` | string | Omitted when null |
| `description` | string | Omitted when null |
| `projectCount` | number | Always present. Active **and** publicly visible projects owned by this person (`countPublicByPerson`) |
| `trending` | boolean | Always present; JSON name is `trending`, Java field is `isTrending` |
| `trendingRank` | number | Present only when the person is in the trending snapshot |
| `trendingScore` | number | Present only when trending |

The precision enums exist because many archival birth/death dates are only known to the month or
year; the `dateOfBirth` / `dateOfDeath` values are still full ISO dates, and the precision field
tells you how much of them to display.

```json
{
  "content": [
    {
      "id": 3,
      "personCode": "HZI",
      "mediaPortrait": "https://s3.example/khi-archive-platform-folders/persons/HZI.jpg",
      "fullName": "حەسەن زیرەک",
      "nickname": "Hasan Zirak",
      "romanizedName": "Hasan Zirak",
      "gender": "MALE",
      "personType": ["singer", "performer"],
      "region": "Sulaymaniyah",
      "dateOfBirth": "1921-01-01",
      "dateOfBirthPrecision": "YEAR_ONLY",
      "placeOfBirth": "Bokan",
      "dateOfDeath": "1972-06-15",
      "dateOfDeathPrecision": "FULL",
      "placeOfDeath": "Sanandaj",
      "description": "Kurdish singer known for radio recordings of folk maqam.",
      "projectCount": 4,
      "trending": true,
      "trendingRank": 1,
      "trendingScore": 132.0
    },
    {
      "id": 21,
      "personCode": "AMA",
      "fullName": "Amina Ahmad",
      "personType": [],
      "projectCount": 0,
      "trending": false
    }
  ],
  "totalElements": 2,
  "totalPages": 1,
  "number": 0,
  "size": 50,
  "numberOfElements": 2,
  "first": true,
  "last": true,
  "empty": false
}
```

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `TYPE_MISMATCH` | `gender` is present but is not `MALE` or `FEMALE`. The `details` object carries `parameter`, `rejectedValue` and `expectedType` |
| `500` | `DATABASE_ERROR` | The person or count query fails (`DataAccessException`) |
| `500` | `INTERNAL_SERVER_ERROR` | Any other unhandled failure |

`gender` is the only parameter on any catalog endpoint that can produce a `400`: it is the only one
bound to a non-`String` type. `region`, `personType` and `q` accept anything, and `page` / `size`
fall back to their defaults instead of being rejected.

Example 400 body (produced by `ApiExceptionHandler.handleTypeMismatch`):

```json
{
  "timestamp": "2026-08-26T11:04:31Z",
  "status": 400,
  "error": "TYPE_MISMATCH",
  "category": "BAD_REQUEST",
  "message": "Parameter 'gender' has the wrong type.",
  "hint": "Pass 'gender' as Gender.",
  "path": "/api/guest/persons",
  "details": {
    "parameter": "gender",
    "rejectedValue": "other",
    "expectedType": "Gender"
  }
}
```

**Example**

```bash
curl -s "{{BASE_URL}}/api/guest/persons?q=zirak&region=Sulaymaniyah&gender=MALE&personType=singer&page=0&size=20"
```

**Notes** — persons have no visibility flag, so every non-trashed person is listable regardless of
whether any of their projects is public. `projectCount` is the honest public number and can be `0`.

---

### `GET /api/guest/persons/{personCode}`

Fetch one active person by their code.

**Authority:** none (public)

**Path parameters**

| Name | Type | Description |
|---|---|---|
| `personCode` | string | Exact `person.person_code` value; case-sensitive |

**Query parameters** — none.

**Response** `200 OK` — a single `GuestPersonDTO` (same fields as the list `content[]` element).

```json
{
  "id": 3,
  "personCode": "HZI",
  "mediaPortrait": "https://s3.example/khi-archive-platform-folders/persons/HZI.jpg",
  "fullName": "حەسەن زیرەک",
  "nickname": "Hasan Zirak",
  "romanizedName": "Hasan Zirak",
  "gender": "MALE",
  "personType": ["singer", "performer"],
  "region": "Sulaymaniyah",
  "dateOfBirth": "1921-01-01",
  "dateOfBirthPrecision": "YEAR_ONLY",
  "placeOfBirth": "Bokan",
  "description": "Kurdish singer known for radio recordings of folk maqam.",
  "projectCount": 4,
  "trending": false
}
```

As with the other by-code endpoints, `trending` is always `false` here and `trendingRank` /
`trendingScore` are always omitted.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `404` | _no body_ | No person with that code, or the person is trashed (`removedAt` set) |
| `500` | `DATABASE_ERROR` | The person or count query fails (`DataAccessException`) |
| `500` | `INTERNAL_SERVER_ERROR` | Any other unhandled failure |

**Example**

```bash
curl -s "{{BASE_URL}}/api/guest/persons/HZI"
```

**Notes** — a successful call fires `logView("person", personCode)` asynchronously.

---

### `GET /api/guest/persons/{personCode}/projects`

Publicly visible projects owned by one person.

**Authority:** none (public)

**Path parameters**

| Name | Type | Description |
|---|---|---|
| `personCode` | string | Exact, case-sensitive person code |

**Query parameters**

| Name | Type | Default | Description |
|---|---|---|---|
| `page` | int | `0` | Zero-based page index |
| `size` | int | `50` | Page size (`@PageableDefault(size = 50)`) |

No `q`, no filters, no sort parameters. Results are always ordered by `projectName`,
case-insensitive, nulls last, ascending.

**Response** `200 OK` — standard `Page` envelope whose `content[]` holds `GuestProjectDTO`, the
shape documented under [`GET /api/guest/projects`](#get-apiguestprojects), trending stamp included.

```json
{
  "content": [
    {
      "id": 12,
      "projectCode": "PRJ-HZI-001",
      "projectName": "Hasan Zirak — Radio Recordings",
      "tags": ["radio", "reel-to-reel"],
      "keywords": ["hasan zirak", "maqam"],
      "person": {
        "id": 3,
        "personCode": "HZI",
        "fullName": "حەسەن زیرەک",
        "nickname": "Hasan Zirak",
        "romanizedName": "Hasan Zirak",
        "mediaPortrait": "https://s3.example/khi-archive-platform-folders/persons/HZI.jpg"
      },
      "categories": [
        { "id": 2, "categoryCode": "MUS", "name": "Music" }
      ],
      "mediaCounts": { "audios": 42, "videos": 0, "texts": 3, "images": 11 },
      "createdAt": "2024-11-03T08:41:12Z",
      "updatedAt": "2025-02-18T13:07:55Z",
      "trending": false
    }
  ],
  "totalElements": 1,
  "totalPages": 1,
  "number": 0,
  "size": 50,
  "numberOfElements": 1,
  "first": true,
  "last": true,
  "empty": false
}
```

**Errors**

| Status | `error` code | When |
|---|---|---|
| `500` | `DATABASE_ERROR` | The person, project or count query fails (`DataAccessException`) |
| `500` | `INTERNAL_SERVER_ERROR` | Any other unhandled failure |

Like the category variant, this endpoint never returns 404: an unknown or trashed `personCode`
yields `Page.empty(pageable)` — `200 OK` with an empty page. `page` and `size` are the only
parameters and neither can be rejected, so no `4xx` is reachable.

**Example**

```bash
curl -s "{{BASE_URL}}/api/guest/persons/HZI/projects?page=0&size=20"
```

**Notes** — a project belongs to exactly one person (`Project.person`, a `@ManyToOne`), so this is
a straight ownership lookup, not a contributor search. Hidden and trashed projects are excluded
before paging. No view is logged.

## Cross-cutting behavior

| Topic | Behavior |
|---|---|
| Null fields | `spring.jackson.default-property-inclusion=non_null` — every null field is omitted from the response. Primitives are not null, so `trending`, `projectCount` and the four `mediaCounts` values always appear |
| Empty collections | The mappers substitute `List.of()` for a null collection, so `tags`, `keywords`, `categories` and `personType` are always present, possibly as `[]` |
| Timestamps | `createdAt`, `updatedAt` are `java.time.Instant` and serialize as ISO-8601. `dateOfBirth` / `dateOfDeath` are `java.time.LocalDate` and serialize as `yyyy-MM-dd`. See [`./01-conventions.md`](./01-conventions.md) |
| Paging | All list endpoints return the standard Spring `Page` envelope. Sorting is performed in memory, so the Spring `sort` parameter has no effect anywhere in this document |
| Caching | These endpoints are not `@Cacheable`. Only the trending snapshot (`trending:snapshot`) and the trending payload (`trending:results`) are cached, for 5 minutes each, in Caffeine |
| Interaction logging | Of the nine endpoints here, only the three by-code detail ones (`projects/{projectCode}`, `categories/{categoryCode}`, `persons/{personCode}`) write a `GuestInteractionLog` row, asynchronously and best-effort; a failed write is swallowed and never affects the response |
| CORS | Governed by `app.cors.*`; `OPTIONS /**` is permitted for everyone |

## Related

- [`./README.md`](./README.md) — index of the external (public) API documentation
- [`./00-overview.md`](./00-overview.md) — the external surface and the no-token endpoint list
- [`./01-conventions.md`](./01-conventions.md) — the `Page` envelope, the `ApiErrorResponse`
  error envelope, timestamp formats, and the `{{BASE_URL}}` convention used in every example here
- [`./02-errors.md`](./02-errors.md) — the error envelope and the full `ErrorCode` set
- [`./06-media.md`](./06-media.md) — the four media DTOs in full, whose shapes appear inside
  `GET /api/guest/projects/{projectCode}/media`
- [`./04-discovery.md`](./04-discovery.md) — `/search`, `/suggest`, `/facets`, `/trending`
  and the grouped `/feed`
