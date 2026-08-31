# Public Media — Audios, Videos, Texts, Images

> **Audience:** anonymous visitors / public website / third-party clients ·
> **Base path:** `/api/guest` ·
> **Source:** `src/main/java/ak/dev/khi_archive_platform/platform/api/guest/GuestSearchAPI.java`

The four public media catalogs. Each media kind has a paged, heavily filterable list endpoint and a
by-code detail endpoint. Responses are `Guest…DTO` shapes that carry descriptive metadata only —
technical fields (path/volume/directory, LCC classification, bit-rate, bit-depth, sample-rate,
resolution, frame rate, file size, version internals) and audit/trash bookkeeping (`createdBy`,
`removedAt`, `version`) are stripped by `GuestMapper`. The playback/view field on every DTO is a
**relative API path**, never an S3 URL — see [Media streaming](./07-streaming.md).

## Access

| Requirement | Value |
|---|---|
| Authentication | not required |
| Authority | none — `GuestSearchAPI` carries no `@PreAuthorize`, on the class or on any method |
| Roles that hold it by default | n/a — `SecurityConfig` permits `/api/guest/**` for everyone |

`SecurityConfig` declares `.requestMatchers("/api/guest/**").permitAll()`, and
`JWTAuthenticationFilter.shouldNotFilter` skips every URI starting with `/api/guest/` outright.
Sending a `khi_auth_token` cookie is therefore harmless — it is never parsed, so even an expired or
revoked token cannot turn one of these calls into a `401`, and it changes nothing about the
response.

## Endpoints

| Method | Path | Authority | Purpose |
|---|---|---|---|
| `GET` | `/api/guest/audios` | none | Paged, filtered list of public audios |
| `GET` | `/api/guest/audios/{audioCode}` | none | One public audio by code |
| `GET` | `/api/guest/videos` | none | Paged, filtered list of public videos |
| `GET` | `/api/guest/videos/{videoCode}` | none | One public video by code |
| `GET` | `/api/guest/texts` | none | Paged, filtered list of public texts/documents |
| `GET` | `/api/guest/texts/{textCode}` | none | One public text by code |
| `GET` | `/api/guest/images` | none | Paged, filtered list of public images |
| `GET` | `/api/guest/images/{imageCode}` | none | One public image by code |

All eight are declared with `@GetMapping` on a controller annotated `@RequestMapping("/api/guest")`.
`GuestSearchAPI` also serves `/trending`, `/search`, `/suggest`, `/facets` and `/feed` — documented
in [`./04-discovery.md`](./04-discovery.md) — and `/projects`, `/categories` and `/persons`,
documented in [`./05-catalog.md`](./05-catalog.md).

---

## Shared behavior

Everything in this section applies identically to all four media kinds. The per-kind sections below
only document what differs.

### Visibility gate

A record reaches the public API only when **all** of the following hold
(`GuestSearchService.isPubliclyVisible`):

| Condition | Meaning |
|---|---|
| `removedAt IS NULL` on the media row | not in the trash |
| media `isPublic` is not `false` (`null` counts as public) | not individually hidden by staff |
| owning project is non-null, `removedAt IS NULL` | parent collection is not trashed |
| owning project `isVisibleToPublic` is not `false` (`null` counts as visible) | parent is public |

Media whose `project` is `null` fails the gate and is never returned. Records that fail the gate are
simply absent from lists; requesting one by code returns `404` (see [Errors](#errors)).

### Paging

The list endpoints resolve a Spring `Pageable` annotated `@PageableDefault(size = 50)`.

| Name | Type | Default | Description |
|---|---|---|---|
| `page` | int | `0` | Zero-based page index |
| `size` | int | `50` | Page size |
| `sort` | string | — | Accepted by the Pageable resolver and echoed back in the `pageable` block of the envelope (and again at the top level), but it does **not** order the results. Ordering comes from `sortBy` / `sortDirection` below. |

Responses use the standard Spring `Page` envelope (`content`, `pageable`, `totalElements`,
`totalPages`, `number`, `size`, `first`, `last`, `numberOfElements`, `empty`) — see
[`./01-conventions.md`](./01-conventions.md). Filtering and paging happen in memory over the
candidate set, so `totalElements` counts every match after the visibility gate and all filters.

### Sorting

| Name | Type | Default | Description |
|---|---|---|---|
| `sortBy` | string | — | Sort key, case-insensitive. Accepted values are listed per media kind below. An unrecognized or blank value applies **no** comparator at all. |
| `sortDirection` | string | — | `desc` (case-insensitive) reverses the comparator. Every other value — including `asc`, garbage, and omission — leaves it ascending. Ignored when `sortBy` produced no comparator. |

With no usable `sortBy`, no comparator runs at all and the result order is the order the candidate
set arrived in: the native query's relevance ranking when `q` is present, otherwise whatever order
`findAllByRemovedAtIsNull()` returns — that derived query carries no `ORDER BY`, so no default order
is guaranteed anywhere in source. Do not rely on it; pass `sortBy` whenever order matters.

### Free-text search (`q`)

`q` is trimmed; blank or absent means "no keyword phase".

* **With `q`** — a two-phase native PostgreSQL query runs against the media table: a candidate CTE
  built from prefix `LIKE`, substring `LIKE` and `pg_trgm`'s `%` operator across the entity's text
  columns and its child collection tables, then a ranked `SELECT` over those candidates. The child
  tables searched differ per kind: audio uses genres, subjects, contributors, tags and keywords;
  video and image use subjects, genres, colors, usages, tags and keywords; text uses subjects,
  genres, tags and keywords. Rows are ranked by best match tier (prefix hit, then substring hit,
  then trigram similarity). The candidate set is prefiltered to 5000 rows and capped at **500**
  ranked hits. The `%` operator uses PostgreSQL's session `pg_trgm.similarity_threshold` — the
  application never calls `set_limit`, so the server default (`0.3`) applies.
* **Scope widening** — every project whose name, code, description, tags or keywords match `q`,
  plus every project owned by a person whose name, code, description, region, places, tag or
  keywords match `q`, is resolved, and all active media in those projects is merged into the
  candidate set. Both lookups use a `similarity(...) > 0.2` threshold
  (`GuestSearchService.SIMILARITY_THRESHOLD`) and are each capped at 500 rows. This is what makes a
  search for a performer's name surface their recordings even when the recording titles do not
  contain it.
* **Without `q`** — the candidate set is the full active table, filtered in memory.

Because of the 500-hit cap, a `q` search is not an exhaustive count of the archive.

### Date range parameters

`dateFrom` / `dateTo`, `publishedFrom` / `publishedTo` and (texts only) `printDateFrom` /
`printDateTo` are strings parsed leniently by the controller:

| Input form | Example | Interpreted as |
|---|---|---|
| ISO instant | `2020-01-01T00:00:00Z` | that instant |
| ISO local date-time | `2020-01-01T00:00:00` | that instant, treated as UTC |
| ISO date, `…From` | `2020-01-01` | start of day, UTC |
| ISO date, `…To` | `2020-01-01` | last nanosecond of that day, UTC (range stays inclusive) |
| blank or unparseable | `yesterday` | treated as absent — **no `400`**, the filter is simply dropped |

Bounds are inclusive on both ends. When either bound of a pair is supplied, records whose target
timestamp is `null` are **excluded**.

### Filter matching semantics

Three matching modes are used; the per-kind tables say which applies to each parameter.

| Mode | Behavior |
|---|---|
| exact | Case-insensitive full-string equality. The supplied value is trimmed and lower-cased; the stored value is only lower-cased, so a stored value with surrounding whitespace will not match |
| contains | Case-insensitive substring match, supplied value trimmed and lower-cased |
| any-of | Repeatable parameter; matches when **any** supplied value equals **any** element of the record's collection, case-insensitively. Collection elements are trimmed before comparison |

Repeatable parameters are repeated in the query string (`tag=studio&tag=reel`). A **single**
occurrence carrying commas is split by Spring's `StringToCollectionConverter`, so `tag=studio,reel`
is two values, not the literal `studio,reel`. When the parameter appears more than once the values
are taken literally and no splitting happens — mixing both forms (`tag=studio,reel&tag=cassette`)
yields the literal values `studio,reel` and `cassette`. A value containing a comma can therefore
only be sent by repeating the parameter.

`projectCode`, `categoryCode` and `personCode` are matched against the media row's **owning
project**: `projectCode` against the project code, `personCode` against the project's person code,
`categoryCode` against any of the project's category codes. All three are exact and
case-insensitive.

### Trending stamping

After a list page is built, `GuestSearchService.stampPage` looks each item's code up in the cached
trending snapshot. On a hit it sets `trending: true` plus `trendingRank` and `trendingScore`.

* List endpoints: items may carry a rank/score.
* Detail endpoints: **never** stamped — `trending` is always `false` and `trendingRank` /
  `trendingScore` are always absent.

The Java field is named `isTrending`, but the Lombok accessors are `isTrending()` / `setTrending(…)`,
so the JSON key is **`trending`**. It is a primitive `boolean` and is therefore always present, even
though `non_null` inclusion drops the two `null` companions.

### Detail endpoints record a view

`GET /api/guest/{kind}s/{code}` calls `GuestTrendingService.logView(kind, code)` on every successful
lookup. That counter feeds `GET /api/guest/trending`. List endpoints do not log views.

### Null handling in responses

`spring.jackson.default-property-inclusion=non_null` — `null` fields are omitted entirely. Note the
difference for collections: `GuestMapper.copyList` maps a `null` or empty collection to an **empty
array**, so `subject`, `genre`, `tags`, `keywords`, `contributors` (audio), `colorOfVideo`,
`colorOfImage`, `whereThisVideoUsed` and `whereThisImageUsed` are always present, even when empty.
`categories` behaves the same way through `GuestMapper.projectCategories`, which returns an empty
list when there is no owning project or the project has no categories. `person` is omitted when the
media row has no owning project.

Timestamps are `java.time.Instant` values serialized as ISO-8601 by the auto-configured Jackson 3
response mapper, never as epoch numbers — see [`./01-conventions.md`](./01-conventions.md).

### Nested object shapes

Shared by all four DTOs.

`person` — `GuestPersonSummaryDTO`:

| Field | Type | Description |
|---|---|---|
| `id` | integer | Person row id |
| `personCode` | string | Public person code |
| `fullName` | string | Full name |
| `nickname` | string | Nickname |
| `romanizedName` | string | Romanized name |
| `mediaPortrait` | string | Portrait URL, stored absolute (S3 or external source) — this is a stored URL, not a proxy path |

`categories[]` — `GuestCategorySummaryDTO`:

| Field | Type | Description |
|---|---|---|
| `id` | integer | Category row id |
| `categoryCode` | string | Public category code |
| `name` | string | Category name |

---

## Audios

### `GET /api/guest/audios`

Paged, filtered list of publicly visible audio records.

**Authority:** none (public)

**Query parameters**

Common filters:

| Name | Type | Default | Match | Description |
|---|---|---|---|---|
| `q` | string | — | search | Free-text keyword search (see [Free-text search](#free-text-search-q)) |
| `projectCode` | string | — | exact | Owning project code |
| `categoryCode` | string | — | exact | Any category code on the owning project |
| `personCode` | string | — | exact | Person code on the owning project |
| `language` | string | — | exact | `language` |
| `dialect` | string | — | exact | `dialect` |
| `region` | string | — | exact | `region` |
| `subject` | string, repeatable | — | any-of | `subject` collection |
| `genre` | string, repeatable | — | any-of | `genre` collection |
| `tag` | string, repeatable | — | any-of | `tags` collection |
| `keyword` | string, repeatable | — | any-of | `keywords` collection |
| `dateFrom` | date/date-time | — | range | Inclusive lower bound on `dateCreated` |
| `dateTo` | date/date-time | — | range | Inclusive upper bound on `dateCreated` |
| `publishedFrom` | date/date-time | — | range | Inclusive lower bound on `datePublished` |
| `publishedTo` | date/date-time | — | range | Inclusive upper bound on `datePublished` |

Audio-specific filters:

| Name | Type | Default | Match | Description |
|---|---|---|---|---|
| `singer` | string | — | contains | `singer` |
| `speaker` | string | — | contains | `speaker` |
| `poet` | string | — | contains | `poet` |
| `composer` | string | — | contains | `composer` |
| `producer` | string | — | contains | `producer` |
| `contributor` | string, repeatable | — | any-of | `contributors` collection |
| `form` | string | — | exact | `form` |
| `lyrics` | string | — | contains | `lyrics` |
| `typeOfBasta` | string | — | exact | `typeOfBasta` |
| `typeOfMaqam` | string | — | exact | `typeOfMaqam` |
| `typeOfComposition` | string | — | exact | `typeOfComposition` |
| `typeOfPerformance` | string | — | exact | `typeOfPerformance` |
| `recordingVenue` | string | — | contains | `recordingVenue` |
| `city` | string | — | exact | `city` |
| `audience` | string | — | exact | `audience` |

Sorting and paging:

| Name | Type | Default | Description |
|---|---|---|---|
| `sortBy` | string | — | One of `title` \| `name` \| `alpha` \| `alphabet` \| `origintitle` (by `originTitle`), `code` \| `audiocode` (by `audioCode`), `date` \| `datecreated` (by `dateCreated`), `published` \| `datepublished` (by `datePublished`), `createdat` \| `created` \| `added` (by the internal ingest timestamp). Case-insensitive. |
| `sortDirection` | string | — | `desc` reverses; anything else is ascending |
| `page` | int | `0` | Zero-based page index |
| `size` | int | `50` | Page size |
| `sort` | string | — | Echoed in the envelope, no effect on ordering |

**Response** `200 OK` — `Page<GuestAudioDTO>`

```json
{
  "content": [
    {
      "id": 1,
      "audioCode": "TAHSINTAHA_V3_PROJ_000001_AUD_RAW_V1_000001",
      "projectCode": "TAHSINTAHA_V3_PROJ_000001",
      "projectName": "کۆکراوەی Tehsîn Taha — مۆسیقا (بەشی 6)",
      "personMediaPortrait": "https://upload.wikimedia.org/wikipedia/commons/e/e0/Tahsin_Taha_Dimokrat_Taha.jpg",
      "person": {
        "id": 124,
        "personCode": "TAHSINTAHA_V3",
        "fullName": "Tehsîn Taha",
        "nickname": "Tahsin Taha",
        "romanizedName": "Tahsin Taha",
        "mediaPortrait": "https://upload.wikimedia.org/wikipedia/commons/e/e0/Tahsin_Taha_Dimokrat_Taha.jpg"
      },
      "categories": [
        { "id": 151, "categoryCode": "MUS_006", "name": "مۆسیقا (بەشی 6)" }
      ],
      "originTitle": "نامەکانی هەڵەبجە",
      "subject": [],
      "genre": ["سەما"],
      "tags": ["لە ڕیلەوە", "کوالیتی بەرز"],
      "keywords": ["دەنگی", "نەریتی"],
      "contributors": ["Mihemedi Diljen", "Selim Qadiri"],
      "dateCreated": "2023-12-06T22:58:16Z",
      "audioFileUrl": "/api/guest/audio/TAHSINTAHA_V3_PROJ_000001_AUD_RAW_V1_000001/stream",
      "trending": true,
      "trendingRank": 4,
      "trendingScore": 18.5
    }
  ],
  "pageable": {
    "pageNumber": 0,
    "pageSize": 20,
    "offset": 0,
    "paged": true,
    "unpaged": false
  },
  "totalElements": 137,
  "totalPages": 7,
  "number": 0,
  "size": 20,
  "first": true,
  "last": false,
  "numberOfElements": 20,
  "empty": false
}
```

The `content[]` item above is abridged for the envelope illustration; the full field set is in
[`GuestAudioDTO`](#guestaudiodto) and the complete payload is shown under the detail endpoint.

**Errors** — none specific to this endpoint. Unrecognized `sortBy` values and unparseable date
parameters are ignored rather than rejected. See [Errors](#errors) for the shared server-side codes.

**Example**

```bash
curl -s "{{BASE_URL}}/api/guest/audios?q=Tehsîn&singer=Taha&genre=سەما&dateFrom=1970-01-01&sortBy=date&sortDirection=desc&page=0&size=20"
```

### `GET /api/guest/audios/{audioCode}`

One publicly visible audio by its code.

**Authority:** none (public)

**Path parameters**

| Name | Type | Description |
|---|---|---|
| `audioCode` | string | Public audio code, matched exactly (`findByAudioCodeAndRemovedAtIsNull`) |

**Response** `200 OK` — a bare `GuestAudioDTO` (no `Page` envelope)

```json
{
  "id": 1,
  "audioCode": "TAHSINTAHA_V3_PROJ_000001_AUD_RAW_V1_000001",
  "projectCode": "TAHSINTAHA_V3_PROJ_000001",
  "projectName": "کۆکراوەی Tehsîn Taha — مۆسیقا (بەشی 6)",
  "personMediaPortrait": "https://upload.wikimedia.org/wikipedia/commons/e/e0/Tahsin_Taha_Dimokrat_Taha.jpg",
  "person": {
    "id": 124,
    "personCode": "TAHSINTAHA_V3",
    "fullName": "Tehsîn Taha",
    "nickname": "Tahsin Taha",
    "romanizedName": "Tahsin Taha",
    "mediaPortrait": "https://upload.wikimedia.org/wikipedia/commons/e/e0/Tahsin_Taha_Dimokrat_Taha.jpg"
  },
  "categories": [
    { "id": 151, "categoryCode": "MUS_006", "name": "مۆسیقا (بەشی 6)" },
    { "id": 61, "categoryCode": "MUS_003", "name": "مۆسیقا (بەشی 3)" }
  ],
  "originTitle": "نامەکانی هەڵەبجە",
  "alterTitle": "چاپی نوێبکراوەوە",
  "centralKurdishTitle": "بەرگی شیعرە کۆنەکان",
  "romanizedTitle": "نامەکانی هەڵەبجە",
  "form": "بێ ئامێر",
  "typeOfBasta": "بەستی مەخامی",
  "typeOfMaqam": "مەقامی شور",
  "subject": [],
  "genre": ["سەما"],
  "abstractText": "تۆمارکراوی «نامەکانی هەڵەبجە» …",
  "description": "وەرگرتنی WAVی بێزیان لە کاسێت/ڕیل بۆ ڕیلی ڕەسەن …",
  "speaker": "Shahla Hejar",
  "producer": "Karwan Mêrgewerî",
  "composer": "Sozdar Goran",
  "poet": "Zakir Sabri",
  "contributors": ["Mihemedi Diljen", "Selim Qadiri"],
  "language": "کوردی",
  "dialect": "کەڵهوڕی",
  "typeOfComposition": "وەرگرتراو",
  "typeOfPerformance": "گرووپ",
  "lyrics": "دەقەکان بە نووسینی ڕەسەنیدا پارێزراون …",
  "recordingVenue": "تەکیە",
  "city": "بەرلینی کۆچبەری",
  "region": "گەرمیان",
  "audience": "گەورەسالان",
  "tags": ["لە ڕیلەوە", "کوالیتی بەرز", "بەخشینی کۆمەڵگە"],
  "keywords": ["دەنگی", "نەریتی", "نەریتی زارەکی", "مۆسیقای کوردی"],
  "dateCreated": "2023-12-06T22:58:16Z",
  "datePublished": "2026-09-14T22:58:16Z",
  "dateModified": "2026-09-30T22:58:16Z",
  "copyright": "© میراتی هونەرمەند",
  "rightOwner": "پلاتفۆڕمی ئەرشیفی KHI",
  "dateCopyrighted": "2022-11-17T19:44:19Z",
  "licenseType": "CC BY-SA 4.0",
  "availability": "تاوەکوو دواتر پاشپێکراو",
  "owner": "هاوبەشی دامەزراوەیی",
  "publisher": "ئەرشیفی KHI",
  "audioFileUrl": "/api/guest/audio/TAHSINTAHA_V3_PROJ_000001_AUD_RAW_V1_000001/stream",
  "trending": false
}
```

`singer` and `duration` are absent above because they are `null` on this record.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `404` | — (empty body) | No active audio with that code, or it fails the visibility gate. The controller returns `ResponseEntity.notFound().build()`, so there is **no** `ApiErrorResponse` body. |

**Example**

```bash
curl -s "{{BASE_URL}}/api/guest/audios/TAHSINTAHA_V3_PROJ_000001_AUD_RAW_V1_000001"
```

**Notes** — records a trending view for `audio:{audioCode}`.

### `GuestAudioDTO`

| Field | Type | Description |
|---|---|---|
| `id` | integer | Row id |
| `audioCode` | string | Public audio code (path parameter of the detail endpoint) |
| `projectCode` | string | Owning project's code |
| `projectName` | string | Owning project's name |
| `personMediaPortrait` | string | Portrait URL of the owning project's person, copied as stored |
| `person` | object | [`GuestPersonSummaryDTO`](#nested-object-shapes); omitted when there is no owning project |
| `categories` | array | [`GuestCategorySummaryDTO`](#nested-object-shapes) list from the owning project; always present, may be empty |
| `originTitle` | string | Original title |
| `alterTitle` | string | Alternative title |
| `centralKurdishTitle` | string | Title in Central Kurdish |
| `romanizedTitle` | string | Romanized title |
| `form` | string | Musical/recording form |
| `typeOfBasta` | string | Type of basta |
| `typeOfMaqam` | string | Type of maqam |
| `subject` | string[] | Subjects; always present, may be empty |
| `genre` | string[] | Genres; always present, may be empty |
| `abstractText` | string | Abstract |
| `description` | string | Description |
| `speaker` | string | Speaker |
| `singer` | string | Singer |
| `producer` | string | Producer |
| `composer` | string | Composer |
| `poet` | string | Poet |
| `contributors` | string[] | Contributors; always present, may be empty |
| `language` | string | Language |
| `dialect` | string | Dialect |
| `typeOfComposition` | string | Type of composition |
| `typeOfPerformance` | string | Type of performance |
| `lyrics` | string | Lyrics text |
| `recordingVenue` | string | Recording venue |
| `city` | string | City |
| `region` | string | Region |
| `audience` | string | Intended audience |
| `tags` | string[] | Tags; always present, may be empty |
| `keywords` | string[] | Keywords; always present, may be empty |
| `duration` | string | Free-text duration as stored; no format is enforced in source |
| `dateCreated` | instant | Date the material was created |
| `datePublished` | instant | Date published |
| `dateModified` | instant | Date modified |
| `copyright` | string | Copyright statement |
| `rightOwner` | string | Rights owner |
| `dateCopyrighted` | instant | Date copyrighted |
| `licenseType` | string | License |
| `availability` | string | Availability statement |
| `owner` | string | Owner |
| `publisher` | string | Publisher |
| **`audioFileUrl`** | string | **Playback path.** Always `/api/guest/audio/{audioCode}/stream`, built by `GuestMapper`. Relative — prepend your API base URL. The S3 URL held on the entity is never serialized. See [Media streaming](./07-streaming.md). |
| `trending` | boolean | Always present. `true` only on list responses for codes in the trending snapshot |
| `trendingRank` | integer | Rank in the trending snapshot; omitted when not trending |
| `trendingScore` | number | Trending score; omitted when not trending |

---

## Videos

### `GET /api/guest/videos`

Paged, filtered list of publicly visible video records.

**Authority:** none (public)

**Query parameters**

Common filters:

| Name | Type | Default | Match | Description |
|---|---|---|---|---|
| `q` | string | — | search | Free-text keyword search |
| `projectCode` | string | — | exact | Owning project code |
| `categoryCode` | string | — | exact | Any category code on the owning project |
| `personCode` | string | — | exact | Person code on the owning project |
| `language` | string | — | exact | `language` |
| `dialect` | string | — | exact | `dialect` |
| `region` | string | — | exact | `region` |
| `subject` | string, repeatable | — | any-of | `subject` collection |
| `genre` | string, repeatable | — | any-of | `genre` collection |
| `tag` | string, repeatable | — | any-of | `tags` collection |
| `keyword` | string, repeatable | — | any-of | `keywords` collection |
| `dateFrom` | date/date-time | — | range | Inclusive lower bound on `dateCreated` |
| `dateTo` | date/date-time | — | range | Inclusive upper bound on `dateCreated` |
| `publishedFrom` | date/date-time | — | range | Inclusive lower bound on `datePublished` |
| `publishedTo` | date/date-time | — | range | Inclusive upper bound on `datePublished` |

Video-specific filters:

| Name | Type | Default | Match | Description |
|---|---|---|---|---|
| `event` | string | — | contains | `event` |
| `location` | string | — | contains | `location` |
| `creatorArtistDirector` | string | — | contains | `creatorArtistDirector` |
| `producer` | string | — | contains | `producer` |
| `contributor` | string | — | contains | `contributor` (single-valued, **not** repeatable) |
| `personShownInVideo` | string | — | contains | `personShownInVideo` |
| `subtitle` | string | — | contains | `subtitle` |
| `audience` | string | — | exact | `audience` |
| `provenance` | string | — | contains | Entity provenance — filterable, **not** returned in `GuestVideoDTO` |
| `videoStatus` | string | — | exact | Entity video status — filterable, **not** returned in `GuestVideoDTO` |
| `publisher` | string | — | contains | `publisher` |
| `color` | string, repeatable | — | any-of | `colorOfVideo` collection |
| `whereUsed` | string, repeatable | — | any-of | `whereThisVideoUsed` collection |

Sorting and paging:

| Name | Type | Default | Description |
|---|---|---|---|
| `sortBy` | string | — | One of `title` \| `name` \| `alpha` \| `alphabet` \| `originaltitle` (by `originalTitle`), `code` \| `videocode` (by `videoCode`), `date` \| `datecreated` (by `dateCreated`), `published` \| `datepublished` (by `datePublished`), `createdat` \| `created` \| `added` (by the internal ingest timestamp). Case-insensitive. |
| `sortDirection` | string | — | `desc` reverses; anything else is ascending |
| `page` | int | `0` | Zero-based page index |
| `size` | int | `50` | Page size |
| `sort` | string | — | Echoed in the envelope, no effect on ordering |

**Response** `200 OK` — `Page<GuestVideoDTO>`, `content[]` elements shaped as below.

**Errors** — none specific to this endpoint. See [Errors](#errors).

**Example**

```bash
curl -s "{{BASE_URL}}/api/guest/videos?location=قامیشلی&color=ڕەنگاوڕەنگ&whereUsed=پۆڕتاڵی%20ئۆنڵاین&sortBy=title&size=24"
```

### `GET /api/guest/videos/{videoCode}`

One publicly visible video by its code.

**Authority:** none (public)

**Path parameters**

| Name | Type | Description |
|---|---|---|
| `videoCode` | string | Public video code, matched exactly (`findByVideoCodeAndRemovedAtIsNull`) |

**Response** `200 OK` — a bare `GuestVideoDTO`

```json
{
  "id": 1,
  "videoCode": "SHEIKHUBEYDULLAH_V8_PROJ_000002_VID_RAW_V1_000001",
  "projectCode": "SHEIKHUBEYDULLAH_V8_PROJ_000002",
  "projectName": "کۆکراوەی Şêx Ubeydullahê Nehrî — خواردنی نەریتی (بەشی 12)",
  "personMediaPortrait": "https://upload.wikimedia.org/wikipedia/commons/9/90/Sheikh_Ubeydullah.jpg",
  "person": {
    "id": 445,
    "personCode": "SHEIKHUBEYDULLAH_V8",
    "fullName": "Şêx Ubeydullahê Nehrî",
    "nickname": "Sheikh Ubeydullah",
    "romanizedName": "Sheikh Ubeydullah",
    "mediaPortrait": "https://upload.wikimedia.org/wikipedia/commons/9/90/Sheikh_Ubeydullah.jpg"
  },
  "categories": [
    { "id": 359, "categoryCode": "CUI_012", "name": "خواردنی نەریتی (بەشی 12)" }
  ],
  "originalTitle": "ستری زەماوەندی گەرمیان",
  "alternativeTitle": "تۆمارکراوی ئەرشیفی",
  "titleInCentralKurdish": "بەرهەمی موکریان",
  "romanizedTitle": "ستری زەماوەندی گەرمیان",
  "subject": ["زەماوەند", "ڕەفتاری ئاینی", "ئاهەنگ"],
  "genre": ["سەمای فۆلکلۆری"],
  "location": "قامیشلی",
  "description": "فووتاژی دۆکیومێنتاری لە ساڵی 1987 …",
  "colorOfVideo": ["ڕەنگاوڕەنگ"],
  "language": "ئینگلیزی",
  "dialect": "سۆرانی",
  "subtitle": "ژێرنووسی عەرەبی",
  "creatorArtistDirector": "Çîçek Têlî",
  "producer": "Sardar Pîroz",
  "contributor": "Bakhtiar Şirwan",
  "audience": "کۆچبەری",
  "tags": ["ستۆدیۆ", "کوالیتی بەرز", "بەخشینی کۆمەڵگە"],
  "keywords": ["میراتی کوردی", "میژووی زارەکی", "مۆسیقا"],
  "whereThisVideoUsed": ["پۆڕتاڵی ئۆنڵاین", "نمایشی کۆنفرانس", "پیشانگای ٢٠٢٢"],
  "duration": "0:39:20",
  "dateCreated": "1987-11-21T15:01:25Z",
  "dateModified": "1988-07-16T15:01:25Z",
  "datePublished": "1988-06-28T15:01:25Z",
  "copyright": "© دامەزراوەی کلتووری گشتی",
  "rightOwner": "هاوبەشی کۆمەڵگە",
  "dateCopyrighted": "2011-10-25T08:37:54Z",
  "licenseType": "هەموو مافەکان پارێزراون",
  "usageRights": "تەنیا بۆ فێرکاری",
  "availability": "سنووردار",
  "owner": "بەخشینی خێزان",
  "publisher": "چاپخانەی زانکۆ",
  "videoFileUrl": "/api/guest/video/SHEIKHUBEYDULLAH_V8_PROJ_000002_VID_RAW_V1_000001/stream",
  "trending": false
}
```

`event`, `region` and `personShownInVideo` are absent because they are `null` on this record.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `404` | — (empty body) | No active video with that code, or it fails the visibility gate (`ResponseEntity.notFound().build()`, no body) |

**Example**

```bash
curl -s "{{BASE_URL}}/api/guest/videos/SHEIKHUBEYDULLAH_V8_PROJ_000002_VID_RAW_V1_000001"
```

**Notes** — records a trending view for `video:{videoCode}`.

### `GuestVideoDTO`

| Field | Type | Description |
|---|---|---|
| `id` | integer | Row id |
| `videoCode` | string | Public video code |
| `projectCode` | string | Owning project's code |
| `projectName` | string | Owning project's name |
| `personMediaPortrait` | string | Portrait URL of the owning project's person |
| `person` | object | [`GuestPersonSummaryDTO`](#nested-object-shapes) |
| `categories` | array | [`GuestCategorySummaryDTO`](#nested-object-shapes) list; always present |
| `originalTitle` | string | Original title |
| `alternativeTitle` | string | Alternative title |
| `titleInCentralKurdish` | string | Title in Central Kurdish |
| `romanizedTitle` | string | Romanized title |
| `subject` | string[] | Subjects; always present |
| `genre` | string[] | Genres; always present |
| `event` | string | Event depicted |
| `location` | string | Location |
| `description` | string | Description |
| `personShownInVideo` | string | Person shown in the video |
| `colorOfVideo` | string[] | Color descriptors; always present |
| `language` | string | Language |
| `dialect` | string | Dialect |
| `region` | string | Region |
| `subtitle` | string | Subtitle track description |
| `creatorArtistDirector` | string | Creator / artist / director |
| `producer` | string | Producer |
| `contributor` | string | Contributor (single string, not a list) |
| `audience` | string | Intended audience |
| `tags` | string[] | Tags; always present |
| `keywords` | string[] | Keywords; always present |
| `whereThisVideoUsed` | string[] | Prior uses; always present |
| `duration` | string | Free-text duration as stored |
| `dateCreated` | instant | Date the material was created |
| `dateModified` | instant | Date modified |
| `datePublished` | instant | Date published |
| `copyright` | string | Copyright statement |
| `rightOwner` | string | Rights owner |
| `dateCopyrighted` | instant | Date copyrighted |
| `licenseType` | string | License |
| `usageRights` | string | Usage rights |
| `availability` | string | Availability statement |
| `owner` | string | Owner |
| `publisher` | string | Publisher |
| **`videoFileUrl`** | string | **Playback path.** Always `/api/guest/video/{videoCode}/stream`. Relative; no S3 URL is exposed. See [Media streaming](./07-streaming.md). |
| `trending` | boolean | Always present; `true` only when stamped on a list response |
| `trendingRank` | integer | Omitted when not trending |
| `trendingScore` | number | Omitted when not trending |

---

## Texts

### `GET /api/guest/texts`

Paged, filtered list of publicly visible text/document records.

**Authority:** none (public)

**Query parameters**

Common filters:

| Name | Type | Default | Match | Description |
|---|---|---|---|---|
| `q` | string | — | search | Free-text keyword search |
| `projectCode` | string | — | exact | Owning project code |
| `categoryCode` | string | — | exact | Any category code on the owning project |
| `personCode` | string | — | exact | Person code on the owning project |
| `language` | string | — | exact | `language` |
| `dialect` | string | — | exact | `dialect` |
| `region` | string | — | exact | `region` |
| `subject` | string, repeatable | — | any-of | `subject` collection |
| `genre` | string, repeatable | — | any-of | `genre` collection |
| `tag` | string, repeatable | — | any-of | `tags` collection |
| `keyword` | string, repeatable | — | any-of | `keywords` collection |
| `dateFrom` | date/date-time | — | range | Inclusive lower bound on `dateCreated` |
| `dateTo` | date/date-time | — | range | Inclusive upper bound on `dateCreated` |
| `publishedFrom` | date/date-time | — | range | Inclusive lower bound on `datePublished` |
| `publishedTo` | date/date-time | — | range | Inclusive upper bound on `datePublished` |

Text-specific filters:

| Name | Type | Default | Match | Description |
|---|---|---|---|---|
| `documentType` | string | — | exact | `documentType` |
| `isbn` | string | — | contains | `isbn` |
| `author` | string | — | contains | `author` |
| `contributors` | string | — | contains | `contributors` (parameter name is plural, value is a single string) |
| `script` | string | — | exact | `script` |
| `series` | string | — | contains | `series` |
| `edition` | string | — | contains | `edition` |
| `volume` | string | — | contains | `volume` |
| `printingHouse` | string | — | contains | `printingHouse` |
| `audience` | string | — | exact | `audience` |
| `provenance` | string | — | contains | Entity provenance — filterable, **not** returned in `GuestTextDTO` |
| `publisher` | string | — | contains | `publisher` |
| `printDateFrom` | date/date-time | — | range | Inclusive lower bound on `printDate` |
| `printDateTo` | date/date-time | — | range | Inclusive upper bound on `printDate` |

Sorting and paging:

| Name | Type | Default | Description |
|---|---|---|---|
| `sortBy` | string | — | One of `title` \| `name` \| `alpha` \| `alphabet` \| `originaltitle` (by `originalTitle`), `code` \| `textcode` (by `textCode`), `date` \| `datecreated` (by `dateCreated`), `published` \| `datepublished` (by `datePublished`), `createdat` \| `created` \| `added` (by the internal ingest timestamp). Case-insensitive. |
| `sortDirection` | string | — | `desc` reverses; anything else is ascending |
| `page` | int | `0` | Zero-based page index |
| `size` | int | `50` | Page size |
| `sort` | string | — | Echoed in the envelope, no effect on ordering |

**Response** `200 OK` — `Page<GuestTextDTO>`, `content[]` elements shaped as below.

**Errors** — none specific to this endpoint. See [Errors](#errors).

**Example**

```bash
curl -s "{{BASE_URL}}/api/guest/texts?author=Rojda&script=عەرەبی&printDateFrom=1990-01-01&printDateTo=1999-12-31&sortBy=published&sortDirection=desc"
```

### `GET /api/guest/texts/{textCode}`

One publicly visible text by its code.

**Authority:** none (public)

**Path parameters**

| Name | Type | Description |
|---|---|---|
| `textCode` | string | Public text code, matched exactly (`findByTextCodeAndRemovedAtIsNull`) |

**Response** `200 OK` — a bare `GuestTextDTO`

```json
{
  "id": 1,
  "textCode": "JALALTALABANI_V6_PROJ_000001_TXT_RAW_V1_000001",
  "projectCode": "JALALTALABANI_V6_PROJ_000001",
  "projectName": "کۆکراوەی Celal Talebanî — ئەتنۆگرافی (بەشی 2)",
  "personMediaPortrait": "https://upload.wikimedia.org/wikipedia/commons/4/42/Jalal_Talabani_2005-09-09.jpg",
  "person": {
    "id": 325,
    "personCode": "JALALTALABANI_V6",
    "fullName": "Celal Talebanî",
    "nickname": "Jalal Talabani",
    "romanizedName": "Jalal Talabani",
    "mediaPortrait": "https://upload.wikimedia.org/wikipedia/commons/4/42/Jalal_Talabani_2005-09-09.jpg"
  },
  "categories": [
    { "id": 40, "categoryCode": "ETH_002", "name": "ئەتنۆگرافی (بەشی 2)" }
  ],
  "originalTitle": "دیوانی گۆران",
  "alternativeTitle": "نوسخەی کارکردن",
  "titleInCentralKurdish": "بەرهەمی موکریان",
  "romanizedTitle": "دیوانی گۆران",
  "subject": ["یاسا"],
  "genre": ["فەرهەنگ", "نووسراو", "نووسراوی سۆفی"],
  "documentType": "دیکرێ",
  "description": "پرۆسەی OCR کراوی پەرتووکچەیەک لە ساڵی 1991 …",
  "script": "عەرەبی",
  "isbn": "978-1-97030-151-0",
  "edition": "وێنەکپی",
  "language": "کوردی",
  "dialect": "لەکی",
  "author": "Rojda Mukriyanî",
  "contributors": "Emel Hewramî؛ Diyari Mahabadî",
  "printingHouse": "چاپخانەی سنە",
  "audience": "منداڵان",
  "tags": ["وێنەکپی", "OCR کراو"],
  "keywords": ["دەقی ئاینی", "ئەدەبی کوردی", "دەستنووس"],
  "pageCount": 579,
  "dateCreated": "1991-08-20T12:00:36Z",
  "printDate": "1994-02-05T12:00:36Z",
  "dateModified": "1995-07-15T12:00:36Z",
  "datePublished": "1994-08-03T12:00:36Z",
  "copyright": "© میراتی هونەرمەند",
  "rightOwner": "خێزانی بەخشەر",
  "dateCopyrighted": "2010-04-19T14:11:16Z",
  "licenseType": "CC BY-NC 4.0",
  "usageRights": "سنووردار؛ مۆڵەت پێویستە",
  "availability": "تەنیا ئەرشیف",
  "owner": "بەخشینی خێزان",
  "publisher": "چاپی تایبەت",
  "textFileUrl": "/api/guest/text/JALALTALABANI_V6_PROJ_000001_TXT_RAW_V1_000001/read",
  "trending": false
}
```

`transcription`, `volume`, `series` and `region` are absent because they are `null`. `coverImageUrl`
is absent because this record has no cover image.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `404` | — (empty body) | No active text with that code, or it fails the visibility gate (`ResponseEntity.notFound().build()`, no body) |

**Example**

```bash
curl -s "{{BASE_URL}}/api/guest/texts/JALALTALABANI_V6_PROJ_000001_TXT_RAW_V1_000001"
```

**Notes** — records a trending view for `text:{textCode}`.

### `GuestTextDTO`

| Field | Type | Description |
|---|---|---|
| `id` | integer | Row id |
| `textCode` | string | Public text code |
| `projectCode` | string | Owning project's code |
| `projectName` | string | Owning project's name |
| `personMediaPortrait` | string | Portrait URL of the owning project's person |
| `person` | object | [`GuestPersonSummaryDTO`](#nested-object-shapes) |
| `categories` | array | [`GuestCategorySummaryDTO`](#nested-object-shapes) list; always present |
| `originalTitle` | string | Original title |
| `alternativeTitle` | string | Alternative title |
| `titleInCentralKurdish` | string | Title in Central Kurdish |
| `romanizedTitle` | string | Romanized title |
| `subject` | string[] | Subjects; always present |
| `genre` | string[] | Genres; always present |
| `documentType` | string | Document type |
| `description` | string | Description |
| `script` | string | Script the document is written in |
| `transcription` | string | Transcription |
| `isbn` | string | ISBN |
| `edition` | string | Edition |
| `volume` | string | Volume |
| `series` | string | Series |
| `language` | string | Language |
| `dialect` | string | Dialect |
| `region` | string | Region |
| `author` | string | Author |
| `contributors` | string | Contributors — a single string on this DTO, not an array |
| `printingHouse` | string | Printing house |
| `audience` | string | Intended audience |
| `tags` | string[] | Tags; always present |
| `keywords` | string[] | Keywords; always present |
| `pageCount` | integer | Page count |
| `dateCreated` | instant | Date the material was created |
| `printDate` | instant | Print date |
| `dateModified` | instant | Date modified |
| `datePublished` | instant | Date published |
| `copyright` | string | Copyright statement |
| `rightOwner` | string | Rights owner |
| `dateCopyrighted` | instant | Date copyrighted |
| `licenseType` | string | License |
| `usageRights` | string | Usage rights |
| `availability` | string | Availability statement |
| `owner` | string | Owner |
| `publisher` | string | Publisher |
| **`textFileUrl`** | string | **Read path.** Always `/api/guest/text/{textCode}/read`, built by `GuestMapper`. Relative; no S3 URL is exposed. See [Media streaming](./07-streaming.md). |
| **`coverImageUrl`** | string | **Cover path.** `/api/guest/text/{textCode}/cover` — emitted **only** when the record actually has a stored cover, so an `<img>` is never pointed at a guaranteed 404. Omitted otherwise. |
| `trending` | boolean | Always present; `true` only when stamped on a list response |
| `trendingRank` | integer | Omitted when not trending |
| `trendingScore` | number | Omitted when not trending |

> The Javadoc on `GuestTextDTO` still calls `textFileUrl` and `coverImageUrl` "public S3 URLs".
> That comment is stale — `GuestMapper.toText` builds both as relative `/api/guest/text/…` proxy
> paths, and that is what the API returns.

---

## Images

### `GET /api/guest/images`

Paged, filtered list of publicly visible image records.

**Authority:** none (public)

**Query parameters**

Common filters:

| Name | Type | Default | Match | Description |
|---|---|---|---|---|
| `q` | string | — | search | Free-text keyword search |
| `projectCode` | string | — | exact | Owning project code |
| `categoryCode` | string | — | exact | Any category code on the owning project |
| `personCode` | string | — | exact | Person code on the owning project |
| `language` | string | — | exact | `language` |
| `dialect` | string | — | exact | `dialect` |
| `region` | string | — | exact | `region` |
| `subject` | string, repeatable | — | any-of | `subject` collection |
| `genre` | string, repeatable | — | any-of | `genre` collection |
| `tag` | string, repeatable | — | any-of | `tags` collection |
| `keyword` | string, repeatable | — | any-of | `keywords` collection |
| `dateFrom` | date/date-time | — | range | Inclusive lower bound on `dateCreated` |
| `dateTo` | date/date-time | — | range | Inclusive upper bound on `dateCreated` |
| `publishedFrom` | date/date-time | — | range | Inclusive lower bound on `datePublished` |
| `publishedTo` | date/date-time | — | range | Inclusive upper bound on `datePublished` |

Image-specific filters:

| Name | Type | Default | Match | Description |
|---|---|---|---|---|
| `event` | string | — | contains | `event` |
| `location` | string | — | contains | `location` |
| `creatorArtistPhotographer` | string | — | contains | `creatorArtistPhotographer` |
| `contributor` | string | — | contains | `contributor` (single-valued, **not** repeatable) |
| `personShownInImage` | string | — | contains | `personShownInImage` |
| `audience` | string | — | exact | `audience` |
| `provenance` | string | — | contains | Entity provenance — filterable, **not** returned in `GuestImageDTO` |
| `photostory` | string | — | contains | `photostory` |
| `imageStatus` | string | — | exact | Entity image status — filterable, **not** returned in `GuestImageDTO` |
| `color` | string, repeatable | — | any-of | `colorOfImage` collection |
| `whereUsed` | string, repeatable | — | any-of | `whereThisImageUsed` collection |

There is no filter for `form`, `manufacturer`, `model` or `lens`, even though those fields are
returned on `GuestImageDTO`.

Sorting and paging:

| Name | Type | Default | Description |
|---|---|---|---|
| `sortBy` | string | — | One of `title` \| `name` \| `alpha` \| `alphabet` \| `originaltitle` (by `originalTitle`), `code` \| `imagecode` (by `imageCode`), `date` \| `datecreated` (by `dateCreated`), `published` \| `datepublished` (by `datePublished`), `createdat` \| `created` \| `added` (by the internal ingest timestamp). Case-insensitive. |
| `sortDirection` | string | — | `desc` reverses; anything else is ascending |
| `page` | int | `0` | Zero-based page index |
| `size` | int | `50` | Page size |
| `sort` | string | — | Echoed in the envelope, no effect on ordering |

**Response** `200 OK` — `Page<GuestImageDTO>`, `content[]` elements shaped as below.

**Errors** — none specific to this endpoint. See [Errors](#errors).

**Example**

```bash
curl -s "{{BASE_URL}}/api/guest/images?q=نەوروز&color=سێپیا&creatorArtistPhotographer=Sherko&dateTo=1950-12-31&sortBy=date&sortDirection=asc"
```

### `GET /api/guest/images/{imageCode}`

One publicly visible image by its code.

**Authority:** none (public)

**Path parameters**

| Name | Type | Description |
|---|---|---|
| `imageCode` | string | Public image code, matched exactly (`findByImageCodeAndRemovedAtIsNull`) |

**Response** `200 OK` — a bare `GuestImageDTO`

```json
{
  "id": 3,
  "imageCode": "IDRISBARZANI_V2_PROJ_000003_IMG_RAW_V1_000001",
  "projectCode": "IDRISBARZANI_V2_PROJ_000003",
  "projectName": "کۆکراوەی Idrîs Barzanî — دەقە ئاینییەکان (بەشی 15)",
  "personMediaPortrait": "https://upload.wikimedia.org/wikipedia/commons/0/07/Kaka_Ziad_Koya%2C_Idris_Barzani%2C_Franso_Hariri.jpg",
  "person": {
    "id": 100,
    "personCode": "IDRISBARZANI_V2",
    "fullName": "Idrîs Barzanî",
    "nickname": "Idris Barzani",
    "romanizedName": "Idris Barzani",
    "mediaPortrait": "https://upload.wikimedia.org/wikipedia/commons/0/07/Kaka_Ziad_Koya%2C_Idris_Barzani%2C_Franso_Hariri.jpg"
  },
  "categories": [
    { "id": 428, "categoryCode": "REL_015", "name": "دەقە ئاینییەکان (بەشی 15)" }
  ],
  "originalTitle": "ئاگری نەوروز",
  "alternativeTitle": "نوسخەی کارکردن",
  "titleInCentralKurdish": "بەرهەمی موکریان",
  "romanizedTitle": "ئاگری نەوروز",
  "subject": ["ئاهەنگ", "لاپەڕەی دەستنووس", "ئامێر"],
  "form": "سلایس",
  "genre": ["لاپەڕەی دەستنووس"],
  "event": "مەولوود",
  "location": "قامیشلی",
  "description": "وێنەیەکی ڕەش و سپی لە ساڵی 1917 …",
  "personShownInImage": "Zêna Kakey",
  "colorOfImage": ["سێپیا", "ڕەش و سپی"],
  "manufacturer": "Zeiss Ikon",
  "lens": "50mm f/2",
  "creatorArtistPhotographer": "Sherko Korkmaz",
  "audience": "کۆچبەری",
  "photostory": "بەشێک لە کۆمەڵە وێنەیەک …",
  "tags": ["لە پلێتی شووشەوە"],
  "keywords": ["ستۆدیۆ", "وێنە", "ئەتنۆگرافی"],
  "whereThisImageUsed": ["بەشی فێرکاری", "پۆڕتاڵی ئۆنڵاین"],
  "dateCreated": "1917-06-03T20:57:23Z",
  "dateModified": "1918-08-26T20:57:23Z",
  "datePublished": "1919-11-10T20:57:23Z",
  "copyright": "© دامەزراوەی کلتووری گشتی",
  "rightOwner": "دامەزراوەی تۆمارکردنی ڕەسەن",
  "dateCopyrighted": "2018-10-25T03:12:24Z",
  "licenseType": "تەنیا لە ئەرشیفدا",
  "usageRights": "تەنیا بۆ نیشاندان — بێ وەرگێڕان",
  "availability": "ناوەخۆیی",
  "owner": "هاوبەشی دامەزراوەیی",
  "publisher": "چاپخانەی زانکۆ",
  "imageFileUrl": "/api/guest/image/IDRISBARZANI_V2_PROJ_000003_IMG_RAW_V1_000001/view",
  "trending": false
}
```

`model`, `contributor`, `language`, `dialect` and `region` are absent because they are `null` on this
record.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `404` | — (empty body) | No active image with that code, or it fails the visibility gate (`ResponseEntity.notFound().build()`, no body) |

**Example**

```bash
curl -s "{{BASE_URL}}/api/guest/images/IDRISBARZANI_V2_PROJ_000003_IMG_RAW_V1_000001"
```

**Notes** — records a trending view for `image:{imageCode}`.

### `GuestImageDTO`

| Field | Type | Description |
|---|---|---|
| `id` | integer | Row id |
| `imageCode` | string | Public image code |
| `projectCode` | string | Owning project's code |
| `projectName` | string | Owning project's name |
| `personMediaPortrait` | string | Portrait URL of the owning project's person |
| `person` | object | [`GuestPersonSummaryDTO`](#nested-object-shapes) |
| `categories` | array | [`GuestCategorySummaryDTO`](#nested-object-shapes) list; always present |
| `originalTitle` | string | Original title |
| `alternativeTitle` | string | Alternative title |
| `titleInCentralKurdish` | string | Title in Central Kurdish |
| `romanizedTitle` | string | Romanized title |
| `subject` | string[] | Subjects; always present |
| `form` | string | Physical/photographic form |
| `genre` | string[] | Genres; always present |
| `event` | string | Event depicted |
| `location` | string | Location |
| `description` | string | Description |
| `personShownInImage` | string | Person shown in the image |
| `colorOfImage` | string[] | Color descriptors; always present |
| `language` | string | Language |
| `dialect` | string | Dialect |
| `region` | string | Region |
| `manufacturer` | string | Camera manufacturer |
| `model` | string | Camera model |
| `lens` | string | Lens |
| `creatorArtistPhotographer` | string | Creator / artist / photographer |
| `contributor` | string | Contributor (single string, not a list) |
| `audience` | string | Intended audience |
| `photostory` | string | Photo-story the image belongs to |
| `tags` | string[] | Tags; always present |
| `keywords` | string[] | Keywords; always present |
| `whereThisImageUsed` | string[] | Prior uses; always present |
| `dateCreated` | instant | Date the material was created |
| `dateModified` | instant | Date modified |
| `datePublished` | instant | Date published |
| `copyright` | string | Copyright statement |
| `rightOwner` | string | Rights owner |
| `dateCopyrighted` | instant | Date copyrighted |
| `licenseType` | string | License |
| `usageRights` | string | Usage rights |
| `availability` | string | Availability statement |
| `owner` | string | Owner |
| `publisher` | string | Publisher |
| **`imageFileUrl`** | string | **View path.** Always `/api/guest/image/{imageCode}/view`, built by `GuestMapper`. Relative; no S3 URL is exposed. See [Media streaming](./07-streaming.md). |
| `trending` | boolean | Always present; `true` only when stamped on a list response |
| `trendingRank` | integer | Omitted when not trending |
| `trendingScore` | number | Omitted when not trending |

---

## Errors

The detail endpoints return a **bodiless** `404` (`ResponseEntity.notFound().build()`), so there is
no `error` code to switch on — check the status alone.

| Status | `error` code | When |
|---|---|---|
| `404` | — (no body) | `GET /api/guest/{audios,videos,texts,images}/{code}` — unknown code, trashed record, or record hidden by the visibility gate |

Everything else that can surface here is produced by the shared `ApiExceptionHandler` in the standard
`ApiErrorResponse` envelope (`timestamp`, `status`, `error`, `category`, `message`, `hint`, `path`,
`traceId`, `details`; `null` fields omitted):

| Status | `error` code | When |
|---|---|---|
| `404` | `NOT_FOUND` | No handler matches the URL at all (misspelled path) |
| `405` | `METHOD_NOT_ALLOWED` | A non-`GET` method against one of these paths |
| `500` | `DATABASE_ERROR` | `DataAccessException` from the search/read query |
| `500` | `INTERNAL_SERVER_ERROR` | Any other unhandled exception |
| `504` | `TIMEOUT` | `QueryTimeoutException` — the search query ran too long |

These endpoints never return `401` or `403` — they are outside the authenticated surface.

## Related

- [Public API index](./README.md)
- [Media streaming, viewing and reading](./07-streaming.md) — the `audioFileUrl`, `videoFileUrl`,
  `textFileUrl`, `coverImageUrl` and `imageFileUrl` targets, including range requests
- [Conventions](./01-conventions.md) — `Page` envelope, timestamp formats, error envelope,
  `{{BASE_URL}}` usage
- [Errors](./02-errors.md) — the `ApiErrorResponse` envelope and the full `ErrorCode` set
