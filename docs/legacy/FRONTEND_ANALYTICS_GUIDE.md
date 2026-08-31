# Frontend Guide — Analytics & User-Activity Enhancements

**Audience:** frontend engineers integrating the admin analytics dashboard.
**Auth:** every endpoint below is **admin-only** (`ROLE_ADMIN`). Send the JWT as usual; a non-admin gets `403`.
**Base path:** `/api/analytics`.

This release adds **inventory**, **visibility**, **maqam-teacher**, and **weekly/yearly** reporting, and makes per-user activity **comprehensive** (now includes admin user-management actions). Nothing existing was removed — only added to.

---

## 0. TL;DR — what's new

| Area | Method & path | Purpose |
|---|---|---|
| Inventory counts | `GET /api/analytics/inventory` | How many items exist, by type, active vs trashed |
| Visibility | `GET /api/analytics/visibility` | Visible vs hidden projects & media; items in hidden projects |
| Maqam overview | `GET /api/analytics/maqam/overview` | Teacher-classification progress + leaderboard |
| Maqam teachers | `GET /api/analytics/maqam/teachers` | Per-teacher leaderboard |
| Maqam one teacher | `GET /api/analytics/maqam/teachers/{username}` | One teacher's activity |
| Weekly buckets | `GET /api/analytics/weekly` | ISO-week time series |
| Yearly buckets | `GET /api/analytics/yearly` | Calendar-year time series |

**Changed (behaviour, not shape-breaking):**
- `GET /api/analytics/me`, `/users/{username}`, `/overview` now also return `weekly` and `yearly` arrays alongside `daily`/`monthly`.
- Every activity endpoint (`/overview`, `/users`, `/feed`, `/entities`, `/me`, `/users/{username}`) now **includes a new `"user"` entity** — admin user-management actions (role changes, permission grants, activations, warnings). See §7.

---

## 1. Inventory — `GET /api/analytics/inventory`

Live row counts against the actual tables (not audit activity). **No query params**; not affected by any date window.

```jsonc
{
  "totalActive": 1234,
  "totalTrashed": 56,
  "grandTotal": 1290,
  "byType": {
    "audio":          { "active": 300, "trashed": 10, "total": 310 },
    "video":          { "active": 120, "trashed": 4,  "total": 124 },
    "image":          { "active": 500, "trashed": 20, "total": 520 },
    "text":           { "active": 80,  "trashed": 2,  "total": 82 },
    "maqam":          { "active": 45,  "trashed": 1,  "total": 46 },
    "physical_media": { "active": 60,  "trashed": 5,  "total": 65 },
    "project":        { "active": 90,  "trashed": 8,  "total": 98 },
    "person":        { "active": 30,  "trashed": 6,  "total": 36 },
    "category":       { "active": 9,   "trashed": 0,  "total": 9 }
  },
  "generatedAt": "2026-07-21T09:00:00Z"
}
```

- `active` = not in trash; `trashed` = soft-deleted (recoverable); `total` = active + trashed.
- Use `byType` for a per-type bar/table and the `total*` fields for headline KPI tiles.

---

## 2. Visibility — `GET /api/analytics/visibility`

Public-vs-hidden snapshot. **No query params.**

```jsonc
{
  "projectsVisible": 70,
  "projectsHidden": 20,
  "projectsTotal": 90,

  "mediaByType": {
    "audio": { "publicCount": 280, "privateCount": 20, "total": 300 },
    "video": { "publicCount": 110, "privateCount": 10, "total": 120 },
    "image": { "publicCount": 480, "privateCount": 20, "total": 500 },
    "text":  { "publicCount": 75,  "privateCount": 5,  "total": 80 }
  },
  "mediaPublicTotal": 945,
  "mediaPrivateTotal": 55,

  "itemsInVisibleProjects": 900,
  "itemsInHiddenProjects": 100,

  "generatedAt": "2026-07-21T09:00:00Z"
}
```

Counts are over **active** rows only. Notes:
- `publicCount` includes rows whose flag is unset (treated as public, matching guest APIs).
- **`itemsInHiddenProjects`** is important UX: an item can be `public` itself yet still invisible to guests because its **project** is hidden. Surface this as "N items hidden because their project is hidden."
- `person`, `category`, `maqam`, `physical_media` have no visibility flag, so they're not in this response.

---

## 3. Maqam teacher analytics

### 3a. Overview — `GET /api/analytics/maqam/overview`

Team-level classification progress + per-teacher leaderboard. **No query params.** Counts **active** records only.

```jsonc
{
  "totalTeachers": 4,
  "totalActiveRecords": 45,
  "totalAssignments": 110,       // teacher vote-rows across active records
  "totalVotesCast": 88,          // of those, how many actually voted
  "totalPendingVotes": 22,       // assigned but not yet voted

  "recordsUnclassified": 5,      // 0 votes cast
  "recordsPartiallyVoted": 12,   // some but not all assigned teachers voted
  "recordsFullyVoted": 28,       // every assigned teacher voted
  "recordsWithConsensus": 20,    // fully voted, all agreed on one maqam_type
  "recordsWithDisagreement": 8,  // fully voted, 2+ distinct maqam_type values

  "totalListenSessions": 640,
  "totalListenSeconds": 512000,

  "maqamTypeDistribution": {     // most-common first
    "Rast": 30, "Bayati": 22, "Hijaz": 18, "Segah": 10
  },

  "teachers": [ /* TeacherActivity objects, see 3b, ordered by votes then listening */ ],
  "generatedAt": "2026-07-21T09:00:00Z"
}
```

Good visual mappings: a stacked bar of `recordsUnclassified/Partial/Full`, a donut of `consensus vs disagreement`, and a bar chart from `maqamTypeDistribution`.

> **Note on `maqamType`:** the value is intentionally free-form (the archive has no closed taxonomy) and is only trimmed on save — **not** case-folded. So `consensus`/`disagreement` and `maqamTypeDistribution` compare the raw strings: `"Rast"` and `"rast"` count as two different types. If you want case-insensitive grouping in the UI, normalize on the client. `totalListenSessions`/`totalListenSeconds` reconcile exactly with the sum of the `teachers[]` rows.

### 3b. Teachers leaderboard — `GET /api/analytics/maqam/teachers`

Returns the `teachers` array on its own — an array of:

```jsonc
{
  "teacherUserId": 12,
  "teacherUsername": "shilan",
  "teacherDisplayName": "Shilan A.",
  "assignedRecords": 30,
  "votesCast": 26,
  "pendingVotes": 4,
  "distinctMaqamTypes": 7,
  "totalListenSeconds": 128000,
  "maxPositionSeconds": 240,
  "listenSessions": 210,
  "recordsListened": 28,
  "firstVotedAt": "2026-01-05T10:00:00Z",
  "lastListenAt": "2026-07-20T14:00:00Z"
}
```

### 3c. One teacher — `GET /api/analytics/maqam/teachers/{username}`

Same object as above for one teacher. **`404`** if the username is not on any active-record panel.

---

## 4. Weekly & Yearly time buckets

These join the existing `daily` and `monthly` endpoints. **Same universal query params** as the rest of `/api/analytics` (see §6). Both return arrays, **newest bucket first**, empty buckets omitted.

### `GET /api/analytics/weekly`
Default window ≈ 12 weeks when no `days`/`from`/`to` given. Weeks are **Monday-anchored** (ISO).

```jsonc
[
  {
    "week": "2026-07-13",     // Monday that starts the ISO week
    "label": "2026-W29",      // ISO year-week
    "total": 320, "created": 40, "updated": 120, "deleted": 10,
    "restored": 2, "purged": 0, "viewed": 130, "searched": 18,
    "activeUsers": 5
  }
]
```

### `GET /api/analytics/yearly`
Default window = **last 5 years** when no `days`/`from`/`to` given.

```jsonc
[
  {
    "year": "2026-01-01",     // first day of the year
    "label": "2026",
    "total": 5400, "created": 700, "updated": 2100, "deleted": 150,
    "restored": 30, "purged": 5, "viewed": 2200, "searched": 300,
    "activeUsers": 9
  }
]
```

> ⚠️ The universal `days` param is still capped at **365**. For a multi-year custom range, pass explicit `from`/`to` (ISO-8601) instead of `days`.

---

## 5. Per-user reports now carry all four granularities

`GET /api/analytics/me` and `GET /api/analytics/users/{username}` (and `/overview`) responses now include **`weekly`** and **`yearly`** arrays in addition to the existing `daily` and `monthly`:

```jsonc
{
  "username": "aland",
  "totalActions": 1280,
  "byEntity": { "audio": {...}, "video": {...}, "user": {...} },
  "daily":   [ /* DailyBucket */ ],
  "weekly":  [ /* WeeklyBucket, NEW */ ],
  "monthly": [ /* MonthlyBucket */ ],
  "yearly":  [ /* YearlyBucket, NEW */ ],
  "recent":  { /* paginated feed */ }
}
```

You can now offer a **Daily / Weekly / Monthly / Yearly** toggle on the per-user activity chart, all from one response — no extra calls needed. (Or keep calling the dedicated `/daily`,`/weekly`,`/monthly`,`/yearly` endpoints if you filter them independently.)

---

## 6. Universal query params (unchanged, apply to §4 + existing time/feed/entity endpoints)

| Param | Meaning |
|---|---|
| `days` | window length 1–365 (default 30) when `from`/`to` absent |
| `from`, `to` | explicit ISO-8601 instants (override `days`; not capped) |
| `entities` | CSV filter: `audio,video,image,text,project,category,person,maqam,physical_media,user` |
| `actions` | CSV filter: `CREATE,READ,UPDATE,DELETE,SEARCH` (+ RESTORE,PURGE and maqam/user actions) |
| `actor` | exact username |
| `actorPattern` | substring on username/display name |
| `entityCode` | exact entity code |
| `q` | free-text over details/entity code/actor |

`/inventory`, `/visibility`, `/maqam/*` take **no params** — they're live snapshots.

---

## 7. ⚠️ Behaviour change: `"user"` entity now appears everywhere

The activity union now includes **`user_audit_logs`**, so admin user-management actions flow into every activity report. Concretely:

- `byEntity` maps (in `/overview`, `/me`, `/users/{username}`, `/entities`) now contain a **`"user"`** key.
- The `/feed` and per-user `recent` feeds now include rows with `entity: "user"`, where:
  - `entityCode` = the **target** user's username (who the action was performed on),
  - `actorUsername` = the admin who did it.
- New action names you may see in `/actions`, `/feed`, and `actions` filters:
  `ROLE_CHANGE, GRANT_PERMISSIONS, REVOKE_PERMISSIONS, ACTIVATE, DEACTIVATE, WARNING_SENT, WARNING_REVOKED, WARNING_ACKNOWLEDGED`.

**Frontend action items:**
1. Add a label/icon for the `user` entity in any entity legend/table.
2. Add human-readable labels for the new action names.
3. If you hardcoded the entity list anywhere, add `user` (and confirm `maqam`, `physical_media` are present).
4. These actions map to the generic `total` count; they aren't split into created/updated/etc. buckets in `EntityStatsDTO` — read them from `/actions` or the feed for a precise breakdown.

> Note: `LOGIN`/`LOGOUT` are **not** yet audited (they live only in the `sessions` table), so they do not appear in these reports. If you need a login/session report, that's a follow-up (see the backend team's notes).

---

## 8. Suggested dashboard layout

1. **KPI row** — `inventory.grandTotal`, `inventory.totalActive`, `inventory.totalTrashed`, `visibility.projectsHidden`.
2. **Inventory by type** — bar chart from `inventory.byType`.
3. **Visibility** — grouped bar (public vs private) from `visibility.mediaByType` + a callout for `itemsInHiddenProjects`.
4. **Activity time series** — one chart with a Daily/Weekly/Monthly/Yearly toggle (`/daily`,`/weekly`,`/monthly`,`/yearly`).
5. **Maqam panel** — classification progress (`/maqam/overview`) + teacher leaderboard table (`/maqam/teachers`).
6. **User drill-down** — `/users/{username}` with the 4-granularity toggle and the `recent` feed.
