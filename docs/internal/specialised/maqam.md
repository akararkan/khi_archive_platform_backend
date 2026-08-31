# Maqam API

> **Audience:** Staff (ADMIN, EMPLOYEE, TEACHER) · **Base path:** `/api/maqam`, `/api/admin/maqam` ·
> **Source:** `platform/api/maqam/MaqamAPI.java`, `platform/api/maqam/AdminMaqamAPI.java`,
> `platform/api/maqam/MaqamStreamAPI.java`

A List-of-Maqam record (`list_of_maqam`) is one song the archive team routes to a small panel of
**1–3 maqam teachers** for classification. Employees and admins own the "upstream" half — song
name, producer (singer), audio file, archive note. The "voting" half lives on
`maqam_teacher_votes`, one row per assigned teacher, and only that teacher may write it. The audio
itself is proxied through a range-aware streaming endpoint that never returns the S3 URL, and every
second a teacher actually listens is logged to `maqam_audio_listen_sessions` before their vote is
weighed.

## Access

| Requirement | Value |
|---|---|
| Authentication | required — `/api/**` is `.authenticated()` in `SecurityConfig`; none of these paths is public |
| Authority | per-method `@PreAuthorize`: `maqam:read`, `maqam:create`, `maqam:update`, `maqam:delete`, `maqam:vote`, `maqam:teacher_manage`, and `hasRole('ADMIN')` on one endpoint |
| Roles that hold it by default | `maqam:read` — ADMIN, EMPLOYEE, TEACHER · `maqam:create`/`maqam:update`/`maqam:teacher_manage` — ADMIN, EMPLOYEE · `maqam:delete` — ADMIN · `maqam:vote` — ADMIN, TEACHER |

None of the three controllers carries a **class-level** `@PreAuthorize` — every method declares its
own, and the exact authority is repeated in each endpoint section below. What *is* class-level is
the path prefix: `@RequestMapping("/api/maqam")` on both `MaqamAPI` and `MaqamStreamAPI`, and
`@RequestMapping("/api/admin/maqam")` on `AdminMaqamAPI`.

Where the default grants come from (`user/enums/Role.java`):

| Authority | ADMIN | EMPLOYEE seed | TEACHER seed |
|---|---|---|---|
| `maqam:read` | yes (role) | yes | yes |
| `maqam:create` | yes (role) | yes | no |
| `maqam:update` | yes (role) | yes | no |
| `maqam:delete` | yes (role) | no | no |
| `maqam:teacher_manage` | yes (role) | yes | no |
| `maqam:vote` | yes (role) | no | yes |

ADMIN holds every authority through the role itself (`Role.ADMIN = EnumSet.allOf(Permission.class)`),
so an admin technically passes the `maqam:vote` check — but `MaqamService.upsertVote` then rejects
any actor whose `role != TEACHER` with `MAQAM_PANEL_ACCESS_DENIED`. **Voting is TEACHER-only in
practice, enforced in the service, not by the annotation.** The same double check guards the
listen-tracking endpoints and the teacher recent-activity feed.

`EmployeeMaqamTeacherManageBackfillInitializer` (`user/configs/`) is a one-shot `JdbcTemplate`
runner that grants `maqam:teacher_manage` to EMPLOYEE accounts created before that permission
existed.

Every grant in that table is a **default, not a fixture**. Authorities live on the user as per-user
grants (`extraPermissions`), so any single non-ADMIN account can be widened or narrowed without a
code change: `POST /api/admin/users/{userId}/permissions` grants and `DELETE` on the same path
revokes, both gated on `user:update`. Giving one teacher `maqam:create` so they can prepare their
own records, or taking `maqam:teacher_manage` away from an employee who should not be picking
panels, are routine operations — see
[Admin — users and permissions](../admin/users-and-permissions.md). ADMIN accounts are the
exception: both endpoints reject them with `ADMIN_PERMISSIONS_LOCKED`, because an admin's
authorities come from the role rather than the grants table.

What no grant can buy is the role checks described above. Voting, listen tracking and the
recent-activity feed all test `role == TEACHER`, and
`GET /api/admin/maqam/teachers/{teacherUserId}/sessions` tests `hasRole('ADMIN')`, so neither is
delegable through a permission.

`Permission.MAQAM_REMOVE` (`maqam:remove`) exists in the catalog but **no maqam endpoint uses it** —
soft-trash is gated on `maqam:delete`, the same authority as restore and purge.

### Role × operation matrix

The tables above give the ingredients — the per-method `@PreAuthorize` and the seeded default
grants. This one collapses them, together with the extra `role` checks `MaqamService` makes on top,
into a single "who can actually do what" view. A **yes** means the default seed gets that role
through; the note beside it is the restriction the service applies afterwards.

| Operation | ADMIN | EMPLOYEE seed | TEACHER seed |
|---|---|---|---|
| Create / update song fields | yes | yes | no |
| Soft-trash, restore, purge | yes | no | no |
| Assign / unassign teachers | yes | yes | no |
| Clear another teacher's vote | yes | yes | no |
| Read records | yes (all) | yes (all) | yes (assigned only) |
| Stream audio | yes | yes | yes (assigned only) |
| Cast / update own vote | no — holds `maqam:vote`, blocked in the service | no | yes |
| Cast / update another teacher's vote | no | no | no |
| Teacher recent-activity feed | no — blocked in the service | no | yes |
| Per-record listen summary / session log | yes | yes | yes (**any** active record, not just assigned) |
| Sessions across all records for one teacher | yes | no | no |
| See `ipAddress` / `userAgent` on session rows | yes | no | no |

Two rows are worth reading twice.

**Assigning teachers and clearing votes are not admin-only**, even though both endpoints sit under
`/api/admin/maqam`. Both are gated on `maqam:teacher_manage`, which `EMPLOYEE_DEFAULT_PERMISSIONS`
seeds, so the employee who prepared a record can also pick its panel and clear a bad vote. That path
prefix is a grouping convention, not an authorization statement — only the trash trio
(`maqam:delete`) and `GET /api/admin/maqam/teachers/{teacherUserId}/sessions` (`hasRole('ADMIN')`)
are genuinely admin-gated.

**The two listen-log reads skip the panel check.** Neither `listenSummary` nor
`listSessionsForRecord` calls `ensureCallerMaySeeRecord`, which is why the teacher column above
reads "any active record" for that row — a teacher who knows a `maqamCode` can read both for a
record that would give them `403 MAQAM_PANEL_ACCESS_DENIED` on the record read and the stream. See
the notes on those two endpoints.

### Visibility split

Read paths apply a role-scoped filter inside the service, on top of the authority check:

| Caller | Sees |
|---|---|
| ADMIN, EMPLOYEE (any non-TEACHER) | every active record |
| TEACHER | only records where they are on the teacher panel |

A teacher who requests a record they are not assigned to gets `403 MAQAM_PANEL_ACCESS_DENIED`, not
a `404`. The split is implemented by `ensureCallerMaySeeRecord` (single-record reads and the stream)
and by routing the list query through `findAssignedToTeacher(...)`.

## Endpoints

| Method | Path | Authority | Purpose |
|---|---|---|---|
| `GET` | `/api/maqam` | `maqam:read` | Paged active list with panel-aware filters and sort |
| `GET` | `/api/maqam/search` | `maqam:read` | Free-text search on song/producer/code, plain array |
| `GET` | `/api/maqam/maqam-types` | `maqam:read` | Distinct voted maqam types, most-common first |
| `GET` | `/api/maqam/{maqamCode}` | `maqam:read` | One active record with its full vote panel |
| `POST` | `/api/maqam` | `maqam:create` | Create a record from `data` + `file` multipart |
| `PATCH` | `/api/maqam/{maqamCode}` | `maqam:update` | Patch song fields and/or replace the audio |
| `PUT` | `/api/admin/maqam/{maqamCode}/teachers` | `maqam:teacher_manage` | Replace the 1–3 teacher panel |
| `GET` | `/api/maqam/teacher/my-recent` | `maqam:vote` | Signed-in teacher's "where was I?" feed |
| `POST` | `/api/maqam/{maqamCode}/vote` | `maqam:vote` | Cast or update this teacher's vote + note |
| `DELETE` | `/api/admin/maqam/{maqamCode}/votes/{teacherUserId}` | `maqam:teacher_manage` | Clear one teacher's vote, keep the assignment |
| `GET` | `/api/maqam/{maqamCode}/stream` | `maqam:read` | Range-streamed audio, inline only |
| `POST` | `/api/maqam/{maqamCode}/listen/start` | `maqam:vote` | Open a listen session |
| `POST` | `/api/maqam/{maqamCode}/listen/progress` | `maqam:vote` | Add listened seconds to an open session |
| `POST` | `/api/maqam/{maqamCode}/listen/end` | `maqam:vote` | Close the session with a final delta |
| `GET` | `/api/maqam/{maqamCode}/listen-summary` | `maqam:read` | Per-teacher listen aggregate for one record |
| `GET` | `/api/maqam/{maqamCode}/sessions` | `maqam:read` | Paged session log for one record |
| `GET` | `/api/admin/maqam/teachers/{teacherUserId}/sessions` | `hasRole('ADMIN')` | Every session one teacher ever recorded |
| `DELETE` | `/api/maqam/{maqamCode}` | `maqam:delete` | Soft-trash the record |
| `GET` | `/api/admin/maqam/trash` | `maqam:delete` | Paged trash listing, same filters as the active list |
| `POST` | `/api/admin/maqam/{maqamCode}/restore` | `maqam:delete` | Restore from trash |
| `DELETE` | `/api/admin/maqam/{maqamCode}/purge` | `maqam:delete` | Permanent delete, including the S3 object |

---

## Conventions used below

**Paged responses** return the standard Spring `Page` envelope — `content`, `pageable`,
`totalElements`, `totalPages`, `number`, `size`, `first`, `last`, `numberOfElements`, `empty`. Only
the `content[]` element shape is documented per endpoint. In-memory paths build the page with
`PaginationSupport.sliceList`, which returns an empty `content` with the correct `totalElements`
for an out-of-range page.

**Timestamps** are `java.time.Instant` fields serialized by Jackson as ISO-8601
(`"2026-08-26T09:12:44.118Z"`); `spring.jackson.time-zone=Asia/Baghdad`. The `LocalDate` filter
parameters each carry `@DateTimeFormat(iso = DateTimeFormat.ISO.DATE)` on the field in
`MaqamFilterParams` (matching the global `spring.mvc.format.date=yyyy-MM-dd`), so they are plain
`YYYY-MM-DD` calendar dates, resolved to day bounds in the archive zone by `ArchiveTime`
(`ARCHIVE_ZONE = Asia/Baghdad`; `startOfDay`/`endOfDay`).

**Null fields are omitted** from every response (`spring.jackson.default-property-inclusion=non_null`).
The examples below show a fully-populated record; a record with no `archiveNote`, or a teacher who
has not voted yet, simply loses those keys.

**`audioDurationSeconds` is never written.** No endpoint, service, or initializer in this repository
calls `setAudioDurationSeconds(...)`; the column is only ever read. Unless a row is populated
directly in the database it stays null, which means: `coverageRatio` is null everywhere, the
duration clamp on listen progress does not apply, and setting `durationSecondsMin`/`durationSecondsMax`
filters out every record (a null value fails the range test whenever a bound is present). How a
record is intended to acquire a duration is _Not documented in source._

**No read-cache.** Unlike audio/video/image/text/project/person, the maqam listings are **not**
backed by a Caffeine `ReadCache` — there is no `maqam:*` entry in
`platform/config/CacheConfig.java`. Both list endpoints read fresh from the database on every
request: one page on the fast path, or the caller's full visible set on the filtered path. The
service javadoc gives the reason — the per-request `streamUrl` and the high write rate of votes and
listen pings make a shared DTO cache a poor fit.

### `MaqamResponseDTO`

The read shape shared by every record endpoint. Note there is no `audioFileUrl` — `MaqamMapper`
never reads it, by design.

```json
{
  "id": 12,
  "maqamCode": "MAQAM_000012",
  "songName": "Ey Niştiman",
  "producer": "Hasan Zirak",
  "audioFileName": "ey-nishtiman.mp3",
  "audioContentType": "audio/mpeg",
  "audioFileSizeBytes": 4193280,
  "streamUrl": "{{BASE_URL}}/api/maqam/MAQAM_000012/stream",
  "archiveNote": "Reel 14, side B. Tape hiss from 0:40.",
  "teacherVotes": [
    {
      "voteId": 41,
      "teacherUserId": 7,
      "teacherUsername": "hemin.t",
      "teacherDisplayName": "Hemin Ali",
      "maqamType": "Bayati Shuri",
      "teacherNote": "Saba coloring in the final phrase.",
      "votedAt": "2026-08-20T11:04:02.551Z",
      "updatedAt": "2026-08-21T08:15:44.108Z",
      "assignedAt": "2026-08-19T09:00:00.000Z",
      "assignedBy": "employee1",
      "totalListenSeconds": 186,
      "maxPositionSeconds": 201,
      "lastListenAt": "2026-08-21T08:14:59.002Z"
    },
    {
      "voteId": 42,
      "teacherUserId": 9,
      "teacherUsername": "shirin.t",
      "teacherDisplayName": "Shirin Qadir",
      "assignedAt": "2026-08-19T09:00:00.000Z",
      "assignedBy": "employee1",
      "totalListenSeconds": 0,
      "maxPositionSeconds": 0
    }
  ],
  "createdAt": "2026-08-19T08:58:11.774Z",
  "updatedAt": "2026-08-21T08:15:44.108Z",
  "createdBy": "employee1",
  "updatedBy": "employee1"
}
```

| Field | Type | Notes |
|---|---|---|
| `id` | long | Surrogate key |
| `maqamCode` | string | Business key, format `MAQAM_000012` — used in every URL and audit row |
| `songName` | string | Required on create |
| `producer` | string | The singer of the recording. Required on create |
| `audioFileName` | string | Original upload filename, display only |
| `audioContentType` | string | MIME recorded at upload; drives the stream `Content-Type` |
| `audioFileSizeBytes` | long | Recorded at upload |
| `audioDurationSeconds` | long | Never written by the API — see above; normally absent |
| `streamUrl` | string | Absolute `/api/maqam/{code}/stream` URL, rebuilt per request from `X-Forwarded-Proto`/`X-Forwarded-Host` (falling back to `Host`, then to the relative path) |
| `archiveNote` | string | Editorial note; teachers cannot edit it |
| `teacherVotes[]` | array | The panel, sorted by `assignedAt` ascending (nulls last). Always present; `[]` when unassigned |
| `createdAt`/`updatedAt`/`removedAt` | instant | `removedAt` is only present on trashed records |
| `createdBy`/`updatedBy`/`removedBy` | string | Actor usernames |

`teacherVotes[]` elements are `MaqamTeacherVoteDTO`. The second element above is the canonical
"assigned but has not voted yet" shape: `maqamType`, `teacherNote` and `votedAt` are null and
therefore omitted, while the two listen counters are non-null zeroes. Peers see each other's
`maqamType` and `teacherNote` in full — the panel is deliberately transparent — but can only write
their own row.

---

## Records

The record lifecycle is `POST` → (optionally `PATCH`) → `DELETE` (soft-trash) → `restore` or
`purge`. Codes are generated as `MAQAM_%06d` from `count() + 1`, serialized by a Postgres advisory
lock (`CodeGenLock`, namespace `maqam-code-gen`) so two simultaneous creates cannot claim the same
code.

### `GET /api/maqam`

Paged list of active records the caller can see, with optional filters and sort.

**Authority:** `maqam:read`

**Query parameters** — page controls plus the full `MaqamFilterParams` binding
(`@ModelAttribute`, `platform/dto/maqam/MaqamFilterParams.java`).

| Name | Type | Default | Description |
|---|---|---|---|
| `page` | int | `0` | Zero-based page index |
| `size` | int | `50` | `@PageableDefault(size = 50)` |
| `sortBy` | string | — | One of the sort keys below. Blank/unknown → default order |
| `sortDirection` | string | `asc` | `asc` or `desc` (case-insensitive) |
| `q` | string | — | Case-insensitive substring across `maqamCode`, `songName`, `producer`, `archiveNote`, `audioFileName`, **and** the vote panel (`maqamType`, `teacherUsername`, `teacherDisplayName`). Composes with every other filter |
| `songName` | string | — | Contains |
| `producer` | string | — | Contains |
| `maqamCode` | string | — | Contains |
| `archiveNote` | string | — | Contains |
| `audioFileName` | string | — | Contains |
| `createdBy` | string | — | Contains |
| `updatedBy` | string | — | Contains |
| `removedBy` | string | — | Contains. Meaningful on the trash listing; inert here (active records have no `removedBy`, so setting it returns nothing) |
| `durationSecondsMin` | long | — | Inclusive lower bound on `audioDurationSeconds` |
| `durationSecondsMax` | long | — | Inclusive upper bound on `audioDurationSeconds` |
| `createdFrom` | date | — | `YYYY-MM-DD`, start of that day in Asia/Baghdad |
| `createdTo` | date | — | `YYYY-MM-DD`, end of that day |
| `updatedFrom` | date | — | `YYYY-MM-DD` |
| `updatedTo` | date | — | `YYYY-MM-DD` |
| `removedFrom` | date | — | `YYYY-MM-DD`. Meaningful on the trash listing |
| `removedTo` | date | — | `YYYY-MM-DD`. Meaningful on the trash listing |
| `teacherUserId` | long | — | Record has this user id on its panel |
| `teacherUsername` | string | — | Contains, against any panel member's username |
| `maqamType` | string | — | Case-insensitive **exact** match; true when any panel member voted it |
| `assignmentStatus` | string | — | `assigned` (≥1 panel member) or `unassigned` (empty panel). Any other value is a no-op |
| `voteStatus` | string | — | `none`, `partial`, or `full`. Any other value matches nothing |

**Reading `voteStatus` correctly.** A vote counts as cast the moment `votedAt` is set, and
`MaqamFilterSupport.voteStatusOf` classifies a record from its panel: `full` when every assigned
teacher has voted, `partial` when some have, `none` otherwise. A record with **zero** assigned
teachers falls into `none` — it is not dropped — because the `voted == 0` test short-circuits before
the assigned count is consulted. So `voteStatus=none` on its own answers the real editorial
question, "what still needs votes?", covering both the unassigned backlog and the assigned-but-silent
panels. To separate the two, combine the filters:

```text
GET /api/maqam?voteStatus=none&assignmentStatus=assigned    # panel exists, nobody has voted yet
GET /api/maqam?assignmentStatus=unassigned                  # no panel assigned at all
GET /api/maqam?voteStatus=partial                           # panel is split — some voted, some not
```

The Spring `sort` parameter is **ignored** on this endpoint. On the DB path the service replaces it
with the `sortBy`-resolved order, or `id ASC` when no `sortBy` was given; on the in-memory path
`PaginationSupport.sliceList` uses only offset and page size. Use `sortBy`/`sortDirection`.

**Sort keys** (`sortBy`, with the synonyms each accepts):

| Key | Synonyms | Sorts on |
|---|---|---|
| `maqamCode` | `code` | `maqamCode`, case-insensitive |
| `songName` | `song`, `title`, `name`, `alpha`, `alphabet`, `alphabetical` | `songName`, case-insensitive |
| `producer` | `singer` | `producer`, case-insensitive |
| `duration` | `durationSeconds`, `audioDurationSeconds` | `audioDurationSeconds` |
| `createdAt` | `created`, `added`, `dateAdded`, `date_added` | `createdAt` |
| `updatedAt` | `updated`, `modified`, `dateModified`, `date_modified` | `updatedAt` |

Every sort finishes on `id ASC` as a tiebreaker so paging stays stable.

**Two execution paths** (`MaqamService.listActive`):

| Condition | Path |
|---|---|
| No parameters at all | DB-paged fast path — one page loaded |
| Sort only, non-teacher caller | DB-paged, order pushed into SQL |
| Sort only, TEACHER caller | In-memory — the teacher's `SELECT DISTINCT … JOIN` cannot be `ORDER BY LOWER(...)`-ed in Postgres, so their (small) assigned set is loaded and sorted in the JVM |
| Any non-sort filter set | In-memory — the caller's full visible set is loaded, mapped, filtered and sorted by `MaqamFilterSupport`, then sliced |

String comparisons in the in-memory engine run both sides through
`KurdishText.normalize` (NFC, Yeh/Kaf folding, ZWNJ and tashkeel removal, whitespace collapse,
lower-case), so Sorani text matches despite codepoint variants. It does **not** fix spelling drift —
that is what `GET /api/maqam/maqam-types` is for.

**Response** `200 OK` — `Page<MaqamResponseDTO>`; `content[]` elements are the shape documented
above.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `VALIDATION_ERROR` | A malformed value for a typed filter field, e.g. `teacherUserId=abc` or `createdFrom=19-08-2026` — `@ModelAttribute` binding failures surface as a `BindException` with the offending field in `details` |
| `401` | `TOKEN_MISSING` | No `khi_auth_token` cookie and no bearer header |
| `403` | `ACCESS_DENIED` | Caller lacks `maqam:read` |

**Example**

```bash
curl -s "{{BASE_URL}}/api/maqam?voteStatus=partial&assignmentStatus=assigned&sortBy=createdAt&sortDirection=desc&page=0&size=20" \
  -H "Cookie: khi_auth_token=$TOKEN"
```

**Notes** — writes one `MaqamAuditAction.LIST` row per call, with a details suffix of
`filtered=true`, `sorted=in-memory` or `sorted=db`.

### `GET /api/maqam/search`

Free-text search across song name, producer and maqam code. Returns a plain array, not a `Page`.

**Authority:** `maqam:read`

**Query parameters**

| Name | Type | Default | Description |
|---|---|---|---|
| `q` | string | — (**required**) | Matched with SQL `LOWER(...) LIKE '%q%'` against `songName`, `producer`, `maqamCode`. Blank → `[]` without touching the DB |
| `limit` | int | `20` | Clamped to `1..100` by the service |

Results come back newest-first (`ORDER BY createdAt DESC`) and are then filtered to the caller's
panel when the caller is a TEACHER — so a teacher can receive fewer than `limit` rows.

**Response** `200 OK`

```json
[
  {
    "id": 12,
    "maqamCode": "MAQAM_000012",
    "songName": "Ey Niştiman",
    "producer": "Hasan Zirak",
    "streamUrl": "{{BASE_URL}}/api/maqam/MAQAM_000012/stream",
    "teacherVotes": [],
    "createdAt": "2026-08-19T08:58:11.774Z",
    "updatedAt": "2026-08-21T08:15:44.108Z",
    "createdBy": "employee1",
    "updatedBy": "employee1"
  }
]
```

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `MISSING_PARAMETER` | `q` omitted |
| `401` | `TOKEN_MISSING` | Not signed in |
| `403` | `ACCESS_DENIED` | Caller lacks `maqam:read` |

**Example**

```bash
curl -s "{{BASE_URL}}/api/maqam/search?q=zirak&limit=10" \
  -H "Cookie: khi_auth_token=$TOKEN"
```

**Notes** — writes a `SEARCH` audit row carrying `q=` and the hit count. Unlike the `q` filter on
`GET /api/maqam`, this endpoint does not search the archive note, file name, or the vote panel, and
cannot be combined with filters or sort.

### `GET /api/maqam/maqam-types`

Distinct maqam types actually voted on active records, most-common first. Backs a real dropdown for
the `maqamType` filter, which is an exact match and would otherwise silently miss free-text spelling
variants.

**Authority:** `maqam:read`

**Query parameters** — none.

**Response** `200 OK`

```json
["Rast", "Bayati Shuri", "Husseini with Saba ending"]
```

Only votes with a non-null `votedAt` **and** a non-null `maqamType` on a non-trashed record are
counted (`ListOfMaqamRepository.maqamTypeDistribution`). The list is **not** scoped to the caller's
panel — a teacher sees every type voted archive-wide.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `401` | `TOKEN_MISSING` | Not signed in |
| `403` | `ACCESS_DENIED` | Caller lacks `maqam:read` |

**Example**

```bash
curl -s "{{BASE_URL}}/api/maqam/maqam-types" \
  -H "Cookie: khi_auth_token=$TOKEN"
```

### `GET /api/maqam/{maqamCode}`

One active record with its full vote panel.

**Authority:** `maqam:read`

**Path parameters**

| Name | Type | Description |
|---|---|---|
| `maqamCode` | string | Business key, e.g. `MAQAM_000012` |

**Response** `200 OK` — a single `MaqamResponseDTO` (shape documented above).

**Errors**

| Status | `error` code | When |
|---|---|---|
| `401` | `TOKEN_MISSING` | Not signed in |
| `403` | `ACCESS_DENIED` | Caller lacks `maqam:read` |
| `403` | `MAQAM_PANEL_ACCESS_DENIED` | Caller is a TEACHER who is not on this record's panel |
| `404` | `MAQAM_NOT_FOUND` | No record with that code, or it is in the trash |

**Example**

```bash
curl -s "{{BASE_URL}}/api/maqam/MAQAM_000012" \
  -H "Cookie: khi_auth_token=$TOKEN"
```

**Notes** — writes a `READ` audit row.

### `POST /api/maqam`

Create a record. `multipart/form-data` with two parts: `data` (the JSON payload) and `file` (the
audio binary). The S3 URL is computed server-side and is never accepted from the client.

**Authority:** `maqam:create`

**Request parts**

| Part | Type | Required | Description |
|---|---|---|---|
| `data` | JSON string | yes | `MaqamCreateRequestDTO`, parsed and bean-validated by the controller (`parseAndValidate`) |
| `file` | file | yes | Must be non-empty; if a `Content-Type` is present it must start with `audio/` |

**Request body** (the `data` part)

```json
{
  "songName": "Ey Niştiman",
  "producer": "Hasan Zirak",
  "archiveNote": "Reel 14, side B. Tape hiss from 0:40.",
  "teacherUserIds": [7, 9]
}
```

| Field | Type | Required | Constraint |
|---|---|---|---|
| `songName` | string | yes | `@NotBlank`, `@Size(max = 1000)` |
| `producer` | string | yes | `@NotBlank`, `@Size(max = 500)` |
| `archiveNote` | string | no | `@Size(max = 10000)` |
| `teacherUserIds` | long[] | no | When present: 1–3 distinct ids, each an **active** user whose role is `TEACHER`. Validated in the service |

Supplying `teacherUserIds` here assigns the panel immediately, but — unlike the dedicated admin
endpoint — **does not** write `TEACHER_ASSIGNED` audit rows (`applyTeacherRoster` is called with
`auditChanges = false`). The teacher count is instead recorded in the `CREATE` row's details.

**Response** `200 OK` — the created `MaqamResponseDTO`.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `MAQAM_VALIDATION_ERROR` | `data` blank or unparseable; a bean-validation violation (per-field reasons in `details`); missing/empty audio file; non-`audio/*` MIME; teacher panel not 1–3 distinct ids; a teacher id not found; a listed user is not `TEACHER`; a listed teacher account is deactivated |
| `400` | `MISSING_REQUEST_PART` | The `data` or `file` part is absent from the multipart body |
| `401` | `TOKEN_MISSING` | Not signed in |
| `403` | `ACCESS_DENIED` | Caller lacks `maqam:create` |
| `409` | `CONFLICT` | The generated `maqamCode` collides with an existing row — possible after purges, since the code counter is `count() + 1` |
| `413` | `UPLOAD_TOO_LARGE` | Above `spring.servlet.multipart.max-file-size` (5GB) |
| `415` | `UNSUPPORTED_MEDIA_TYPE` | Request was not sent as `multipart/form-data` |
| `500` | `INTERNAL_SERVER_ERROR` | S3 upload failed — `UserStorageException` is declared in the `user` advice, so under the `platform` advice it falls through to the catch-all |

**Example**

```bash
curl -s -X POST "{{BASE_URL}}/api/maqam" \
  -H "Cookie: khi_auth_token=$TOKEN" \
  -F 'data={"songName":"Ey Niştiman","producer":"Hasan Zirak","teacherUserIds":[7,9]};type=application/json' \
  -F "file=@ey-nishtiman.mp3;type=audio/mpeg"
```

**Notes** — writes a `CREATE` audit row. The audio object lands at
`{aws.s3.base-folder}/maqam-audio/{uuid}-{sanitized-filename}`, which by default resolves to
`khi-archive-platform-folders/maqam-audio/`. That prefix is **flat** — every maqam record in the
archive shares it, unlike audio/video/image/text, which nest a folder per business code — so nothing
in the key ties an object back to its `maqamCode`. The `list_of_maqam.audio_file_url` column is the
only link, which matters when reconciling orphaned S3 objects after a failed purge. The prefix is
deliberately separate from the long-form audio archive so maqam clips can be audited and
lifecycle-ruled on their own. See [Storage and media](../operations/storage-and-media.md) for the
full key layout.

### `PATCH /api/maqam/{maqamCode}`

Patch the song fields, optionally replacing the audio. `multipart/form-data`; every field is
optional and only non-null fields are applied.

**Authority:** `maqam:update`

**Request parts**

| Part | Type | Required | Description |
|---|---|---|---|
| `data` | JSON string | yes | `MaqamUpdateRequestDTO`; send `{}` to change nothing but the file |
| `file` | file | no | When present and non-empty, replaces the audio and deletes the previous S3 object |

**Request body** (the `data` part)

```json
{
  "songName": "Ey Niştiman (restored)",
  "producer": "Hasan Zirak",
  "archiveNote": ""
}
```

| Field | Type | Constraint | Behavior |
|---|---|---|---|
| `songName` | string | `@Size(max = 1000)` | Applied only when non-null **and non-blank** |
| `producer` | string | `@Size(max = 500)` | Applied only when non-null **and non-blank** |
| `archiveNote` | string | `@Size(max = 10000)` | Applied whenever non-null; an empty or whitespace string clears it to null |

Teacher assignments cannot be changed here — use the admin panel endpoint.

**Response** `200 OK` — the updated `MaqamResponseDTO`.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `MAQAM_VALIDATION_ERROR` | `data` blank or unparseable; a bean-validation violation; a supplied file has a non-`audio/*` MIME type |
| `400` | `MISSING_REQUEST_PART` | The `data` part is absent |
| `401` | `TOKEN_MISSING` | Not signed in |
| `403` | `ACCESS_DENIED` | Caller lacks `maqam:update` |
| `404` | `MAQAM_NOT_FOUND` | No active record with that code |
| `409` | `STALE_VERSION` | Concurrent edit detected through the `@Version` column |
| `413` | `UPLOAD_TOO_LARGE` | Replacement file above the 5GB cap |
| `415` | `UNSUPPORTED_MEDIA_TYPE` | Request was not sent as `multipart/form-data` |
| `500` | `INTERNAL_SERVER_ERROR` | The S3 upload of the replacement file failed. A failed **delete** of the superseded object does not surface: `S3Service.deleteByKey` catches `S3Exception`, logs it and returns `false` |

**Example**

```bash
curl -s -X PATCH "{{BASE_URL}}/api/maqam/MAQAM_000012" \
  -H "Cookie: khi_auth_token=$TOKEN" \
  -F 'data={"archiveNote":"Re-digitized from the master reel."};type=application/json'
```

**Notes** — writes an `UPDATE` audit row whose details list the fields touched
(`fields=songName,archiveNote`, or `fields=<none>`). Replacing the file deletes the old S3 object
**before** the transaction commits, so a later rollback leaves a row pointing at a fresh object and
an already-deleted predecessor.

---

## Teacher panel

Each record carries 1–3 teachers. A panel slot **is** a `maqam_teacher_votes` row: assignment
creates the row with a null `maqamType`, and the teacher later fills it in. The cap lives in
`ListOfMaqam.MIN_TEACHERS = 1` / `MAX_TEACHERS = 3` and is enforced in the service, because JPA
cannot express a row-count limit on a collection. A unique constraint
(`uk_maqam_teacher_one_vote_per_song` on `(list_of_maqam_id, teacher_user_id)`) guarantees one row
per teacher per song.

### `PUT /api/admin/maqam/{maqamCode}/teachers`

Replaces the **entire** panel — this is a PUT, not a merge. Teachers absent from the payload are
removed and their vote rows are deleted by `orphanRemoval`.

**Authority:** `maqam:teacher_manage`

**Request body** (`application/json`, `MaqamTeacherAssignmentDTO`)

```json
{ "teacherUserIds": [7, 9, 11] }
```

| Field | Type | Constraint |
|---|---|---|
| `teacherUserIds` | long[] | `@NotNull`, `@NotEmpty`, `@Size(min = 1, max = 3)`. Duplicates are collapsed before the count is re-checked in the service |

Each id must resolve to a user whose `role` is `TEACHER` and whose `isActivated` is not `false`.
Ids already on the panel keep their existing vote, note, listen counters and `assignedAt`; only new
ids get a fresh row.

**Response** `200 OK` — the updated `MaqamResponseDTO`, with `teacherVotes[]` reflecting the new
panel.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `VALIDATION_ERROR` | Bean validation on the body — `teacherUserIds` null, empty, or longer than 3 |
| `400` | `MAQAM_VALIDATION_ERROR` | After de-duplication the count is outside 1–3; an id was not found; a listed user is not `TEACHER`; a listed teacher account is deactivated |
| `400` | `JSON_PARSE_ERROR` | Body is not valid JSON |
| `401` | `TOKEN_MISSING` | Not signed in |
| `403` | `ACCESS_DENIED` | Caller lacks `maqam:teacher_manage` |
| `404` | `MAQAM_NOT_FOUND` | No active record with that code |
| `409` | `STALE_VERSION` | Concurrent edit detected through the `@Version` column |

**Example**

```bash
curl -s -X PUT "{{BASE_URL}}/api/admin/maqam/MAQAM_000012/teachers" \
  -H "Cookie: khi_auth_token=$TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"teacherUserIds":[7,9,11]}'
```

**Notes** — writes one `TEACHER_ASSIGNED` row per added teacher and one `TEACHER_REMOVED` row per
dropped teacher, each carrying the teacher context. Removing a teacher who had already voted is
allowed and **destroys their vote and note** along with the row; use the vote-clearing endpoint
instead if the teacher should stay on the panel and re-vote.

### `GET /api/maqam/teacher/my-recent`

The signed-in teacher's "where was I?" feed: one row per active record they are assigned to, newest
activity first, each row carrying enough state to render a Resume button and a vote badge without a
second request.

**Authority:** `maqam:vote` — plus a service-level check that the actor's role is `TEACHER`. Admins
and employees are deliberately pushed to `GET /api/maqam` instead.

**Query parameters**

| Name | Type | Default | Description |
|---|---|---|---|
| `q` | string | — | Case-insensitive substring against `songName`, `producer`, or `maqamCode`. Applied in memory |
| `page` | int | `0` | Zero-based page index |
| `size` | int | `50` | `@PageableDefault(size = 50)` |

Ordering is fixed: `lastActivityAt` descending, with rows that have no activity at all sorted to the
bottom. The Spring `sort` parameter has no effect — pagination is applied in memory after the sort.

**Response** `200 OK` — `Page<MaqamTeacherRecentDTO>`.

```json
{
  "content": [
    {
      "voteId": 41,
      "maqamId": 12,
      "maqamCode": "MAQAM_000012",
      "songName": "Ey Niştiman",
      "producer": "Hasan Zirak",
      "archiveNote": "Reel 14, side B.",
      "audioFileName": "ey-nishtiman.mp3",
      "streamUrl": "{{BASE_URL}}/api/maqam/MAQAM_000012/stream",
      "maqamType": "Bayati Shuri",
      "teacherNote": "Saba coloring in the final phrase.",
      "hasVoted": true,
      "votedAt": "2026-08-20T11:04:02.551Z",
      "updatedAt": "2026-08-21T08:15:44.108Z",
      "assignedAt": "2026-08-19T09:00:00.000Z",
      "assignedBy": "employee1",
      "totalListenSeconds": 186,
      "maxPositionSeconds": 201,
      "lastListenAt": "2026-08-21T08:14:59.002Z",
      "lastActivityAt": "2026-08-21T08:15:44.108Z",
      "recordCreatedAt": "2026-08-19T08:58:11.774Z",
      "recordUpdatedAt": "2026-08-21T08:15:44.108Z"
    }
  ],
  "totalElements": 6,
  "number": 0,
  "size": 50,
  "first": true,
  "last": true
}
```

| Field | Type | Notes |
|---|---|---|
| `voteId` | long | Id of this teacher's `maqam_teacher_votes` row |
| `maqamId`, `maqamCode`, `songName`, `producer`, `archiveNote`, `audioFileName` | — | Record context, so the teacher recognizes the song |
| `audioDurationSeconds` | long | Never written by the API — normally absent |
| `streamUrl` | string | Same per-request stream URL as the record read |
| `maqamType`, `teacherNote`, `votedAt` | — | **This teacher's own** vote; absent until they vote |
| `hasVoted` | bool | `maqamType` is non-null and non-blank |
| `updatedAt`, `assignedAt`, `assignedBy` | — | Vote-row audit fields |
| `totalListenSeconds`, `maxPositionSeconds` | long | Defaulted to `0`, never null |
| `lastListenAt` | instant | Absent until the first listen ping lands |
| `coverageRatio` | double | `totalListenSeconds / audioDurationSeconds`, clamped to `[0, 1]`; null (omitted) when duration is unknown — which, per the note above, is currently always |
| `lastActivityAt` | instant | The latest of `lastListenAt`, `updatedAt`, `votedAt`, `assignedAt`. Drives the ordering |
| `recordCreatedAt`, `recordUpdatedAt` | instant | Record-level timestamps |

**Errors**

| Status | `error` code | When |
|---|---|---|
| `401` | `TOKEN_MISSING` | Not signed in |
| `403` | `ACCESS_DENIED` | Caller lacks `maqam:vote` |
| `403` | `MAQAM_PANEL_ACCESS_DENIED` | Caller holds `maqam:vote` but their role is not `TEACHER` (for example an admin) |

**Example**

```bash
curl -s "{{BASE_URL}}/api/maqam/teacher/my-recent?q=zirak&page=0&size=20" \
  -H "Cookie: khi_auth_token=$TOKEN"
```

**Notes** — one JPQL query with `JOIN FETCH` loads every vote row plus its parent record, so there
is no N+1; filtering, the composite sort and paging all run in the JVM. Writes a `LIST` audit row
with `teacher recent activity` in its details.

---

## Voting

A teacher votes by writing their own `maqam_teacher_votes` row. The vote is **free text** by
design — the organization explicitly refuses a closed taxonomy, so "Rast", "Bayati Shuri" and
"Husseini with Saba ending" are all valid. `votedAt` is frozen at the first successful vote and
later edits only bump `updatedAt`; that is what distinguishes `VOTE_CAST` from `VOTE_UPDATED` in
the audit log, and what the `voteStatus` filter counts.

### `POST /api/maqam/{maqamCode}/vote`

Cast or update the calling teacher's vote and note.

**Authority:** `maqam:vote` — plus two service-level checks: the actor's role must be `TEACHER`, and
a vote row must already exist for them on this record (i.e. they are on the panel).

**Request body** (`application/json`, `MaqamVoteRequestDTO`)

```json
{
  "maqamType": "Bayati Shuri",
  "teacherNote": "Saba coloring in the final phrase."
}
```

| Field | Type | Required | Constraint |
|---|---|---|---|
| `maqamType` | string | yes | `@NotBlank`, `@Size(max = 1000)`. Trimmed before saving |
| `teacherNote` | string | no | `@Size(max = 10000)`. Trimmed; an empty result is stored as null |

The call is an upsert on the existing row and is idempotent: re-sending the same payload only bumps
`updatedAt`. A `teacherNote` of `null` (key omitted) leaves any existing note untouched; send `""`
to clear it.

**Response** `200 OK` — the whole `MaqamResponseDTO`, re-read after the write, so the caller sees
the refreshed panel including peers' votes.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `VALIDATION_ERROR` | `maqamType` blank or over 1000 chars; `teacherNote` over 10000 chars |
| `400` | `JSON_PARSE_ERROR` | Body is not valid JSON |
| `401` | `TOKEN_MISSING` | Not signed in |
| `403` | `ACCESS_DENIED` | Caller lacks `maqam:vote` |
| `403` | `MAQAM_PANEL_ACCESS_DENIED` | Caller's role is not `TEACHER`, or they are not on this record's panel |
| `404` | `MAQAM_NOT_FOUND` | No active record with that code |
| `409` | `STALE_VERSION` | Concurrent write to the same vote row |

**Example**

```bash
curl -s -X POST "{{BASE_URL}}/api/maqam/MAQAM_000012/vote" \
  -H "Cookie: khi_auth_token=$TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"maqamType":"Bayati Shuri","teacherNote":"Saba coloring in the final phrase."}'
```

**Notes** — writes `VOTE_CAST` on the first save and `VOTE_UPDATED` afterwards, each carrying the
teacher context and the voted `maqamType` in a dedicated column.

### `DELETE /api/admin/maqam/{maqamCode}/votes/{teacherUserId}`

Clears one teacher's vote (for example after misconduct) while **keeping** them on the panel so they
can vote again. Removing them entirely is a job for the panel endpoint.

**Authority:** `maqam:teacher_manage`

**Path parameters**

| Name | Type | Description |
|---|---|---|
| `maqamCode` | string | Business key of an active record |
| `teacherUserId` | long | The panel member whose vote is cleared |

The row survives with `maqamType`, `teacherNote` and `votedAt` set to null and `updatedAt` refreshed.
Listen counters (`totalListenSeconds`, `maxPositionSeconds`, `lastListenAt`) are **not** reset, and
the record's `voteStatus` falls back toward `partial`/`none` accordingly.

**Response** `200 OK` — the updated `MaqamResponseDTO`.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `TYPE_MISMATCH` | `teacherUserId` is not a number |
| `401` | `TOKEN_MISSING` | Not signed in |
| `403` | `ACCESS_DENIED` | Caller lacks `maqam:teacher_manage` |
| `404` | `MAQAM_NOT_FOUND` | No active record with that code, **or** that teacher has no vote row on it — both conditions return this code |

**Example**

```bash
curl -s -X DELETE "{{BASE_URL}}/api/admin/maqam/MAQAM_000012/votes/7" \
  -H "Cookie: khi_auth_token=$TOKEN"
```

**Notes** — writes a `VOTE_DELETED` audit row with the teacher context.

---

## Streaming and listen tracking

Maqam audio is deliberately **not downloadable**. Three mechanisms combine:

1. `MaqamResponseDTO` never carries `audioFileUrl`. `MaqamMapper` is hand-written rather than
   generated precisely so this is enforceable by inspection — there is exactly one entity→DTO
   translation and it never calls `getAudioFileUrl()`.
2. The bytes are proxied by `MaqamStreamAPI` with `Content-Disposition: inline` (never
   `attachment`), `Cache-Control: no-store, private` and `X-Content-Type-Options: nosniff`, so the
   browser's built-in download affordance has nothing to latch onto. The frontend pairs this with
   `<audio controlsList="nodownload">`.
3. Pre-signed S3 GETs were considered and **explicitly rejected** — they would be faster but would
   hand the client a downloadable link and break the audit guarantee.

The trade-off is documented in the controller: each call downloads the whole S3 object into memory
(`S3Service.downloadByUrl`) and serves the requested slice from the byte array. Maqam clips are a
few MB, and the audit guarantee is considered worth the cost.

Listening is measured, not assumed. The player opens a session, pings progress every ~10s and on
pause, then closes it; each ping adds *audio time advanced*, so a paused teacher accrues nothing.
Per-session rows land in `maqam_audio_listen_sessions` and the rolling per-teacher totals are
mirrored onto the vote row (`totalListenSeconds`, `maxPositionSeconds`, `lastListenAt`) for fast
reads.

Three read endpoints expose what that tracking recorded. They are not interchangeable — each
answers a different question:

| Question | Endpoint | Authority | Scope |
|---|---|---|---|
| "Did each panel member actually listen before voting?" | `GET /api/maqam/{maqamCode}/listen-summary` | `maqam:read` | One record, one row per panel member, aggregates only |
| "When and how often did they play it, and from where?" | `GET /api/maqam/{maqamCode}/sessions` | `maqam:read` | One record, one row per play session, optionally narrowed to one teacher |
| "How engaged is this teacher across the whole archive?" | `GET /api/admin/maqam/teachers/{teacherUserId}/sessions` | `hasRole('ADMIN')` | One teacher, every record they ever played |

Cross-teacher leaderboards and archive-wide rollups are not here at all — they live in the
analytics module, see [Inventory and maqam analytics](../analytics/inventory-and-maqam.md).

### `GET /api/maqam/{maqamCode}/stream`

Range-aware audio proxy.

**Authority:** `maqam:read` — re-checked on every request, and a TEACHER only gets bytes for records
they are on the panel for.

**Request headers**

| Header | Required | Description |
|---|---|---|
| `Range` | no | Only the simple `bytes=start-end` and `bytes=start-` forms are parsed. Anything else — a missing `bytes=` prefix, no dash, unparseable numbers — silently degrades to the whole object, still served as a `206`. `start` is floored at 0, `end` is capped at `total - 1`, and an `end` below `start` is raised to `start`. Multi-range requests are not supported |

**Response** `200 OK` when the `Range` header is absent **or blank**; `206 Partial Content` for any
non-blank value — including one the parser cannot understand, which still returns `206` with a
`Content-Range` spanning the whole object rather than falling back to `200`.

The body is the raw audio bytes. Response headers on both statuses:

| Header | Value |
|---|---|
| `Content-Type` | The stored `audioContentType`, or `application/octet-stream` when it is missing or unparseable |
| `Accept-Ranges` | `bytes` |
| `Content-Disposition` | `inline` — never `attachment`, and no filename |
| `Cache-Control` | `no-store, private` |
| `X-Content-Type-Options` | `nosniff` |
| `X-Maqam-Code` | The record's `maqamCode` |
| `Content-Length` | Full size on `200`; slice length on `206` |
| `Content-Range` | `206` only — `bytes {start}-{end}/{total}` |

**Errors**

| Status | `error` code | When |
|---|---|---|
| `401` | `TOKEN_MISSING` | Not signed in — note the `<audio>` element must be same-origin for the cookie to ride along |
| `403` | `ACCESS_DENIED` | Caller lacks `maqam:read` |
| `403` | `MAQAM_PANEL_ACCESS_DENIED` | Caller is a TEACHER who is not on this record's panel |
| `404` | `MAQAM_NOT_FOUND` | No active record with that code |
| `500` | `INTERNAL_SERVER_ERROR` | S3 download failed (`UserStorageException` reaches the platform advice's catch-all) |

**Example**

```bash
curl -s -D - -o /dev/null "{{BASE_URL}}/api/maqam/MAQAM_000012/stream" \
  -H "Cookie: khi_auth_token=$TOKEN" \
  -H "Range: bytes=0-65535"
```

**Notes** — writes a `STREAM` audit row **per range request**, with the raw `Range` header in its
details, so a single playthrough produces several rows.

### `POST /api/maqam/{maqamCode}/listen/start`

Opens (or re-attaches to) a listen session.

**Authority:** `maqam:vote` — plus the service-level `TEACHER` + on-the-panel check.

**Request body** (`application/json`, `MaqamListenStartRequestDTO`)

```json
{ "sessionKey": "0f5b6e1a-6b2f-4f6a-9c3d-2f1c0a44e9b1", "startPositionSeconds": 0 }
```

| Field | Type | Required | Constraint |
|---|---|---|---|
| `sessionKey` | string | yes | `@NotBlank`, `@Size(max = 100)`. Client-generated, UUID v4 canonically; reused for every `progress`/`end` call of the same play session |
| `startPositionSeconds` | long | no | `@PositiveOrZero`. The seek offset the player started from; when present it overwrites `lastPositionSeconds` |

Calling `start` with a `sessionKey` that already exists for this teacher **reuses the existing row**
rather than creating a second one — the lookup is by `(sessionKey, teacherUserId)`, so `start` is
effectively idempotent. It is not scoped by record: reusing one key across two records would keep
writing to the row created first.

**Response** `200 OK` — `MaqamListenSessionDTO`.

```json
{
  "id": 900,
  "maqamId": 12,
  "maqamCode": "MAQAM_000012",
  "teacherUserId": 7,
  "teacherUsername": "hemin.t",
  "sessionKey": "0f5b6e1a-6b2f-4f6a-9c3d-2f1c0a44e9b1",
  "startedAt": "2026-08-21T08:11:02.114Z",
  "secondsListened": 0,
  "lastPositionSeconds": 0,
  "ipAddress": "10.0.0.4",
  "userAgent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) …"
}
```

`ipAddress` and `userAgent` are populated **only for ADMIN callers** (`MaqamMapper.toSessionDTO`
takes an `adminView` flag); for everyone else they are null and therefore omitted. On the three
`listen/*` endpoints that flag is in practice always `false`: the service demands the actor's role
be `TEACHER`, and a TEACHER account never carries `ROLE_ADMIN`, so both columns are always omitted
here. They surface on the two session-log reads instead. `endedAt` is absent until the session is
closed.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `VALIDATION_ERROR` | `sessionKey` blank or over 100 chars; a negative `startPositionSeconds` |
| `400` | `JSON_PARSE_ERROR` | Body is not valid JSON |
| `401` | `TOKEN_MISSING` | Not signed in |
| `403` | `ACCESS_DENIED` | Caller lacks `maqam:vote` |
| `403` | `MAQAM_PANEL_ACCESS_DENIED` | Caller's role is not `TEACHER`, or they are not on this record's panel |
| `404` | `MAQAM_NOT_FOUND` | No active record with that code |

**Example**

```bash
curl -s -X POST "{{BASE_URL}}/api/maqam/MAQAM_000012/listen/start" \
  -H "Cookie: khi_auth_token=$TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"sessionKey":"0f5b6e1a-6b2f-4f6a-9c3d-2f1c0a44e9b1","startPositionSeconds":0}'
```

**Notes** — records the request IP, a `User-Agent` truncated to 500 chars, and the HTTP session id
when one exists. Writes a `LISTEN_STARTED` audit row.

### `POST /api/maqam/{maqamCode}/listen/progress`

Adds listened seconds to an open session. Called every ~10s and on pause.

**Authority:** `maqam:vote` — plus the service-level `TEACHER` + on-the-panel check.

**Request body** (`application/json`, `MaqamListenProgressRequestDTO`)

```json
{
  "sessionKey": "0f5b6e1a-6b2f-4f6a-9c3d-2f1c0a44e9b1",
  "addSeconds": 10,
  "positionSeconds": 73
}
```

| Field | Type | Required | Constraint |
|---|---|---|---|
| `sessionKey` | string | yes | `@NotBlank`, `@Size(max = 100)`. Must match an existing session for this teacher |
| `addSeconds` | long | yes | `@NotNull`, `@PositiveOrZero`. Delta of audio time advanced since the last ping |
| `positionSeconds` | long | yes | `@NotNull`, `@PositiveOrZero`. The player's current cursor |

Server-side clamping (`MaqamService.recordProgress`):

| Rule | Effect |
|---|---|
| `PROGRESS_PING_MAX_SECONDS = 60` | `addSeconds` is clamped to `0..60` per call, defeating tampering and clock drift. Note the DTO javadoc says "3× interval"; the constant in force is a flat 60 |
| Duration cap | When `audioDurationSeconds` is set and positive, the clamp is further reduced so the session total can never exceed the track length. Since the column is never written, this cap is currently inert |
| Position | `lastPositionSeconds` only ever moves forward — `max(reported, stored)` — so seeking backwards does not lower it |
| Aggregate | When the clamped delta is `> 0`, `bumpListen` adds it to the vote row's `totalListenSeconds`, raises `maxPositionSeconds` via SQL `GREATEST`, and stamps `lastListenAt` |

**Response** `200 OK` — the updated `MaqamListenSessionDTO` (same shape as `start`).

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `VALIDATION_ERROR` | Missing or negative `addSeconds`/`positionSeconds`; blank or over-long `sessionKey` |
| `400` | `MAQAM_VALIDATION_ERROR` | No session with that key for this teacher — `start` was never called, or the key was mistyped |
| `400` | `JSON_PARSE_ERROR` | Body is not valid JSON |
| `401` | `TOKEN_MISSING` | Not signed in |
| `403` | `ACCESS_DENIED` | Caller lacks `maqam:vote` |
| `403` | `MAQAM_PANEL_ACCESS_DENIED` | Caller's role is not `TEACHER`, or they are not on this record's panel |
| `404` | `MAQAM_NOT_FOUND` | No active record with that code |

**Example**

```bash
curl -s -X POST "{{BASE_URL}}/api/maqam/MAQAM_000012/listen/progress" \
  -H "Cookie: khi_auth_token=$TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"sessionKey":"0f5b6e1a-6b2f-4f6a-9c3d-2f1c0a44e9b1","addSeconds":10,"positionSeconds":73}'
```

**Notes** — writes a `LISTEN_PROGRESS` audit row per ping, carrying the **clamped** delta and the
reported position.

### `POST /api/maqam/{maqamCode}/listen/end`

Closes the session, folding in any final delta the client buffered.

**Authority:** `maqam:vote` — plus the service-level `TEACHER` + on-the-panel check.

**Request body** (`application/json`, `MaqamListenEndRequestDTO`)

```json
{
  "sessionKey": "0f5b6e1a-6b2f-4f6a-9c3d-2f1c0a44e9b1",
  "addSeconds": 4,
  "positionSeconds": 214
}
```

| Field | Type | Required | Constraint |
|---|---|---|---|
| `sessionKey` | string | yes | `@NotBlank`, `@Size(max = 100)` |
| `addSeconds` | long | no | `@PositiveOrZero`. Null is treated as `0` by the controller |
| `positionSeconds` | long | no | `@PositiveOrZero`. Null is treated as `0`; because position only moves forward, a `0` never lowers the stored cursor |

Identical to `progress` except that `endedAt` is stamped. Same clamping rules and the same
`bumpListen` aggregate update.

**Response** `200 OK` — the closed `MaqamListenSessionDTO`, now including `endedAt`.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `VALIDATION_ERROR` | Blank or over-long `sessionKey`; a negative `addSeconds`/`positionSeconds` |
| `400` | `MAQAM_VALIDATION_ERROR` | No session with that key for this teacher |
| `400` | `JSON_PARSE_ERROR` | Body is not valid JSON |
| `401` | `TOKEN_MISSING` | Not signed in |
| `403` | `ACCESS_DENIED` | Caller lacks `maqam:vote` |
| `403` | `MAQAM_PANEL_ACCESS_DENIED` | Caller's role is not `TEACHER`, or they are not on this record's panel |
| `404` | `MAQAM_NOT_FOUND` | No active record with that code |

**Example**

```bash
curl -s -X POST "{{BASE_URL}}/api/maqam/MAQAM_000012/listen/end" \
  -H "Cookie: khi_auth_token=$TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"sessionKey":"0f5b6e1a-6b2f-4f6a-9c3d-2f1c0a44e9b1","addSeconds":4,"positionSeconds":214}'
```

**Notes** — writes a `LISTEN_ENDED` audit row. Nothing prevents a later `progress` call against the
same key from reopening accrual; `endedAt` is a stamp, not a lock.

### `GET /api/maqam/{maqamCode}/listen-summary`

Per-teacher engagement aggregate for one record — the "how engaged is each teacher" widget in one
row apiece.

**Authority:** `maqam:read`

**Response** `200 OK` — a plain array of `MaqamListenSummaryDTO`, sorted by `teacherUsername`
ascending. One entry per **panel member**, including teachers who have never listened.

```json
[
  {
    "teacherUserId": 7,
    "teacherUsername": "hemin.t",
    "teacherDisplayName": "Hemin Ali",
    "totalSeconds": 186,
    "maxPositionSeconds": 201,
    "sessionCount": 3,
    "firstListenAt": "2026-08-20T11:04:02.551Z",
    "lastListenAt": "2026-08-21T08:14:59.002Z"
  }
]
```

| Field | Type | Notes |
|---|---|---|
| `teacherUserId`, `teacherUsername`, `teacherDisplayName` | — | Snapshotted on the vote row at assignment time, so they survive a later rename |
| `totalSeconds` | long | The vote row's `totalListenSeconds`, coerced to `0` when null |
| `maxPositionSeconds` | long | Furthest offset reached, coerced to `0` when null |
| `sessionCount` | long | Rows in `maqam_audio_listen_sessions` for this `(record, teacher)` pair |
| `firstListenAt` | instant | Mapped from the vote row's **`votedAt`**, not from a listen timestamp — despite the field name it is absent until the teacher votes |
| `lastListenAt` | instant | Absent until the first listen ping lands |
| `coverageRatio` | double | `totalSeconds / audioDurationSeconds`, capped at `1.0`; null (omitted) when the record has no duration — currently always |

**Errors**

| Status | `error` code | When |
|---|---|---|
| `401` | `TOKEN_MISSING` | Not signed in |
| `403` | `ACCESS_DENIED` | Caller lacks `maqam:read` |
| `404` | `MAQAM_NOT_FOUND` | No active record with that code |

**Example**

```bash
curl -s "{{BASE_URL}}/api/maqam/MAQAM_000012/listen-summary" \
  -H "Cookie: khi_auth_token=$TOKEN"
```

**Notes** — deliberately visible to every `maqam:read` holder so peer teachers can see how each
teammate engaged before voting. Unlike the single-record read, this endpoint does **not** run the
teacher-panel visibility check, so a teacher who knows a code can read the summary of a record they
are not assigned to. It writes no audit row.

### `GET /api/maqam/{maqamCode}/sessions`

Paged per-session listen log for one record.

**Authority:** `maqam:read`

**Query parameters**

| Name | Type | Default | Description |
|---|---|---|---|
| `teacherUserId` | long | — | Optional. Restricts the log to one teacher |
| `page` | int | `0` | Zero-based page index |
| `size` | int | `100` | `@PageableDefault(size = 100)` |

Ordering comes from the repository method name (`findAllByListOfMaqamId…OrderByStartedAtDesc`), so
rows arrive newest-session-first. Whether an explicit Spring `sort` parameter overrides that
method-name ordering is _Not documented in source._

**Response** `200 OK` — `Page<MaqamListenSessionDTO>`; `content[]` elements have the shape shown
under `listen/start`. `ipAddress` and `userAgent` appear only when the caller holds `ROLE_ADMIN`.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `TYPE_MISMATCH` | `teacherUserId` is not a number |
| `401` | `TOKEN_MISSING` | Not signed in |
| `403` | `ACCESS_DENIED` | Caller lacks `maqam:read` |
| `404` | `MAQAM_NOT_FOUND` | No active record with that code |

**Example**

```bash
curl -s "{{BASE_URL}}/api/maqam/MAQAM_000012/sessions?teacherUserId=7&size=50" \
  -H "Cookie: khi_auth_token=$TOKEN"
```

**Notes** — like `listen-summary`, this endpoint checks only that the record exists and is active;
it does not apply the teacher-panel visibility check. Writes no audit row.

### `GET /api/admin/maqam/teachers/{teacherUserId}/sessions`

Every listen session a single teacher ever recorded, across all records — the raw material for an
engagement report.

**Authority:** `hasRole('ADMIN')` — the only maqam endpoint gated on a role rather than a
permission, because the rows carry IP and user-agent PII.

**Query parameters**

| Name | Type | Default | Description |
|---|---|---|---|
| `page` | int | `0` | Zero-based page index |
| `size` | int | `100` | `@PageableDefault(size = 100)` |

Ordering comes from the repository method name (`findAllByTeacherUserIdOrderByStartedAtDesc`);
whether an explicit Spring `sort` parameter overrides it is _Not documented in source._

**Response** `200 OK` — `Page<MaqamListenSessionDTO>` with `ipAddress` and `userAgent` populated.
An unknown `teacherUserId` yields an empty page, not a `404`.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `TYPE_MISMATCH` | `teacherUserId` is not a number |
| `401` | `TOKEN_MISSING` | Not signed in |
| `403` | `ACCESS_DENIED` | Caller is not ADMIN — `details.requiredAuthority` is `ADMIN` |

**Example**

```bash
curl -s "{{BASE_URL}}/api/admin/maqam/teachers/7/sessions?page=0&size=100" \
  -H "Cookie: khi_auth_token=$TOKEN"
```

**Notes** — writes no audit row.

---

## Trash

Maqam follows the project-wide trash model: `DELETE` sets `removedAt`/`removedBy` instead of
removing the row, trashed records disappear from every read path, and only a `maqam:delete` holder
(ADMIN by default) can list, restore, or permanently purge them. Purge is the only operation that
deletes the S3 object.

Trashed records keep their teacher panel and votes intact, so a restore brings the whole voting
state back.

### `DELETE /api/maqam/{maqamCode}`

Soft-trash a record.

**Authority:** `maqam:delete`

**Response** `204 No Content` — empty body.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `401` | `TOKEN_MISSING` | Not signed in |
| `403` | `ACCESS_DENIED` | Caller lacks `maqam:delete` |
| `404` | `MAQAM_NOT_FOUND` | No active record with that code — already-trashed records also return this |

**Example**

```bash
curl -s -X DELETE "{{BASE_URL}}/api/maqam/MAQAM_000012" \
  -H "Cookie: khi_auth_token=$TOKEN" -o /dev/null -w '%{http_code}\n'
```

**Notes** — writes a `REMOVE` audit row with details `soft-trashed`. The S3 object is untouched.

### `GET /api/admin/maqam/trash`

Paged listing of soft-trashed records.

**Authority:** `maqam:delete`

**Query parameters** — identical to `GET /api/maqam`: `page`, `size` (**default `100`** here), and
the full `MaqamFilterParams` binding. `removedBy`, `removedFrom` and `removedTo` are the filters
that come into their own on this endpoint ("what did we trash last week, and who did it?").

Because the endpoint is admin-only there is no teacher-visibility branch — every trashed record is
in scope. The same two paths apply: no filters → DB-paged; any non-sort filter → the full trashed
set is loaded, filtered and sorted in memory, then sliced. As with the active list, the Spring
`sort` parameter is ignored in favor of `sortBy`/`sortDirection`.

**Response** `200 OK` — `Page<MaqamResponseDTO>`. Trashed records carry `removedAt` and `removedBy`
in addition to the usual fields.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `VALIDATION_ERROR` | A malformed typed filter field, e.g. `removedFrom=last-week` — `@ModelAttribute` binding failures surface as a `BindException` |
| `401` | `TOKEN_MISSING` | Not signed in |
| `403` | `ACCESS_DENIED` | Caller lacks `maqam:delete` |

**Example**

```bash
curl -s "{{BASE_URL}}/api/admin/maqam/trash?removedFrom=2026-08-01&removedTo=2026-08-26&sortBy=updatedAt&sortDirection=desc" \
  -H "Cookie: khi_auth_token=$TOKEN"
```

**Notes** — writes a `LIST` audit row prefixed `trash`.

### `POST /api/admin/maqam/{maqamCode}/restore`

Restore a trashed record: clears `removedAt`/`removedBy` and stamps `updatedBy` with the actor.

**Authority:** `maqam:delete`

**Request body** — none.

**Response** `200 OK` — the restored `MaqamResponseDTO`.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `MAQAM_VALIDATION_ERROR` | The record exists but is not in the trash |
| `401` | `TOKEN_MISSING` | Not signed in |
| `403` | `ACCESS_DENIED` | Caller lacks `maqam:delete` |
| `404` | `MAQAM_NOT_FOUND` | No record with that code at all |

**Example**

```bash
curl -s -X POST "{{BASE_URL}}/api/admin/maqam/MAQAM_000012/restore" \
  -H "Cookie: khi_auth_token=$TOKEN"
```

**Notes** — writes a `RESTORE` audit row.

### `DELETE /api/admin/maqam/{maqamCode}/purge`

Permanently delete a trashed record and its S3 audio object. Cascades to the record's teacher-vote
rows through `orphanRemoval`; the `maqam_audio_listen_sessions` rows are **not** cascaded (they
reference the record by a plain `list_of_maqam_id` column, not a foreign-key association), so the
listen history outlives the record.

**Authority:** `maqam:delete`

**Response** `204 No Content` — empty body.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `MAQAM_VALIDATION_ERROR` | The record exists but has not been trashed first |
| `401` | `TOKEN_MISSING` | Not signed in |
| `403` | `ACCESS_DENIED` | Caller lacks `maqam:delete` |
| `404` | `MAQAM_NOT_FOUND` | No record with that code at all |

**Example**

```bash
curl -s -X DELETE "{{BASE_URL}}/api/admin/maqam/MAQAM_000012/purge" \
  -H "Cookie: khi_auth_token=$TOKEN" -o /dev/null -w '%{http_code}\n'
```

**Notes** — the `PURGE` audit row is written **before** the row is deleted, so the audit trail keeps
the code, song name and producer. The S3 delete runs after the row delete and is best-effort.
Purging lowers `count()`, which is what the code generator uses — see the `409 CONFLICT` note on
`POST /api/maqam`.

---

## MAQAM_PANEL_ACCESS_DENIED

`MaqamAccessDeniedException` → `403` with `error: "MAQAM_PANEL_ACCESS_DENIED"` and
`category: "AUTHORIZATION"` (`ApiExceptionHandler.handleMaqamAccessDenied`). It is the maqam
domain's own authorization failure and is distinct from the generic `ACCESS_DENIED` that Spring
Security raises when a `@PreAuthorize` check fails: `ACCESS_DENIED` means *you lack the permission*,
`MAQAM_PANEL_ACCESS_DENIED` means *you have the permission but this record is not yours to touch*.

Every message it can carry, verbatim from `MaqamService`:

| Message | Raised by |
|---|---|
| `You are not on the teacher panel for maqam {code}` | `ensureCallerMaySeeRecord` (record read, stream) and `ensureTeacherAssignedTo` (listen start/progress/end) |
| `You are not assigned to this maqam record: {code}` | `upsertVote` — the actor is a TEACHER but has no vote row on the record |
| `Only teachers may cast votes` | `upsertVote` — the actor's role is not `TEACHER` |
| `Only teachers may track audio listening sessions` | `ensureTeacherAssignedTo` — the actor's role is not `TEACHER` |
| `Only teachers may view their recent-activity feed` | `getMyRecentActivity` |
| `Authentication is required` | `mustGetCurrentUser` — the principal could not be resolved to a `User` |

The standing hint is always `Only TEACHER or ADMIN accounts may access the maqam voting panel.` — a
generic string; the specific reason is in `message`. No `details` map is attached.

```json
{
  "timestamp": "2026-08-26T09:12:44.118Z",
  "status": 403,
  "error": "MAQAM_PANEL_ACCESS_DENIED",
  "category": "AUTHORIZATION",
  "message": "You are not on the teacher panel for maqam MAQAM_000012",
  "hint": "Only TEACHER or ADMIN accounts may access the maqam voting panel.",
  "path": "/api/maqam/MAQAM_000012/stream"
}
```

## Error envelope

Every error above is the shared `ApiErrorResponse` (`common/exceptions/ApiErrorResponse.java`),
produced by `platform/exceptions/ApiExceptionHandler.java`
(`@RestControllerAdvice(basePackages = "ak.dev.khi_archive_platform.platform")`) — or, for `401`s,
by the JWT filter and `JwtAuthenticationEntryPoint`. Null fields are omitted.

```json
{
  "timestamp": "2026-08-26T09:12:44.118Z",
  "status": 400,
  "error": "MAQAM_VALIDATION_ERROR",
  "category": "VALIDATION",
  "message": "Validation failed for maqam data.",
  "hint": "Maqam record invalid — see field-level reasons in 'details'.",
  "path": "/api/maqam",
  "details": {
    "songName": "songName is required"
  }
}
```

`traceId` appears only when an MDC correlation id is present. `details` appears only when the
handler attaches structured data — field errors for the two validation codes, and
`requiredAuthority` / `actor` / `actorAuthorities` / `requestMethod` for `ACCESS_DENIED`.

The `401` codes reachable here are `TOKEN_MISSING` (no cookie and no bearer header),
`TOKEN_EXPIRED`, `TOKEN_MALFORMED`, `TOKEN_INVALID_SIGNATURE`, `TOKEN_INVALID` and `TOKEN_REVOKED`;
the per-endpoint tables list `TOKEN_MISSING` as the representative case.

## Audit logging

Every consequential action writes one row to `maqam_audit_logs` through `MaqamAuditService`, in a
`REQUIRES_NEW` transaction so an audit failure cannot roll back the operation. The table is
shape-aligned with the other `*_audit_logs` tables (so the analytics `UNION ALL` stays valid) and
adds maqam-specific columns: `teacher_user_id` / `teacher_username` / `teacher_display_name`,
`maqam_type`, `session_key`, `seconds_listened`, `position_seconds`.

| `MaqamAuditAction` | Written by |
|---|---|
| `CREATE` | `POST /api/maqam` |
| `READ` | `GET /api/maqam/{maqamCode}` |
| `LIST` | `GET /api/maqam`, `GET /api/admin/maqam/trash`, `GET /api/maqam/teacher/my-recent` |
| `SEARCH` | `GET /api/maqam/search` |
| `UPDATE` | `PATCH /api/maqam/{maqamCode}` |
| `REMOVE` | `DELETE /api/maqam/{maqamCode}` |
| `RESTORE` | `POST /api/admin/maqam/{maqamCode}/restore` |
| `PURGE` | `DELETE /api/admin/maqam/{maqamCode}/purge` |
| `TEACHER_ASSIGNED`, `TEACHER_REMOVED` | `PUT /api/admin/maqam/{maqamCode}/teachers` |
| `VOTE_CAST`, `VOTE_UPDATED` | `POST /api/maqam/{maqamCode}/vote` |
| `VOTE_DELETED` | `DELETE /api/admin/maqam/{maqamCode}/votes/{teacherUserId}` |
| `STREAM` | `GET /api/maqam/{maqamCode}/stream`, once per range request |
| `LISTEN_STARTED`, `LISTEN_PROGRESS`, `LISTEN_ENDED` | The three `listen/*` endpoints |

`MaqamAuditAction.DELETE` is declared in the enum for alignment with the other entities but is never
written by the maqam service. The `listen-summary` and both `sessions` endpoints write no audit row.
Free-text `details` are HTML-escaped before storage.

## Related

- [Internal API index](../README.md)
- [Conventions](../01-conventions.md) — the Spring `Page` envelope, timestamp formats
  (`Asia/Baghdad`) and the shared error codes referenced throughout this document
- [Authorization](../02-authorization.md) — the permission catalog and the role seed sets behind
  `maqam:read` / `maqam:vote` / `maqam:teacher_manage`
- [Errors](../03-errors.md) — the full `ApiErrorResponse` contract and every error code
- [Maqam schema](../database/schema-maqam.md) — `list_of_maqam`, `maqam_teacher_votes`,
  `maqam_audio_listen_sessions`, `maqam_audit_logs`
- [Inventory and maqam analytics](../analytics/inventory-and-maqam.md) — the vote and
  listen-engagement rollups built on these tables
- **No OpenAPI document is generated or checked in.** There is no springdoc or swagger dependency in
  `pom.xml` and no `openapi.yaml` anywhere in the tree, so this page plus the maqam schema *are* the
  contract. A hand-written OpenAPI 3.1 spec covering the maqam surface
  (`info.title: KHI Archive Platform — Maqam API`, version `1.0.0`) used to live in the retired
  `MAQAM_FEATURE.md`; it had already drifted from the code before it was archived — it predates
  `GET /api/maqam/maqam-types` and `GET /api/maqam/teacher/my-recent`, and it still described the
  ignored Spring `sort` parameter instead of the `MaqamFilterParams` binding. Treat it as history,
  not as a source. If a machine-readable contract is wanted again, generate it from the controllers
  rather than hand-maintaining a second copy that drifts the same way.
