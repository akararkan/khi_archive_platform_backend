# Changelog

All notable changes to the KHI Archive Platform backend are documented in this file.

The format is based on [Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/),
and this project aims to adhere to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## About this changelog

The versions below were **assigned retroactively**. No release has ever been formally cut from
this repository: `pom.xml` still declares `0.0.1-SNAPSHOT`, there are no git tags, and 52 of the
62 commit messages read exactly "new commit". Nothing listed here was published to a registry,
announced, or deployed under the version number it now carries.

This file was reconstructed by reading the diff of every commit on `main` — 62 commits between
2026-04-19 and 2026-07-30 — and grouping them into coherent feature milestones. Each milestone
was then given a `0.x.0` number in chronological order, oldest first. The date on a section is
the date of the **last commit in that group**, not a release date.

Commit ranges at the end of each section are inclusive of both endpoints, so the full range can
be listed with `git log --oneline <first>^..<last>` — except for 0.1.0, whose first commit
`bc94433` is the root commit and has no parent; list that range with `git log --oneline dbea886`.

Because these are reconstructions rather than published releases, "breaking" markers describe a
change relative to the previous milestone's code, not to any artifact a consumer ever installed.
Within the first milestone, breaking markers describe changes made against earlier commits in the
same range, since no prior state existed.

---

## [Unreleased]

### Fixed

- Authentication no longer fails on a request that carries a valid token behind a stale one.
  `JWTAuthenticationFilter` now verifies every token the client sent — the `Authorization` header
  first, then each `khi_auth_token` cookie — and accepts the first that passes. A browser holding
  duplicate cookies for different paths or hosts previously had its first cookie chosen blindly,
  and `clearAuthCookie` could not delete the duplicate, so the resulting
  `401 TOKEN_INVALID_SIGNATURE` survived logging out.
- Every token-rejection payload from the filter now carries `details.source` (`header` or
  `cookie`). Clearing the auth cookie does nothing for a client replaying a dead token from
  `localStorage`; the source tells the frontend which copy to drop.

### Changed

- `JwtTokenProvider` validates `jwt.secret` at startup and fails fast when it is blank. A blank
  `JWT_SECRET` resolves fine as a Spring placeholder, so the app used to boot normally and then
  reject every authenticated request. A secret shorter than 32 bytes logs a warning rather than
  blocking boot.
- Each boot logs the active signing key as a truncated SHA-256 fingerprint
  (`JWT signing key loaded (fingerprint …)`). Comparing it across restarts turns an unexplained
  burst of `TOKEN_INVALID_SIGNATURE` into a one-line diagnosis. The secret itself is never logged.
- The signature-mismatch warning now names the token's source, its unverified subject and
  `issuedAt`, and the active key fingerprint.
- The signing algorithm and verifier are built once at startup instead of on every call to
  `getSubject`, `decodeToken` and friends.

### Editorial notes

Open questions raised while editing this file. Each one needs somebody who knows the code to confirm
it before the next release; none of them asserts anything about current behavior.

- Confirm whether the configured CORS allowed-headers and allow-credentials settings are still
  ignored, as the 0.6.0 entry records, and whether that is intended.
- Confirm what `sortBy=relevance` does on the guest feed now that the 0.7.0 regrouping removed the
  relevance score, and document the intended behavior.
- Track the orphaned `physical_media.sub_type` column that the 0.9.0 backfill deliberately left in
  place, and add a Removed entry here when it is finally dropped.
- No release so far records a `### Deprecated` change. If anything is being phased out rather than
  removed outright, record it under that heading so clients get warning before the removal.

---

## [0.9.0] - 2026-07-30

_Private media streaming, analytics expansion, and vocabulary administration._

### Breaking changes

- **BREAKING** **Media:** `audioFileUrl`, `videoFileUrl` and `imageFileUrl` now return a relative
  API path instead of a public S3 URL — `/api/guest/audio/{audioCode}/stream` on guest responses
  and `/api/audio/{audioCode}/stream` on the authenticated ones, and likewise for video and image —
  so clients must prepend the API base URL and send their JWT on the authenticated variants.
- **BREAKING** **Text:** `textFileUrl` and `coverImageUrl` likewise changed from S3 URLs to
  relative proxy paths — `/api/guest/text/{textCode}/read` and `/cover` on guest responses,
  `/api/text/{textCode}/read` and `/cover` on the authenticated ones.
- **BREAKING** **Physical media:** renamed the `size` field to `physicalSize` across the entity,
  the create and update requests and the response payload.
- **BREAKING** **Physical media:** replaced the `subType` field with a free-text `sizeGB` backed by
  a new `size_gb` column, deliberately not carrying the old sub-type text over; the `sub_type`
  column itself is retired but left in the table.
- **BREAKING** **Physical media:** a manual `POST /api/physical-media` now always server-assigns
  the per-type inventory number as `max(Number) + 1` and ignores any client-supplied value.
- **BREAKING** **Search:** date-range filters on the list endpoints now take a bare `YYYY-MM-DD`
  calendar date resolved in the archive time zone rather than an ISO-8601 instant, so clients
  still sending full timestamps are rejected as malformed.

### Added

- **Media streaming:** added byte-proxying endpoints so media is served through the API instead of
  from S3 — `GET /api/guest/audio/{code}/stream`, `/api/guest/video/{code}/stream` and
  `/api/guest/image/{code}/view`, plus authenticated twins under `/api/audio`, `/api/video` and
  `/api/image`.
- **Media streaming:** audio and video streams now honor the HTTP `Range` header and answer
  `206 Partial Content` with `Content-Range`/`Accept-Ranges`, fetching only the requested byte
  window from S3 so players can seek and downloads can resume.
- **Media streaming:** image responses now carry an ETag derived from the image code and answer
  `304 Not Modified` to a matching `If-None-Match`, skipping the S3 fetch entirely.
- **Text:** added proxied `read` and `cover` endpoints for book and document files, with Range
  support for PDF viewers and content types resolved for PDF, EPUB, DOC/DOCX, TXT and HTML.
- **Text:** text and cover responses are now cacheable for guests (`public, max-age=3600`) and never
  cached for authenticated admin requests (`no-store, private`).
- **Storage:** added non-buffering S3 reads behind the streaming endpoints — an object can be opened
  as a stream, read over a byte range, and sized through a HEAD request instead of a full download.
- **Audio:** audio records now carry a `duration` value, returned by both the admin and guest
  endpoints and accepted as a case-insensitive exact filter on the list endpoint.
- **Media:** audio and video uploads now fall back to server-side duration extraction when the
  client supplies none, reading MP4/QuickTime/WAV container metadata or MP3 frame headers via
  mp3agic and formatting the result as `M:SS` or `H:MM:SS`.
- **Analytics:** added weekly and yearly activity reports at `GET /api/analytics/weekly`
  (Monday-anchored ISO weeks, about a 12-week default window) and `GET /api/analytics/yearly`
  (five whole calendar years by default), taking the same filters as the daily and monthly reports.
- **Analytics:** the per-user activity report and the team overview now carry `weekly` and `yearly`
  bucket lists alongside the daily and monthly series, each bucket reporting its distinct
  active-user count.
- **Analytics:** admin user-management activity joined the cross-table union as the `user` entity,
  whitelisting ROLE_CHANGE, GRANT_PERMISSIONS, REVOKE_PERMISSIONS, ACTIVATE, DEACTIVATE and the
  three warning actions, attributed to the acting admin with the affected username as entity code.
- **Analytics:** added a live inventory snapshot at `GET /api/analytics/inventory` reporting active
  versus trashed counts per item type, counted from the operational tables rather than the audit
  trail.
- **Analytics:** added a visibility snapshot at `GET /api/analytics/visibility` covering visible
  versus hidden projects, the public/private split per media type, and how many active items sit
  inside visible versus hidden projects.
- **Analytics:** added maqam teacher analytics at `/api/analytics/maqam/overview`, `/teachers` and
  `/teachers/{username}`, reporting classification progress, maqam-type distribution, listening
  totals and a per-teacher leaderboard, with 404 for an unknown teacher.
- **Analytics:** every new view now writes its own audit row through the new VIEW_WEEKLY,
  VIEW_YEARLY, VIEW_INVENTORY, VIEW_VISIBILITY and three maqam audit actions.
- **Branding:** added the KHI logo API at `/api/khi-logo` — upload, fetch by id, replace and delete
  a logo image stored in the S3 `khi_logo` folder — backed by a new `khi_logo` table, four
  dedicated permissions held only by admins, and a `KHI_LOGO_NOT_FOUND` error code.
- **Maqam:** added filter and sort parameters to the maqam listing and the admin trash listing,
  covering text-contains on song, producer, code, note and file name, a duration-seconds range,
  created and updated date ranges, and teacher, maqam-type, assignment and vote-status filters.
- **Physical media:** added filter and sort parameters to the inventory listing and its admin trash
  listing, covering case-insensitive equals on the categorical columns, digitization and
  needToClear codes, contains-filters across roughly twenty text columns, numeric ranges and date
  ranges.
- **Search:** added a free-text `q` parameter to the maqam and physical-media listings that
  composes with every other filter and sort, matching across the vote panel for maqam and across
  labels, content, owner, tags and track names for physical media.
- **Search:** added trash-oriented `removedBy` and `removedFrom`/`removedTo` filters so an admin can
  ask what was trashed in a given window.
- **Maqam:** added `GET /api/maqam/maqam-types`, returning the distinct types teachers have actually
  voted on active records, most common first, so the exact-match filter can be driven by a dropdown.
- **Tags:** added the admin tag vocabulary API at `/api/admin/tags` — list distinct tags with live
  usage counts, rename a tag everywhere across audio, video, image, text and project (merging into
  the target when it already exists), and delete a tag everywhere.
- **Keywords:** added the matching admin keyword vocabulary API at `/api/admin/keywords` with the
  same list, rename and delete surface across the six keyword tables the autocomplete draws from.
- **Vocabulary:** added the shared bulk engine behind both admin vocabulary APIs — one native
  statement per collection table over a trusted catalog, usage counted only from non-trashed
  parents, and duplicates collapsed after a rename.
- **Docs:** added `PRIVATE_MEDIA_STREAMING.md`, `FRONTEND_ANALYTICS_GUIDE.md`,
  `SORT_AND_FILTER_REFERENCE.md` and `TAG_KEYWORD_MANAGEMENT.md`.

### Changed

- **Performance: Search:** sort-only requests are now pushed into the database instead of loading
  the whole visible set into memory, with the in-memory engine used only when real filters are
  present or the sort key has no column behind it.
- **Cache:** a vocabulary rename or delete evicts the read caches of every owning entity, which also
  clears the shared tag and keyword suggestion regions, since the bulk statements bypass Hibernate.
- **Tags:** values supplied to the admin vocabulary endpoints are canonicalized with the same rule
  used on save, and a blank source or an over-length target is rejected with a 400; the person
  entity's delimited tag column sits outside the collection-table system and was left untouched.
- **Physical media:** the .xlsx importer now maps the sheet's size headers to `sizeGB` and
  `physicalSize`, and free-text search matches on `physical_size` instead of the retired
  `sub_type` column.
- **Physical media:** a one-shot, idempotent startup migration now backfills
  `physical_media.physical_size` from the legacy `size` column, leaving the orphaned `sub_type`
  column in place for manual removal.
- **Docs:** replaced the guest frontend API guide with a media URL guide covering guest versus
  admin URL prefixes, blob-based admin previews and the 404-versus-500 behavior for missing media.

### Fixed

- **Media streaming:** a media record whose stored S3 object is missing now returns `404 Not Found`
  naming the media code instead of an opaque 500 — the deliberate statuses raised by the streaming
  endpoints are passed through rather than rewritten by the generic catch-all — while genuine
  storage failures still return 500.
- **Media streaming:** non-ASCII filenames survive the download dialog — `Content-Disposition` now
  emits an RFC 5987 `filename*=UTF-8''…` value alongside a sanitized ASCII fallback.
- **Text:** responses no longer advertise a cover-image proxy URL for records that have no cover.
- **Search:** text filters now match Sorani Kurdish values that differ only by codepoint or
  invisible characters, canonicalizing both the stored value and the filter through NFC
  composition, Yeh and Kaf folding, and removal of tatweel, zero-width characters and harakat.
- **Search:** sorted paging is now stable — every generated sort appends an `id ASC` tiebreaker and
  the in-memory comparators mirror it, so paging a sorted list no longer repeats or skips rows.

### Security

- **Media streaming:** S3 object URLs are no longer handed to the browser for audio, video, image or
  text, so storage locations stay server-side.
- **Media streaming:** guest stream endpoints serve only untrashed records, while the authenticated
  endpoints also serve soft-deleted records so an admin can preview what sits in the trash.
- **Media streaming:** every proxied response sets `X-Content-Type-Options: nosniff`, with public
  caching on guest routes and `no-store, private` on admin routes.

_Commits: f4c0660…2bb4e82 (12 commits)_

---

## [0.8.0] - 2026-07-12

_Large uploads, token lifetime, and the code-scheme rework._

### Breaking changes

- **BREAKING** **Project:** project codes are now generated with hyphen separators
  (`PERSONCODE-PROJ-000001`) instead of underscores, for both single and bulk creation.
- **BREAKING** **Project:** projects with no linked person no longer get the fixed `UNTITLED`
  prefix; the prefix is derived from the project name, uppercased with non-alphanumeric runs
  collapsed to underscores.
- **BREAKING** **Media:** media codes for person-less projects first took a
  `PROJECTNAME(CATEGORYCODE)` prefix and then, later the same day, the project code's own prefix
  before `-PROJ-`/`_PROJ_`, with the name-derived prefix kept only as a fallback.
- **BREAKING** **Audio:** renamed the `fullName` field to `fileName` in request and response
  payloads and the backing column from `fullname` to `file_name`, repointing audio search, ranking,
  the trigram and btree indexes and the audit log title at the new column.

### Added

- **Storage:** uploads larger than 16 MB are now streamed to S3 as a multipart upload in 16 MB
  parts instead of being read fully into memory, so very large media no longer has to fit in heap.
- **Text:** text records gained a cover image, uploaded as an optional `coverImage` multipart part,
  stored under `texts/covers/{textCode}` and returned as `coverImageUrl` on the admin, guest and
  items responses.
- **Text:** bulk payloads and the seed loader now accept a pre-existing `coverImageUrl`, so imported
  books keep their cover without re-uploading a file.
- **Media uploads:** a create or update that leaves `fileName` blank now stores the uploaded file's
  original filename automatically, for audio, video, image and text alike.
- **Project:** project creation now accepts an optional `projectCode` in the request body, trimmed
  and used as-is, honored by both single and bulk create.
- **Testing:** added unit tests covering the S3 multipart path — correct part splitting just over
  the part size, and abort plus a storage exception when a part upload fails.

### Changed

- **Auth:** the default JWT lifetime was extended from 24 to 72 hours, still overridable through
  `JWT_EXPIRATION_MS`.
- **Uploads:** raised the maximum uploadable file size from 1 GB to 5 GB, with a 6 GB request cap
  to leave room for the JSON part and multipart boundaries.
- **Database:** Hibernate schema management was pinned back to `update` rather than being taken
  from an environment variable, because a create or create-drop mode wiped the sessions table
  behind token revocation and logged every user out on restart.
- **Security:** the project's S3 bucket origin is now always allowed by CORS, alongside the two
  localhost development origins and the Vercel frontend.
- **Config:** moved the error-response detail settings from `server.error` to `spring.web.error`.
- **Project:** server-side project-code generation and its per-prefix lock now run only as a
  fallback for requests that omit `projectCode`, and the create audit entry for a person-less
  project records the stored project code rather than the derived prefix.
- **Text:** purging a text from trash and cascading a project delete now also remove the stored
  cover image from S3, not just the text file.
- **Docs:** documented the new project create flow and added a recommended frontend rendering
  section to the frontend API guide.

### Removed

- **Auth:** removed the standalone `jwt.cookie-max-age` setting and its environment variable, since
  cookie lifetime is now derived from the token lifetime.
- **Auth:** removed the unused servlet session cookie configuration; authentication is carried
  entirely by the JWT cookie.
- **Media:** removed `ProjectCodeSupport.primaryCategoryCode` and with it the "At least one category
  is required" failure that media-code generation raised for person-less projects.

### Fixed

- **Auth:** the authentication cookie's `Max-Age` is now derived from the configured JWT lifetime so
  cookie and token expire on the same clock — previously the cookie lasted one day while the token
  lived three and browsers silently stopped sending a still-valid token — and startup now fails
  fast on a non-positive expiry.
- **Storage:** a failed large upload aborts its in-progress S3 multipart upload instead of leaving
  orphaned parts in the bucket.
- **Config:** Tomcat's swallow and form-post limits are now unlimited because those connector
  limits are 32-bit byte counts that overflowed at the 5 GB setting, leaving the Spring multipart
  limits to enforce the real cap.
- **Media import:** bulk-import media code generation no longer fails for projects that have
  neither a person nor a category.

_Commits: b904488…851bf42 (8 commits)_

---

## [0.7.0] - 2026-06-25

_The guest media feed and public visibility rules._

### Breaking changes

- **BREAKING** **Guest feed:** `GET /api/guest/feed` was introduced as a single paginated
  UNION ALL page of slim cards and then reshaped into a grouped object with separate `images`,
  `audios`, `videos` and `texts` sections, each carrying the full per-type DTO and its own paging
  metadata.
- **BREAKING** **Guest feed:** the shared `page`/`size` request is now applied independently to each
  selected section, so `size=12` returns up to 12 of each kind rather than 12 rows overall.
- **BREAKING** **Guest feed:** project and person cards were added to the feed and then removed
  again two days later; the feed is media-only and `types=project` or `types=person` is silently
  ignored, with those entities browsable through their own endpoints.
- **BREAKING** **Guest API:** removed `GET /api/guest/results` and its unified-result response
  shape, superseded by the database-paginated feed.
- **BREAKING** **Guest visibility:** an audio, video, text or image is now shown only if it is
  untrashed, not explicitly marked non-public, and its project is untrashed and not hidden, and
  media whose `isPublic` flag is null counts as public where before only an explicit true was shown.
- **BREAKING** **Guest feed:** the hand-built native query, its relevance score and its batched
  hydration were removed with the regrouping, so `sortBy=relevance` now falls back to plain field
  ordering within each section.

### Added

- **Guest feed:** added feed filters for free-text `q`, a repeatable `types` filter, project,
  category and person codes, language, dialect and region, repeatable subject, genre, tag and
  keyword, a date range over `dateCreated`, and a `sortBy` of relevance, date, published date or
  title.
- **Guest feed:** `types` now accepts comma-separated values in addition to repeated parameters,
  plus the UI-facing aliases `photo`/`photos` for images and `sound`/`sounds` for audio.
- **Guest search:** added `date` and `published` as media sort aliases for `dateCreated` and
  `datePublished`.
- **Testing:** added the first unit test for the grouped feed, asserting section order, per-section
  kind tagging and that total elements sum the four sections.

### Changed

- **Guest feed:** the fixed kind grouping was introduced as photos, videos, sounds then texts and
  changed a day later to photos, sounds, videos then texts, with the caller's sort ordering rows
  only within each kind block.
- **Guest visibility:** project and media counts on guest category, person and project pages now
  count only publicly visible rows instead of all active rows.
- **Performance: Guest feed:** while it existed, the feed was served by a dynamically assembled
  UNION ALL native query that emitted predicates only for supplied filters, computed relevance in
  the SELECT and applied ordering, limit and offset across the unioned stream, with a parallel
  count query and batched project, person and category hydration so the JVM hydrated one page only.
- **Guest API:** a commit that deleted the feed outright and tightened visibility to a strict
  `isPublic == TRUE` check was reverted four minutes later, restoring the feed and the lenient
  handling of null flags — and rolling back with it the visibility filtering of autocomplete
  suggestions and the cap-after-filter fix for global search sections.
- **Build:** the HTTP server port is now read from the `PORT` environment variable, defaulting to
  8080, so the application can bind a port assigned by a container platform.
- **Docs:** added a guest frontend API guide documenting the feed endpoint, type values, supported
  filters and the expected render order.

### Fixed

- **Guest feed:** corrected the person table name in the feed query, which referenced a
  non-existent `persons` table and would have failed every request.
- **Guest feed:** rewrote PostgreSQL `::type` casts as `CAST(... AS ...)` because Hibernate's
  named-parameter parser read them as placeholders.
- **Guest feed:** legacy rows whose `is_public` or `is_visible_to_public` was still null are treated
  as public through `COALESCE`, instead of being hidden by a strict equality test.
- **Projects:** project list responses served from the read cache now carry the public-visibility
  flag, which was previously left unset.
- **Projects:** a project whose visibility flag was never set is reported as publicly visible rather
  than null, in the project list and in the `projectVisibleToPublic` field of image, text and video
  responses.

_Commits: cd3fe82…8a66bcc (12 commits)_

---

## [0.6.0] - 2026-06-15

_The unified items endpoint, the typed error envelope, tag vocabulary, Caffeine cache, and guest
trending._

### Breaking changes

- **BREAKING** **Auth:** removed the password-reset flow — `POST /api/auth/reset-token` and
  `/api/auth/reset-password`, their public security rules, the reset-token delivery service and the
  `reset_token` and `reset_token_expiration` columns are all gone, with expired-password messages
  now directing users to their profile or an admin.
- **BREAKING** **Config:** rewrote `application.yaml` onto required environment variables — the
  datasource reads `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER` and `PGPASSWORD`, the JWT cookie
  settings lost their inline defaults, the seed-loader block and Hibernate batch tuning were
  dropped, the S3 bucket and base folder changed, and the production frontend origin was dropped
  from the CORS allow-list.
- **BREAKING** **Auth:** JWT cookie settings regained inline defaults later the same day (cookie
  name `khi_auth_token`, 24-hour expiry, Secure, HttpOnly, SameSite=None), and the servlet session
  cookie was switched from Secure=false/SameSite=Strict to Secure=true/SameSite=Lax so auth cookies
  are only sent over HTTPS.
- **BREAKING** **Physical media:** the .xlsx importer no longer upserts on media type plus physical
  label, which silently merged distinct artifacts sharing a label; every sheet row becomes its own
  record, the `updated` counter was removed from the import report, and a failing row is retried
  with encoded and date columns stripped rather than skipped.

### Added

- **Items:** added `GET /api/items`, a single paged list merging audio, video, image and text with
  free-text search plus filters on type, project, person, category, language, record and project
  visibility and date ranges, sortable by date, title, code, project, person or type.
- **Visibility:** added a project-level `isVisibleToPublic` flag settable at create and update time,
  with `visibilityCascade=CASCADE` pushing the value onto every active media record under the
  project in one bulk statement and evicting their read caches.
- **Visibility:** audio, video, image and text responses now expose their own `isPublic` flag
  alongside the owning project's flag, so a list row can show both without extra calls.
- **Visibility:** added one-field visibility toggles — `PATCH /api/{type}/{code}/visibility`, a
  type-dispatching `PATCH /api/items/{type}/{code}/visibility`, and
  `PATCH /api/project/{projectCode}/visibility` accepting the same optional cascade — all
  idempotent and returning 404 for trashed records.
- **Maqam:** added `GET /api/maqam/teacher/my-recent`, the signed-in teacher's feed of every active
  record they are assigned to, newest activity first, each row carrying their vote state, listen
  progress, audio duration and a stream URL.
- **Physical media:** added `GET /api/physical-media/next-number`, previewing the inventory number
  the server would mint for the next record of a given type, and audited media-type catalog changes
  as TYPE_CREATE, TYPE_UPDATE and TYPE_DELETE.
- **Tags:** added tag and keyword autocomplete at `GET /api/tags/suggest` and
  `GET /api/keywords/suggest`, ranked exact before prefix before substring with usage-count and
  alphabetical tie-breaks.
- **Trending:** added `GET /api/guest/trending`, returning the top 20 trending items across media
  kinds with the full entity inlined, the top 10 guest search queries of the last 24 hours, and the
  top 5 per media type, cached for five minutes.
- **Trending:** added `guest_interaction_logs` and `guest_search_logs`, indexed for aggregation and
  cleanup, with rank computed as a time-decay weighted count in PostgreSQL — three points within
  the hour, two within a day, one within seven days.
- **Trending:** guest list, search and unified-result responses now carry `isTrending`,
  `trendingRank` and `trendingScore`, stamped from a cached five-minute snapshot so no extra query
  is issued per request.
- **Infrastructure:** enabled Spring async execution and scheduling with a dedicated bounded
  executor used only for fire-and-forget view and search logging, plus a nightly 3 AM job that
  deletes tracking rows older than 30 days and evicts the trending caches.
- **Media metadata:** audio gained a `singer` field and a multi-valued `subject` list; video, text
  and image gained `region`; and image additionally gained `language` and `dialect`.
- **Guest API:** added `singer`, `contributor` and `subject` filters to guest audio, `region` to
  guest video and text, `isbn` to guest text, and `language`, `dialect` and `region` to guest
  images, with all new metadata exposed in the responses.
- **Users:** self-service profile updates now accept an email change, validated, normalized and
  checked for uniqueness, with blank fields treated as no-ops so partial updates work.
- **Users:** added DNS deliverability checking for email addresses, requiring an MX record with an
  A/AAAA fallback, applied only to GUEST accounts, failing open on resolver errors and disableable
  through `app.email.verify-mx=false`.
- **CORS:** added an MVC-level CORS mapping across all routes, driven by the configured origins,
  methods and max-age; allowed headers and allow-credentials are hard-coded to `*` and true.
- **Cache:** added an explicit cache manager with individually tuned regions — seven single-entry
  entity list caches, the two autocomplete caches, three analytics caches, the two trending caches
  and a new `users:details` cache.

### Changed

- **Errors:** every error response now uses one envelope carrying timestamp, status, a
  machine-readable code, a category, a user-safe message, an optional recovery hint, the request
  path, a trace id and structured details, with null fields omitted and a new error-code catalog as
  the single source of truth.
- **Auth:** authentication failures are classified rather than returning one generic 401, with the
  filter distinguishing expired, revoked, invalid-signature, malformed and otherwise invalid tokens.
- **Tags:** tags and keywords are canonicalized on save — NFKC-normalized, zero-width characters
  stripped, whitespace collapsed, lower-cased and deduplicated, with over-length entries dropped
  rather than truncated — across audio, video, image, text, project and category writes.
- **Cache:** replaced the Redis cache backend with an in-process Caffeine cache, so the application
  no longer needs a Redis server to run.
- **Cache:** any media, project or category mutation also evicts the tag and keyword suggestion
  caches, so autocomplete no longer serves stale entries after an edit.
- **Performance: Auth:** JWT blacklist checks are served from a 10,000-entry two-minute cache and
  logout writes the revocation into it immediately, removing the blacklist and session lookups from
  repeat requests.
- **Performance: Auth:** the signed-in user's details are cached per username for one minute, with
  every profile update, role change, permission change, lock, activation, create and delete evicting
  the entry.
- **Storage:** the S3 client is now built with explicit static credentials from
  `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY` instead of the default provider chain, and the
  region, bucket and folder names became environment-overridable.
- **Storage:** added a dedicated person-folder setting alongside the renamed S3 bucket and base
  folder.
- **CORS:** a standalone CORS filter now runs at highest precedence, before the Spring Security
  chain, so security-generated 401, 403 and 500 responses carry CORS headers, and the security
  chain no longer defines its own CORS source.
- **CORS:** the two localhost development origins and the Vercel frontend are hard-coded as
  always-allowed credentialed origins merged ahead of anything the environment supplies, and the
  configured allowed-headers and allow-credentials settings are effectively no longer honored since
  both layers hard-code `*` and true.
- **Deployment:** set `forward-headers-strategy=framework` so the app honors `X-Forwarded-*` headers
  behind a reverse proxy, renamed the Spring application, pinned the PostgreSQL driver class and
  updated the deployed frontend origin.
- **Database:** Hibernate schema management churned within this range — switched to `create-drop`,
  reverted two minutes later, then made environment-driven through a required `JPA_DDL_AUTO`.
- **Seed data:** added `app.seed.load` and `app.seed.dir` settings so seeding can be toggled and
  relocated per environment.
- **Performance: Database:** re-added Hibernate batching — batch fetch size 1000, JDBC batch size
  100, ordered inserts and updates.
- **Search:** free-text search matches the new fields, with audio singer and subjects and media
  region participating in prefix, substring and trigram ranking.
- **Physical media:** an inventory update's audit entry now names the fields that actually changed
  instead of recording a bare `PATCH`.

### Removed

- **Configuration:** removed all Redis connection settings — host, port, password, pool, key prefix
  and TTL — and the Redis debug logger.
- **Auth:** removed the unused Google OAuth2 client registration and redirect-uri setting, which
  had no supporting code or dependency.

### Fixed

- **API:** corrected two mis-nested Jackson configuration keys, restoring indented output and the
  omission of null fields from JSON responses.
- **Auth:** the JWT filter now stamps CORS headers on every short-circuited error response, so the
  JSON error body is readable in the browser instead of being hidden behind a generic CORS failure.
- **Configuration:** corrected the indentation of the cache log level, which had been left at the
  document root as an invalid config block.

_Commits: dd0ec3a…bb4868a (14 commits)_

---

## [0.5.0] - 2026-06-03

_Maqam, corrections, warnings, and the physical media inventory._

### Breaking changes

- **BREAKING** **Analytics:** LIST actions no longer count as work — they are excluded from every
  aggregate, and the `listed` field on per-entity stats and `listCount` on user summaries were
  removed from the response.

### Added

- **Maqam:** added the List-of-Maqam feature — a song record with full CRUD at `/api/maqam`, admin
  trash, restore and purge at `/api/admin/maqam`, a panel of one to three teachers per record, and
  per-teacher maqam-type votes with notes.
- **Maqam:** added audited, download-proof playback at `GET /api/maqam/{maqamCode}/stream`, which
  proxies the S3 bytes through the backend with Range support so the storage URL is never exposed
  and every range request is recorded as a STREAM audit row.
- **Maqam:** added listen-session tracking through start, progress and end endpoints so a teacher's
  actual listening time on each record is recorded, with per-record and per-teacher listings for
  admins.
- **Auth:** added a TEACHER role and the maqam permission family, seeding teachers with read and
  vote only, granting employees read, create, update and teacher management, and backfilling the
  teacher-management grant onto existing employee accounts at boot.
- **Corrections:** added the guest correction-suggestion system — any signed-in user can submit a
  field correction and track it, while admins search, forward, resolve, apply, reject or delete
  suggestions, with apply writing the suggested value onto the target record.
- **Corrections:** added a dedicated `guest_correction_audit_logs` trail and correction permissions
  gating the admin endpoints.
- **Warnings:** added the in-app user-warning system — admins send, edit and revoke severity-tagged
  warnings, recipients read, count and acknowledge their own, and every action is recorded in the
  user audit log.
- **Physical media:** added the inventory for cassettes, reels, DVDs and the like — a 29-column
  record covering inventory number, labels, content, ownership, digitization status and nine
  technical capture fields, with CRUD and search at `/api/physical-media` and admin trash, restore
  and purge.
- **Physical media:** added bulk .xlsx import with a sheet-listing endpoint, headers matched by
  Kurdish or English text with whitespace and zero-width characters normalized away, deduplication
  on media type plus physical label, and a report of matched headers, unknown headers and per-row
  errors.
- **Physical media:** added an editable media-type catalog holding each type's nine technical
  defaults so the create form can autofill them, pre-populated by a non-destructive boot seeder
  that leaves admin edits alone.
- **Physical media:** added a digitization status enum mapping the sheet's 0/1/2 codes to
  NOT_DIGITIZED, DIGITIZED and DUPLICATED, stored as a readable label rather than an integer.
- **Auth:** added the physical-media permission family, seeding employees with read, create, update
  and import through a backfill while remove, delete and catalog management stay admin-only.
- **Analytics:** added monthly work statistics at `GET /api/analytics/monthly` and monthly bucket
  lists inside the overview and per-user activity responses, each bucket carrying its
  distinct-actor count.
- **Analytics:** added `GET /api/analytics/actions/catalog` and correction backlog counts — total,
  pending, forwarded, resolved and rejected, plus a by-media-type breakdown — on the team overview.
- **Visibility:** added a per-record `isPublic` flag defaulting to true on audio, video, image and
  text, honored by every guest browse, search, detail and per-project media endpoint.
- **Guest API:** guest media responses now include the linked person summary and the owning
  project's categories, so detail pages no longer need a second lookup.
- **Seed data:** added an idempotent boot-time seed loader that imports categories, persons,
  projects and the four media types in foreign-key order, skipping records whose business code
  already exists, shipped with generated payloads and generation scripts.
- **Database:** added CHECK-constraint re-sync initializers for the analytics, guest-correction,
  maqam, user and physical-media audit-action columns and the digitization column, so adding an
  enum value no longer breaks inserts under `ddl-auto=update`.
- **Build:** added Apache POI 5.3.0 to support .xlsx parsing.
- **Docs:** added `MAQAM_FEATURE.md` and `PHYSICAL_MEDIA_FEATURE.md`.

### Changed

- **Analytics:** maqam and physical-media activity were folded into the cross-table union feed as
  selectable entity keys — maqam's actions were whitelisted for the actions filter and its table
  joined the audit-log index initializer, while physical media records an IMPORT action once per
  uploaded workbook.

_Commits: af7b046…8d05eb2 (2 commits)_

---

## [0.4.0] - 2026-05-10

_The public guest browse and search API._

### Added

- **Guest API:** added a public, unauthenticated browse and search API under `/api/guest` —
  `/search` returning top matches per section with counts, `/suggest` powering search-box
  autocomplete, and `/facets` returning per-media-type counts plus category, person, language,
  dialect, region, genre, tag and keyword buckets.
- **Guest API:** added `GET /api/guest/results`, a unified ranked feed across audio, video, text and
  image where a single query matches media titles, person names and project names, narrowable by
  project, category, person, language, dialect, tags, keywords, date range and media type.
- **Guest API:** added public per-entity listings and detail-by-code endpoints for projects,
  categories, persons and the four media types, plus per-project media, per-category projects and
  per-person projects.
- **Guest API:** added a guest DTO projection layer and mapper so public responses expose only
  presentable fields plus the media file URL, keeping storage paths, classification, technical
  fields, version internals and audit bookkeeping away from anonymous callers, with all queries
  scoped to untrashed records.
- **Search:** added typo-tolerant project search combining case-insensitive matching over name,
  code and description with trigram similarity over name, tags and keywords.
- **Database:** added active-scope repository finders and counters for the guest browse paths,
  including batched per-project media lookups, active media counts for the per-project badge, and
  active project counts per person and per category.

### Changed

- **Auth:** everything under `/api/guest/**` is permitted without a token and the JWT filter skips
  those paths entirely, so a stale cookie never turns an anonymous visit into a 401.

_Commits: d64b25e (1 commit)_

---

## [0.3.0] - 2026-05-04

_Admin user management, per-user permissions, and list filtering._

### Breaking changes

- **BREAKING** **Auth:** self-registration and admin-less user creation now default to the GUEST
  role instead of EMPLOYEE, so a new account has no resource permissions until an admin promotes it
  or grants permissions.
- **BREAKING** **Auth:** EMPLOYEE no longer carries baseline authorities; new employees are seeded
  with a default grant set covering read, create and update on the seven archive resources —
  deliberately excluding soft-remove, now admin-only — which an admin can then edit individually,
  and seeding is skipped when the user already has grants.
- **BREAKING** **Analytics:** `GET /api/analytics/me` and `/users/{username}` replaced the `recent`
  limit parameter with `page`, `size` and `sort`, and the `recent` field is now a paginated page
  object with total counts instead of a plain list; `/feed` gained the same sort parameter.
- **BREAKING** **Analytics:** a transposed date window is now rejected instead of being silently
  swapped, and the analytics caches were re-keyed to `.v2` names to account for the new pagination
  parameters.

### Added

- **Admin:** added the user-management API at `/api/admin/users` — list and fetch users, create,
  update and hard-delete them, change a role, grant and revoke individual permissions, activate,
  deactivate, lock, unlock, reset the failed-login counter, force logout of all sessions, and read
  the role and permission catalogs that populate admin dropdowns.
- **Auth:** added per-user permission grants stored in a `user_permissions` table and merged with
  the role's authorities, so an admin can give one user a single capability without promoting them.
- **Audit:** added a `user_audit_logs` table recording every admin user-management action with the
  target user, the acting admin's authorities, the originating session, IP, device and request
  method and path, written in a separate transaction so rows survive a rollback.
- **Admin:** added guard rails — an admin cannot demote their own account, strip their own user
  permissions, deactivate or lock themselves, or delete the last remaining admin, and permission
  grants are validated against the catalog with unknown strings rejected.
- **Admin:** added a read API over the user audit log with a paged, filterable listing by target,
  actor, action, time range and free text, a single-row lookup, actions and actors catalogs, and a
  per-user shortcut.
- **Errors:** added two typed admin errors — an illegal-admin-operation exception mapping to 409
  with structured details, and an unknown-permission exception mapping to 400 listing the offending
  strings and pointing at the permission catalog.
- **Database:** added a boot-time initializer that drops and recreates the role CHECK constraint
  from the live role enum, so newly added roles stop failing under `ddl-auto=update`.
- **Media:** the audio, video, image and text list endpoints now accept a large filter and sort
  catalog — case-insensitive exact matches on categorical fields, substring matches on long-text
  fields, any/all matching over genre, contributors, tags and keywords, boolean and numeric-range
  filters, ISO date ranges over content and audit dates, and sort keys with field-name synonyms.
- **Person:** the person list endpoint gained filters for gender, person type, region, birth and
  death date ranges, place of birth and death, tags and keywords, audit date ranges, and sorting by
  name, dates or audit timestamps.
- **Category:** the category list endpoint gained created and updated date-range filters, tag
  matching with any/all semantics, and sort by name or audit timestamps.

### Changed

- **Auth:** merged the account-management permissions into the single permission catalog and deleted
  the separate user-permission enum, so a role now carries one permission set.
- **Auth:** session management became stateless, with the JWT filter reloading the user from the
  database on every request.
- **Admin:** granting or revoking extra permissions on an admin is rejected with a 409 and a locked
  error code, since admin authorities come from the role and stray grants would survive a demotion.
- **Errors:** 403 responses now explain the denial, carrying the authority the handler required, the
  calling user, their full sorted authority list and the request method.
- **Audit:** listing users no longer writes a LIST audit row and the audit-log read endpoints are
  not self-audited, so page loads stop drowning out genuine changes.
- **Audit:** list-endpoint audit entries record which filters and sort were applied alongside the
  result count.
- **Database:** the audit-log index initializer now also indexes the user audit table.
- **Performance: Media:** filtering and sorting run in memory over the already-cached active list
  rather than hitting the database, and a request with no filter parameters short-circuits to the
  previous cached pass-through.

_Commits: 6b2cbae…0d46871 (3 commits)_

---

## [0.2.0] - 2026-04-30

_Search, bulk import, pagination, the trash model, and audit analytics._

### Breaking changes

- **BREAKING** **API:** the seven list endpoints now return a paged response with a default size of
  100 and accept `page`, `size` and `sort`, instead of returning the full array of active records.
- **BREAKING** **Security:** `/api/**` previously permitted everything and now requires a valid
  token, leaving only register, register-with-image, login and the two reset endpoints public, with
  every controller method additionally requiring a specific authority.
- **BREAKING** **Auth:** rewrote the permission model into `<resource>:<action>` permissions for the
  seven archive resources, moved account permissions into a separate enum, removed the SUPER_ADMIN
  role and added a permission-less GUEST role.
- **BREAKING** **Trash:** replaced soft-remove plus hard-delete with a trash model on all seven
  resources — the `PATCH /{code}/remove` endpoint is gone and `DELETE /{code}` now trashes the
  record and requires the resource's delete authority, where soft-remove had only needed the remove
  authority that employees held.
- **BREAKING** **Trash:** `DELETE /api/person/{personCode}` now returns 200 with a body listing the
  project codes trashed alongside the person, instead of 204 No Content.

### Added

- **Search:** added typo-tolerant multi-token search endpoints for audio, image, text, video,
  category and person, each taking a query and an optional limit, where a row matches only if every
  token hits somewhere in its own columns or child collections, ranked by prefix match, substring
  match and trigram similarity.
- **Database:** added startup initializers that create the pg_trgm extension plus GIN trigram and
  btree text-pattern indexes on every searchable column of the media, category and person tables
  and their child collection tables.
- **Bulk import:** added JSON bulk-create endpoints for audio, image, text, video, category and
  project that auto-generate codes from an in-memory per-project counter, skip invalid or duplicate
  rows, insert in one transaction and return a requested, inserted, skipped and elapsed summary.
- **Analytics:** added an admin-only analytics API at `/api/analytics` with eight endpoints sharing
  one filter set and served by a single union query across the seven audit-log tables, with hot
  reads cached.
- **Analytics:** added self-auditing of the analytics console, recording which view was opened, the
  filter used and the actor's session and request context.
- **Trash:** added trash management on every resource — a paged trash listing, restore, and a purge
  that permanently deletes the record and its stored file — all admin-only, with purge requiring
  the record to already be trashed.
- **Trash:** trash operations now cascade, so trashing or restoring a project moves all of its media
  and trashing or restoring a person moves all of their projects and their media, while purge is
  blocked while a person is still referenced by a project or a category still joined to one.
- **Concurrency:** added optimistic locking to the seven entities through a version column, so a
  concurrent edit fails with 409 and a stale-version code, with existing rows backfilled at startup.
- **Audit:** added SEARCH, RESTORE and PURGE audit actions across the entity audit enums, recording
  the query, tokens, limit and hit count on every search, with an initializer dropping the stale
  Hibernate-generated CHECK constraint so the new values persist.
- **Audit:** category audit entries now record session context, matching the other entity logs.
- **Testing:** added a fixture generator and six 1000-row JSON payloads exercising the bulk
  endpoints and multilingual search with Kurdish, romanized and English content.

### Changed

- **Performance: Caching:** added a read-side cache in front of the list endpoints, one per entity
  holding all active records and evicted on every write, with response DTOs made serializable and
  collections copied into plain lists so cached values hold no Hibernate session references.
- **Performance: Database:** tuned Hibernate for the new bulk and list paths with lazy-collection
  batch fetching, JDBC insert batching and ordered inserts and updates.
- **Performance: Analytics:** added a startup initializer creating actor, time and action indexes on
  all eight audit-log tables, which the aggregations depend on.

### Fixed

- **Auth:** authorities are read from the live user record on every request instead of from the JWT
  claims, so a role or permission change takes effect immediately rather than after reissue.
- **Concurrency:** entity-code generation is serialized with a transaction-scoped advisory lock
  keyed per project and prefix, so two simultaneous creates can no longer generate the same code,
  while creates against different projects still run in parallel.

_Commits: 188df1e…53afad0 (2 commits)_

---

## [0.1.0] - 2026-04-27

_The initial platform and the core archive domain._

This is the first reconstructed milestone, covering the initial import and the build-out of the
archive domain. The breaking markers below describe changes made against earlier commits inside
this same range; there was no prior state for them to break.

### Breaking changes

- **BREAKING** **Auth:** removed the GUEST role, so self-registration and admin-created users
  without an explicit role became EMPLOYEE and received permissions they previously did not have.
- **BREAKING** **Person:** renamed the portrait multipart part from `image` to `mediaPortrait` on
  create and update, and made a portrait file required when creating a person.
- **BREAKING** **Person:** a missing person now returns 404 and a duplicate person code 409, where
  both previously surfaced as 400.
- **BREAKING** **Object:** removed the archive-object feature entirely — its endpoints, entity,
  DTOs, repositories, service and error codes — with projects taking its place as the parent of
  archive media.
- **BREAKING** **Audio:** audio moved from person or object parentage to a required project code,
  with responses returning project id, code, name and the project's category codes instead of the
  object fields.
- **BREAKING** **Audio:** the generated audio code changed twice within this range, ending at
  `<PARENT>_AUD_<VERSION>_V<n>_Copy(<n>)_000001` with the sequence counted per project, and
  creation began requiring a RAW or MASTER version plus version and copy numbers of at least one.
- **BREAKING** **Audio:** the genre field changed from a single string to a list stored in a
  collection table, so requests and responses now send and return an array.
- **BREAKING** **Category:** removed automatic category-code generation from the name; a code must
  now be supplied by the caller and is validated as non-blank letters, digits, underscores and
  hyphens.
- **BREAKING** **Project:** project codes changed from person plus category to a per-owner sequence,
  so one person can hold multiple projects without a code collision.
- **BREAKING** **Database:** renamed the soft-delete columns and response fields from `deleted_at`
  and `deleted_by` to `removed_at` and `removed_by` on person, category and audio.
- **BREAKING** **API:** deletion became two-step for person, category, audio and project — a patch
  endpoint soft-removes the record while delete permanently removes the row and is restricted to
  admins, with audio hard delete also dropping the stored file.

### Added

- **Build:** created the backend as a Maven and Spring Boot 4.0.5 application on Java 21 with
  PostgreSQL, Spring Data JPA, Spring Security, Spring Cache backed by Redis, the AWS S3 SDK v2,
  JJWT and auth0 java-jwt, Hibernate Validator, Actuator, metadata-extractor and spring-dotenv,
  with Lombok pinned to 1.18.42.
- **Auth:** added the authentication API at `/api/auth` with register, register-with-image, login,
  reset-token, reset-password, logout and logout-all.
- **Auth:** added JWT authentication over an HttpOnly cookie, validated per request, with expired
  or revoked tokens clearing the cookie and returning a JSON error and logout blacklisting the
  token so it cannot be reused.
- **Auth:** added device-aware login sessions recording session id, device, IP, login time and
  expiry per issued token, exposed at `/api/auth/sessions` for listing and revoking one or all.
- **Auth:** added the role and permission model expanding each role into user-scoped authorities
  plus a role authority, with method security enabled.
- **Auth:** added a password-reset token flow behind a pluggable delivery interface, shipped with an
  implementation that logs the token instead of emailing it.
- **Users:** added the self-service profile API at `/api/user` — read the current user, update
  profile fields, change password, upload or remove a profile image, and delete the account.
- **Person:** added the person API with list, fetch, create, update and delete by person code,
  carrying a multipart portrait, nickname and romanized name, gender, person types, region, birth
  and death dates with an explicit precision, places, tags, keywords and notes, where deletion is
  permanent and also removes the portrait from S3.
- **Category:** added the category API with create, list, fetch, update and soft delete by code, and
  a guard preventing deletion while an active record still uses the category.
- **Category:** added a keyword list on categories, intended to prevent near-duplicate categories,
  accepted on create and update and returned in responses.
- **Project:** added the project (collection) API with list, get by code, create, update, soft
  remove and hard delete, linking an optional person to one or more categories and carrying
  description, tags and keywords, with codes generated automatically.
- **Project:** added referential guards keyed off projects, so a category cannot be removed while an
  active project uses it, a person cannot be removed while they still have active projects, and a
  project cannot be hard-deleted while media is attached.
- **Audio:** added the audio API with multipart create and update uploading to S3, plus list, fetch
  by code and soft delete, and an archival metadata record covering titles, form, maqam and basta
  type, genre, abstract, speaker, producer, composer, contributors, language and dialect, lyrics
  and poet, recording venue and dates, tags and keywords, physical availability, digitization
  details, technical fields and rights fields.
- **Video:** added the video API and entity with technical metadata, descriptive metadata, rights
  and provenance, physical-archive location and tag and keyword lists, plus generated codes
  restricted to a fixed version vocabulary and a project link fixed at creation time.
- **Image:** added the image API and entity with color and DPI metadata, version and copy numbering
  and soft-remove auditing, with the same code scheme and version vocabulary.
- **Text:** added the text API and entity with script, language and page-count metadata, version and
  copy numbering and soft-remove auditing, with the same code scheme and version vocabulary.
- **Analytics:** added per-entity audit logging for every archive entity, written to its own table
  in a separate transaction, capturing the acting user with their authorities, the resolved login
  session, device and IP, HTTP method and path, and an escaped field-level description of what
  changed.
- **Storage:** added the S3 storage service used by all uploads — upload from byte array or
  multipart, download by URL, delete by URL or key, bulk delete, filename sanitizing and public URL
  construction — with dedicated folders for profile images and person portraits.
- **Errors:** added module exception handlers returning a consistent JSON error body and mapping
  domain exceptions to statuses, including not-found, conflict for duplicates and integrity
  violations, bad request for validation, and 413 for oversized uploads.
- **Errors:** added per-field validation details on the multipart create and update endpoints in
  place of one concatenated message, with malformed or missing data parts surfacing the
  entity-specific validation code rather than a generic bad-request code.
- **Config:** added the application configuration — PostgreSQL datasource, Redis cache with a
  ten-minute TTL, 1 GB multipart and Tomcat upload limits, an environment-driven CORS allowlist,
  JWT cookie settings, the ckb locale and the Asia/Baghdad JSON timezone.
- **Security:** configured the filter chain to disable CSRF, apply the CORS allowlist and permit
  preflight requests, and wired the JWT authentication entry point and access-denied handler into
  its exception handling.
- **Validation:** added shared code-format patterns for the archive entities and dropped an unused
  minimum-length password pattern that no DTO referenced.

### Changed

- **Errors:** unified the error format across the whole application behind one shared response
  record, so unauthenticated and forbidden requests return that JSON body instead of the servlet
  container's default error page, and broadened coverage to missing parameters and parts, type
  mismatches, unsupported media types, integrity violations, data-access failures and unmatched
  routes.
- **Database:** switched Hibernate schema management from create-drop to update, so the database is
  no longer wiped on every restart.
- **Config:** registered an application object mapper with Java time support and ISO date
  serialization rather than numeric timestamps.

_Commits: bc94433…dbea886 (8 commits)_

---

## Versioning policy

This project is pre-1.0 and its public surface is an HTTP API plus a database schema managed by
`ddl-auto=update`. Until 1.0.0, use the following rules.

**Patch — `0.x.Y`.** A change no client can observe as a contract change: a bug fix that restores
intended behavior, an internal refactor, a performance improvement, a documentation change, added
tests, or a configuration default that does not change request or response shapes. Examples from
this history: returning 404 instead of 500 for a missing S3 object, or adding an `id` tiebreaker so
sorted paging stops repeating rows.

**Minor — `0.X.0`.** Anything additive, and — because SemVer allows breaking changes in `0.x`
without a major bump — anything breaking as well, provided it is flagged. Additive means a new
endpoint, a new optional query parameter or filter, a new response field, a new permission, or a
new table or nullable column. Breaking means anything a client must change for: removing or
renaming an endpoint, field, or business-code format; changing an HTTP status or error code for an
existing condition; changing pagination or response shape; requiring authentication or a new
authority on an existing route; or changing how an existing parameter is parsed. Examples from this
history: renaming audio `fullName` to `fileName`, changing `DELETE /{code}` from a hard delete to a
trash operation, and changing date-range filters from ISO instants to bare calendar dates.

**Major — `X.0.0`.** Reserve for the first stable release and, after that, for any of the breaking
categories listed above. Once 1.0.0 is cut, breaking changes must wait for a major bump or ship
behind a versioned path.

**Start tagging.** The commit ranges above are verifiable from git, but the version numbers are
not — nothing in the repository records them. Tag the current tip so the next entry in this file
has a real boundary to compare against:

```sh
git tag -a v0.9.0 -m "0.9.0 - private media streaming, analytics expansion, vocabulary administration"
git push --tags
```

Then set the project version in `pom.xml` to match the release, and bump it to the next snapshot
once work resumes:

```xml
<version>0.9.0</version>
<!-- after tagging, on the next commit: -->
<version>0.10.0-SNAPSHOT</version>
```

The earlier milestones can be tagged retroactively at the commit each section ends on, for example
`git tag -a v0.1.0 dbea886 -m "0.1.0 - initial platform and core archive domain"`. Tag messages
should name the milestone, not repeat the changelog.

## How to update this file

- Add a bullet to `## [Unreleased]` in the same commit or pull request that makes the change, under
  the right Keep a Changelog heading (Added, Changed, Deprecated, Removed, Fixed, Security).
- Write one bullet per user-visible change, one sentence, past tense, describing what a client of
  the API can now do differently. Skip refactors nobody outside the codebase can observe.
- Prefix anything a client must change with `**BREAKING**`, and repeat it under a
  `### Breaking changes` subsection when the release is large.
- When cutting a release, rename `## [Unreleased]` to `## [X.Y.Z] - YYYY-MM-DD`, add a fresh empty
  `## [Unreleased]` above it, tag the commit, update `pom.xml`, and add the link-reference line.
- Keep `### Editorial notes` out of released sections: resolve each note, or carry it forward into
  the new `## [Unreleased]` block, when you cut a release.
- Write commit messages that make this kind of reconstruction unnecessary. Conventional Commits
  works well here — `type(scope): summary`, with a `!` and a `BREAKING CHANGE:` footer for
  incompatible changes:

  ```text
  feat(maqam): add teacher recent-activity feed at GET /api/maqam/teacher/my-recent

  fix(guest-feed): treat NULL is_public as public so pre-flag rows appear

  feat(audio)!: rename fullName to fileName in payloads and the audios table

  BREAKING CHANGE: clients reading or writing audio `fullName` must switch to `fileName`;
  the column was renamed from `fullname` to `file_name`.

  perf(search): push sort-only list requests into the database instead of sorting in memory
  ```

<!--
Link-reference definitions are intentionally omitted.

The remote is github.com/akararkan/khi_archive_platform_backend, but the repository carries no
tags, so none of the versions below has a ref to compare against yet. Once the versions above have
been tagged and pushed, add one line per version at the bottom of this file, each comparing a
version to the one before it, and point [Unreleased] at the newest tag:

[Unreleased]: https://github.com/akararkan/khi_archive_platform_backend/compare/v0.9.0...HEAD
[0.9.0]: https://github.com/akararkan/khi_archive_platform_backend/compare/v0.8.0...v0.9.0
[0.8.0]: https://github.com/akararkan/khi_archive_platform_backend/compare/v0.7.0...v0.8.0
[0.7.0]: https://github.com/akararkan/khi_archive_platform_backend/compare/v0.6.0...v0.7.0
[0.6.0]: https://github.com/akararkan/khi_archive_platform_backend/compare/v0.5.0...v0.6.0
[0.5.0]: https://github.com/akararkan/khi_archive_platform_backend/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/akararkan/khi_archive_platform_backend/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/akararkan/khi_archive_platform_backend/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/akararkan/khi_archive_platform_backend/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/akararkan/khi_archive_platform_backend/releases/tag/v0.1.0
-->
