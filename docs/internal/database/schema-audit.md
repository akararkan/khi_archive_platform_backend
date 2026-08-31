# Schema — Audit and Activity-Log Tables

> **Audience:** Backend / DBA · **Source:** `platform/model/audio`, `platform/model/video`,
> `platform/model/image`, `platform/model/text`, `platform/model/category`,
> `platform/model/person`, `platform/model/project`, `platform/model/maqam`,
> `platform/model/physicalmedia`, `platform/model/correction`, `platform/model/analytics`,
> `platform/model/trending`, `user/model` (all under
> `src/main/java/ak/dev/khi_archive_platform/`)

The platform keeps two families of append-only tables. The first is twelve `*_audit_logs`
tables — one per audited entity plus one for the analytics console and one for admin user
management — that record **who did what, from where, and when**, with the actor, session and
request envelope denormalized into every row. The second is two guest activity tables
(`guest_search_logs`, `guest_interaction_logs`) that feed the public trending endpoint and are
purged on a 30-day rolling window.

All fourteen tables are created by Hibernate under `spring.jpa.hibernate.ddl-auto=update`.
There is no Flyway or Liquibase; indexes and CHECK-constraint refreshes come from
`@Component` initializer beans in `platform/config/` and `user/configs/` whose
`@EventListener(ApplicationReadyEvent.class)` method issues raw SQL through `JdbcTemplate`.

## Tables at a glance

| Table | Java entity | Purpose | Rows grow with |
|---|---|---|---|
| `audio_audit_logs` | `platform.model.audio.AudioAuditLog` | Audio CRUD / read / list / search trail | Every audio API call that records an action |
| `video_audit_logs` | `platform.model.video.VideoAuditLog` | Video CRUD / read / list / search trail | Every video API call that records an action |
| `image_audit_logs` | `platform.model.image.ImageAuditLog` | Image CRUD / read / list / search trail | Every image API call that records an action |
| `text_audit_logs` | `platform.model.text.TextAuditLog` | Text CRUD / read / list / search trail | Every text API call that records an action |
| `category_audit_logs` | `platform.model.category.CategoryAuditLog` | Category vocabulary trail | Every category API call that records an action |
| `person_audit_logs` | `platform.model.person.PersonAuditLog` | Person record trail | Every person API call that records an action |
| `project_audit_logs` | `platform.model.project.ProjectAuditLog` | Project trail, including trash cascade counts | Every project API call that records an action |
| `maqam_audit_logs` | `platform.model.maqam.MaqamAuditLog` | List-of-Maqam CRUD plus teacher voting and per-second listening | CRUD, plus one row per listen tick and per range request |
| `physical_media_audit_logs` | `platform.model.physicalmedia.PhysicalMediaAuditLog` | Physical inventory trail, Excel imports, and media-type catalog edits | Inventory CRUD, one row per `.xlsx` import, catalog edits |
| `guest_correction_audit_logs` | `platform.model.correction.GuestCorrectionAuditLog` | Guest correction lifecycle trail | Correction submit / view / list / triage actions |
| `analytics_audit_logs` | `platform.model.analytics.AnalyticsAuditLog` | Which analytics view a staff member opened | One row per analytics console request |
| `user_audit_logs` | `user.model.UserAuditLog` | Admin user management and warnings trail | Role changes, permission grants, warnings, admin user CRUD |
| `guest_search_logs` | `platform.model.trending.GuestSearchLog` | Guest search queries for the "Top Searches" list | Every non-blank guest search; purged after 30 days |
| `guest_interaction_logs` | `platform.model.trending.GuestInteractionLog` | Guest entity views for time-decay trending scores | Every guest view of a public entity; purged after 30 days |

Ten of the twelve `*_audit_logs` tables are read together by the analytics module through a
single `UNION ALL` CTE — see [The UNION ALL analytics view](#the-union-all-analytics-view).

## The common audit-log shape

Every one of the twelve `*_audit_logs` tables carries the same seventeen columns — `id`,
`action` and the fifteen-column request/actor envelope from `actor_user_id` through
`occurred_at`. The two guest trending tables do **not** — they carry their own much narrower
shapes (three columns for `guest_search_logs`, four for `guest_interaction_logs`) and are
documented separately below.

| Column | Type | Null | Default | Description |
|---|---|---|---|---|
| `id` | `bigint` (identity) | no | identity | PK. `@GeneratedValue(strategy = IDENTITY)` |
| `action` | `varchar(N)` | no | — | Enum name, `@Enumerated(EnumType.STRING)`. `N` varies per table (20, 30 or 32). CHECK-constrained |
| `actor_user_id` | `bigint` | yes | — | Snapshot of `users_tbl.user_id` for the signed-in caller. **Not** a declared FK |
| `actor_username` | `varchar(255)` | yes | — | Snapshot of `users_tbl.username`; falls back to `authentication.getName()`, then the literal `anonymous` |
| `actor_display_name` | `varchar(255)` | yes | — | Snapshot of `users_tbl.name`; same fallback chain as `actor_username` |
| `actor_authorities` | `text` | yes | — | `columnDefinition = "TEXT"`. Comma-joined distinct `GrantedAuthority` strings, **including** the `ROLE_*` entry |
| `actor_permissions` | `text` | yes | — | `columnDefinition = "TEXT"`. Same list with every `ROLE_`-prefixed authority filtered out |
| `device_info` | `varchar(255)` | yes | — | `sessions.device_info` when the JWT resolves to a session row; otherwise the `User-Agent` header |
| `ip_address` | `varchar(255)` | yes | — | `sessions.ip_address` when a session resolves; otherwise `HttpServletRequest.getRemoteAddr()` |
| `session_id` | `varchar(255)` | yes | — | Snapshot of `sessions.session_id`. Null when no session could be resolved. **Not** a declared FK |
| `session_login_timestamp` | `timestamp(6) with time zone` | yes | — | Snapshot of `sessions.login_timestamp` |
| `session_expires_at` | `timestamp(6) with time zone` | yes | — | Snapshot of `sessions.expires_at` |
| `session_is_active` | `boolean` | yes | — | Snapshot of `sessions.is_active`. Java field is `sessionActive`; the column name is explicit in `@Column(name = "session_is_active")` |
| `request_method` | `varchar(255)` | yes | — | `HttpServletRequest.getMethod()` |
| `request_path` | `varchar(255)` | yes | — | `HttpServletRequest.getRequestURI()` |
| `details` | `text` | yes | — | `columnDefinition = "TEXT"`. Free-text summary written by the calling service, passed through `HtmlUtils.htmlEscape(...)` |
| `occurred_at` | `timestamp(6) with time zone` | no | — | `Instant.now()` captured at write time |

Facts that hold for all twelve tables:

- **`id` is the only inferred column name.** The `id` field carries no `@Column`, so Spring
  Boot's default `CamelCaseToUnderscoresNamingStrategy` yields `id`. Every other column in
  every audit entity has an explicit `@Column(name = ...)`, and every table has an explicit
  `@Table(name = ...)` — nothing else in this document was inferred.
- **No foreign keys.** Audit rows are snapshots, deliberately denormalized so a purge of the
  underlying record does not cascade into or invalidate the trail. `actor_user_id`,
  `session_id` and every `*_id` / `*_code` entity column are plain values with no referential
  constraint. Queries that join back to a live entity must tolerate misses.
- **No associations and no side tables.** None of these entities declares `@OneToMany`,
  `@ManyToOne`, `@ManyToMany`, `@ElementCollection`, `@CollectionTable` or `@JoinTable`, so
  there are no join or collection tables in this schema area.
- **No optimistic locking.** No `@Version` column exists on any audit entity; rows are
  insert-only and never updated.
- **No `DEFAULT` clauses.** Nothing in these entities declares a column default. Every value is
  supplied by the writing service.
- **Writes are `REQUIRES_NEW`.** Each `*AuditService.record(...)` runs in
  `@Transactional(propagation = Propagation.REQUIRES_NEW)`, so an audit row survives a rollback
  of the business transaction that triggered it.
- **Timestamps.** `java.time.Instant` maps to Hibernate 6's `TIMESTAMP_UTC` JDBC type, which on
  PostgreSQL is `timestamp(6) with time zone`. Values are stored in UTC; the API layer renders
  them in `Asia/Baghdad` per `spring.jackson.time-zone`.

---

## `audio_audit_logs`

**Entity:** `ak.dev.khi_archive_platform.platform.model.audio.AudioAuditLog`

| Column | Type | Null | Default | Description |
|---|---|---|---|---|
| `id` | `bigint` (identity) | no | identity | PK |
| `audio_id` | `bigint` | yes | — | Snapshot of `audio.id`; no FK. Null for list / search / bulk-create rows |
| `audio_code` | `varchar(255)` | yes | — | Snapshot of the audio business code (`length = 255` declared explicitly) |
| `audio_title` | `varchar(255)` | yes | — | Snapshot of `Audio.fileName` at the time of the action |
| `project_id` | `bigint` | yes | — | Parent project id snapshot; set only when the audio has a project |
| `project_code` | `varchar(200)` | yes | — | Parent project code snapshot |
| `project_name` | `varchar(255)` | yes | — | Parent project name snapshot |
| `person_id` | `bigint` | yes | — | Person id reached through the parent project |
| `person_code` | `varchar(50)` | yes | — | Person code reached through the parent project |
| `person_name` | `varchar(255)` | yes | — | `Person.fullName` reached through the parent project |
| `category_code` | `varchar(120)` | yes | — | Comma-joined category codes of the parent project. **Singular** column name here — the video / image / text / project tables use `category_codes` (`text`) instead |
| `action` | `varchar(20)` | no | — | `AudioAuditAction`, CHECK-constrained |
| `actor_user_id` | `bigint` | yes | — | See [common shape](#the-common-audit-log-shape) |
| `actor_username` | `varchar(255)` | yes | — | See common shape |
| `actor_display_name` | `varchar(255)` | yes | — | See common shape |
| `actor_authorities` | `text` | yes | — | See common shape |
| `actor_permissions` | `text` | yes | — | See common shape |
| `device_info` | `varchar(255)` | yes | — | See common shape |
| `ip_address` | `varchar(255)` | yes | — | See common shape |
| `session_id` | `varchar(255)` | yes | — | See common shape |
| `session_login_timestamp` | `timestamp(6) with time zone` | yes | — | See common shape |
| `session_expires_at` | `timestamp(6) with time zone` | yes | — | See common shape |
| `session_is_active` | `boolean` | yes | — | See common shape |
| `request_method` | `varchar(255)` | yes | — | See common shape |
| `request_path` | `varchar(255)` | yes | — | See common shape |
| `details` | `text` | yes | — | See common shape |
| `occurred_at` | `timestamp(6) with time zone` | no | — | See common shape |

**Keys and constraints**

- PK: `id`.
- Unique constraints: none.
- Foreign keys: none declared. `audio_id`, `project_id`, `person_id`, `actor_user_id` and
  `session_id` are unconstrained snapshots.
- CHECK: Hibernate emits an inline `CHECK (action IN (...))` on the `action` column when it
  first creates it, from `AudioAuditAction`. **No initializer re-syncs this constraint** — see
  [CHECK constraint re-sync](#check-constraint-re-sync).

**Actions** (`platform.enums.AudioAuditAction` — these are the CHECK-constrained values)

| Value | Written when |
|---|---|
| `CREATE` | A single audio record is created (details carry the created field summary), and once per bulk-create call with `audio_id` null and a `requested / inserted / skipped / elapsedMs` summary |
| `READ` | `getByAudioCode(...)` resolves an active record — details `Read audio record` |
| `LIST` | The paged active list is served, and the admin trash listing is served |
| `SEARCH` | The native full-text search runs — details carry the query, tokens, limit and hit count |
| `UPDATE` | A partial update is applied (details carry a before → after field diff), and when the `isPublic` visibility toggle actually flips (a no-op toggle writes nothing) |
| `REMOVE` | **Never written.** The enum declares it, but no code path in `AudioService` uses it; soft-trash is recorded as `DELETE` |
| `DELETE` | The record is soft-trashed (`removed_at` / `removed_by` set) — details `Sent audio record to trash` |
| `RESTORE` | An admin restores the record from trash — details `Restored audio record from trash` |
| `PURGE` | An admin permanently deletes a trashed record. Written **before** the row is deleted |

**Indexes**

Created at startup by `platform/config/AuditLogIndexInitializer.java` (this table is in its
`TABLES` list):

```sql
CREATE INDEX IF NOT EXISTS idx_audio_audit_logs_actor_occurred  ON audio_audit_logs (actor_username, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_audio_audit_logs_occurred        ON audio_audit_logs (occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_audio_audit_logs_action_occurred ON audio_audit_logs (action, occurred_at DESC);
```

No `@Table(indexes = ...)` is declared on the entity.

**Relationships**

None. The entity declares no JPA associations and no collection tables.

**Notes**

- `audio_id` / `audio_code` are null on `LIST`, `SEARCH` and bulk `CREATE` rows, because those
  actions are not tied to one record. Filter them out before joining to `audio`.
- `category_code` is a comma-joined string, not a normalized link. Use `LIKE`/`string_to_array`,
  not equality, if you need to match a single code.
- Project, person and category columns are only populated when the audio had a project attached
  at write time; a project-less audio leaves all seven (`project_id`, `project_code`,
  `project_name`, `person_id`, `person_code`, `person_name`, `category_code`) null. The person
  trio additionally requires the project to have a person, and `category_code` requires a
  non-empty category set on the project.

---

## `video_audit_logs`

**Entity:** `ak.dev.khi_archive_platform.platform.model.video.VideoAuditLog`

| Column | Type | Null | Default | Description |
|---|---|---|---|---|
| `id` | `bigint` (identity) | no | identity | PK |
| `video_id` | `bigint` | yes | — | Snapshot of `video.id`; no FK |
| `video_code` | `varchar(255)` | yes | — | Video business code snapshot (`length = 255` explicit) |
| `video_title` | `varchar(255)` | yes | — | Video title snapshot |
| `project_id` | `bigint` | yes | — | Parent project id snapshot |
| `project_code` | `varchar(200)` | yes | — | Parent project code snapshot |
| `project_name` | `varchar(255)` | yes | — | Parent project name snapshot |
| `person_id` | `bigint` | yes | — | Person id via the parent project |
| `person_code` | `varchar(50)` | yes | — | Person code via the parent project |
| `person_name` | `varchar(255)` | yes | — | Person name via the parent project |
| `category_codes` | `text` | yes | — | `columnDefinition = "TEXT"`. Comma-joined category codes (plural, unlike `audio_audit_logs.category_code`) |
| `action` | `varchar(20)` | no | — | `VideoAuditAction`, CHECK-constrained |
| `actor_user_id` … `occurred_at` | | | | The fifteen shared envelope columns — identical types, nullability and semantics to [the common shape](#the-common-audit-log-shape) |

**Keys and constraints**

- PK: `id`. No unique constraints. No foreign keys.
- CHECK: Hibernate-generated `CHECK (action IN (...))` from `VideoAuditAction`. Not re-synced by
  any initializer.

**Actions** (`platform.enums.VideoAuditAction`)

| Value | Written when |
|---|---|
| `CREATE` | Single video created; also once per bulk-create call with `video_id` null |
| `READ` | A single video is fetched by code |
| `LIST` | Paged active list, and the admin trash listing |
| `SEARCH` | The video search endpoint runs |
| `UPDATE` | Partial update applied, or the `isPublic` visibility toggle flips |
| `REMOVE` | **Never written.** Declared by the enum; soft-trash is recorded as `DELETE` |
| `DELETE` | Video is soft-trashed |
| `RESTORE` | Admin restores from trash |
| `PURGE` | Admin permanently deletes a trashed video |

**Indexes**

From `AuditLogIndexInitializer`:

```sql
CREATE INDEX IF NOT EXISTS idx_video_audit_logs_actor_occurred  ON video_audit_logs (actor_username, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_video_audit_logs_occurred        ON video_audit_logs (occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_video_audit_logs_action_occurred ON video_audit_logs (action, occurred_at DESC);
```

No entity-level `@Table(indexes = ...)`.

**Relationships**

None.

**Notes**

- The column is `category_codes` (`text`), not `category_code`. Cross-table queries that union
  audio with video must alias one of the two.

---

## `image_audit_logs`

**Entity:** `ak.dev.khi_archive_platform.platform.model.image.ImageAuditLog`

| Column | Type | Null | Default | Description |
|---|---|---|---|---|
| `id` | `bigint` (identity) | no | identity | PK |
| `image_id` | `bigint` | yes | — | Snapshot of `image.id`; no FK |
| `image_code` | `varchar(255)` | yes | — | Image business code snapshot (`length = 255` explicit) |
| `image_title` | `varchar(255)` | yes | — | Image title snapshot |
| `project_id` | `bigint` | yes | — | Parent project id snapshot |
| `project_code` | `varchar(200)` | yes | — | Parent project code snapshot |
| `project_name` | `varchar(255)` | yes | — | Parent project name snapshot |
| `person_id` | `bigint` | yes | — | Person id via the parent project |
| `person_code` | `varchar(50)` | yes | — | Person code via the parent project |
| `person_name` | `varchar(255)` | yes | — | Person name via the parent project |
| `category_codes` | `text` | yes | — | `columnDefinition = "TEXT"`. Comma-joined category codes |
| `action` | `varchar(20)` | no | — | `ImageAuditAction`, CHECK-constrained |
| `actor_user_id` … `occurred_at` | | | | The fifteen shared envelope columns — see [the common shape](#the-common-audit-log-shape) |

**Keys and constraints**

- PK: `id`. No unique constraints. No foreign keys.
- CHECK: Hibernate-generated `CHECK (action IN (...))` from `ImageAuditAction`. Not re-synced.

**Actions** (`platform.enums.ImageAuditAction`)

| Value | Written when |
|---|---|
| `CREATE` | Single image created; also once per bulk-create call with `image_id` null |
| `READ` | A single image is fetched by code |
| `LIST` | Paged active list, and the admin trash listing |
| `SEARCH` | The image search endpoint runs |
| `UPDATE` | Partial update applied, or the `isPublic` visibility toggle flips |
| `REMOVE` | **Never written.** Declared by the enum; soft-trash is recorded as `DELETE` |
| `DELETE` | Image is soft-trashed |
| `RESTORE` | Admin restores from trash |
| `PURGE` | Admin permanently deletes a trashed image |

**Indexes**

From `AuditLogIndexInitializer`:

```sql
CREATE INDEX IF NOT EXISTS idx_image_audit_logs_actor_occurred  ON image_audit_logs (actor_username, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_image_audit_logs_occurred        ON image_audit_logs (occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_image_audit_logs_action_occurred ON image_audit_logs (action, occurred_at DESC);
```

No entity-level `@Table(indexes = ...)`.

**Relationships**

None.

**Notes**

- Image byte proxying through `GET /api/guest/image/{imageCode}/view` (and its authenticated
  twin `GET /api/image/{imageCode}/view`, both in `platform/api/image/ImageStreamAPI.java`) is
  not audited into this table — those endpoints write no log row at all. The guest *detail*
  endpoint `GET /api/guest/images/{imageCode}` is what writes a `guest_interaction_logs` row,
  via `GuestTrendingService.logView("image", …)`.

---

## `text_audit_logs`

**Entity:** `ak.dev.khi_archive_platform.platform.model.text.TextAuditLog`

| Column | Type | Null | Default | Description |
|---|---|---|---|---|
| `id` | `bigint` (identity) | no | identity | PK |
| `text_id` | `bigint` | yes | — | Snapshot of `text.id`; no FK |
| `text_code` | `varchar(255)` | yes | — | Text business code snapshot (`length = 255` explicit) |
| `text_title` | `varchar(255)` | yes | — | Text title snapshot |
| `project_id` | `bigint` | yes | — | Parent project id snapshot |
| `project_code` | `varchar(200)` | yes | — | Parent project code snapshot |
| `project_name` | `varchar(255)` | yes | — | Parent project name snapshot |
| `person_id` | `bigint` | yes | — | Person id via the parent project |
| `person_code` | `varchar(50)` | yes | — | Person code via the parent project |
| `person_name` | `varchar(255)` | yes | — | Person name via the parent project |
| `category_codes` | `text` | yes | — | `columnDefinition = "TEXT"`. Comma-joined category codes |
| `action` | `varchar(20)` | no | — | `TextAuditAction`, CHECK-constrained |
| `actor_user_id` … `occurred_at` | | | | The fifteen shared envelope columns — see [the common shape](#the-common-audit-log-shape) |

**Keys and constraints**

- PK: `id`. No unique constraints. No foreign keys.
- CHECK: Hibernate-generated `CHECK (action IN (...))` from `TextAuditAction`. Not re-synced.

**Actions** (`platform.enums.TextAuditAction`)

| Value | Written when |
|---|---|
| `CREATE` | Single text created; also once per bulk-create call with `text_id` null |
| `READ` | A single text is fetched by code |
| `LIST` | Paged active list, and the admin trash listing |
| `SEARCH` | The text search endpoint runs |
| `UPDATE` | Partial update applied, or the `isPublic` visibility toggle flips |
| `REMOVE` | **Never written.** Declared by the enum; soft-trash is recorded as `DELETE` |
| `DELETE` | Text is soft-trashed |
| `RESTORE` | Admin restores from trash |
| `PURGE` | Admin permanently deletes a trashed text |

**Indexes**

From `AuditLogIndexInitializer`:

```sql
CREATE INDEX IF NOT EXISTS idx_text_audit_logs_actor_occurred  ON text_audit_logs (actor_username, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_text_audit_logs_occurred        ON text_audit_logs (occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_text_audit_logs_action_occurred ON text_audit_logs (action, occurred_at DESC);
```

No entity-level `@Table(indexes = ...)`.

**Relationships**

None.

**Notes**

- `text_audit_logs` is not the table PostgreSQL uses for full-text search; it is only the audit
  trail for the text entity.

---

## `category_audit_logs`

**Entity:** `ak.dev.khi_archive_platform.platform.model.category.CategoryAuditLog`

| Column | Type | Null | Default | Description |
|---|---|---|---|---|
| `id` | `bigint` (identity) | no | identity | PK |
| `category_id` | `bigint` | yes | — | Snapshot of `category.id`; no FK. Null for list / search / bulk rows |
| `category_code` | `varchar(120)` | yes | — | Category business code snapshot |
| `category_name` | `varchar(255)` | yes | — | Category name snapshot |
| `action` | `varchar(20)` | no | — | `CategoryAuditAction`, CHECK-constrained |
| `actor_user_id` … `occurred_at` | | | | The fifteen shared envelope columns — see [the common shape](#the-common-audit-log-shape) |

**Keys and constraints**

- PK: `id`. No unique constraints. No foreign keys.
- CHECK: Hibernate-generated `CHECK (action IN (...))` from `CategoryAuditAction`. Not re-synced.

**Actions** (`platform.enums.CategoryAuditAction`)

| Value | Written when |
|---|---|
| `CREATE` | A category is created (details `Created category with code=...`), and once per bulk-create call with `category_id` null and a `requested / inserted / skippedDuplicates` summary |
| `READ` | A single category is fetched by code — details `Read category` |
| `LIST` | Paged active list, and the admin trash listing |
| `SEARCH` | The fuzzy category search runs — details carry query, limit and hit count |
| `UPDATE` | A partial update is applied — details list the changed fields, or `Updated category (no field changes detected)` |
| `REMOVE` | **Never written.** Declared by the enum; soft-trash is recorded as `DELETE` |
| `DELETE` | Category is soft-trashed — details `Sent category to trash` |
| `RESTORE` | Admin restores from trash — details `Restored category from trash` |
| `PURGE` | Admin permanently deletes a trashed category |

**Indexes**

From `AuditLogIndexInitializer`:

```sql
CREATE INDEX IF NOT EXISTS idx_category_audit_logs_actor_occurred  ON category_audit_logs (actor_username, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_category_audit_logs_occurred        ON category_audit_logs (occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_category_audit_logs_action_occurred ON category_audit_logs (action, occurred_at DESC);
```

No entity-level `@Table(indexes = ...)`.

**Relationships**

None.

**Notes**

- This table has no project or person columns; a category audit row never names the projects
  that reference the category.

---

## `person_audit_logs`

**Entity:** `ak.dev.khi_archive_platform.platform.model.person.PersonAuditLog`

| Column | Type | Null | Default | Description |
|---|---|---|---|---|
| `id` | `bigint` (identity) | no | identity | PK |
| `person_id` | `bigint` | yes | — | Snapshot of `person.id`; no FK. Null for list / search rows |
| `person_code` | `varchar(50)` | yes | — | Person business code snapshot |
| `person_name` | `varchar(255)` | yes | — | `Person.fullName` snapshot |
| `action` | `varchar(20)` | no | — | `PersonAuditAction`, CHECK-constrained |
| `actor_user_id` … `occurred_at` | | | | The fifteen shared envelope columns — see [the common shape](#the-common-audit-log-shape) |

**Keys and constraints**

- PK: `id`. No unique constraints. No foreign keys.
- CHECK: Hibernate-generated `CHECK (action IN (...))` from `PersonAuditAction`. Not re-synced.

**Actions** (`platform.enums.PersonAuditAction`)

| Value | Written when |
|---|---|
| `CREATE` | A person record is created — details `Created person record with code=...` |
| `READ` | A single person is fetched by code — details `Read person record` |
| `LIST` | Paged active list, and the admin trash listing |
| `SEARCH` | The person search endpoint runs |
| `UPDATE` | A partial update is applied |
| `REMOVE` | **Never written.** Declared by the enum; soft-trash is recorded as `DELETE` |
| `DELETE` | Person is soft-trashed |
| `RESTORE` | Admin restores from trash |
| `PURGE` | Admin permanently deletes a trashed person |

**Indexes**

From `AuditLogIndexInitializer`:

```sql
CREATE INDEX IF NOT EXISTS idx_person_audit_logs_actor_occurred  ON person_audit_logs (actor_username, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_person_audit_logs_occurred        ON person_audit_logs (occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_person_audit_logs_action_occurred ON person_audit_logs (action, occurred_at DESC);
```

No entity-level `@Table(indexes = ...)`.

**Relationships**

None.

**Notes**

- `person_code` here is `varchar(50)`, the same width the media and project audit tables use for
  their denormalized `person_code` snapshot.

---

## `project_audit_logs`

**Entity:** `ak.dev.khi_archive_platform.platform.model.project.ProjectAuditLog`

| Column | Type | Null | Default | Description |
|---|---|---|---|---|
| `id` | `bigint` (identity) | no | identity | PK |
| `project_id` | `bigint` | yes | — | Snapshot of `project.id`; no FK. Null for list / bulk-create rows |
| `project_code` | `varchar(200)` | yes | — | Project business code snapshot |
| `project_name` | `varchar(255)` | yes | — | Project name snapshot |
| `person_id` | `bigint` | yes | — | Owning person id snapshot |
| `person_code` | `varchar(50)` | yes | — | Owning person code snapshot |
| `person_name` | `varchar(255)` | yes | — | Owning person name snapshot |
| `category_codes` | `text` | yes | — | `columnDefinition = "TEXT"`. Comma-separated category codes for the audit trail |
| `action` | `varchar(20)` | no | — | `ProjectAuditAction`, CHECK-constrained |
| `actor_user_id` … `occurred_at` | | | | The fifteen shared envelope columns — see [the common shape](#the-common-audit-log-shape) |

**Keys and constraints**

- PK: `id`. No unique constraints. No foreign keys.
- CHECK: Hibernate-generated `CHECK (action IN (...))` from `ProjectAuditAction`. Not re-synced.

**Actions** (`platform.enums.ProjectAuditAction` — note this enum has **eight** values, one fewer
than the media enums: it declares no `SEARCH`)

| Value | Written when |
|---|---|
| `CREATE` | A project is created (details carry code, person and category summary), and once per bulk-create call with `project_id` null and a `requested / inserted / skipped` summary |
| `READ` | A single project is fetched — details `Read project record` |
| `LIST` | Paged active list, and the projects-in-trash listing |
| `UPDATE` | A partial update is applied |
| `REMOVE` | **Never written.** Declared by the enum; soft-trash is recorded as `DELETE` |
| `DELETE` | Project is soft-trashed. Details carry the cascade counts — `Sent project to trash (audios=… videos=… images=… …)` |
| `RESTORE` | Admin restores the project and its cascaded media — details carry the restored counts |
| `PURGE` | Admin permanently deletes the project — details carry the purged media counts |

**Indexes**

From `AuditLogIndexInitializer`:

```sql
CREATE INDEX IF NOT EXISTS idx_project_audit_logs_actor_occurred  ON project_audit_logs (actor_username, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_project_audit_logs_occurred        ON project_audit_logs (occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_project_audit_logs_action_occurred ON project_audit_logs (action, occurred_at DESC);
```

No entity-level `@Table(indexes = ...)`.

**Relationships**

None.

**Notes**

- `ProjectAuditAction` lacking `SEARCH` matters for the analytics `actions=` filter: a request
  for `SEARCH` matches every other entity but can never match a project row, because the enum
  value does not exist in this column's CHECK list.
- A project trash / restore / purge writes **one** project row carrying cascade counts in
  `details`; the individual media are not separately audited by that cascade.

---

## `maqam_audit_logs`

**Entity:** `ak.dev.khi_archive_platform.platform.model.maqam.MaqamAuditLog`

| Column | Type | Null | Default | Description |
|---|---|---|---|---|
| `id` | `bigint` (identity) | no | identity | PK |
| `maqam_id` | `bigint` | yes | — | Snapshot of `list_of_maqam.id`; no FK |
| `maqam_code` | `varchar(100)` | yes | — | Maqam business code snapshot |
| `song_name` | `varchar(255)` | yes | — | Snapshot of the song name, so the feed renders without joining `list_of_maqam` |
| `producer` | `varchar(255)` | yes | — | Producer snapshot |
| `action` | `varchar(30)` | no | — | `MaqamAuditAction`, CHECK-constrained. Wider than the media tables (`20`) to fit `TEACHER_ASSIGNED` / `LISTEN_PROGRESS` |
| `actor_user_id` | `bigint` | yes | — | See [common shape](#the-common-audit-log-shape) |
| `actor_username` | `varchar(255)` | yes | — | See common shape |
| `actor_display_name` | `varchar(255)` | yes | — | See common shape |
| `actor_authorities` | `text` | yes | — | See common shape |
| `actor_permissions` | `text` | yes | — | See common shape |
| `teacher_user_id` | `bigint` | yes | — | Teacher context; set only for vote / listen / teacher-assignment actions. No FK |
| `teacher_username` | `varchar(80)` | yes | — | Teacher username snapshot |
| `teacher_display_name` | `varchar(120)` | yes | — | Teacher display-name snapshot |
| `maqam_type` | `text` | yes | — | `columnDefinition = "TEXT"`. Vote context — the maqam type recorded by the vote |
| `session_key` | `varchar(100)` | yes | — | Listen context — the client-supplied listening-session key |
| `seconds_listened` | `bigint` | yes | — | Listen context — seconds added by this tick (`0` on `LISTEN_STARTED`) |
| `position_seconds` | `bigint` | yes | — | Listen context — playhead position at the time of the tick |
| `device_info` | `varchar(255)` | yes | — | See common shape |
| `ip_address` | `varchar(255)` | yes | — | See common shape |
| `session_id` | `varchar(255)` | yes | — | See common shape. Distinct from `session_key`, which is a listening session, not an auth session |
| `session_login_timestamp` | `timestamp(6) with time zone` | yes | — | See common shape |
| `session_expires_at` | `timestamp(6) with time zone` | yes | — | See common shape |
| `session_is_active` | `boolean` | yes | — | See common shape |
| `request_method` | `varchar(255)` | yes | — | See common shape. Nullable-safe: the service tolerates a null `HttpServletRequest` |
| `request_path` | `varchar(255)` | yes | — | See common shape |
| `details` | `text` | yes | — | See common shape |
| `occurred_at` | `timestamp(6) with time zone` | no | — | See common shape |

**Keys and constraints**

- PK: `id`. No unique constraints. No foreign keys.
- CHECK: `maqam_audit_logs_action_check` — `CHECK (action IN (...))` over every
  `MaqamAuditAction` value. **Dropped and recreated on every boot** by
  `platform/config/MaqamAuditActionConstraintInitializer.java`.

**Actions** (`platform.enums.MaqamAuditAction` — 18 values, the widest action vocabulary in the
schema)

| Value | Written when |
|---|---|
| `CREATE` | A List-of-Maqam record is created |
| `READ` | A single record is fetched by code |
| `LIST` | The paged list is served, the trash listing is served, and the teacher recent-activity panel is served |
| `SEARCH` | The maqam search runs — details `q=… hits=…` |
| `UPDATE` | A record update is applied |
| `REMOVE` | The record is soft-trashed — details `soft-trashed`. Unlike the media tables, maqam **does** use `REMOVE` for soft-trash |
| `DELETE` | **Never written.** Declared for shape-alignment with the media enums; the maqam service uses `REMOVE` and `PURGE` |
| `RESTORE` | Admin restores from trash — details `restored from trash` |
| `PURGE` | Admin permanently deletes a trashed record |
| `TEACHER_ASSIGNED` | An admin attaches a teacher to the record's 1–3-teacher vote panel |
| `TEACHER_REMOVED` | An admin detaches a teacher from the vote panel |
| `VOTE_CAST` | A teacher submits a vote for the **first** time on this record |
| `VOTE_UPDATED` | The same teacher changes an existing vote (the service picks `VOTE_CAST` vs `VOTE_UPDATED` from a `firstTime` flag) |
| `VOTE_DELETED` | A teacher's vote is deleted |
| `STREAM` | Every range request against the audio stream endpoint — details carry the raw `Range` header |
| `LISTEN_STARTED` | A teacher opens a listening session — details `session=<key>`, `seconds_listened` written as `0` |
| `LISTEN_PROGRESS` | A mid-session listen tick — details `session=<key> add=<n>s pos=<n>s` |
| `LISTEN_ENDED` | The final listen tick of a session (the service picks `LISTEN_ENDED` vs `LISTEN_PROGRESS` from an `isEnd` flag) |

**Indexes**

Declared on the entity via `@Table(indexes = ...)` and created by Hibernate:

| Index | Columns |
|---|---|
| `idx_mal_maqam` | `maqam_id` |
| `idx_mal_action` | `action` |
| `idx_mal_actor` | `actor_username` |
| `idx_mal_teacher` | `teacher_user_id` |
| `idx_mal_occurred_at` | `occurred_at` |

Plus, from `AuditLogIndexInitializer`:

```sql
CREATE INDEX IF NOT EXISTS idx_maqam_audit_logs_actor_occurred  ON maqam_audit_logs (actor_username, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_maqam_audit_logs_occurred        ON maqam_audit_logs (occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_maqam_audit_logs_action_occurred ON maqam_audit_logs (action, occurred_at DESC);
```

The entity-level single-column indexes overlap the initializer's composite ones. Both exist; the
composite `(actor_username, occurred_at DESC)` is the one the analytics CTE actually uses.

**Relationships**

None.

**Notes**

- This is by far the fastest-growing audit table. `LISTEN_PROGRESS` writes one row per tick per
  teacher per session, and `STREAM` writes one row per HTTP range request — a single audio
  playthrough can produce dozens of rows. Bound any interactive query with an `occurred_at`
  window.
- `teacher_user_id` and `actor_user_id` are usually the same person for vote and listen actions,
  but differ for `TEACHER_ASSIGNED` / `TEACHER_REMOVED`, where the actor is the admin and the
  teacher is the subject.
- `session_key` (listening session, `varchar(100)`) and `session_id` (JWT auth session,
  `varchar(255)`) are unrelated. Do not join on the wrong one.

---

## `physical_media_audit_logs`

**Entity:** `ak.dev.khi_archive_platform.platform.model.physicalmedia.PhysicalMediaAuditLog`

| Column | Type | Null | Default | Description |
|---|---|---|---|---|
| `id` | `bigint` (identity) | no | identity | PK |
| `physical_media_id` | `bigint` | yes | — | Snapshot of `physical_media.id`; no FK. For `TYPE_*` actions this carries the **media-type catalog id** instead |
| `physical_media_code` | `varchar(60)` | yes | — | Internal business key snapshot (`PM_NNNNNN`) |
| `physical_label` | `varchar(200)` | yes | — | Sheet "Physical Label" snapshot. For `TYPE_*` actions this carries the type **name** |
| `title` | `varchar(255)` | yes | — | Artefact title snapshot |
| `physical_media_type` | `varchar(200)` | yes | — | Media-type snapshot (Audio Cassette, VHS, …) |
| `action` | `varchar(20)` | no | — | `PhysicalMediaAuditAction`, CHECK-constrained |
| `actor_user_id` … `occurred_at` | | | | The fifteen shared envelope columns — see [the common shape](#the-common-audit-log-shape) |

**Keys and constraints**

- PK: `id`. No unique constraints. No foreign keys.
- CHECK: `physical_media_audit_logs_action_check` — `CHECK (action IN (...))` over every
  `PhysicalMediaAuditAction` value, **dropped and recreated on every boot** by
  `platform/config/PhysicalMediaAuditActionConstraintInitializer.java`.

**Actions** (`platform.enums.PhysicalMediaAuditAction` — 13 values)

| Value | Written when |
|---|---|
| `CREATE` | A physical-media inventory record is created |
| `READ` | A single inventory record is fetched |
| `LIST` | The paged active list is served, and the trash listing is served |
| `SEARCH` | The inventory search runs — details `q=… hits=…` |
| `UPDATE` | An inventory record update is applied |
| `REMOVE` | The record is soft-trashed — details `soft-trashed` |
| `DELETE` | **Never written.** Declared for shape-alignment with the media enums; the service uses `REMOVE` and `PURGE` |
| `RESTORE` | Admin restores from trash — details `restored from trash` |
| `PURGE` | Admin permanently deletes a trashed record — details `permanent deletion` |
| `IMPORT` | One row per successful `.xlsx` inventory ingestion batch |
| `TYPE_CREATE` | An admin adds a row to the `physical_media_types` catalog — details `added type '<name>'` |
| `TYPE_UPDATE` | An admin edits a catalog type — details `fields=<changed list>` or `fields=<none>` |
| `TYPE_DELETE` | An admin deletes a catalog type — details `deleted type '<name>'` |

**Indexes**

Declared on the entity via `@Table(indexes = ...)`:

| Index | Columns |
|---|---|
| `idx_pmal_pm` | `physical_media_id` |
| `idx_pmal_action` | `action` |
| `idx_pmal_actor` | `actor_username` |
| `idx_pmal_occurred_at` | `occurred_at` |

Plus, from `AuditLogIndexInitializer`:

```sql
CREATE INDEX IF NOT EXISTS idx_physical_media_audit_logs_actor_occurred  ON physical_media_audit_logs (actor_username, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_physical_media_audit_logs_occurred        ON physical_media_audit_logs (occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_physical_media_audit_logs_action_occurred ON physical_media_audit_logs (action, occurred_at DESC);
```

**Relationships**

None.

**Notes**

- The `TYPE_CREATE` / `TYPE_UPDATE` / `TYPE_DELETE` rows are written by
  `PhysicalMediaAuditService.recordTypeAction(...)`, which builds a throwaway in-memory
  `PhysicalMedia` "ghost" carrying the catalog id in `id` and the type name in
  `physicalLabel`, `physicalMediaType` and `title`. **`physical_media_id` on those rows is a
  `physical_media_types` id, not a `physical_media` id.** Any query that joins
  `physical_media_id` back to the inventory table must exclude the three `TYPE_*` actions.
- `physical_media_code` is null on `TYPE_*` rows because the ghost record has no `pmCode`.

---

## `guest_correction_audit_logs`

**Entity:** `ak.dev.khi_archive_platform.platform.model.correction.GuestCorrectionAuditLog`

| Column | Type | Null | Default | Description |
|---|---|---|---|---|
| `id` | `bigint` (identity) | no | identity | PK |
| `correction_id` | `bigint` | yes | — | Snapshot of the correction id; no FK. Null on `LIST` rows |
| `media_type` | `varchar(10)` | yes | — | `CorrectionMediaType` (`AUDIO`, `VIDEO`, `IMAGE`, `TEXT`), `@Enumerated(EnumType.STRING)`. Hibernate emits a CHECK on this column too |
| `media_code` | `varchar(255)` | yes | — | Code of the media record the correction targets |
| `media_title` | `varchar(255)` | yes | — | Title snapshot of that media record |
| `target_field` | `varchar(100)` | yes | — | Name of the field the guest proposed to change |
| `action` | `varchar(30)` | no | — | `GuestCorrectionAuditAction`, CHECK-constrained |
| `actor_user_id` … `occurred_at` | | | | The fifteen shared envelope columns — see [the common shape](#the-common-audit-log-shape) |

**Keys and constraints**

- PK: `id`. No unique constraints. No foreign keys.
- CHECK on `action`: `guest_correction_audit_logs_action_check` — **dropped and recreated on
  every boot** by `platform/config/GuestCorrectionAuditActionConstraintInitializer.java`.
- CHECK on `media_type`: Hibernate emits one for the `@Enumerated(EnumType.STRING)` column from
  `CorrectionMediaType`. Its exact generated name is _not documented in source_, and **no
  initializer re-syncs it** — adding a value to `CorrectionMediaType` requires manual DDL.

**Actions** (`platform.enums.GuestCorrectionAuditAction` — 7 values)

| Value | Written when |
|---|---|
| `SUBMIT` | A guest submits a new correction suggestion |
| `VIEW` | A guest views their own correction, or an admin opens one by id |
| `LIST` | An admin lists or searches corrections — `correction_id` is null on these rows |
| `FORWARD` | An admin forwards the correction to the employee who created the record (this also raises a `UserWarning`) |
| `RESOLVE` | An admin marks the correction resolved. Written from two distinct service paths |
| `REJECT` | An admin rejects the suggestion |
| `REMOVE` | An admin soft-deletes the correction |

**Indexes**

Declared on the entity via `@Table(indexes = ...)`:

| Index | Columns |
|---|---|
| `idx_gcal_correction` | `correction_id` |
| `idx_gcal_action` | `action` |
| `idx_gcal_actor` | `actor_username` |
| `idx_gcal_occurred_at` | `occurred_at` |

`AuditLogIndexInitializer` does **not** touch this table — it is absent from that class's
`TABLES` list, so the three `(…, occurred_at DESC)` composites do not exist here.

**Relationships**

None.

**Notes**

- This table is **not** part of the analytics `UNION ALL` CTE. Correction statistics are
  computed separately by `AnalyticsService` from `GuestCorrectionRepository`, not from this
  audit trail.
- `SUBMIT` rows are written for anonymous guests, so `actor_username` and `actor_display_name`
  can hold the literal `anonymous` and `actor_user_id` can be null.

---

## `analytics_audit_logs`

**Entity:** `ak.dev.khi_archive_platform.platform.model.analytics.AnalyticsAuditLog`

| Column | Type | Null | Default | Description |
|---|---|---|---|---|
| `id` | `bigint` (identity) | no | identity | PK |
| `action` | `varchar(32)` | no | — | `AnalyticsAuditAction`, CHECK-constrained. One value per analytics endpoint |
| `filter_summary` | `text` | yes | — | `columnDefinition = "TEXT"`. The executed query serialized in its stable cache-key form |
| `actor_user_id` … `occurred_at` | | | | The fifteen shared envelope columns — see [the common shape](#the-common-audit-log-shape) |

Note the column order: `action` comes **before** the actor block in this entity, and there are no
entity-identity columns at all.

**Keys and constraints**

- PK: `id`. No unique constraints. No foreign keys.
- CHECK: `analytics_audit_logs_action_check` — **dropped and recreated on every boot** by
  `platform/config/AnalyticsAuditActionConstraintInitializer.java`.

**Actions** (`platform.enums.AnalyticsAuditAction` — 16 values; each maps one-to-one to an
`/api/analytics/...` endpoint)

| Value | Written when | Written by |
|---|---|---|
| `VIEW_OVERVIEW` | The team overview view is requested | `platform/api/analytics/AnalyticsAPI.java` |
| `VIEW_USER` | A single user's activity report is requested | `AnalyticsAPI` |
| `VIEW_USERS` | The per-user summary list is requested | `AnalyticsAPI` |
| `VIEW_FEED` | The cross-entity activity feed is requested | `AnalyticsAPI` |
| `VIEW_ACTIONS` | The per-action statistics view is requested | `AnalyticsAPI` |
| `VIEW_DAILY` | The daily bucket report is requested | `AnalyticsAPI` |
| `VIEW_WEEKLY` | The weekly bucket report is requested | `AnalyticsAPI` |
| `VIEW_MONTHLY` | The monthly bucket report is requested | `AnalyticsAPI` |
| `VIEW_YEARLY` | The yearly bucket report is requested | `AnalyticsAPI` |
| `VIEW_ENTITY_STATS` | The per-entity statistics view is requested | `AnalyticsAPI` |
| `VIEW_ACTION_CATALOG` | The selectable-action catalog is requested | `AnalyticsAPI` |
| `VIEW_INVENTORY` | The inventory statistics view is requested | `platform/api/analytics/InventoryAnalyticsAPI.java` |
| `VIEW_VISIBILITY` | The public-visibility statistics view is requested | `InventoryAnalyticsAPI` |
| `VIEW_MAQAM_OVERVIEW` | The maqam analytics overview is requested | `platform/api/analytics/MaqamAnalyticsAPI.java` |
| `VIEW_MAQAM_TEACHERS` | The all-teachers maqam view is requested | `MaqamAnalyticsAPI` |
| `VIEW_MAQAM_TEACHER` | A single teacher's maqam view is requested | `MaqamAnalyticsAPI` |

**Indexes**

From `AuditLogIndexInitializer` (this table **is** in its `TABLES` list):

```sql
CREATE INDEX IF NOT EXISTS idx_analytics_audit_logs_actor_occurred  ON analytics_audit_logs (actor_username, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_analytics_audit_logs_occurred        ON analytics_audit_logs (occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_analytics_audit_logs_action_occurred ON analytics_audit_logs (action, occurred_at DESC);
```

No entity-level `@Table(indexes = ...)`.

**Relationships**

None.

**Indexed but not unioned:** this table receives the three analytics indexes yet is **not** a
branch of the `UNION ALL` CTE. Reading the analytics console therefore does not inflate the
analytics console's own numbers.

**Notes**

- For the filterable `AnalyticsAPI` endpoints `filter_summary` is the same string used as the
  Caffeine cache key (`AnalyticsFilter.toCacheKey()`), which makes it a convenient grouping key
  for "which filter combinations do people actually use". The endpoints that take no filter
  write a fixed label instead — `catalog`, `inventory`, `visibility`, `maqam/overview`,
  `maqam/teachers`, `maqam/teacher:<username>` — and the paged `VIEW_USER` variant appends
  `:page=…:size=…:sort=…` to the cache key.
- A cache **hit** still writes an audit row: auditing happens in the API layer, above the
  `@Cacheable` service method.

---

## `user_audit_logs`

**Entity:** `ak.dev.khi_archive_platform.user.model.UserAuditLog`

| Column | Type | Null | Default | Description |
|---|---|---|---|---|
| `id` | `bigint` (identity) | no | identity | PK |
| `action` | `varchar(32)` | no | — | `UserAuditAction`, CHECK-constrained |
| `target_user_id` | `bigint` | yes | — | The user being acted on — snapshot of `users_tbl.user_id`; no FK |
| `target_username` | `varchar(255)` | yes | — | Target's `users_tbl.username` snapshot |
| `target_display_name` | `varchar(255)` | yes | — | Target's `users_tbl.name` snapshot |
| `target_email` | `varchar(255)` | yes | — | Target's `users_tbl.email` snapshot |
| `previous_role` | `varchar(30)` | yes | — | Role before the change, as a plain string (not `@Enumerated`) |
| `new_role` | `varchar(30)` | yes | — | Role after the change, as a plain string |
| `permissions_changed` | `text` | yes | — | `columnDefinition = "TEXT"`. Comma-separated permission strings affected by this action |
| `actor_user_id` | `bigint` | yes | — | The acting **admin** — see [common shape](#the-common-audit-log-shape) |
| `actor_username` | `varchar(255)` | yes | — | See common shape |
| `actor_display_name` | `varchar(255)` | yes | — | See common shape |
| `actor_authorities` | `text` | yes | — | See common shape |
| `actor_permissions` | `text` | yes | — | See common shape |
| `device_info` | `varchar(255)` | yes | — | See common shape |
| `ip_address` | `varchar(255)` | yes | — | See common shape |
| `session_id` | `varchar(255)` | yes | — | See common shape |
| `session_login_timestamp` | `timestamp(6) with time zone` | yes | — | See common shape |
| `session_expires_at` | `timestamp(6) with time zone` | yes | — | See common shape |
| `session_is_active` | `boolean` | yes | — | See common shape |
| `request_method` | `varchar(255)` | yes | — | See common shape |
| `request_path` | `varchar(255)` | yes | — | See common shape |
| `details` | `text` | yes | — | See common shape |
| `occurred_at` | `timestamp(6) with time zone` | no | — | See common shape |

**Keys and constraints**

- PK: `id`. No unique constraints. No foreign keys — both `target_user_id` and `actor_user_id`
  are unconstrained snapshots, so the trail survives a user deletion.
- CHECK: `user_audit_logs_action_check` — **dropped and recreated on every boot** by
  `user/configs/UserAuditActionConstraintInitializer.java`.

**Actions** (`user.enums.UserAuditAction` — 13 values)

| Value | Written when | Written by |
|---|---|---|
| `CREATE` | An admin creates a user record | `user/service/AdminUserService.java` |
| `UPDATE` | An admin edits a user's profile fields, and on several other user-mutation paths in `AdminUserService`; also written by `UserWarningService` when a warning record is edited | `AdminUserService`, `user/service/UserWarningService.java` |
| `DELETE` | An admin deletes a user | `AdminUserService` |
| `ROLE_CHANGE` | An admin changes a user's role directly, and additionally when a permission grant implies a role change | `AdminUserService` |
| `GRANT_PERMISSIONS` | An admin grants per-user permissions — `permissions_changed` lists them | `AdminUserService` |
| `REVOKE_PERMISSIONS` | An admin revokes per-user permissions — `permissions_changed` lists them | `AdminUserService` |
| `ACTIVATE` | An admin activates a user (the service picks `ACTIVATE` vs `DEACTIVATE` from an `activate` flag) | `AdminUserService` |
| `DEACTIVATE` | An admin deactivates a user. Self-deactivation is blocked before the audit write | `AdminUserService` |
| `READ` | An admin opens a single user record | `AdminUserService` |
| `LIST` | **Never written** by any service. The value exists so the admin audit-log and analytics filters can accept it as a query term | — |
| `WARNING_SENT` | An admin issues a written warning to an employee | `UserWarningService` |
| `WARNING_REVOKED` | An admin retracts a warning (soft-delete) | `UserWarningService` |
| `WARNING_ACKNOWLEDGED` | The recipient confirms they read a warning. Note the actor here is the **recipient**, not an admin | `UserWarningService` |

**Indexes**

From `AuditLogIndexInitializer`:

```sql
CREATE INDEX IF NOT EXISTS idx_user_audit_logs_actor_occurred  ON user_audit_logs (actor_username, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_user_audit_logs_occurred        ON user_audit_logs (occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_user_audit_logs_action_occurred ON user_audit_logs (action, occurred_at DESC);
```

No entity-level `@Table(indexes = ...)`.

**Relationships**

None.

**Notes**

- This is the only audit table whose entity-identity columns are named `target_*` rather than
  `<entity>_id` / `<entity>_code`. The analytics CTE aliases `target_user_id` into the
  `entity_id` slot and `target_username` into `entity_code`.
- Because rows are attributed to the **actor**, "what happened to user X" is not reachable from
  X's own actor-keyed report. Query it through the feed filtered by `entityCode=X`, or through
  `target_username` directly.
- `previous_role` / `new_role` are plain `String` fields, **not** `@Enumerated`, so Hibernate
  emits no CHECK constraint on them. They can legitimately hold a value that no longer exists in
  `user.enums.Role`.

---

## `guest_search_logs`

**Entity:** `ak.dev.khi_archive_platform.platform.model.trending.GuestSearchLog`

| Column | Type | Null | Default | Description |
|---|---|---|---|---|
| `id` | `bigint` (identity) | no | identity | PK |
| `query` | `varchar(500)` | no | — | The guest's search string, `trim()`ed and lower-cased before insert. Blank queries are never written |
| `searched_at` | `timestamp(6) with time zone` | no | — | `Instant.now()` at write time |

**Keys and constraints**

- PK: `id`.
- Unique constraints: none — the table is intentionally append-only with duplicates, since
  frequency is the signal.
- Foreign keys: none. Guest searches are anonymous; there is no actor column at all.
- CHECK: none. No enum columns.

**Indexes**

Declared on the entity via `@Table(indexes = ...)`:

| Index | Columns | Serves |
|---|---|---|
| `idx_guest_search_time` | `searched_at` | The `WHERE searched_at >= :since` window and the nightly purge |
| `idx_guest_search_query` | `query` | The `GROUP BY query` in `findTopSearches` |

`AuditLogIndexInitializer` does not touch this table.

**Relationships**

None.

**Notes**

- Writes are fire-and-forget: `GuestTrendingService.logSearch(...)` is `@Async("trendingLogExecutor")`
  and swallows exceptions at `DEBUG` level, so a failed insert never fails the guest request.
- Retention is 30 days. `GuestTrendingService.purgeOldLogs()` runs on
  `@Scheduled(cron = "0 0 3 * * *")` and calls `deleteOlderThan(now - 30 days)`, which issues
  `DELETE FROM GuestSearchLog g WHERE g.searchedAt < :cutoff`. Do not build long-horizon reports
  on this table.
- Reads go through the native query `findTopSearches`, which returns `Object[]` rows of
  `[query, count]`.

---

## `guest_interaction_logs`

**Entity:** `ak.dev.khi_archive_platform.platform.model.trending.GuestInteractionLog`

| Column | Type | Null | Default | Description |
|---|---|---|---|---|
| `id` | `bigint` (identity) | no | identity | PK |
| `entity_type` | `varchar(20)` | no | — | One of `audio`, `video`, `text`, `image`, `project`, `person`, `category` (lower-case, per the entity's own comment). A plain `String` — **not** `@Enumerated`, so there is no CHECK constraint |
| `entity_code` | `varchar(100)` | no | — | The viewed entity's business code; no FK |
| `interacted_at` | `timestamp(6) with time zone` | no | — | `Instant.now()` at write time |

**Keys and constraints**

- PK: `id`.
- Unique constraints: none — duplicates are the point; each view is a separate row.
- Foreign keys: none. `entity_code` points at seven different code spaces depending on
  `entity_type`, so no single referential constraint is possible.
- CHECK: none.

**Indexes**

Declared on the entity via `@Table(indexes = ...)`:

| Index | Columns | Serves |
|---|---|---|
| `idx_guest_interaction_entity` | `entity_type, entity_code, interacted_at` | The `GROUP BY entity_type, entity_code` trending query |
| `idx_guest_interaction_time` | `interacted_at` | The nightly cleanup |

`AuditLogIndexInitializer` does not touch this table.

**Relationships**

None.

**Notes**

- The trending score is computed in SQL with time-decay weights inside
  `GuestInteractionLogRepository.findTrendingRaw`: a view within the last hour scores 3, within
  24 hours scores 2, otherwise 1, over a 7-day window. The result is capped at
  `TRENDING_POOL = 100` raw candidates.
- Writes are `@Async("trendingLogExecutor")` and exception-swallowing, exactly like
  `guest_search_logs`.
- Retention is 30 days via the same `purgeOldLogs()` job, which also evicts the
  `trending:results` and `trending:snapshot` Caffeine caches after the delete.

---

## The UNION ALL analytics view

There is **no database view or materialized view**. The "view" is a SQL string constant —
`ALL_LOGS_CTE` in
`src/main/java/ak/dev/khi_archive_platform/platform/service/analytics/AnalyticsService.java` —
that is prepended to every analytics aggregation and feed query. It runs as a single statement
so a report costs one round trip instead of one per table.

### Which tables it reads

Ten of the twelve `*_audit_logs` tables, in this order:

| Branch | Source table | `entity` literal | `entity_id` ← | `entity_code` ← |
|---|---|---|---|---|
| 1 | `audio_audit_logs` | `'audio'` | `audio_id` | `audio_code` |
| 2 | `video_audit_logs` | `'video'` | `video_id` | `video_code` |
| 3 | `image_audit_logs` | `'image'` | `image_id` | `image_code` |
| 4 | `text_audit_logs` | `'text'` | `text_id` | `text_code` |
| 5 | `project_audit_logs` | `'project'` | `project_id` | `project_code` |
| 6 | `category_audit_logs` | `'category'` | `category_id` | `category_code` |
| 7 | `person_audit_logs` | `'person'` | `person_id` | `person_code` |
| 8 | `maqam_audit_logs` | `'maqam'` | `maqam_id` | `maqam_code` |
| 9 | `physical_media_audit_logs` | `'physical_media'` | `physical_media_id` | `physical_media_code` |
| 10 | `user_audit_logs` | `'user'` | `target_user_id` | `target_username` |

The same ten keys appear as `AnalyticsService.ENTITY_KEYS`.

**Excluded:** `analytics_audit_logs` (so console usage does not pollute the reports it produces)
and `guest_correction_audit_logs` (correction statistics are computed separately from
`GuestCorrectionRepository`). The two guest trending tables are also outside this query
entirely.

### The projected shape

Every branch emits the same sixteen columns, which is why the tables must stay column-aligned.
The first and last branches, verbatim from `AnalyticsService`:

```sql
WITH all_logs AS (
    SELECT 'audio'    AS entity, action::text AS action,
           audio_id    AS entity_id, audio_code    AS entity_code,
           actor_user_id, actor_username, actor_display_name,
           actor_authorities, actor_permissions,
           device_info, ip_address, session_id,
           request_method, request_path,
           occurred_at, details
      FROM audio_audit_logs
    UNION ALL
    -- … eight more branches …
    UNION ALL
    SELECT 'user'     , action::text, target_user_id, target_username,
           actor_user_id, actor_username, actor_display_name,
           actor_authorities, actor_permissions,
           device_info, ip_address, session_id,
           request_method, request_path,
           occurred_at, details
      FROM user_audit_logs
)
```

Two consequences of this shape:

- `action::text` casts each table's own `varchar` enum column to text so the branches type-check
  against each other, and so the `actions=` filter can compare as strings across heterogeneous
  action vocabularies. Values are guarded against `AnalyticsService.ACTION_KEYS` before being
  interpolated; unknown values are dropped and the filter degrades to "all".
- `LIST` rows are excluded from every aggregate by the inlined
  `EXCLUDE_LIST_PREDICATE` — `" AND action <> 'LIST' "` — so that a report's `total` stays
  consistent with its per-action counts. `LIST` is page-load noise, not productive work.

Two groups of columns are **not** projected. First, the entity-specific ones that exist on only
some tables: `project_*`, `person_*`, `category_code(s)`, `song_name`, `producer`, `teacher_*`,
`maqam_type`, `session_key`, `seconds_listened`, `position_seconds`, `physical_label`, `title`,
`physical_media_type`, `previous_role`, `new_role`, `permissions_changed`. Second, three columns
that every audit table carries but the CTE still drops: `session_login_timestamp`,
`session_expires_at`, `session_is_active`. (`id` is dropped too — the CTE has no row-identity
column.) Reports that need any of them must query the underlying table directly.

### The indexes that make it fast

`platform/config/AuditLogIndexInitializer.java` runs on `ApplicationReadyEvent` and creates
three indexes on each of **eleven** tables — the ten CTE branches plus `analytics_audit_logs`.
Each statement is issued as:

```java
jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS " + name + " " + tail);
```

which produces, for each table `T` in its `TABLES` list:

```sql
CREATE INDEX IF NOT EXISTS idx_T_actor_occurred  ON T (actor_username, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_T_occurred        ON T (occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_T_action_occurred ON T (action, occurred_at DESC);
```

Spelled out for the first table, exactly as executed:

```sql
CREATE INDEX IF NOT EXISTS idx_audio_audit_logs_actor_occurred ON audio_audit_logs (actor_username, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_audio_audit_logs_occurred ON audio_audit_logs (occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_audio_audit_logs_action_occurred ON audio_audit_logs (action, occurred_at DESC);
```

The eleven tables, from the class's `TABLES` constant:

`audio_audit_logs`, `video_audit_logs`, `image_audit_logs`, `text_audit_logs`,
`project_audit_logs`, `category_audit_logs`, `person_audit_logs`, `maqam_audit_logs`,
`physical_media_audit_logs`, `analytics_audit_logs`, `user_audit_logs`.

That is 33 indexes in total. What each shape is for:

| Index shape | Serves |
|---|---|
| `(actor_username, occurred_at DESC)` | Per-user windowed scans — the `/api/analytics/me` and `/users/{name}` paths |
| `(occurred_at DESC)` | Team-wide windowed scans — the `/overview` and `/users` paths |
| `(action, occurred_at DESC)` | The `FILTER (WHERE action = …)` aggregations and the `actions=` filter |

Operational details worth knowing:

- Every statement uses `IF NOT EXISTS`, so the initializer is idempotent across boots.
- Failures are caught per-index and logged at `WARN` (`"Skipped index {}: {}"`) rather than
  failing startup — necessary because on a first boot a table may not exist yet when the
  initializer runs. If you see those warnings on a fresh database, a second boot creates the
  missing indexes.
- `guest_correction_audit_logs`, `guest_search_logs` and `guest_interaction_logs` get **no**
  indexes from this class. They rely on their own `@Table(indexes = ...)` declarations.

## CHECK constraint re-sync

Hibernate emits an inline `CHECK (<col> IN ('A','B',…))` the first time it creates a column
mapped with `@Enumerated(EnumType.STRING)`. Under `spring.jpa.hibernate.ddl-auto=update`
Hibernate **never revisits that constraint**. Add a value to the Java enum and the column will
happily accept it at the JPA layer, then fail at insert time with

```text
ERROR: new row for relation "<table>" violates check constraint "<table>_<column>_check"
```

The fix in this codebase is a per-table initializer bean that, on every
`ApplicationReadyEvent`, (1) queries `pg_constraint` for every CHECK constraint attached to the
`action` column, (2) drops each one by name, and (3) recreates a single constraint built from
the live enum values. All five follow the same three-step shape; here it is verbatim from
`AnalyticsAuditActionConstraintInitializer`:

```sql
SELECT con.conname
FROM pg_constraint con
JOIN pg_class c ON c.oid = con.conrelid
JOIN pg_attribute a
  ON a.attrelid = c.oid AND a.attnum = ANY(con.conkey)
WHERE c.relname = 'analytics_audit_logs'
  AND con.contype = 'c'
  AND a.attname = 'action'
```

```sql
ALTER TABLE analytics_audit_logs DROP CONSTRAINT IF EXISTS "<conname>";
```

```sql
ALTER TABLE analytics_audit_logs ADD CONSTRAINT analytics_audit_logs_action_check CHECK (action IN ('VIEW_OVERVIEW','VIEW_USER',…));
```

The value list is generated in Java, not hard-coded:

```java
String values = Stream.of(AnalyticsAuditAction.values())
        .map(a -> "'" + a.name() + "'")
        .collect(Collectors.joining(","));
```

### The five audit-action initializers

| Initializer | Source file | Table | Constraint it owns | Enum it mirrors |
|---|---|---|---|---|
| `AnalyticsAuditActionConstraintInitializer` | `platform/config/` | `analytics_audit_logs` | `analytics_audit_logs_action_check` | `AnalyticsAuditAction` (16 values) |
| `MaqamAuditActionConstraintInitializer` | `platform/config/` | `maqam_audit_logs` | `maqam_audit_logs_action_check` | `MaqamAuditAction` (18 values) |
| `GuestCorrectionAuditActionConstraintInitializer` | `platform/config/` | `guest_correction_audit_logs` | `guest_correction_audit_logs_action_check` | `GuestCorrectionAuditAction` (7 values) |
| `PhysicalMediaAuditActionConstraintInitializer` | `platform/config/` | `physical_media_audit_logs` | `physical_media_audit_logs_action_check` | `PhysicalMediaAuditAction` (13 values) |
| `UserAuditActionConstraintInitializer` | `user/configs/` | `user_audit_logs` | `user_audit_logs_action_check` | `UserAuditAction` (13 values) |

Each logs a success line naming the constraint and the regenerated value list (for example
`maqam_audit_logs_action_check re-synced with MaqamAuditAction enum: 'CREATE','READ',…`), and
each wraps the whole sequence in a `try/catch` that downgrades any failure to
`log.warn("Could not re-sync <constraint>: {}", …)` — a missing table on first boot must not
prevent startup.

### Tables with no re-sync initializer

Seven audit-action CHECK constraints are **not** refreshed at boot:

`audio_audit_logs`, `video_audit_logs`, `image_audit_logs`, `text_audit_logs`,
`category_audit_logs`, `person_audit_logs`, `project_audit_logs`.

Their action enums have been stable, so no initializer was written. **Adding a value to
`AudioAuditAction`, `VideoAuditAction`, `ImageAuditAction`, `TextAuditAction`,
`CategoryAuditAction`, `PersonAuditAction` or `ProjectAuditAction` will break inserts on an
existing database** until either the constraint is dropped and recreated by hand or a matching
initializer is added alongside the five above. The same applies to
`guest_correction_audit_logs.media_type` (`CorrectionMediaType`), which has a Hibernate-generated
CHECK that no initializer touches.

Two sibling initializers use the identical technique on non-audit tables, and are worth knowing
about when tracing a `violates check constraint` error:

| Initializer | Source file | Constraint it owns |
|---|---|---|
| `UserRoleConstraintInitializer` | `user/configs/` | `users_tbl_role_check` (mirrors `user.enums.Role`) |
| `PhysicalMediaDigitizationConstraintInitializer` | `platform/config/` | `physical_media_digitization_check` (mirrors `DigitizationStatus`) |

## Related

- [Database documentation index](./README.md)
- [Category API](../content/category.md) — the endpoints that write `category_audit_logs`
- [Project API](../content/project.md) — trash and restore cascades reflected in
  `project_audit_logs.details`
- [Person API](../content/person.md) — the endpoints that write `person_audit_logs`
- [Items endpoint](../content/items.md) — the merged media listing whose `LIST` calls are audited
  into the four media audit tables
