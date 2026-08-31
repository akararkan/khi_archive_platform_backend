# External API Documentation

> **Audience:** public website, anonymous visitors, third-party clients ·
> **Base paths:** `/api/guest`, `/api/auth`, `/api/user`, `/api/corrections` ·
> **Scope:** everything callable without a staff permission

This folder documents the **external** surface of the KHI Archive Platform backend: the read-only
public catalog and search under `/api/guest/**`, the byte proxies that stream audio, video, images
and texts, self-service registration and login, a signed-in visitor's own profile and device
sessions, and the "Help Us" correction submissions under `/api/corrections`. Nothing here needs a
`<resource>:<action>` permission — the endpoints are either fully public or gated by nothing
stronger than "you are signed in". It deliberately does **not** cover the staff back office: content
CRUD, bulk create, trash and restore, visibility control, tags and keywords administration, the
maqam voting panel, physical-media inventory and Excel import, admin user and permission management,
warnings, audit logs and analytics, plus the database, caching, S3 and configuration references, all
live in [`../internal/`](../internal/README.md) and are not for public consumption.

## Contents

| File | What it covers | Read it when |
|---|---|---|
| [`00-overview.md`](./00-overview.md) | The whole external surface in one table, the exact list of endpoints reachable with no token, base URL, content types per endpoint, the `khi_auth_token` cookie, and why media never leaves through an S3 URL | You are new to this API, or you need to settle "does this call need a token?" and "which folder documents this endpoint?" before writing any code |
| [`01-conventions.md`](./01-conventions.md) | The shared contract: `page`/`size`, the Spring `Page` envelope, `sortBy`/`sortDirection` and their accepted aliases per endpoint, date and time-zone formats, omitted `null` fields, multipart shape, CORS, `Range` handling, and every HTTP status the app actually produces | A list comes back in the wrong order because you passed `sort=` instead of `sortBy`, a field you expected is missing from the JSON, a timestamp is offset by hours, or a browser call dies in CORS preflight |
| [`02-errors.md`](./02-errors.md) | The `ApiErrorResponse` envelope field by field, the complete `ErrorCode` set grouped by status, `details` payloads per code, error categories, the legacy `UserApiErrorResponse`, and which producer answers which request | A request failed and you have to branch on `error` rather than the human message, or you are writing the client's central error handler and need the closed set of codes |
| [`03-authentication.md`](./03-authentication.md) | Register, register-with-image, login, logout, logout-all, own sessions, own profile, password change, account deletion; the cookie contract, validation rules, the two response envelopes, and a full curl walkthrough | You are building sign-in or sign-out, a call returns `401` right after a successful login, or you need to show a user their active devices and revoke one |
| [`04-discovery.md`](./04-discovery.md) | `GET /api/guest/trending`, `/search`, `/suggest`, `/facets` and the grouped `/feed` — parameters, response shapes, the trending pipeline, and what gets written to `guest_search_logs` and `guest_interaction_logs` | You are building the home page, a search box with autocomplete, or a facet sidebar, or you need to know why a query you ran shows up later in trending |
| [`05-catalog.md`](./05-catalog.md) | Projects, categories and persons: paged lists, detail by business code, a project's media, the child listings, and the two-flag visibility gate that decides what an anonymous caller may see | A project you can see in the back office is missing from the public listing, or a detail call returns `404` with an empty body and you need to know whether the record is hidden or absent |
| [`06-media.md`](./06-media.md) | The four public media catalogs — audios, videos, texts, images — with per-kind filters, `sortBy` values, free-text `q`, and the `Guest…DTO` field lists showing which technical fields are stripped | A list endpoint ignores your filter or sort key, or you need the exact field names on a media object before binding a detail page to it |
| [`07-streaming.md`](./07-streaming.md) | The five byte proxies (`/stream`, `/view`, `/read`, `/cover`), where their URLs come from, the visibility gate they apply, `Range` and `206` behavior, `ETag` revalidation, response headers, and page-level usage | An audio or video player cannot seek, an image refetches on every load instead of returning `304`, or you are tempted to build an S3 URL by hand |
| [`08-corrections.md`](./08-corrections.md) | `POST /api/corrections` plus the submitter's own views: the correction object, `CorrectionMediaType` values, choosing `targetField`, the status lifecycle, and what a submitter can and cannot see | You are building the "Help Us" form, or a resubmission fails with `CORRECTION_ALREADY_PROCESSED` and you need the lifecycle rules |
| [`09-recipes.md`](./09-recipes.md) | Eight end-to-end curl walkthroughs chaining the endpoints: home page, search-as-you-type, project page, audio playback, text plus cover, register and log in with a cookie jar, submit and poll a correction, and paginate a large result set | You want a known-good sequence to copy rather than assembling four endpoint pages yourself, or a multi-call flow works in isolation but breaks when chained |

## Start here

1. [`00-overview.md`](./00-overview.md) — learn what the external surface exposes and which calls
   need no token at all.
2. [`01-conventions.md`](./01-conventions.md) — paging, `sortBy`/`sortDirection`, date formats and
   null omission apply to nearly every endpoint that follows.
3. [`02-errors.md`](./02-errors.md) — wire up error handling once, against the closed `ErrorCode`
   set, before you write feature code.
4. The endpoint page for what you are building: [`04-discovery.md`](./04-discovery.md) for search
   and browse, [`05-catalog.md`](./05-catalog.md) plus [`06-media.md`](./06-media.md) for detail
   pages, [`07-streaming.md`](./07-streaming.md) for playback,
   [`03-authentication.md`](./03-authentication.md) for sign-in.
5. [`09-recipes.md`](./09-recipes.md) — check your call sequence against a working walkthrough.

## Conventions

Know these before reading anything else in this folder.

- **Auth model** — `/api/guest/**` and the three register/login endpoints are open; every other
  `/api/**` path needs a JWT. See [`00-overview.md`](./00-overview.md#endpoints-reachable-without-a-token).
- **Token transport** — `Authorization: Bearer <jwt>` is read first, the HttpOnly `khi_auth_token`
  cookie second; browsers use the cookie. See
  [`01-conventions.md`](./01-conventions.md#authenticating-a-request) and
  [`03-authentication.md`](./03-authentication.md#the-auth-cookie-contract).
- **Error envelope** — every failure is one `ApiErrorResponse` shape; switch on `error`, never on
  `message`. See [`02-errors.md`](./02-errors.md#1-the-envelope).
- **Pagination** — `page` and `size` in, the standard Spring `Page` envelope out. See
  [`01-conventions.md`](./01-conventions.md#pagination).
- **Sorting** — guest listings order by `sortBy` and `sortDirection`; a `sort` parameter is echoed
  back but does not reorder results, and an unrecognized `sortBy` is silently ignored. See
  [`01-conventions.md`](./01-conventions.md#sorting).
- **`{{BASE_URL}}`** — every example uses this placeholder for your API origin, for example
  `http://localhost:8080`. See [`00-overview.md`](./00-overview.md#base-url).
- **Omitted `null` fields** — null properties are absent from JSON, not serialized as `null`. See
  [`01-conventions.md`](./01-conventions.md#omitted-null-fields).
- **Timestamps** — ISO-8601 strings in the `Asia/Baghdad` zone; request-side dates are `yyyy-MM-dd`
  and date-times `yyyy-MM-dd HH:mm:ss`. See
  [`01-conventions.md`](./01-conventions.md#dates-and-times).
- **Media is proxied, never linked** — DTOs carry host-relative `/api/guest/...` paths; no bucket
  name, object key or presigned URL is ever sent to a client. See
  [`07-streaming.md`](./07-streaming.md#where-the-urls-come-from).
- **Visibility gate** — a trashed or non-public record, or one inside a hidden project, is
  indistinguishable from a record that never existed. See
  [`05-catalog.md`](./05-catalog.md#visibility-gate).

## Related

- [`../README.md`](../README.md) — the documentation root index for the whole project.
- [`../internal/README.md`](../internal/README.md) — the staff back-office, database and operations
  set: the other half of every feature documented here.
- [`../internal/00-overview.md`](../internal/00-overview.md) — what the internal surface covers, if
  you are looking for an endpoint that is not in this folder.
- [`../internal/admin/corrections.md`](../internal/admin/corrections.md) — the review side of
  [`08-corrections.md`](./08-corrections.md): the admin queue, forwarding, applying and rejecting.
- [`../legacy/README.md`](../legacy/README.md) — the superseded root-level feature guides, kept for
  history only. Prefer this folder wherever the two disagree.
