# Analytics — Inventory, Visibility and Maqam API

> **Audience:** Staff (ADMIN only) · **Base path:** `/api/analytics`, `/api/analytics/maqam` ·
> **Source:** `src/main/java/ak/dev/khi_archive_platform/platform/api/analytics/InventoryAnalyticsAPI.java`,
> `src/main/java/ak/dev/khi_archive_platform/platform/api/analytics/MaqamAnalyticsAPI.java`

These five endpoints are **state snapshots**, not activity reports. `InventoryAnalyticsAPI` counts
rows in the operational tables right now (how many items exist, how many are trashed, what is
visible to the public), and `MaqamAnalyticsAPI` aggregates the teacher classification workflow
straight from `maqam_teacher_votes`, `maqam_audio_listen_sessions` and `list_of_maqam`. Neither
group reads the `*_audit_logs` union that powers the activity reports on `AnalyticsAPI`, and
neither accepts a date window — the numbers describe the archive as of the moment you call.

## Access

| Requirement | Value |
|---|---|
| Authentication | Required — JWT in the `khi_auth_token` HttpOnly cookie (an `Authorization: Bearer` header is also accepted by the filter) |
| Authority | `hasRole('ADMIN')` — declared **on the class** on both `InventoryAnalyticsAPI` and `MaqamAnalyticsAPI`, so it applies to every method in both controllers. No method carries its own `@PreAuthorize` |
| Effective authority string | `ROLE_ADMIN` |
| Roles that hold it by default | ADMIN only |
| Roles that do **not** | GUEST, EMPLOYEE, TEACHER |

There is no `analytics:*` entry in `user/enums/Permission.java`. This surface is gated by the ADMIN
role itself, so it cannot be delegated to an EMPLOYEE or TEACHER through a per-user permission
grant the way `audio:read` or `maqam:vote` can.

On a 403 the platform `ApiExceptionHandler` reads the `@PreAuthorize` from the handler method and,
finding none, falls back to the **class** annotation — so `details.requiredAuthority` on the error
envelope reads `ADMIN`.

## Endpoints

| Method | Path | Authority | Purpose |
|---|---|---|---|
| `GET` | `/api/analytics/inventory` | `hasRole('ADMIN')` (class-level) | Live row counts per item type, split active vs trashed |
| `GET` | `/api/analytics/visibility` | `hasRole('ADMIN')` (class-level) | Public-vs-hidden split for projects and media, plus items inside hidden projects |
| `GET` | `/api/analytics/maqam/overview` | `hasRole('ADMIN')` (class-level) | Team classification progress, maqam-type distribution and the teacher leaderboard |
| `GET` | `/api/analytics/maqam/teachers` | `hasRole('ADMIN')` (class-level) | The teacher leaderboard on its own |
| `GET` | `/api/analytics/maqam/teachers/{username}` | `hasRole('ADMIN')` (class-level) | One teacher's maqam activity |

None of the five takes a query parameter, a request body, or a `Pageable`. The controller methods
bind only `Authentication` and `HttpServletRequest` (plus `{username}` on the last one), so there
is no `@ModelAttribute` filter object here — the `AnalyticsFilter` query contract
(`days`, `from`, `to`, `entities`, `actions`, `actor`, …) belongs to the activity endpoints on
`AnalyticsAPI`, not to these. Unknown query parameters are ignored.

Responses are plain JSON objects (or a plain JSON array on `/maqam/teachers`) — none of these
endpoints uses the Spring `Page` envelope. Every timestamp below (`generatedAt`, `firstVotedAt`,
`lastListenAt`) is a `java.time.Instant` rendered by the shared Jackson configuration
(`spring.jackson.time-zone: Asia/Baghdad`, `default-property-inclusion: non_null`); the examples
show the ISO-8601 instant form, and [`../01-conventions.md`](../01-conventions.md) is the single
place that pins the exact rendering. Null fields are omitted from every response.

---

### `GET /api/analytics/inventory`

How many items exist right now, per type, split active vs soft-trashed.

**Authority:** `hasRole('ADMIN')` — from the class-level `@PreAuthorize` on `InventoryAnalyticsAPI`.

**Query parameters** — none.

**Response** `200 OK` — `InventoryStatsDTO`

| Field | Type | What it counts |
|---|---|---|
| `totalActive` | long | Sum of `byType[*].active` across every type — every row in the archive with `removed_at IS NULL` |
| `totalTrashed` | long | Sum of `byType[*].trashed` — every row with `removed_at IS NOT NULL` (soft-trashed, not yet purged) |
| `grandTotal` | long | `totalActive + totalTrashed` |
| `byType` | object | Per-type counts, keyed by lower-case type name. See below |
| `generatedAt` | instant | Server clock (`Instant.now()`) when the snapshot was computed |

`byType` always carries exactly these nine keys, in this insertion order (`LinkedHashMap`):
`audio`, `video`, `image`, `text`, `maqam`, `physical_media`, `project`, `person`, `category`.
Every key is present on every call, even when its counts are zero. Note that the audit-side entity
list (`AnalyticsService.ENTITY_KEYS`) also contains `user`; the inventory map does **not** — there
is no user row count here.

Each value is an `InventoryTypeCountDTO`:

| Field | Type | What it counts |
|---|---|---|
| `active` | long | `countByRemovedAtIsNull()` on that type's repository — rows still live |
| `trashed` | long | `countByRemovedAtIsNotNull()` — rows soft-trashed but still in the table |
| `total` | long | `active + trashed`, computed in the service |

**Are trashed records included?** Yes — explicitly, as their own `trashed` bucket, and rolled up
into `totalTrashed` and `grandTotal`. `totalActive` never includes them. Records that were
**purged** (hard-deleted) are gone from the table and are therefore counted nowhere.

```json
{
  "totalActive": 12480,
  "totalTrashed": 317,
  "grandTotal": 12797,
  "byType": {
    "audio": { "active": 4120, "trashed": 96, "total": 4216 },
    "video": { "active": 1802, "trashed": 41, "total": 1843 },
    "image": { "active": 5233, "trashed": 128, "total": 5361 },
    "text": { "active": 604, "trashed": 12, "total": 616 },
    "maqam": { "active": 188, "trashed": 3, "total": 191 },
    "physical_media": { "active": 402, "trashed": 27, "total": 429 },
    "project": { "active": 91, "trashed": 8, "total": 99 },
    "person": { "active": 34, "trashed": 2, "total": 36 },
    "category": { "active": 6, "trashed": 0, "total": 6 }
  },
  "generatedAt": "2026-08-26T09:12:44.118Z"
}
```

**Errors**

| Status | `error` code | When |
|---|---|---|
| `401` | `TOKEN_MISSING` | No `khi_auth_token` cookie and no `Authorization: Bearer` header |
| `401` | `TOKEN_EXPIRED` | The JWT is past its expiry |
| `401` | `TOKEN_REVOKED` | The session was logged out / blacklisted |
| `401` | `TOKEN_MALFORMED`, `TOKEN_INVALID_SIGNATURE`, `TOKEN_INVALID` | The JWT filter could not accept the token |
| `403` | `ACCESS_DENIED` | Authenticated but not ADMIN. `details.requiredAuthority` is `ADMIN` |
| `405` | `METHOD_NOT_ALLOWED` | Any method other than `GET` on this path |
| `500` | `DATABASE_ERROR` | A count query failed (`DataAccessException`) |
| `504` | `TIMEOUT` | A count query exceeded the database timeout (`QueryTimeoutException`) |
| `500` | `INTERNAL_SERVER_ERROR` | Anything else unhandled |

**Notes**

- Nine pairs of `SELECT COUNT(*)` against the indexed `removed_at` column — one pair per type.
- **Not cached.** `InventoryAnalyticsService` carries no `@Cacheable`, and `CacheConfig` defines
  only `analytics:user.v2`, `analytics:overview.v2` and `analytics:users.v2` (all used by
  `AnalyticsAPI`). Every call recomputes, so the numbers reflect a create/trash/restore instantly.
- Audited: one `analytics_audit_logs` row with `action = VIEW_INVENTORY`,
  `filterSummary = "inventory"` and `details = "Inventory snapshot (grandTotal=<n>)"`.

---

### `GET /api/analytics/visibility`

Visible-vs-hidden split for projects and the four media types, plus how many items sit inside
hidden projects.

**Authority:** `hasRole('ADMIN')` — from the class-level `@PreAuthorize` on `InventoryAnalyticsAPI`.

**Query parameters** — none.

**Response** `200 OK` — `VisibilityStatsDTO`

| Field | Type | What it counts |
|---|---|---|
| `projectsVisible` | long | Active projects with `is_visible_to_public` true **or NULL** (NULL is treated as visible) |
| `projectsHidden` | long | Active projects with `is_visible_to_public = false` — explicit false only |
| `projectsTotal` | long | `projectsVisible + projectsHidden`, i.e. every active project |
| `mediaByType` | object | Per media-type public/private split. Keys `audio`, `video`, `image`, `text`, in that order, always all four present |
| `mediaPublicTotal` | long | Sum of `mediaByType[*].publicCount` across the four media types |
| `mediaPrivateTotal` | long | Sum of `mediaByType[*].privateCount` across the four media types |
| `itemsInVisibleProjects` | long | Active audio + video + image + text whose **parent project** is visible (flag true or NULL) |
| `itemsInHiddenProjects` | long | Active audio + video + image + text whose **parent project** is hidden (flag explicitly false) |
| `generatedAt` | instant | Server clock (`Instant.now()`) when the snapshot was computed |

Each `mediaByType` value is a `VisibilityTypeCountDTO`:

| Field | Type | What it counts |
|---|---|---|
| `publicCount` | long | Active rows of that type with `is_public` true **or NULL** — NULL counts as public, matching the guest-API convention `isPublic IS NULL OR isPublic = true` |
| `privateCount` | long | Active rows with `is_public = false` — explicit false only |
| `total` | long | `publicCount + privateCount`, i.e. every active row of that type |

**Are trashed records included?** No. Every query on this endpoint is scoped to
`removed_at IS NULL`, so soft-trashed rows appear in none of these counters — not in
`projectsTotal`, not in `mediaByType[*].total`, not in the project-visibility roll-ups. Compare
against `/api/analytics/inventory` when you need the trashed side.

Three behaviors worth knowing before you build a dashboard on these numbers:

- `itemsInVisibleProjects + itemsInHiddenProjects` equals the total active media count, because
  `project_id` is `nullable = false` on `Audio`, `Video`, `Image` and `Text` — every media row has
  exactly one parent project and therefore lands in exactly one bucket.
- The project-side queries filter on the **media** row's `removed_at` only. They do not check the
  parent project's own `removed_at`, so an active item whose project has been trashed is still
  counted, in whichever bucket the trashed project's `is_visible_to_public` flag puts it.
- `person`, `category`, `maqam` and `physical_media` have no visibility flag and are absent from
  this response entirely.

**Why `itemsInHiddenProjects` earns its own panel.** Visibility is enforced at two levels and the
media row only knows about one of them — `GuestSearchService.isPubliclyVisible` requires `isPublic`
on the item **and** `isVisibleToPublic` on its parent project, so an item flagged public is still
invisible to every guest when its project is hidden. Nothing on the item itself records that, which
makes it the archive's most frequent "why can't the public see this?" support question, and this
field is the pre-computed answer. Surface it as a standalone callout beside the `mediaByType` chart
— "N items are hidden because their project is hidden" — rather than as one more bar in a table.
Read against `mediaPublicTotal` it tells an admin how much of the public-flagged archive is actually
reachable, which neither number says alone. The bucket is scoped by the project flag alone, though:
it does not also require `isPublic = true` on the item, so it counts everything a project-level
unhide would expose, not only the items the project flag alone is holding back.

```json
{
  "projectsVisible": 74,
  "projectsHidden": 17,
  "projectsTotal": 91,
  "mediaByType": {
    "audio": { "publicCount": 3980, "privateCount": 140, "total": 4120 },
    "video": { "publicCount": 1700, "privateCount": 102, "total": 1802 },
    "image": { "publicCount": 5100, "privateCount": 133, "total": 5233 },
    "text": { "publicCount": 560, "privateCount": 44, "total": 604 }
  },
  "mediaPublicTotal": 11340,
  "mediaPrivateTotal": 419,
  "itemsInVisibleProjects": 10982,
  "itemsInHiddenProjects": 777,
  "generatedAt": "2026-08-26T09:12:51.402Z"
}
```

**Errors**

| Status | `error` code | When |
|---|---|---|
| `401` | `TOKEN_MISSING` | No `khi_auth_token` cookie and no `Authorization: Bearer` header |
| `401` | `TOKEN_EXPIRED` | The JWT is past its expiry |
| `401` | `TOKEN_REVOKED` | The session was logged out / blacklisted |
| `401` | `TOKEN_MALFORMED`, `TOKEN_INVALID_SIGNATURE`, `TOKEN_INVALID` | The JWT filter could not accept the token |
| `403` | `ACCESS_DENIED` | Authenticated but not ADMIN. `details.requiredAuthority` is `ADMIN` |
| `405` | `METHOD_NOT_ALLOWED` | Any method other than `GET` on this path |
| `500` | `DATABASE_ERROR` | A count query failed (`DataAccessException`) |
| `504` | `TIMEOUT` | A count query exceeded the database timeout (`QueryTimeoutException`) |
| `500` | `INTERNAL_SERVER_ERROR` | Anything else unhandled |

**Notes**

- Eighteen counting queries: public/private for each of the four media types (8), visible/hidden
  project-parent for each of the four media types (8), and visible/hidden for projects (2).
- **Not cached** — same reasoning as `/api/analytics/inventory`; a visibility toggle shows up on
  the next call.
- Audited: `action = VIEW_VISIBILITY`, `filterSummary = "visibility"`,
  `details = "Visibility snapshot (projectsVisible=<n> projectsHidden=<n>)"`.

**Example — inventory and visibility**

```bash
curl -s "{{BASE_URL}}/api/analytics/inventory" \
  -H "Cookie: khi_auth_token=$TOKEN"

curl -s "{{BASE_URL}}/api/analytics/visibility" \
  -H "Cookie: khi_auth_token=$TOKEN"
```

---

### `GET /api/analytics/maqam/overview`

Team-level maqam classification progress plus the full per-teacher leaderboard in one call.

**Authority:** `hasRole('ADMIN')` — from the class-level `@PreAuthorize` on `MaqamAnalyticsAPI`.

**Query parameters** — none.

**Response** `200 OK` — `MaqamTeacherOverviewDTO`

Every record-scoped number counts **active** maqam records only (`list_of_maqam.removed_at IS
NULL`). Trashed maqam records, and the vote and listen rows hanging off them, are excluded from
every field below.

| Field | Type | What it counts |
|---|---|---|
| `totalTeachers` | long | Size of the `teachers` array — distinct `teacher_user_id` values with at least one vote row on an active record |
| `totalActiveRecords` | long | `list_of_maqam` rows with `removed_at IS NULL` |
| `totalAssignments` | long | Sum of `teachers[*].assignedRecords` — every vote row on an active record (an assignment *is* a vote row) |
| `totalVotesCast` | long | Sum of `teachers[*].votesCast` — assignments whose `voted_at` is set |
| `totalPendingVotes` | long | `totalAssignments − totalVotesCast` |
| `recordsUnclassified` | long | Active records where zero votes have been cast. Includes records with no teacher panel assigned at all (the per-record query is a `LEFT JOIN`) |
| `recordsPartiallyVoted` | long | Active records where some, but not all, assigned teachers have voted |
| `recordsFullyVoted` | long | Active records where every assigned teacher has voted |
| `recordsWithConsensus` | long | Of the fully-voted records, those with exactly **one** distinct non-null `maqam_type` |
| `recordsWithDisagreement` | long | Of the fully-voted records, those with **two or more** distinct non-null `maqam_type` values |
| `totalListenSessions` | long | Sum of `teachers[*].listenSessions` — rows in `maqam_audio_listen_sessions` on active records |
| `totalListenSeconds` | long | Sum of `teachers[*].totalListenSeconds` — the seconds rolled up onto the vote rows, not a re-sum of the session table |
| `maqamTypeDistribution` | object | `maqam_type` string → number of **cast** votes carrying it, ordered most-common first. Only votes with `voted_at` set and a non-null type are counted |
| `teachers` | array | The same leaderboard `GET /api/analytics/maqam/teachers` returns. See the field table there |
| `generatedAt` | instant | Server clock (`Instant.now()`) when the snapshot was computed |

The three classification buckets are mutually exclusive and, together, cover every active record:
`recordsUnclassified + recordsPartiallyVoted + recordsFullyVoted = totalActiveRecords`.
`recordsWithConsensus` and `recordsWithDisagreement` are a **sub-split of `recordsFullyVoted`** and
do not necessarily sum to it: a fully-voted record where every teacher left `maqam_type` blank has
zero distinct types and falls into neither.

`maqamTypeDistribution` counts votes, not records — a record voted the same way by three teachers
contributes 3. It is a `LinkedHashMap` built from an `ORDER BY COUNT(...) DESC` query, so JSON key
order is the ranking.

**`maqam_type` is free-form and compared verbatim.** The archive has no closed maqam taxonomy — the
stored value is whatever a teacher typed, and `MaqamService.upsertVote` only calls `.trim()` on it
before saving. It is never case-folded, transliterated or matched against a lookup table, because
the names are Kurdish free text and the vocabulary was deliberately left open. Every number above
therefore compares raw strings: `COUNT(DISTINCT v.maqamType)` is what splits `recordsWithConsensus`
from `recordsWithDisagreement`, and `maqamTypeDistribution` groups on `v.maqamType` directly. `Rast`
and `rast` are two distinct types — a fully-voted panel where one teacher typed each lands in
**disagreement**, and the distribution shows two separate keys a reader would call the same maqam.

The list endpoint does not behave this way: the `maqamType` filter on `GET /api/maqam` runs both
sides through `KurdishText.normalize` (case folding, Yeh/Kaf folding, ZWNJ and tashkeel removal), so
it matches variants these aggregates count separately. Nothing normalizes on the analytics side.
Group case-insensitively on the client if the console needs a tidier chart, and drive any maqam-type
picker from [`GET /api/maqam/maqam-types`](../specialised/maqam.md#get-apimaqammaqam-types) — it
returns the distinct strings that have actually been voted, most-common first, off this same
`maqamTypeDistribution()` aggregate, where a hand-typed exact match silently misses on spelling
drift.

```json
{
  "totalTeachers": 4,
  "totalActiveRecords": 188,
  "totalAssignments": 421,
  "totalVotesCast": 337,
  "totalPendingVotes": 84,
  "recordsUnclassified": 22,
  "recordsPartiallyVoted": 51,
  "recordsFullyVoted": 115,
  "recordsWithConsensus": 98,
  "recordsWithDisagreement": 15,
  "totalListenSessions": 1204,
  "totalListenSeconds": 486300,
  "maqamTypeDistribution": {
    "Bayat": 96,
    "Rast": 74,
    "Saba": 61,
    "Hijaz": 58
  },
  "teachers": [
    {
      "teacherUserId": 12,
      "teacherUsername": "hemin",
      "teacherDisplayName": "Hemin A.",
      "assignedRecords": 140,
      "votesCast": 131,
      "pendingVotes": 9,
      "distinctMaqamTypes": 7,
      "totalListenSeconds": 201400,
      "maxPositionSeconds": 742,
      "listenSessions": 512,
      "recordsListened": 138,
      "firstVotedAt": "2026-03-02T07:41:10.220Z",
      "lastListenAt": "2026-08-25T18:22:05.900Z"
    }
  ],
  "generatedAt": "2026-08-26T09:13:02.771Z"
}
```

The `teachers` array is abridged to one entry above so the example stays readable. A real response
carries `totalTeachers` entries — four, for the numbers shown — and the `total*` roll-ups are sums
over the full array, not over the abridged one.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `401` | `TOKEN_MISSING` | No `khi_auth_token` cookie and no `Authorization: Bearer` header |
| `401` | `TOKEN_EXPIRED` | The JWT is past its expiry |
| `401` | `TOKEN_REVOKED` | The session was logged out / blacklisted |
| `401` | `TOKEN_MALFORMED`, `TOKEN_INVALID_SIGNATURE`, `TOKEN_INVALID` | The JWT filter could not accept the token |
| `403` | `ACCESS_DENIED` | Authenticated but not ADMIN. `details.requiredAuthority` is `ADMIN`. TEACHER accounts are rejected here too — this is the analytics console, not the voting panel |
| `405` | `METHOD_NOT_ALLOWED` | Any method other than `GET` on this path |
| `500` | `DATABASE_ERROR` | An aggregate query failed (`DataAccessException`) |
| `504` | `TIMEOUT` | An aggregate query exceeded the database timeout (`QueryTimeoutException`) |
| `500` | `INTERNAL_SERVER_ERROR` | Anything else unhandled |

`MAQAM_NOT_FOUND` and `MAQAM_PANEL_ACCESS_DENIED` are **not** reachable here — this endpoint
touches no single record and never calls the voting-panel guard.

**Notes**

- Five queries: the per-teacher listen roll-up, the per-teacher vote roll-up, the per-record vote
  stats, the maqam-type distribution, and the active-record count.
- **Not cached** — `MaqamAnalyticsService` carries no `@Cacheable` and no `CacheConfig` cache is
  declared for it. Every call recomputes.
- Audited: `action = VIEW_MAQAM_OVERVIEW`, `filterSummary = "maqam/overview"`,
  `details = "Maqam overview (teachers=<n> records=<n>)"`.

---

### `GET /api/analytics/maqam/teachers`

The per-teacher leaderboard on its own, without the team roll-ups.

**Authority:** `hasRole('ADMIN')` — from the class-level `@PreAuthorize` on `MaqamAnalyticsAPI`.

**Query parameters** — none. The list is not paged and not filterable; it returns every teacher who
holds at least one vote row on an active record.

**Response** `200 OK` — a bare JSON array of `TeacherActivityDTO`, ordered by `votesCast`
descending, then `totalListenSeconds` descending.

| Field | Type | What it counts |
|---|---|---|
| `teacherUserId` | long | `maqam_teacher_votes.teacher_user_id` — the grouping key |
| `teacherUsername` | string | Username snapshotted onto the vote rows (`MAX(teacher_username)`) |
| `teacherDisplayName` | string | Display name snapshotted onto the vote rows. Nullable — omitted from the JSON when null |
| `assignedRecords` | long | Vote rows this teacher holds on active records (assignment = vote row) |
| `votesCast` | long | Of those, the rows whose `voted_at` is set |
| `pendingVotes` | long | `assignedRecords − votesCast` — assigned but not yet classified |
| `distinctMaqamTypes` | long | `COUNT(DISTINCT maqam_type)` over this teacher's vote rows on active records; SQL `DISTINCT` ignores NULLs, so blank votes never count |
| `totalListenSeconds` | long | Sum of `total_listen_seconds` rolled up onto this teacher's vote rows (`COALESCE(..., 0)`) |
| `maxPositionSeconds` | long | Furthest playback offset reached across this teacher's records — `MAX(max_position_seconds)`, `COALESCE(..., 0)` |
| `listenSessions` | long | Rows in `maqam_audio_listen_sessions` for this teacher on active records. `0` when the teacher has no session rows |
| `recordsListened` | long | Distinct `list_of_maqam_id` values this teacher has listened to. `0` when the teacher has no session rows |
| `firstVotedAt` | instant | `MIN(voted_at)` over this teacher's vote rows **on active records** — not their first vote ever, since trashing a record drops its vote row from the aggregate. Null (omitted) when they have never voted |
| `lastListenAt` | instant | `MAX(last_listen_at)` across this teacher's vote rows on active records. Null (omitted) when they have never listened |

The two data sources are joined in memory on `teacherUserId`: assignment/vote/second columns come
from `maqam_teacher_votes`, while `listenSessions` and `recordsListened` come from
`maqam_audio_listen_sessions`. The leaderboard is driven by the vote table — a teacher with listen
sessions but no vote row on any active record does not appear at all.

```json
[
  {
    "teacherUserId": 12,
    "teacherUsername": "hemin",
    "teacherDisplayName": "Hemin A.",
    "assignedRecords": 140,
    "votesCast": 131,
    "pendingVotes": 9,
    "distinctMaqamTypes": 7,
    "totalListenSeconds": 201400,
    "maxPositionSeconds": 742,
    "listenSessions": 512,
    "recordsListened": 138,
    "firstVotedAt": "2026-03-02T07:41:10.220Z",
    "lastListenAt": "2026-08-25T18:22:05.900Z"
  },
  {
    "teacherUserId": 19,
    "teacherUsername": "sara",
    "assignedRecords": 96,
    "votesCast": 41,
    "pendingVotes": 55,
    "distinctMaqamTypes": 4,
    "totalListenSeconds": 38200,
    "maxPositionSeconds": 610,
    "listenSessions": 0,
    "recordsListened": 0,
    "firstVotedAt": "2026-06-11T12:05:44.010Z"
  }
]
```

The second element shows the `non_null` inclusion rule in practice: `teacherDisplayName` and
`lastListenAt` are null for that teacher and are simply absent from the object.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `401` | `TOKEN_MISSING` | No `khi_auth_token` cookie and no `Authorization: Bearer` header |
| `401` | `TOKEN_EXPIRED` | The JWT is past its expiry |
| `401` | `TOKEN_REVOKED` | The session was logged out / blacklisted |
| `401` | `TOKEN_MALFORMED`, `TOKEN_INVALID_SIGNATURE`, `TOKEN_INVALID` | The JWT filter could not accept the token |
| `403` | `ACCESS_DENIED` | Authenticated but not ADMIN. `details.requiredAuthority` is `ADMIN` |
| `405` | `METHOD_NOT_ALLOWED` | Any method other than `GET` on this path |
| `500` | `DATABASE_ERROR` | An aggregate query failed (`DataAccessException`) |
| `504` | `TIMEOUT` | An aggregate query exceeded the database timeout (`QueryTimeoutException`) |
| `500` | `INTERNAL_SERVER_ERROR` | Anything else unhandled |

An empty teacher panel is a `200 OK` with `[]`, never a 404.

**Notes**

- Two aggregate queries (vote roll-up + listen roll-up), joined and sorted in the service.
- **Not cached.**
- Audited: `action = VIEW_MAQAM_TEACHERS`, `filterSummary = "maqam/teachers"`,
  `details = "Maqam teacher leaderboard (teachers=<n>)"`.

---

### `GET /api/analytics/maqam/teachers/{username}`

One teacher's maqam activity, picked out of the same leaderboard.

**Authority:** `hasRole('ADMIN')` — from the class-level `@PreAuthorize` on `MaqamAnalyticsAPI`.

**Path parameters**

| Name | Type | Description |
|---|---|---|
| `username` | string | The teacher's username as snapshotted on their vote rows. Trimmed and matched **case-insensitively** — `HEMIN`, `Hemin` and `hemin` all resolve to the same teacher |

**Query parameters** — none.

**Response** `200 OK` — a single `TeacherActivityDTO`, exactly the object documented under
[`GET /api/analytics/maqam/teachers`](#get-apianalyticsmaqamteachers).

```json
{
  "teacherUserId": 12,
  "teacherUsername": "hemin",
  "teacherDisplayName": "Hemin A.",
  "assignedRecords": 140,
  "votesCast": 131,
  "pendingVotes": 9,
  "distinctMaqamTypes": 7,
  "totalListenSeconds": 201400,
  "maxPositionSeconds": 742,
  "listenSessions": 512,
  "recordsListened": 138,
  "firstVotedAt": "2026-03-02T07:41:10.220Z",
  "lastListenAt": "2026-08-25T18:22:05.900Z"
}
```

**Errors**

| Status | `error` code | When |
|---|---|---|
| `404` | _none — empty body_ | The username is not on any active-record teacher panel (including a blank username). The controller returns `ResponseEntity.notFound().build()` directly, so this 404 carries **no** `ApiErrorResponse` envelope and no `error` code — do not switch on one |
| `401` | `TOKEN_MISSING` | No `khi_auth_token` cookie and no `Authorization: Bearer` header |
| `401` | `TOKEN_EXPIRED` | The JWT is past its expiry |
| `401` | `TOKEN_REVOKED` | The session was logged out / blacklisted |
| `401` | `TOKEN_MALFORMED`, `TOKEN_INVALID_SIGNATURE`, `TOKEN_INVALID` | The JWT filter could not accept the token |
| `403` | `ACCESS_DENIED` | Authenticated but not ADMIN. `details.requiredAuthority` is `ADMIN` |
| `405` | `METHOD_NOT_ALLOWED` | Any method other than `GET` on this path |
| `500` | `DATABASE_ERROR` | An aggregate query failed (`DataAccessException`) |
| `504` | `TIMEOUT` | An aggregate query exceeded the database timeout (`QueryTimeoutException`) |
| `500` | `INTERNAL_SERVER_ERROR` | Anything else unhandled |

`USER_NOT_FOUND` is never returned here: the lookup never touches the `users` table, it scans the
vote-row snapshot. A real TEACHER account with no assignment on any active record produces the
bodyless 404 above.

**Notes**

- Builds the whole leaderboard, then filters it in memory — the cost is the same as
  `GET /api/analytics/maqam/teachers`.
- **Not cached.**
- Audited **on both outcomes**: `action = VIEW_MAQAM_TEACHER`,
  `filterSummary = "maqam/teacher:<username>"`, and
  `details = "Maqam teacher activity for <username>"` — with ` (not found)` appended when the
  lookup missed. The audit row is written before the 404 is returned.

**Example — maqam analytics**

```bash
curl -s "{{BASE_URL}}/api/analytics/maqam/overview" \
  -H "Cookie: khi_auth_token=$TOKEN"

curl -s "{{BASE_URL}}/api/analytics/maqam/teachers" \
  -H "Cookie: khi_auth_token=$TOKEN"

curl -s "{{BASE_URL}}/api/analytics/maqam/teachers/hemin" \
  -H "Cookie: khi_auth_token=$TOKEN"
```

---

## Audit trail

Every one of the five endpoints writes exactly one row to `analytics_audit_logs` through
`AnalyticsAuditService.record(...)`, in a `REQUIRES_NEW` transaction so the audit survives even if
the surrounding read transaction rolls back.

| Endpoint | `action` | `filterSummary` |
|---|---|---|
| `GET /api/analytics/inventory` | `VIEW_INVENTORY` | `inventory` |
| `GET /api/analytics/visibility` | `VIEW_VISIBILITY` | `visibility` |
| `GET /api/analytics/maqam/overview` | `VIEW_MAQAM_OVERVIEW` | `maqam/overview` |
| `GET /api/analytics/maqam/teachers` | `VIEW_MAQAM_TEACHERS` | `maqam/teachers` |
| `GET /api/analytics/maqam/teachers/{username}` | `VIEW_MAQAM_TEACHER` | `maqam/teacher:<username>` |

Each row also captures the actor (`actorUserId`, `actorUsername`, `actorDisplayName`,
`actorAuthorities`, `actorPermissions`), the session (`sessionId`, `deviceInfo`, `ipAddress`,
`sessionLoginTimestamp`, `sessionExpiresAt`, `sessionActive`), the request (`requestMethod`,
`requestPath`), an HTML-escaped `details` string and `occurredAt`. The `action` values live in
`platform/enums/AnalyticsAuditAction.java`; its CHECK constraint is re-synced on every boot by
`AnalyticsAuditActionConstraintInitializer`, so adding a value needs no manual DDL.

## Suggested visualizations

These payloads were shaped around particular chart types; the field groupings are not accidental.

| Data | Chart |
|---|---|
| `inventory.byType` | Bar per type with `active` and `trashed` stacked — the stack makes the trash backlog visible without a second view |
| `inventory.totalActive` / `totalTrashed` / `grandTotal` | Headline KPI tiles. They are pre-summed in the service precisely so the client never re-adds `byType` |
| `visibility.mediaByType` | Grouped bar, `publicCount` against `privateCount` per media type |
| `visibility.itemsInHiddenProjects` | A standalone callout, not a bar — see the note under [`GET /api/analytics/visibility`](#get-apianalyticsvisibility) |
| `maqam.recordsUnclassified` / `recordsPartiallyVoted` / `recordsFullyVoted` | Stacked or progress bar: the three are mutually exclusive and sum to `totalActiveRecords` |
| `maqam.recordsWithConsensus` vs `recordsWithDisagreement` | Donut — but label it a slice of `recordsFullyVoted`, since the two do not sum to it |
| `maqam.maqamTypeDistribution` | Bar chart in key order; the map already arrives sorted most-common first |
| `maqam.teachers[]` | Leaderboard table, already sorted by `votesCast` then `totalListenSeconds` |

Because none of the five endpoints is cached or windowed, a dashboard that polls them shows live
numbers on every refresh — and pays the full query cost each time (nine count pairs for
`/inventory`, eighteen counts for `/visibility`, five aggregates for `/maqam/overview`). Poll on a
timer measured in minutes, not seconds.

## Related

- [Internal API index](../README.md)
- [Conventions — page envelope, timestamps, error envelope](../01-conventions.md)
- [Projects API](../content/project.md) — the `isVisibleToPublic` flag counted by
  `/api/analytics/visibility` and its optional cascade to media
- [Unified Items API](../content/items.md) — the per-row `isPublic` flag behind `mediaByType`,
  and `PATCH /api/items/{type}/{code}/visibility`, the write that moves these counters
- [Audio API](../content/audio.md), [Video API](../content/video.md),
  [Image API](../content/image.md), [Text API](../content/text.md) — the trash/restore/purge
  lifecycle behind the `active` vs `trashed` split
- Activity-side analytics (`/api/analytics/overview`, `/users`, `/feed`, `/daily`, …) live on
  `platform/api/analytics/AnalyticsAPI.java` and are the audit-log counterpart to these snapshots.
