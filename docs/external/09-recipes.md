# Recipes

> **Audience:** Developers building the public website · **Base paths:** `/api/guest`, `/api/auth`,
> `/api/user`, `/api/corrections` · **Source — controllers:**
> `platform/api/guest/GuestSearchAPI.java`, `platform/api/audio/AudioStreamAPI.java`,
> `platform/api/text/TextStreamAPI.java`, `platform/api/image/ImageStreamAPI.java`,
> `platform/api/video/VideoStreamAPI.java`, `platform/api/correction/GuestCorrectionAPI.java`,
> `user/api/UserAPI.java`, `user/api/UserProfileAPI.java` · **services:**
> `platform/service/guest/GuestSearchService.java`,
> `platform/service/guest/GuestTrendingService.java`, `platform/service/guest/GuestMapper.java`,
> `platform/service/correction/GuestCorrectionService.java`, `user/service/UserService.java` ·
> **config and error handling:** `user/configs/SecurityConfig.java`,
> `platform/config/CacheConfig.java`, `platform/exceptions/ApiExceptionHandler.java`,
> `user/exceptions/GlobalExceptionHandler.java`, `src/main/resources/application.yaml`

End-to-end task recipes that chain the external endpoints together. Every path, parameter, field
and limit below is copied from the controllers, services and DTOs listed above. Nothing here is
aspirational — if a recipe needs something the API does not expose, that is stated instead of
invented.

## Before you start

| Item | Value |
|---|---|
| `{{BASE_URL}}` | Placeholder — substitute your API origin, e.g. `http://localhost:8080` |
| Public surface | `GET /api/guest/**` needs no token (`SecurityConfig`) |
| Public auth surface | `POST /api/auth/register`, `/api/auth/register-with-image`, `/api/auth/login` |
| Everything else | Any other `/api/**` path requires a valid token |
| Token transport | HttpOnly cookie `khi_auth_token`, or `Authorization: Bearer <jwt>` |
| Null fields | Omitted from every response (`spring.jackson.default-property-inclusion=non_null`) |
| Error envelope | `timestamp`, `status`, `error`, `category`, `message`, `hint`, `path`, `traceId`, `details` |

Timestamp rendering, the Spring `Page` envelope and the full error-code list are described once in
`./01-conventions.md` rather than repeated in each recipe.

Every JSON block below is abbreviated to the fields the recipe uses; the guest DTOs carry many
more. The complete per-endpoint field lists live in `./04-discovery.md` (search, suggest, facets,
trending, feed), `./05-catalog.md` (projects, categories, persons), `./06-media.md` (audios,
videos, texts, images) and `./08-corrections.md`. One field worth knowing about up front: the seven
full entity DTOs — audio, video, text, image, project, person, category — declare
`private boolean isTrending`, which Jackson writes as `trending`. Being a primitive it is always
present, `false` unless the item is in the current trending snapshot, and the listing endpoints
additionally stamp `trendingRank` and `trendingScore` onto the items that are trending. The summary
stubs (`GuestProjectSummaryDTO`, `GuestPersonSummaryDTO`, `GuestCategorySummaryDTO`) and the
suggestion, facet, feed and trending envelopes have no such field.

Two curl notes that apply to every example below:

- `{{BASE_URL}}` contains braces. Either substitute it before running, or pass `-g` so curl does
  not treat the braces as a glob.
- Always quote URLs that carry a query string.

---

## 1. Render a home page: trending, facets, and the first feed page

Three independent public calls. Fire them in parallel — none of them depends on the others.

**Step 1 — trending rows and popular-search chips**

```bash
curl -s "{{BASE_URL}}/api/guest/trending"
```

`GET /api/guest/trending` takes no parameters and is cached server-side for 5 minutes
(`trending:snapshot` / `trending:results`, TTL 5 min in `CacheConfig`).

```json
{
  "generatedAt" : "2026-08-26T09:15:00Z",
  "trendingItems" : [ {
    "rank" : 1,
    "score" : 148.5,
    "kind" : "audio",
    "code" : "AUD-001",
    "title" : "Bastay Hawler",
    "projectCode" : "PRJ-014",
    "projectName" : "Hawler Radio Tapes",
    "audio" : { "id" : 91, "audioCode" : "AUD-001", "originTitle" : "Bastay Hawler" }
  } ],
  "topSearches" : [ { "query" : "hawler", "count" : 42 } ],
  "trendingByType" : {
    "audio" : [ { "rank" : 1, "score" : 148.5, "kind" : "audio", "code" : "AUD-001",
                  "title" : "Bastay Hawler" } ],
    "video" : [ ],
    "text" : [ ],
    "image" : [ ]
  }
}
```

What to do with it: `trendingItems` is ranked (up to 20 items, `rank` is 1-based); render the
first five as a hero row. `trendingByType.audio` / `.video` / `.text` / `.image` hold up to five
items each, for per-kind rows; they are the same `TrendingItem` objects, filtered by `kind`.
`topSearches` holds up to ten entries (the most frequent queries of the last 24 hours, stored
trimmed and lower-cased) and `topSearches[].query` values are ready-made search chips — feed each
straight into recipe 2. Only one of `audio` / `video` / `text` / `image` / `project` / `person` is
present on a given `TrendingItem`, chosen by `kind`; the rest are null and therefore omitted. A
`TrendingItem` also carries `thumbnail`, `personCode` and `personName` when the source record has
them; they are omitted when null.

**Step 2 — sidebar facet counts**

```bash
curl -s "{{BASE_URL}}/api/guest/facets"
```

```json
{
  "mediaTypes" : {
    "audios" : 1204, "videos" : 318, "texts" : 92, "images" : 4471, "projects" : 260
  },
  "categories" : [ { "code" : "CAT-03", "label" : "Music", "count" : 512 } ],
  "persons"    : [ { "code" : "PER-11", "label" : "Tahir Tofiq", "count" : 63 } ],
  "languages"  : [ { "label" : "Kurdish", "count" : 980 } ],
  "dialects"   : [ ], "regions" : [ ], "genres" : [ ], "tags" : [ ], "keywords" : [ ]
}
```

What to do with it: each facet list is already ordered count-desc then label-asc and capped at 50
entries. `mediaTypes` is an object, not a list, and drives the "Photos / Sounds / Videos / Texts"
tab counters — it also carries `projects`. For the
checkbox lists, bind `categories[].code` to `categoryCode`, `persons[].code` to `personCode`,
and the remaining buckets' `label` values to `language`, `dialect`, `region`, `genre`, `tag` and
`keyword` on the feed call in step 3. `code` is optional on a `Bucket` and is omitted for the
buckets that have no stable code (languages, dialects, regions, genres, tags, keywords).

**Step 3 — the first page of the grouped media feed**

```bash
curl -s "{{BASE_URL}}/api/guest/feed?page=0&size=12"
```

```json
{
  "order" : [ "image", "audio", "video", "text" ],
  "images" : {
    "kind" : "image",
    "content" : [ { "id" : 7, "imageCode" : "IMG-0007",
                    "imageFileUrl" : "/api/guest/image/IMG-0007/view" } ],
    "page" : 0, "size" : 12, "totalElements" : 4471, "totalPages" : 373,
    "numberOfElements" : 12, "first" : true, "last" : false, "empty" : false
  },
  "audios" : {
    "kind" : "audio",
    "content" : [ { "id" : 91, "audioCode" : "AUD-001",
                    "audioFileUrl" : "/api/guest/audio/AUD-001/stream" } ],
    "page" : 0, "size" : 12, "totalElements" : 1204, "totalPages" : 101,
    "numberOfElements" : 12, "first" : true, "last" : false, "empty" : false
  },
  "videos" : {
    "kind" : "video",
    "content" : [ { "id" : 5, "videoCode" : "VID-0005",
                    "videoFileUrl" : "/api/guest/video/VID-0005/stream" } ],
    "page" : 0, "size" : 12, "totalElements" : 318, "totalPages" : 27,
    "numberOfElements" : 12, "first" : true, "last" : false, "empty" : false
  },
  "texts" : {
    "kind" : "text",
    "content" : [ { "id" : 42, "textCode" : "TXT-0042",
                    "textFileUrl" : "/api/guest/text/TXT-0042/read" } ],
    "page" : 0, "size" : 12, "totalElements" : 92, "totalPages" : 8,
    "numberOfElements" : 12, "first" : true, "last" : false, "empty" : false
  },
  "totalElements" : 6085,
  "page" : 0,
  "size" : 12,
  "hasNext" : true,
  "hasPrevious" : false
}
```

Each `content[]` element is the same full media DTO the per-kind endpoint returns — the arrays
above are trimmed to one item and a few fields for readability.

What to do with it: `/api/guest/feed` is **not** a Spring `Page` — it is four independent
sections. `size=12` returns up to 12 of *each* kind, so no single kind can push the others off
page 0. Render in the sequence given by `order` (`image`, `audio`, `video`, `text`), which is
fixed. `totalElements` at the top level is the sum of the four section totals. `hasNext` is true
when *any* section still has a page left. All four section objects are always present, and each
one always carries the full set of counters: a section that is empty or was excluded by `types`
comes back with `content: []`, `totalElements: 0`, `totalPages: 0`, `empty: true` and both `first`
and `last` set to `true`.

---

## 2. Full-text search with suggestions as the user types

**Step 1 — autocomplete on each keystroke**

```bash
curl -s "{{BASE_URL}}/api/guest/suggest?q=haw&limit=8"
```

`q` is required — omitting it returns `400` with `"error": "MISSING_PARAMETER"` and
`details.parameter = "q"`. `limit` is optional: values `<= 0` or absent fall back to 10, and
anything above 50 is clamped to 50.

```json
[
  { "value" : "Hawler Radio Tapes", "kind" : "project", "code" : "PRJ-014" },
  { "value" : "Hawler", "kind" : "category", "code" : "CAT-09" },
  { "value" : "Bastay Hawler", "kind" : "audio", "code" : "AUD-001" }
]
```

What to do with it: the response is a plain JSON array, not a page. `kind` is one of
`project`, `category`, `person`, `audio`, `video`, `text`, `image` — use it to pick the row icon
and the destination route. (`GuestSuggestionDTO`'s javadoc also names `tag` and `keyword`, but
`GuestSearchService.suggest` never emits those two kinds; do not branch on them.) Rows are
produced in that order — projects, categories and persons first, then the four media kinds, which
share whatever room is left under the cap. `code` is optional; when present, deep-link straight to
the entity detail endpoint instead of running a search.

**Step 2 — full cross-entity search when the user presses Enter**

```bash
curl -s "{{BASE_URL}}/api/guest/search?q=hawler&perSection=10"
```

`q` is required here too (same `MISSING_PARAMETER` behavior). `perSection` is optional: absent or
`<= 0` gives 10 per section, and it is clamped to a maximum of 500. A `q` that is present but
blank returns an otherwise empty payload with `"query": ""` and no sections.

```json
{
  "query" : "hawler",
  "projects"   : { "total" : 1,  "items" : [ { "projectCode" : "PRJ-014" } ] },
  "categories" : { "total" : 1,  "items" : [ { "categoryCode" : "CAT-09" } ] },
  "persons"    : { "total" : 0,  "items" : [ ] },
  "audios"     : { "total" : 10, "items" : [ { "audioCode" : "AUD-001" } ] },
  "videos"     : { "total" : 0,  "items" : [ ] },
  "texts"      : { "total" : 0,  "items" : [ ] },
  "images"     : { "total" : 10, "items" : [ { "imageCode" : "IMG-0007" } ] }
}
```

All seven sections are always present. `items` holds full entity DTOs; the arrays and their
fields are trimmed above, so a section showing `"total": 10` really carries ten complete DTOs.

What to do with it: `items` is capped at `perSection`, and `total` is *the number of items in
that section*, not the archive-wide hit count — `GuestSearchService.section` sets it to
`items.size()`, so it can never exceed `perSection`. Render one block per non-empty section, and
take the real hit count for a "see all N" link from the per-kind endpoint in step 3, whose
`Page.totalElements` is the true total. There is no top-level total on the wire either:
`GuestGlobalSearchDTO.total()` is a Java helper, not a `getTotal()` accessor, so Jackson never
serializes it.

**Step 3 — drill into one section**

The "see all" link goes to the per-kind endpoint, which is paged and filterable:
`/api/guest/audios`, `/api/guest/videos`, `/api/guest/texts`, `/api/guest/images`,
`/api/guest/projects`, `/api/guest/persons`, `/api/guest/categories`.

```bash
curl -s "{{BASE_URL}}/api/guest/audios?q=hawler&language=Kurdish&sortBy=date&sortDirection=desc&page=0&size=24"
```

Note that hitting `/api/guest/search` or `/api/guest/feed` with a non-blank `q` also records the
query for the "popular searches" list surfaced by recipe 1.

---

## 3. Open a project page: detail, then its media

**Step 1 — the project record**

```bash
curl -s "{{BASE_URL}}/api/guest/projects/PRJ-014"
```

```json
{
  "id" : 14,
  "projectCode" : "PRJ-014",
  "projectName" : "Hawler Radio Tapes",
  "description" : "Reel-to-reel recordings from the Hawler station.",
  "tags" : [ "radio", "hawler" ],
  "keywords" : [ "reel to reel" ],
  "person" : {
    "id" : 11, "personCode" : "PER-11", "fullName" : "Tahir Tofiq",
    "mediaPortrait" : "https://…"
  },
  "categories" : [ { "id" : 3, "categoryCode" : "CAT-03", "name" : "Music" } ],
  "mediaCounts" : { "audios" : 42, "videos" : 3, "texts" : 0, "images" : 61 },
  "createdAt" : "2025-11-02T08:00:00Z",
  "updatedAt" : "2026-04-19T12:30:00Z"
}
```

What to do with it: `mediaCounts` gives the tab badges ("42 audios · 3 videos") before you fetch
anything else. A missing or non-public project returns **`404` with an empty body** — this handler
uses `ResponseEntity.notFound().build()`, so there is no `ApiErrorResponse` JSON to parse. Branch
on the status code alone.

**Step 2 — the media inside the project**

```bash
curl -s "{{BASE_URL}}/api/guest/projects/PRJ-014/media"
```

```json
{
  "projectCode" : "PRJ-014",
  "projectName" : "Hawler Radio Tapes",
  "audios" : [ { "id" : 91, "audioCode" : "AUD-001",
                 "audioFileUrl" : "/api/guest/audio/AUD-001/stream" } ],
  "videos" : [ ],
  "texts"  : [ ],
  "images" : [ ]
}
```

What to do with it: this is a flat object, not a page — every public item of the project is
returned in one shot. The elements of `audios`, `videos`, `texts` and `images` are the same full
DTO shapes returned by `/api/guest/audios/{audioCode}` and friends, so you can render cards
without a second round trip.

To load one tab at a time, pass `type`. The service accepts `all` (or an omitted/blank value) plus
the singular and plural spellings, case-insensitively: `audio`/`audios`, `video`/`videos`,
`text`/`texts`, `image`/`images`. Only the requested key is included in the response, alongside
`projectCode` and `projectName`.

```bash
curl -s "{{BASE_URL}}/api/guest/projects/PRJ-014/media?type=audio"
```

As with step 1, a missing or non-public project gives a bodiless `404`.

---

## 4. Play an audio track in a browser

**Step 1 — read the DTO field**

```bash
curl -s "{{BASE_URL}}/api/guest/audios/AUD-001"
```

The response is a `GuestAudioDTO`. The field you need is `audioFileUrl`:

```json
{
  "id" : 91,
  "audioCode" : "AUD-001",
  "originTitle" : "Bastay Hawler",
  "singer" : "Tahir Tofiq",
  "duration" : "00:04:12",
  "language" : "Kurdish",
  "audioFileUrl" : "/api/guest/audio/AUD-001/stream"
}
```

`audioFileUrl` is **always a relative API path**, never an S3 URL — `GuestMapper` builds it as
`/api/guest/audio/{audioCode}/stream`. The bytes are proxied by the backend; the storage URL is
never sent to the browser.

**Step 2 — prepend the base URL and check the stream**

```bash
curl -s -D - -o /dev/null "{{BASE_URL}}/api/guest/audio/AUD-001/stream"
```

A full request answers `200 OK` with `Accept-Ranges: bytes`, `Cache-Control: public, max-age=300`,
`X-Content-Type-Options: nosniff` and `Content-Disposition: inline; filename="…"`. The
`Content-Type` is derived from the file extension: `audio/mpeg` (`.mp3`), `audio/ogg`,
`audio/wav`, `audio/flac`, `audio/aac`, `audio/mp4` (`.m4a`), otherwise
`application/octet-stream`.

**Step 3 — confirm seeking works**

```bash
curl -s -D - -o /dev/null -H "Range: bytes=0-1023" \
  "{{BASE_URL}}/api/guest/audio/AUD-001/stream"
```

A `Range` request answers `206 Partial Content` with `Content-Range: bytes 0-1023/<total>`. Only
the requested window is fetched from storage. An unparsable `Range` value never errors with `416`:
the parser falls back to the whole object, so the reply is still `206 Partial Content` but carries
every byte and `Content-Range: bytes 0-<total-1>/<total>`.

**Step 4 — hand the URL to the element**

```html
<audio controls preload="metadata"
       src="{{BASE_URL}}/api/guest/audio/AUD-001/stream"></audio>
```

Nothing else is required: the endpoint is public (`/api/guest/**` is `permitAll`), so no cookie or
header is needed, and `Accept-Ranges` plus `206` support means the browser's scrub bar works. Only
records with `removedAt IS NULL` are served on the guest path.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `404` | `NOT_FOUND` | No live record with that code, no stored file on the record, or the storage object is missing |
| `500` | `INTERNAL_SERVER_ERROR` | The storage key could not be derived, or the byte range could not be read |

---

## 5. Read a text item and show its cover

**Step 1 — the text record**

```bash
curl -s "{{BASE_URL}}/api/guest/texts/TXT-0042"
```

```json
{
  "id" : 42,
  "textCode" : "TXT-0042",
  "originalTitle" : "Diwani Nali",
  "author" : "Nali",
  "documentType" : "book",
  "isbn" : "978-0-00-000000-0",
  "pageCount" : 318,
  "language" : "Kurdish",
  "textFileUrl" : "/api/guest/text/TXT-0042/read",
  "coverImageUrl" : "/api/guest/text/TXT-0042/cover"
}
```

Both URLs are relative API paths built by `GuestMapper`. Critically, **`coverImageUrl` is omitted
entirely when the record has no cover** — the mapper only advertises the cover proxy when a cover
actually exists, precisely so the frontend never renders an `<img>` that is guaranteed to 404.
Test for the field's presence, not for an empty string.

**Step 2 — render the cover**

```html
<img src="{{BASE_URL}}/api/guest/text/TXT-0042/cover" alt="Diwani Nali">
```

```bash
curl -s -D - -o /dev/null "{{BASE_URL}}/api/guest/text/TXT-0042/cover"
```

The cover endpoint returns an `ETag` derived from the text code and honors `If-None-Match`: a
matching conditional request short-circuits to `304 Not Modified` with no storage round-trip.
Guest responses carry `Cache-Control: public, max-age=3600`.

```bash
curl -s -D - -o /dev/null -H 'If-None-Match: "<etag-from-previous-response>"' \
  "{{BASE_URL}}/api/guest/text/TXT-0042/cover"
```

**Step 3 — open the document itself**

```bash
curl -s -D - -o /dev/null "{{BASE_URL}}/api/guest/text/TXT-0042/read"
```

`/read` supports `Range` requests, which is what lets PDF.js and native browser PDF viewers pull
individual pages instead of downloading the whole file first. Guest responses are cached with
`Cache-Control: public, max-age=3600`. Point your viewer at
`{{BASE_URL}}/api/guest/text/TXT-0042/read` directly.

---

## 6. Register, log in with a cookie jar, and call an authenticated endpoint

**Step 1 — register**

```bash
curl -s -c /tmp/khi-cookies.txt \
  -X POST "{{BASE_URL}}/api/auth/register" \
  -H "Content-Type: application/json" \
  -d '{
        "name": "Rojan Ahmed",
        "username": "rojan",
        "email": "rojan@example.com",
        "password": "s3cret!"
      }'
```

Request fields (all required): `name` (max 120), `username` (3–80, letters/digits/underscore),
`email` (max 160, must be a valid address), `password` (6–128). To attach a profile picture
instead, `POST /api/auth/register-with-image` takes `multipart/form-data` with a `data` part
holding the same JSON and an optional `image` file part.

Success is `201 Created`:

```json
{
  "token" : "eyJhbGciOiJIUzI1NiJ9…",
  "response" : "Registration successful. You can now login."
}
```

A duplicate username or email comes back as `400 Bad Request` with the *same* `Token` envelope —
only `response` is populated and `token` is omitted. The same is true of every failure `UserService`
handles itself, so branch on the status code and show `response`. Bean validation is the one
exception: a body that violates the constraints above never reaches the service, and
`GlobalExceptionHandler` answers it with the regular `ApiErrorResponse` shape — `400`,
`"error": "VALIDATION_ERROR"`, one entry per rejected field under `details`.

**Step 2 — log in**

```bash
curl -s -c /tmp/khi-cookies.txt \
  -X POST "{{BASE_URL}}/api/auth/login" \
  -H "Content-Type: application/json" \
  -d '{ "username": "rojan", "password": "s3cret!" }'
```

`username` accepts either the username or the email address. Success is `200 OK`:

```json
{
  "token" : "eyJhbGciOiJIUzI1NiJ9…",
  "response" : "Login successfully done."
}
```

Failure paths, again as a `Token` body with only `response`: `401` for wrong credentials (the
message states how many attempts remain), `403` when the account is locked after too many failed
attempts, and `403` when the password has expired.

On success both endpoints also set the auth cookie via `Set-Cookie`, which `-c` writes into the
jar; the failure responses above carry no token, so no cookie is set.

**Step 3 — call an authenticated endpoint with the jar**

```bash
curl -s -b /tmp/khi-cookies.txt "{{BASE_URL}}/api/user/me"
```

```json
{
  "userId" : 57,
  "name" : "Rojan Ahmed",
  "username" : "rojan",
  "email" : "rojan@example.com",
  "role" : "GUEST",
  "isActivated" : true,
  "provider" : "local",
  "createdAt" : "2026-08-26T09:20:00Z",
  "updatedAt" : "2026-08-26T09:20:00Z",
  "passwordExpiryDate" : "2026-11-24T09:20:00Z"
}
```

A self-registered account is created with `role: "GUEST"` and `provider: "local"`, and
`passwordExpiryDate` is set 90 days out (`UserService.PASSWORD_EXPIRY`).

**Cookie-jar caveat.** The auth cookie is issued with the attributes from `application.yaml`:
`jwt.cookie-secure` defaults to `true` and `jwt.cookie-same-site` defaults to `None`. A `Secure`
cookie is only replayed over HTTPS, so a jar captured from a plain `http://localhost` run will not
be sent back. Either point `{{BASE_URL}}` at an HTTPS origin, or use the token from the response
body — the JWT filter resolves `Authorization: Bearer <jwt>` before falling back to the cookie:

```bash
TOKEN="eyJhbGciOiJIUzI1NiJ9…"
curl -s "{{BASE_URL}}/api/user/me" -H "Cookie: khi_auth_token=$TOKEN"
# or
curl -s "{{BASE_URL}}/api/user/me" -H "Authorization: Bearer $TOKEN"
```

Calling a protected path with no credentials returns `401` with
`"error": "TOKEN_MISSING"`; an expired token gives `TOKEN_EXPIRED`, a tampered one
`TOKEN_INVALID_SIGNATURE` or `TOKEN_MALFORMED`, and a logged-out one `TOKEN_REVOKED`.

**Step 4 — log out**

```bash
curl -s -b /tmp/khi-cookies.txt -c /tmp/khi-cookies.txt \
  -X POST "{{BASE_URL}}/api/auth/logout"
```

Returns the plain-text body `Successfully logged out`, blacklists the presented token and clears
the cookie. `POST /api/auth/logout-all` additionally deactivates every session row for the user
and answers `Logged out from all devices successfully`. Neither path is in the `permitAll` list,
so an unauthenticated call is rejected by the security filter with `401` /
`"error": "TOKEN_MISSING"` before the controller runs; the controller's own
`400 Authentication token is missing` branch (and `logout-all`'s `401 Not authenticated`) only
fires if a request reaches it without a resolvable token.

---

## 7. Submit a correction on a media record and poll its status

All of `/api/corrections` is annotated `@PreAuthorize("isAuthenticated()")` **on the class**, so
every method needs a signed-in user — any role qualifies, no staff permission is involved. Run
recipe 6 first.

**Step 1 — populate the media-type dropdown**

```bash
curl -s "{{BASE_URL}}/api/corrections/catalog/media-types" \
  -H "Cookie: khi_auth_token=$TOKEN"
```

```json
[ "AUDIO", "VIDEO", "IMAGE", "TEXT" ]
```

**Step 2 — submit the suggestion**

```bash
curl -s -X POST "{{BASE_URL}}/api/corrections" \
  -H "Cookie: khi_auth_token=$TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
        "mediaType": "AUDIO",
        "mediaCode": "AUD-001",
        "targetField": "singer",
        "currentValue": "Tahir Tofik",
        "suggestedValue": "Tahir Tofiq",
        "note": "Spelling on the reel label reads Tofiq."
      }'
```

| Field | Required | Limit |
|---|---|---|
| `mediaType` | yes | one of `AUDIO`, `VIDEO`, `IMAGE`, `TEXT` |
| `mediaCode` | yes | max 255, must match a live record of that type |
| `targetField` | yes | max 100 |
| `currentValue` | no | max 5000 |
| `suggestedValue` | yes | max 5000 |
| `note` | no | max 2000 |

Success is `201 Created`:

```json
{
  "id" : 318,
  "mediaType" : "AUDIO",
  "mediaCode" : "AUD-001",
  "mediaTitle" : "Bastay Hawler",
  "targetField" : "singer",
  "currentValue" : "Tahir Tofik",
  "suggestedValue" : "Tahir Tofiq",
  "note" : "Spelling on the reel label reads Tofiq.",
  "guestUserId" : 57,
  "guestUsername" : "rojan",
  "guestDisplayName" : "Rojan Ahmed",
  "status" : "PENDING",
  "recordCreatedBy" : "sara.h",
  "createdAt" : "2026-08-26T09:31:00Z",
  "updatedAt" : "2026-08-26T09:31:00Z"
}
```

Keep `id` — it is the handle for step 3. `recordCreatedBy` is the username of the staff member who
created the media record; the service copies it onto every submission, so it is on the wire
whenever the record has one. It is internal bookkeeping — do not render it. The forwarding and
resolution fields (`forwardedBy`, `forwardedAt`, `forwardNote`, `resolvedBy`, `resolvedAt`,
`resolveNote`, `removedAt`) are null on a fresh submission and therefore omitted.

`targetField`, `currentValue`, `suggestedValue` and `note` are trimmed and HTML-escaped
(`HtmlUtils.htmlEscape`) before they are stored, so a value containing `&`, `<` or `"` comes back
in its escaped form. Render it as text, not as HTML.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `VALIDATION_ERROR` | A `@NotBlank` / `@NotNull` / `@Size` rule failed; `details` names each rejected field |
| `400` | `JSON_PARSE_ERROR` | Body is not valid JSON, or `mediaType` is not one of the four enum values; `details.field` names the offending field |
| `401` | `TOKEN_MISSING` | No credentials presented |
| `404` | `CORRECTION_NOT_FOUND` | `mediaCode` matches no live record of that `mediaType` |

**Step 3 — poll one submission**

```bash
curl -s "{{BASE_URL}}/api/corrections/me/318" \
  -H "Cookie: khi_auth_token=$TOKEN"
```

Returns the same DTO with a refreshed `status`, which moves through the `CorrectionStatus` enum:

| `status` | Meaning |
|---|---|
| `PENDING` | Submitted, awaiting admin review |
| `FORWARDED` | Admin sent it to the employee who created the record |
| `RESOLVED` | Admin marked it resolved |
| `REJECTED` | Admin rejected the suggestion |

`FORWARDED` fills `forwardedBy` / `forwardedAt` / `forwardNote`; `RESOLVED` and `REJECTED` fill
`resolvedBy` / `resolvedAt` / `resolveNote`. Poll politely — every read of this endpoint is
written to the correction audit trail.

A deleted or unknown id gives `404` with `"error": "CORRECTION_NOT_FOUND"`. Requesting an id that
belongs to a different user is refused by the service, but the exception it raises
(`IllegalAdminOperationException`, carrying the code `CORRECTION_NOT_YOURS`) has no handler in
`platform/exceptions/ApiExceptionHandler.java` — the advice that covers this controller's
package — so it lands in that class's `Exception` catch-all and surfaces as
`500` / `"error": "INTERNAL_SERVER_ERROR"` with the generic message
`"An unexpected error occurred."`. Do not build UI that expects the `CORRECTION_NOT_YOURS` string
on the wire; simply never request an id the user did not receive from `GET /api/corrections/me`.

**Step 4 — list all of the user's submissions**

```bash
curl -s "{{BASE_URL}}/api/corrections/me?page=0&size=25" \
  -H "Cookie: khi_auth_token=$TOKEN"
```

A standard Spring `Page` of the same DTO, newest first (sorted `createdAt` descending). `page`
and `size` are optional: a null or negative `page` becomes `0`, and a null or non-positive `size`
becomes `25`, clamped to a maximum of `200`.

---

## 8. Paginate correctly through a large result set

**Step 1 — request page 0 and read the envelope**

```bash
curl -s "{{BASE_URL}}/api/guest/images?projectCode=PRJ-014&page=0&size=24"
```

The per-kind guest listings (`/projects`, `/categories`, `/persons`, `/audios`, `/videos`,
`/texts`, `/images`) return the standard Spring `Page` envelope described in
`./01-conventions.md`: `content`, `pageable`, `totalElements`, `totalPages`, `number`, `size`,
`first`, `last`, `numberOfElements`, `empty`.

**Step 2 — drive the loop from `totalPages` / `last`, not from a guessed count**

```bash
page=0
while : ; do
  body=$(curl -s "{{BASE_URL}}/api/guest/images?projectCode=PRJ-014&page=${page}&size=24")
  echo "$body" | jq -r '.content[].imageCode'
  [ "$(echo "$body" | jq -r '.last')" = "true" ] && break
  page=$((page + 1))
done
```

**Step 3 — sort with `sortBy` and `sortDirection`, not with `sort`**

```bash
curl -s "{{BASE_URL}}/api/guest/images?page=0&size=24&sortBy=date&sortDirection=desc"
```

The guest listings sort in the service using the `sortBy` / `sortDirection` request parameters and
then slice by page offset. Spring's own `sort=field,dir` parameter is not consulted by that
slicing step, so use `sortBy` / `sortDirection`.

Accepted `sortBy` values, all case-insensitive; anything unrecognized leaves the natural order
untouched:

| Endpoint | Accepted `sortBy` values |
|---|---|
| `/api/guest/projects` | `name`, `alpha`, `alphabet`, `alphabetical`, `projectname`, `code`, `projectcode`, `createdat`, `created`, `added`, `updatedat`, `updated`, `modified` |
| `/api/guest/audios` | `title`, `name`, `alpha`, `alphabet`, `origintitle`, `code`, `audiocode`, `date`, `datecreated`, `published`, `datepublished`, `createdat`, `created`, `added` |
| `/api/guest/videos` | `title`, `name`, `alpha`, `alphabet`, `originaltitle`, `code`, `videocode`, `date`, `datecreated`, `published`, `datepublished`, `createdat`, `created`, `added` |
| `/api/guest/texts` | `title`, `name`, `alpha`, `alphabet`, `originaltitle`, `code`, `textcode`, `date`, `datecreated`, `published`, `datepublished`, `createdat`, `created`, `added` |
| `/api/guest/images` | `title`, `name`, `alpha`, `alphabet`, `originaltitle`, `code`, `imagecode`, `date`, `datecreated`, `published`, `datepublished`, `createdat`, `created`, `added` |

`sortDirection` is `desc` when the value equals `desc` case-insensitively, and ascending
otherwise.

`/api/guest/persons` and `/api/guest/categories` declare no `sortBy` / `sortDirection` parameters
at all, so sending them changes nothing. Both fall back to name order — `fullName` and `name`
respectively, case-insensitive ascending — when `q` is absent, and to match order when `q` is
present. The nested listings `/api/guest/categories/{categoryCode}/projects` and
`/api/guest/persons/{personCode}/projects` are always ordered by `projectName` ascending.

**Step 4 — respect the defaults and the caps**

| Endpoint group | Default `size` | Notes |
|---|---|---|
| `/api/guest/categories` | `100` | `@PageableDefault(size = 100)` |
| Every other guest listing and `/api/guest/feed` | `50` | `@PageableDefault(size = 50)` |
| `/api/corrections/me` | `25` | Clamped to a maximum of `200` |

The guest listings and the feed bind `size` through Spring Data's resolver, which is left at its
defaults: pages are zero-indexed and `size` is capped at `2000`, so a larger value is silently
reduced. `/api/corrections/me` does its own clamping instead, as the table says.

Two caps that change what deep paging means:

- **Text search is capped.** When `q` is present on any guest listing, the text-match candidate
  set is capped at 500 rows for that entity kind (`GuestSearchService.MAX_LIMIT`) before filters
  and paging are applied. On the four media listings that capped set is then widened with every
  public item belonging to a project or person whose own name matched `q`, which is what makes a
  performer's name return their recordings. `totalElements` therefore reflects that candidate set
  after filtering — not the whole archive. Narrow the query or switch to structured filters
  (`projectCode`, `categoryCode`, `personCode`, `language`, `tag`, …) instead of paging deeply
  through a broad `q`.
- **The feed pages per section.** `/api/guest/feed` applies one shared `page`/`size` to each of
  the four sections independently, so `size=12` can return up to 48 items. Advance the page only
  while the top-level `hasNext` is true, and read each section's own `last` flag to know which
  ones have run out.

**Step 5 — for `/api/guest/feed`, page the sections together**

```bash
curl -s "{{BASE_URL}}/api/guest/feed?q=hawler&types=audio&types=image&page=1&size=12"
```

`types` is repeatable and also accepts a comma-separated list. Recognized values are `image`,
`images`, `photo`, `photos`, `audio`, `audios`, `sound`, `sounds`, `video`, `videos`, `text`,
`texts`. Unrecognized values — including `project` and `person` — are ignored, and if nothing
recognized remains, all four kinds are returned.

---

## Related

- [External API index](./README.md) — the full list of public-surface docs.
- [Conventions](./01-conventions.md) — the `Page` envelope, timestamp formats, the
  `ApiErrorResponse` shape and the paging/sorting rules referenced throughout these recipes.
- [Errors](./02-errors.md) — the full `ErrorCode` catalog behind every `error` value quoted here.
- [Authentication](./03-authentication.md) — register, login, logout and the `khi_auth_token`
  cookie in full (recipe 6).
- [Discovery](./04-discovery.md) — search, suggest, facets, trending and the feed envelope
  (recipes 1, 2, 8).
- [Catalog](./05-catalog.md) — projects, categories and persons (recipe 3).
- [Media](./06-media.md) — the audio, video, text and image listings and their DTO fields
  (recipes 4, 5).
- [Streaming](./07-streaming.md) — the byte proxies, `Range` handling and caching (recipes 4, 5).
- [Corrections](./08-corrections.md) — the correction endpoints in full (recipe 7).
