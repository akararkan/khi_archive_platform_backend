# Request and Response Conventions

> **Audience:** public website, anonymous visitors, third-party clients ·
> **Base path:** `/api` ·
> **Source:** `src/main/resources/application.yaml`,
> `src/main/java/ak/dev/khi_archive_platform/platform/config/JacksonConfig.java`,
> `src/main/java/ak/dev/khi_archive_platform/platform/config/WebConfig.java`,
> `src/main/java/ak/dev/khi_archive_platform/platform/config/MultipartJsonConfig.java`,
> `src/main/java/ak/dev/khi_archive_platform/user/configs/AppCorsProperties.java`,
> `src/main/java/ak/dev/khi_archive_platform/user/configs/SecurityConfig.java`,
> `src/main/java/ak/dev/khi_archive_platform/platform/api/guest/GuestSearchAPI.java`,
> `src/main/java/ak/dev/khi_archive_platform/platform/api/audio/AudioStreamAPI.java`,
> `src/main/java/ak/dev/khi_archive_platform/platform/api/video/VideoStreamAPI.java`,
> `src/main/java/ak/dev/khi_archive_platform/platform/api/image/ImageStreamAPI.java`,
> `src/main/java/ak/dev/khi_archive_platform/platform/api/text/TextStreamAPI.java`,
> `src/main/java/ak/dev/khi_archive_platform/user/api/UserProfileAPI.java`

This page is the shared contract every endpoint in this folder obeys: how pages are requested and
returned, how lists are sorted, how dates and times cross the wire, why fields go missing from
JSON, how multipart uploads are shaped, what CORS requires of the browser, how the byte proxies
answer `Range` requests, and which HTTP status codes the API actually produces. Endpoint pages
link here instead of repeating any of it.

## At a glance

| Concern | Convention |
|---|---|
| Response media type | `application/json`, pretty-printed (`spring.jackson.serialization.indent-output: true`) |
| Paging parameters | `page`, `size`, `sort` — resolved into a Spring `Pageable` |
| Paged response | Standard Spring `Page` envelope (`content`, `pageable`, `totalElements`, …) |
| List ordering | `sortBy` + `sortDirection` query parameters |
| Date parameters | `yyyy-MM-dd` (`spring.mvc.format.date`) |
| Date-time parameters | `yyyy-MM-dd HH:mm:ss` (`spring.mvc.format.date-time`) |
| Timestamps in JSON | ISO-8601 strings, never epoch numbers |
| `null` fields | Omitted entirely (`spring.jackson.default-property-inclusion: non_null`) |
| Uploads | `multipart/form-data`; a binary part, plus an `application/json` `data` part when the endpoint takes a DTO |
| Authentication | `Authorization: Bearer <jwt>` (checked first) or the `khi_auth_token` cookie (fallback) — both fully supported |
| CORS | Credentialed, explicit origin allow-list, `max-age` 3600 |
| Streaming | `Range: bytes=…` honored; `206` with `Content-Range`, `Accept-Ranges: bytes` |
| Errors | The `ApiErrorResponse` envelope, machine code in `error` |

Two facts that shape everything else on this page:

- **Sessions are stateless.** `SecurityConfig` sets `SessionCreationPolicy.STATELESS`, so there is
  no server-side session and no CSRF token (`csrf` is disabled). Every request is authenticated on
  its own from the JWT, or not at all.
- **Response locale is fixed.** `spring.web.locale: ckb` with `spring.web.locale-resolver: fixed`
  means the `Accept-Language` request header is ignored. `spring.messages.basename` points at
  `i18n/messages`, but no such bundle exists under `src/main/resources`, so the `message` and
  `hint` strings you receive are the literals written in the exception handlers.

## Authenticating a request

`JWTAuthenticationFilter.resolveToken` reads the `Authorization` header first and falls back to
the cookie:

| Order | Where the token is read from | Notes |
|---|---|---|
| 1 | `Authorization: Bearer <jwt>` | Prefix is exactly `Bearer ` (`SecurityConstants.TOKEN_PREFIX`) |
| 2 | Cookie `khi_auth_token` | Name comes from `jwt.cookie-name`, default `khi_auth_token` |

Both transports work on every protected endpoint, and the header takes precedence: when a request
carries both, the cookie is never read. Browser clients use the cookie — it is `HttpOnly` by
default (`jwt.cookie-http-only: true`), so page JavaScript cannot read it and must let the browser
attach it. Scripts and server-to-server callers usually send the header instead, using the `token`
value from the login response. Examples in this folder use the cookie form, which works
interchangeably with `-H "Authorization: Bearer $TOKEN"`:

```bash
curl -s "{{BASE_URL}}/api/corrections/me" \
  -H "Cookie: khi_auth_token=$TOKEN"
```

Nothing under `/api/guest/**` needs either form. See
[`./03-authentication.md`](./03-authentication.md) for how the cookie is issued and cleared.

---

## Pagination

### Request parameters

List endpoints declare a Spring `Pageable` argument, which is populated from these query
parameters:

| Name | Type | Default | Description |
|---|---|---|---|
| `page` | int | `0` | Zero-based page index |
| `size` | int | Per endpoint — see below | Number of elements per page |
| `sort` | string, repeatable | none | `property,direction` — see the caveat below |

There is no `spring.data.web.*` block in `application.yaml`, so nothing about the resolver —
parameter names, or a ceiling on `size` — is overridden at the application level. A project-level
maximum for `size` is _Not documented in source._

### Default page size per external endpoint

The default comes from `@PageableDefault` on the controller method and differs by endpoint:

| Endpoint | `@PageableDefault` size |
|---|---|
| `GET /api/guest/projects` | `50` |
| `GET /api/guest/categories` | `100` |
| `GET /api/guest/categories/{categoryCode}/projects` | `50` |
| `GET /api/guest/persons` | `50` |
| `GET /api/guest/persons/{personCode}/projects` | `50` |
| `GET /api/guest/audios` | `50` |
| `GET /api/guest/videos` | `50` |
| `GET /api/guest/texts` | `50` |
| `GET /api/guest/images` | `50` |
| `GET /api/guest/feed` | `50` |

### The `Page` envelope

Paged endpoints return the standard Spring `Page` serialization — the guest listings build a
`PageImpl` in the service, `/api/corrections/me` passes the repository's page through, and either
way the `Page` goes straight to Jackson. The example below is one page of
`GET /api/corrections/me?page=0&size=25`; its `content[]` element shape is documented in
[`./08-corrections.md`](./08-corrections.md), and every other paged endpoint documents its own.

```json
{
  "content" : [ {
    "id" : 41,
    "mediaType" : "AUDIO",
    "mediaCode" : "AUD-0912",
    "mediaTitle" : "Bihar le Hewler",
    "targetField" : "singer",
    "currentValue" : "Unknown",
    "suggestedValue" : "Tehsîn Taha",
    "note" : "Named on the sleeve of the 1974 pressing.",
    "guestUserId" : 8,
    "guestUsername" : "rezan",
    "guestDisplayName" : "Rezan A.",
    "status" : "PENDING",
    "createdAt" : "2026-08-20T07:41:09.318Z",
    "updatedAt" : "2026-08-20T07:41:09.318Z"
  } ],
  "pageable" : {
    "pageNumber" : 0,
    "pageSize" : 25,
    "offset" : 0,
    "paged" : true,
    "unpaged" : false
  },
  "totalElements" : 1,
  "totalPages" : 1,
  "number" : 0,
  "size" : 25,
  "first" : true,
  "last" : true,
  "numberOfElements" : 1,
  "empty" : false
}
```

| Field | Type | Meaning |
|---|---|---|
| `content` | array | The elements on this page, in the endpoint's order |
| `pageable.pageNumber` | int | Echo of the requested `page` |
| `pageable.pageSize` | int | Echo of the effective `size` |
| `pageable.offset` | number | `pageNumber * pageSize` — index of the first element |
| `pageable.paged` | boolean | `true` for every endpoint documented here |
| `pageable.unpaged` | boolean | Inverse of `paged` |
| `totalElements` | number | Total matching elements across all pages |
| `totalPages` | number | Total number of pages at this `size` |
| `number` | int | Zero-based index of this page |
| `size` | int | Effective page size |
| `first` | boolean | `true` when `number == 0` |
| `last` | boolean | `true` when there is no page after this one |
| `numberOfElements` | int | Length of `content` — the last page is usually shorter |
| `empty` | boolean | `true` when `content` is empty |

Spring also serializes a sort descriptor inside `pageable` and again at the top level; it is
elided from the example above. Its exact shape is framework-defined rather than something this
codebase controls, so treat it as opaque and drive your UI from `sortBy` / `sortDirection`
instead.

Paginate from `last` (or `totalPages`), never from a guessed total:

```bash
curl -s "{{BASE_URL}}/api/guest/audios?projectCode=PRJ-014&page=0&size=24"
```

### Endpoints that page differently

Two external endpoints page differently from the pattern above:

| Endpoint | How it pages |
|---|---|
| `GET /api/guest/feed` | Does not use the `Page` envelope at all — a custom grouped envelope — the shared `page`/`size` is applied independently to each media section, and each section carries its own `page`, `size`, `totalElements`, `totalPages`, `numberOfElements`, `first`, `last`, `empty`. See [`./04-discovery.md`](./04-discovery.md) |
| `GET /api/corrections/me` | Takes plain `page` and `size` integer parameters instead of a `Pageable`: a null or negative `page` becomes `0`, a null or non-positive `size` becomes `25`, and `size` is clamped to `200`. The response is still the standard `Page` envelope |

---

## Sorting

Guest list endpoints order results with two query parameters rather than with Spring's `sort`:

| Name | Type | Default | Description |
|---|---|---|---|
| `sortBy` | string | none — the natural order applies (`/api/guest/feed` is the exception, below) | Which property to order by; matched case-insensitively |
| `sortDirection` | string | ascending | `desc` (case-insensitive) reverses the order; anything else leaves it ascending |

An unrecognized `sortBy` value is ignored — the endpoint falls back to its natural order instead of
returning `400`. String comparisons are case-insensitive, and records whose sort property is null
come last in ascending order; `sortDirection=desc` reverses the comparator wholesale, which moves
those records to the front.

**Accepted `sortBy` values.** Each row lists the aliases that select the same ordering.

| Endpoint | `sortBy` aliases | Orders by |
|---|---|---|
| `/api/guest/projects` | `name`, `alpha`, `alphabet`, `alphabetical`, `projectname` | Project name |
| | `code`, `projectcode` | Project code |
| | `createdat`, `created`, `added` | Record creation time |
| | `updatedat`, `updated`, `modified` | Record update time |
| `/api/guest/audios` | `title`, `name`, `alpha`, `alphabet`, `origintitle` | Origin title |
| | `code`, `audiocode` | Audio code |
| | `date`, `datecreated` | Date the material was created |
| | `published`, `datepublished` | Date the material was published |
| | `createdat`, `created`, `added` | Record creation time |
| `/api/guest/videos` | `title`, `name`, `alpha`, `alphabet`, `originaltitle` | Original title |
| | `code`, `videocode` | Video code |
| | `date`, `datecreated` / `published`, `datepublished` / `createdat`, `created`, `added` | As for audios |
| `/api/guest/texts` | `title`, `name`, `alpha`, `alphabet`, `originaltitle` | Original title |
| | `code`, `textcode` | Text code |
| | `date`, `datecreated` / `published`, `datepublished` / `createdat`, `created`, `added` | As for audios |
| `/api/guest/images` | `title`, `name`, `alpha`, `alphabet`, `originaltitle` | Original title |
| | `code`, `imagecode` | Image code |
| | `date`, `datecreated` / `published`, `datepublished` / `createdat`, `created`, `added` | As for audios |

`GET /api/guest/feed` adds a `relevance` value on top of these: `relevance` is the default when `q`
is present, `date` is the default when it is not. When `sortDirection` is omitted the feed picks
`desc` for the date-like values (`date`, `datecreated`, `datepublished`, `published`, `createdat`,
`created`, `added`) and `asc` for every other named value. `relevance` carries no direction at all,
because it selects no comparator — the section keeps its natural relevance order.

`GET /api/guest/categories`, `GET /api/guest/persons` and the two `/projects` sub-listings take no
`sortBy` parameter at all. Their order is fixed in the service: categories sort by name and persons
by full name whenever no `q` is supplied (with `q`, both keep the search's relevance ranking), and
`/categories/{categoryCode}/projects` and `/persons/{personCode}/projects` always sort by project
name. All four comparisons are case-insensitive with nulls last.

**The `sort` parameter caveat.** Guest listings load their candidates from the repository, filter
and order them in the service with a comparator chosen by `sortBy`, then slice the result into a
`PageImpl`. A `sort` query parameter is still accepted by the argument resolver and echoed back
inside `pageable`, but it does not reorder guest results. Use `sortBy` / `sortDirection`.

```bash
curl -s "{{BASE_URL}}/api/guest/audios?sortBy=datepublished&sortDirection=desc&size=24"
```

---

## Dates and times

### Configuration

| Setting | Value | What it governs |
|---|---|---|
| `spring.mvc.format.date` | `yyyy-MM-dd` | Binding of `LocalDate` request parameters and path variables |
| `spring.mvc.format.date-time` | `yyyy-MM-dd HH:mm:ss` | Binding of `LocalDateTime` request parameters and path variables |
| `spring.jackson.time-zone` | `Asia/Baghdad` | The JSON mapper's context time zone (UTC+03:00) |
| `spring.jpa.jdbc.time_zone` | `UTC` | Declared in `application.yaml` for the persistence layer, but at a property path Spring Boot does not bind, so it is inert. Either way it does not change the wire format, which is always a UTC `Instant` |

The two `spring.mvc.format` settings are the fallback pattern for any parameter bound as a
temporal type without its own `@DateTimeFormat`. No endpoint in this folder currently declares
one: the guest date filters are `String` parameters parsed by the controller, as described below.

Responses are written by the Jackson 3 mapper Spring Boot 4 auto-configures for HTTP message
conversion — the same mapper that reads the `spring.jackson.*` keys above — and it writes
`java.time` values as ISO-8601 text. The practical consequence is the one that matters to a client:
**temporal values are ISO-8601 strings, never epoch numbers.**

(`platform/config/JacksonConfig.java` declares a second, Jackson 2 `ObjectMapper` that registers
`JavaTimeModule` and disables `SerializationFeature.WRITE_DATES_AS_TIMESTAMPS`. It parses the JSON
`data` part of multipart uploads, and it is also what the security filter uses to write `401` /
`403` bodies. It honors none of the `spring.jackson.*` settings, so those error bodies arrive
compact rather than pretty-printed — timestamps in them are still ISO-8601, and their nulls are
still omitted because `ApiErrorResponse` carries its own `@JsonInclude(NON_NULL)`.)

### On the way out

`Instant` is the timestamp type used across the DTOs, but the field names differ by kind:
`GuestProjectDTO` and `GuestCorrectionResponseDTO` carry `createdAt` and `updatedAt`,
`GuestCategoryDTO` carries `createdAt`, and the four guest media DTOs carry `dateCreated`,
`dateModified`, `datePublished` and `dateCopyrighted` — plus `printDate` on `GuestTextDTO`.
Record-level `createdAt` / `updatedAt` are deliberately absent from the media DTOs. Every one of
these serializes as a UTC instant with a trailing `Z`:

```json
{ "createdAt" : "2026-08-20T07:41:09.318Z" }
```

Convert to the reader's local zone in the client. The archive's own reference zone is
`Asia/Baghdad` (UTC+03:00), which is what `spring.jackson.time-zone` declares.

### On the way in

The guest date filters are declared as `String` and parsed leniently by
`GuestSearchAPI.parseStart` / `parseEnd`, not by the MVC formatter. Three forms are accepted:

| Form | Example | Interpreted as |
|---|---|---|
| ISO instant | `2020-01-01T00:00:00Z` | Exactly that instant |
| ISO local date-time | `2020-01-01T00:00:00` | That wall time, read as UTC |
| ISO date | `2020-01-01` | `*From`: start of that day, UTC. `*To`: the last nanosecond of that day, UTC — so a day named in `dateTo` is included |

A value that matches none of the three is treated as absent: the filter is dropped and the request
still succeeds. It does not produce `400`.

The parameters that go through this parser are `dateFrom` and `dateTo` (on `/api/guest/audios`,
`/videos`, `/texts`, `/images` and `/feed`), `publishedFrom` and `publishedTo` (the four media
listings only — the feed does not accept them), and `printDateFrom` and `printDateTo` on
`/api/guest/texts` alone. `/api/guest/projects`, `/api/guest/categories` and `/api/guest/persons`
take no date filters at all.

```bash
curl -s "{{BASE_URL}}/api/guest/videos?dateFrom=1979-01-01&dateTo=1979-12-31&size=24"
```

---

## Omitted `null` fields

`spring.jackson.default-property-inclusion` is `non_null`, and `ApiErrorResponse` additionally
carries `@JsonInclude(JsonInclude.Include.NON_NULL)`. A field whose value is `null` is left out of
the JSON entirely rather than written as `"field": null`.

What this means when you consume a response:

- **Absence is the null.** Read fields defensively (`response.note ?? ""`); do not assume a key is
  present because it appears in this documentation.
- **Only `null` is omitted.** An empty string and an empty array are still serialized — `"note": ""`
  and `"tags": []` are values, not absences.
- **Primitives always appear.** `boolean`, `int`, `long` and `double` fields cannot be null, so they
  show up even when they are `false` or `0`.
- **The shape varies per record.** Two elements of the same `content[]` array can carry different
  key sets. A pending correction has no `forwardedAt`; a forwarded one does. A correction can be
  resolved straight from `PENDING` without ever being forwarded, so a `RESOLVED` record does not
  always carry `forwardedAt` either — test for the key, not for the status.
- **The error envelope shrinks too.** `hint`, `path`, `traceId` and `details` disappear when they
  are not set. No code in this application writes a trace id into MDC, so `traceId` is currently
  never populated. Full envelope in [`./02-errors.md`](./02-errors.md).

---

## Multipart requests

Endpoints that accept a file take `multipart/form-data`. When the endpoint also needs a DTO, that
DTO travels as **its own `application/json` part named `data`** — the JSON body is never nested
inside a form field, and an endpoint that uploads nothing but bytes has no `data` part at all.
Spring Boot parses the JSON part natively; `MultipartJsonConfig` exists only to document that and
registers no beans.

| Part | Content type | Contents |
|---|---|---|
| `data` | `application/json` | The DTO that a JSON-only endpoint would take as its request body. Absent where the endpoint takes only a file |
| The file part | The file's own type | The bytes. **The part name varies by endpoint** |

Two multipart endpoints exist on the external surface:

| Method | Path | JSON part | File part | Required |
|---|---|---|---|---|
| `POST` | `/api/auth/register-with-image` | `data` (`RegisterRequestDTO`) | `image` | File part optional |
| `POST` | `/api/user/profile-image` | none | `file` | File part required |

Both declare `consumes = MediaType.MULTIPART_FORM_DATA_VALUE`, so any other request content type is
answered with `415`. The profile-image upload is documented in
[`./03-authentication.md`](./03-authentication.md).

Staff content endpoints use the same pattern with the file part named `file` (media uploads and the
physical-media `.xlsx` import) or `mediaPortrait` (person portraits); text create and update take
two binary parts, `file` and `coverImage`. Those are covered in [`../internal/`](../internal/).

### Configured limits

| Property | Value | Effect |
|---|---|---|
| `spring.servlet.multipart.enabled` | `true` | Multipart parsing is on |
| `spring.servlet.multipart.max-file-size` | `5GB` | Largest single part |
| `spring.servlet.multipart.max-request-size` | `6GB` | Largest whole request — a 5 GB file plus the JSON part and the multipart boundaries must fit |
| `spring.servlet.multipart.file-size-threshold` | `2MB` | Parts above this are buffered to disk instead of memory |
| `server.tomcat.max-swallow-size` | `-1` | Unlimited, so a rejected upload does not break the connection |
| `server.tomcat.max-http-form-post-size` | `-1` | Unlimited; the Spring limits above are the real cap |
| `server.tomcat.max-parameter-count` | `10000` | Cap on request parameters |

The connector limits are set to `-1` deliberately: Tomcat stores them as 32-bit byte counts, so any
value above 2 GB would overflow.

### Multipart failures

| Status | `error` code | When |
|---|---|---|
| `400` | `MISSING_REQUEST_PART` | A required part is absent; `details.part` names it |
| `400` | `BAD_REQUEST` | The multipart request could not be parsed at all (bad boundary, truncated body) |
| `413` | `UPLOAD_TOO_LARGE` | A part exceeded the limit; `details.maxBytes` carries the cap |
| `415` | `UNSUPPORTED_MEDIA_TYPE` | The request content type is not multipart; `details.received` and `details.supported` show both sides |

**Example**

```bash
curl -s -X POST "{{BASE_URL}}/api/auth/register-with-image" \
  -F "data=@register.json;type=application/json" \
  -F "image=@portrait.jpg"
```

`-F "data=@file.json;type=application/json"` is the reliable curl form: the `type=` suffix sets the
part's `Content-Type`, without which the JSON part is not recognized. In a browser, set the part
explicitly, for example
`form.append("data", new Blob([JSON.stringify(dto)], { type: "application/json" }))`.

---

## CORS

CORS is applied twice: a standalone `CorsFilter` registered at `Ordered.HIGHEST_PRECEDENCE` in
`WebConfig`, which runs **before** Spring Security so that `401`, `403` and `500` responses still
carry CORS headers and the browser can read the real error body, plus an MVC-level mapping as a
fallback. `JWTAuthenticationFilter` stamps `Access-Control-Allow-Origin`,
`Access-Control-Allow-Credentials` and `Vary: Origin` as well, for the paths where it
short-circuits.

| Property | Configured value | Effect |
|---|---|---|
| `app.cors.allowed-origins` | `${CORS_ALLOWED_ORIGINS:}` — empty by default | Comma-separated extra origins, merged with the built-in list below |
| `app.cors.allowed-methods` | `GET,POST,PUT,DELETE,OPTIONS,PATCH` | Methods a cross-origin caller may use |
| `app.cors.allowed-headers` | `*` | Every request header is allowed |
| `app.cors.allow-credentials` | `true` | Cookies and credentials may be sent |
| `app.cors.max-age` | `3600` | Preflight result is cacheable for one hour |

`AppCorsProperties.ALWAYS_ALLOWED_ORIGINS` is always permitted regardless of the environment
variable, and the environment list is appended to it:

| Origin |
|---|
| `http://localhost:5173` |
| `http://localhost:3000` |
| `https://khi-archive-platform-frontend.vercel.app` |
| `https://khi-archive-platform.s3.us-east-1.amazonaws.com` |

### What credentialed CORS requires of the client

- **Send credentials explicitly.** `fetch(url, { credentials: "include" })`, or
  `xhr.withCredentials = true`. Without it the browser will not attach `khi_auth_token`, and a
  protected endpoint answers `401` with `TOKEN_MISSING`.
- **Your origin must be on the list.** With `allowCredentials(true)` the response echoes one
  concrete origin; a wildcard is not usable. An origin outside the merged list gets no
  `Access-Control-Allow-Origin` header and the browser blocks the read.
- **The auth cookie is cross-site by default.** `jwt.cookie-same-site` is `None` and
  `jwt.cookie-secure` is `true`, so a browser only stores and returns it over HTTPS. A page served
  over plain HTTP from a different origin will not keep the session.
- **Preflights always pass.** `SecurityConfig` permits `OPTIONS /**` before any other rule, so a
  preflight is never answered with `401`.
- **No response headers are exposed.** The filter configures no `exposedHeaders`, so cross-origin
  JavaScript can read only the CORS-safelisted response headers. This is why `Content-Range` and
  `Accept-Ranges` are invisible to `fetch` across origins; `<audio>`, `<video>` and `<img>` handle
  ranging internally and are unaffected.

---

## Byte-range requests on the streaming endpoints

The five public media proxies fetch bytes from S3 and relay them; no S3 URL ever reaches the
browser. Range behavior differs per kind, and the differences are deliberate:

| Endpoint | Range support | Status without `Range` | Status with `Range` |
|---|---|---|---|
| `GET /api/guest/audio/{audioCode}/stream` | Yes | `200 OK`, whole file | `206 Partial Content` |
| `GET /api/guest/video/{videoCode}/stream` | Yes, always ranged | `206 Partial Content`, first 2 MB | `206 Partial Content` |
| `GET /api/guest/text/{textCode}/read` | Yes | `200 OK`, whole file | `206 Partial Content` |
| `GET /api/guest/image/{imageCode}/view` | No | `200 OK`, whole file | `200 OK`, whole file |
| `GET /api/guest/text/{textCode}/cover` | No | `200 OK`, whole file | `200 OK`, whole file |

Video never returns a full-file `200`: files run to gigabytes, so even a first, `Range`-less
request is served as a `206` carrying only the first `2 * 1024 * 1024` bytes. The player then
issues its own `Range` requests as the viewer watches, and the file is never loaded into the JVM
heap.

### Request header

| Header | Format | Handling |
|---|---|---|
| `Range` | `bytes=start-end` | Either bound may be omitted: `bytes=1024-` runs to the end of the file, `bytes=-500` is read as `bytes=0-500`, and `bytes=-` means the whole file |

Bounds are clamped rather than rejected — `start < 0` becomes `0`, `end >= total` becomes
`total - 1`, and `end < start` becomes `start`. Anything the parser cannot read — a value not
starting with `bytes=`, a non-numeric bound, or a multi-range value such as
`bytes=0-99,200-299` — falls back to the default window: the whole file for audio and text, the
first 2 MB for video. **No `416 Range Not Satisfiable` is ever returned**, and only one range is
ever served.

### Response headers

| Header | Value | On |
|---|---|---|
| `Accept-Ranges` | `bytes` | Audio, video, text read |
| `Content-Range` | `bytes <start>-<end>/<total>` | Every `206` |
| `Content-Length` | Length of the returned slice, not of the file | All byte responses |
| `Content-Type` | Inferred from the stored file extension; `application/octet-stream` when unknown | All byte responses |
| `Content-Disposition` | `inline; filename="<ascii>"; filename*=UTF-8''<encoded>` — RFC 5987, so Kurdish and Arabic filenames survive | All byte responses except the text cover, which sends a bare `inline` |
| `Cache-Control` | `public, max-age=300` for audio and video; `public, max-age=3600` for image, text file and text cover | Public endpoints |
| `X-Content-Type-Options` | `nosniff` | All byte responses |
| `ETag` | Short SHA-1 derived from the code | Image view and text cover only |

The authenticated twins of these endpoints (`/api/audio/{audioCode}/stream`,
`/api/image/{imageCode}/view`, and so on) send `Cache-Control: no-store, private` instead, because
they can serve soft-deleted records for staff preview. They are covered in
[`../internal/`](../internal/); the public proxies only ever serve records where
`removedAt IS NULL`.

### Conditional requests

Image and text-cover responses carry an `ETag`. Send it back as `If-None-Match` and a match is
answered with `304 Not Modified` and no body — the API does not even call S3.

**Example** — fetch the first mebibyte of an audio file and inspect the headers:

```bash
curl -s -D - -o /dev/null \
  -H "Range: bytes=0-1048575" \
  "{{BASE_URL}}/api/guest/audio/AUD-0912/stream"
```

```http
HTTP/1.1 206 Partial Content
Accept-Ranges: bytes
Content-Range: bytes 0-1048575/8213004
Content-Type: audio/mpeg
Content-Length: 1048576
Cache-Control: public, max-age=300
X-Content-Type-Options: nosniff
```

Full details, including the per-kind content-type mapping, are in
[`./07-streaming.md`](./07-streaming.md).

---

## HTTP status codes

Every status below is produced by code in this repository — the controllers, the two
`@RestControllerAdvice` handlers (`platform/exceptions/ApiExceptionHandler`,
`user/exceptions/GlobalExceptionHandler`), the JWT filter, or the security entry-point and
access-denied handler.

| Status | Meaning here | Representative `error` codes |
|---|---|---|
| `200 OK` | Successful read, or a full-file byte response | — |
| `201 Created` | `POST /api/corrections`, `POST /api/auth/register`, `POST /api/auth/register-with-image` | — |
| `204 No Content` | `DELETE /api/user/account` on the external surface, plus the staff delete and restore endpoints; no body | — |
| `206 Partial Content` | A byte range was served — always for video | — |
| `304 Not Modified` | `If-None-Match` matched the image or text-cover `ETag` | — |
| `400 Bad Request` | Malformed input the client can fix | `VALIDATION_ERROR`, `JSON_PARSE_ERROR`, `MISSING_PARAMETER`, `MISSING_REQUEST_PART`, `TYPE_MISMATCH`, `CONSTRAINT_VIOLATION`, `BAD_REQUEST`, `UNKNOWN_PERMISSION`, the per-entity `*_VALIDATION_ERROR` codes |
| `401 Unauthorized` | No usable credentials — sign in and retry | `TOKEN_MISSING`, `TOKEN_EXPIRED`, `TOKEN_MALFORMED`, `TOKEN_INVALID_SIGNATURE`, `TOKEN_INVALID`, `TOKEN_REVOKED`, `BAD_CREDENTIALS`, `AUTHENTICATION_FAILED`, `CREDENTIALS_EXPIRED` |
| `403 Forbidden` | Authenticated, but not allowed. `details.requiredAuthority` names the missing authority when it can be read off the handler's `@PreAuthorize` | `ACCESS_DENIED`, `ACCOUNT_DISABLED`, `MAQAM_PANEL_ACCESS_DENIED` |
| `404 Not Found` | No such record, no such route, or the S3 object behind a media record is missing. The byte proxies report `NOT_FOUND`; the entity-specific codes are raised by the staff endpoints | `NOT_FOUND`, `AUDIO_NOT_FOUND`, `VIDEO_NOT_FOUND`, `IMAGE_NOT_FOUND`, `TEXT_NOT_FOUND`, `PROJECT_NOT_FOUND`, `PERSON_NOT_FOUND`, `CATEGORY_NOT_FOUND`, `CORRECTION_NOT_FOUND`, `USER_NOT_FOUND` |
| `405 Method Not Allowed` | Wrong verb; `details.supportedMethods` lists the right ones | `METHOD_NOT_ALLOWED` |
| `406 Not Acceptable` | No representation matches your `Accept` header; `details.supported` lists what is available | `NOT_ACCEPTABLE` |
| `409 Conflict` | State conflict: duplicate, still in use, already processed, or a concurrent edit | `CONFLICT`, `STALE_VERSION`, `USER_ALREADY_EXISTS`, `CORRECTION_ALREADY_PROCESSED`, the `*_ALREADY_EXISTS` and `*_IN_USE` codes |
| `413 Payload Too Large` | Upload above the multipart cap; `details.maxBytes` gives it | `UPLOAD_TOO_LARGE` |
| `415 Unsupported Media Type` | Wrong request content type | `UNSUPPORTED_MEDIA_TYPE` |
| `423 Locked` | The account is locked, so the credentials cannot be used | `ACCOUNT_LOCKED` |
| `500 Internal Server Error` | Server-side failure — retry shortly. The `message` is deliberately generic; internal details are logged, never returned | `INTERNAL_SERVER_ERROR`, `DATABASE_ERROR`, `STORAGE_ERROR` |
| `504 Gateway Timeout` | A database query timed out | `TIMEOUT` |

Four declared codes are never emitted by any current handler: `INSUFFICIENT_AUTHORITY`,
`RATE_LIMITED` (there is no rate limiter), `SERVICE_UNAVAILABLE` and `EXTERNAL_SERVICE_ERROR`. They
exist in `ErrorCode` for future use — a client switch statement can safely ignore them today.

Almost every non-2xx response uses the same envelope. This is a real `404` from the audio proxy:

```json
{
  "timestamp" : "2026-08-20T07:41:09.318Z",
  "status" : 404,
  "error" : "NOT_FOUND",
  "category" : "NOT_FOUND",
  "message" : "Audio not found",
  "hint" : "Check the identifier and try again.",
  "path" : "/api/guest/audio/AUD-9999/stream"
}
```

Three exceptions worth coding against:

- **Guest detail lookups return an empty body.** `GET /api/guest/audios/{audioCode}`,
  `/videos/{videoCode}`, `/texts/{textCode}`, `/images/{imageCode}`,
  `/projects/{projectCode}`, `/projects/{projectCode}/media`, `/categories/{categoryCode}` and
  `/persons/{personCode}` answer a miss with `ResponseEntity.notFound().build()` — a bare `404`
  with no JSON at all. Do not try to parse an envelope out of it.
- **Registration answers with its own body.** Some failures of `POST /api/auth/register` and
  `POST /api/auth/register-with-image` return the endpoint's `Token` shape
  (`{ "response": "..." }`, with `token` omitted because it is null) instead of the shared
  envelope. See [`./03-authentication.md`](./03-authentication.md).
- **Logout and session management answer in plain text.** `POST /api/auth/logout`,
  `POST /api/auth/logout-all`, `DELETE /api/auth/sessions/{sessionId}` and
  `DELETE /api/auth/sessions/revokeAll` return a bare `String` body — `"Session not found"`,
  `"Not authenticated"`, `"You can only revoke your own sessions"` — on both success and failure,
  served as `text/plain`. Check the status code; do not `JSON.parse` the body.

Switch on `error` for a specific case and on `category` for a family
(`BAD_REQUEST`, `VALIDATION`, `AUTHENTICATION`, `AUTHORIZATION`, `ACCOUNT_STATE`, `NOT_FOUND`,
`CONFLICT`, `MEDIA`, `RATE_LIMIT`, `DATABASE`, `STORAGE`, `EXTERNAL_SERVICE`, `SERVER_ERROR`).
`message` is safe to show to a user. The full code list is in
[`./02-errors.md`](./02-errors.md).

## Related

- [`./README.md`](./README.md) — index of the external documentation set
- [`./00-overview.md`](./00-overview.md) — what the external surface exposes and which endpoints need no token
- [`./02-errors.md`](./02-errors.md) — the `ApiErrorResponse` envelope and the full `ErrorCode` set
- [`./03-authentication.md`](./03-authentication.md) — obtaining and clearing the `khi_auth_token` cookie
- [`./04-discovery.md`](./04-discovery.md) — search, suggest, facets, trending, and the grouped feed envelope
- [`./05-catalog.md`](./05-catalog.md) — the project, category and person listings that page with these parameters
- [`./06-media.md`](./06-media.md) — the media listings whose paging and sorting parameters are defined here
- [`./07-streaming.md`](./07-streaming.md) — the byte proxies, `Range` handling and caching in full
- [`./08-corrections.md`](./08-corrections.md) — the `content[]` shape used in the `Page` example above
- [`./09-recipes.md`](./09-recipes.md) — end-to-end client walkthroughs that apply these conventions
- [`../internal/`](../internal/) — the staff and operational documentation set
