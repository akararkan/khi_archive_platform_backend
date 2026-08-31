# Important Fields and Data Conventions

> **Audience:** backend engineers, data/ops staff writing SQL against the archive database ·
> **Source:** `src/main/java/ak/dev/khi_archive_platform/platform/model/`,
> `src/main/java/ak/dev/khi_archive_platform/user/model/`,
> `src/main/resources/application.yaml`

Read this before your first query. Nine conventions repeat across almost every table in the
schema — business codes, soft delete, visibility, timestamps, attribution, enum columns,
tag/keyword collections, media URLs and optimistic locking. Learn them once and every table
becomes predictable. Get one of them wrong and you will silently read trashed rows, leak
non-public records, or compare a Baghdad timestamp against a UTC one.

Schema is created by Hibernate (`spring.jpa.hibernate.ddl-auto=update`) plus hand-written
initializer beans in `platform/config/` and `user/configs/`. There is no Flyway or Liquibase,
so the entity classes listed above are the authoritative schema definition.

## Contents

| Section | Convention |
|---|---|
| [1. Business codes](#1-business-codes) | `audio_code`, `project_code`, `pm_code`, … |
| [2. Soft delete / trash](#2-soft-delete--trash) | `removed_at`, `removed_by` |
| [3. Visibility](#3-visibility) | `is_public` vs `is_visible_to_public` |
| [4. Timestamps and time zones](#4-timestamps-and-time-zones) | `created_at`, `updated_at`, `occurred_at` |
| [5. Ownership and attribution](#5-ownership-and-attribution) | `created_by`, `updated_by`, `removed_by` |
| [6. Enum-backed columns](#6-enum-backed-columns) | `@Enumerated(STRING)` + generated CHECK |
| [7. Free-text collections](#7-free-text-collections-tags-and-keywords) | `*_tags`, `*_keywords` |
| [8. Media URL columns](#8-media-url-columns) | `audio_file_url`, `video_file_url`, … |
| [9. Numeric and quality fields](#9-numeric-and-quality-fields) | ranges, precision |
| [10. Optimistic locking](#10-optimistic-locking-version) | `version` |
| [Query cookbook](#query-cookbook) | 10 ready snippets |

---

## 1. Business codes

Every content entity carries a human-readable unique business key alongside its numeric
primary key. The code — not the id — is what the API, the audit log and the archive team all
use to name a record.

| Table | Column | SQL type | Constraints | Format |
|---|---|---|---|---|
| `audios` | `audio_code` | `varchar(255)` | `NOT NULL`, `UNIQUE`, index `idx_audio_code` | `PARENT_AUD_VERSION_Vn_Copy(n)_000001` |
| `videos` | `video_code` | `varchar(255)` | `NOT NULL`, `UNIQUE`, index `idx_video_code` | `PARENT_VID_VERSION_Vn_Copy(n)_000001` |
| `images` | `image_code` | `varchar(255)` | `NOT NULL`, `UNIQUE`, index `idx_image_code` | `PARENT_IMG_VERSION_Vn_Copy(n)_000001` |
| `texts` | `text_code` | `varchar(255)` | `NOT NULL`, `UNIQUE`, index `idx_text_code` | `PARENT_TXT_VERSION_Vn_Copy(n)_000001` |
| `projects` | `project_code` | `varchar(200)` | `NOT NULL`, `UNIQUE`, index `idx_project_code` | `PREFIX-PROJ-000001` |
| `person` | `person_code` | `varchar(50)` | `NOT NULL`, `UNIQUE`, index `idx_person_code` | Supplied by staff, e.g. `HZI`, `AMA` |
| `categories` | `category_code` | `varchar(120)` | `NOT NULL`, `UNIQUE`, index `idx_category_code` | Supplied by staff |
| `list_of_maqam` | `maqam_code` | `varchar(100)` | `NOT NULL`, `UNIQUE`, index `idx_maqam_code` | `MAQAM_000001` |
| `physical_media` | `pm_code` | `varchar(60)` | `NOT NULL`, `UNIQUE` (`uk_pm_code`), index `idx_pm_code` | `PM_000001` |

### How they are generated

Codes are minted in the service layer, never by a database sequence.

- **Media codes** (`AudioService.generateAudioCode` and its Video/Image/Text twins) concatenate
  a parent prefix, a three-letter kind marker, the version string, the version number, the copy
  number and a zero-padded 6-digit sequence:

  ```text
  parentCode + "_AUD_" + audioVersion + "_V" + versionNumber
             + "_Copy(" + copyNumber + ")" + "_" + String.format(Locale.ROOT, "%06d", sequence)
  ```

  The parent prefix is `person.person_code` upper-cased when the project has a person,
  otherwise the segment of `project_code` before the `-PROJ-` / `_PROJ_` marker
  (`ProjectCodeSupport.untitledMediaPrefix`). The sequence is
  `countByProject(project) + 1`.

- **Project codes** are `prefix + "-PROJ-" + %06d`, where prefix is the person code
  (upper-cased) or the project name normalized via
  `projectName.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_")` with leading
  and trailing underscores stripped. The sequence is `countByPerson(person) + 1`, or
  `countByPersonIsNull() + 1` for untitled projects.

- **`maqam_code` / `pm_code`** are `String.format(Locale.ROOT, "%s_%06d", PREFIX, sequence)`
  with `PREFIX` = `MAQAM` / `PM` and `sequence = repository.count() + 1`.

- **`person_code` and `category_code`** are supplied by staff, only trimmed. `category_code`
  is additionally validated against `ValidationPatterns.CATEGORY_CODE` — letters, numbers,
  underscores and hyphens only.

Because generation reads `count(...) + 1` and then inserts, concurrent creates would collide.
Every generator therefore takes a PostgreSQL transaction-scoped advisory lock first
(`platform/service/common/CodeGenLock.java`):

```sql
SELECT pg_advisory_xact_lock(?)
```

The lock key is a stable 64-bit hash of a namespace string (`"project-code:" + prefix`, the
maqam namespace, the physical-media namespace), so two creates under different projects never
block each other while two creates under the same project serialize.

> **Consequence for SQL:** the numeric suffix is a per-scope counter, not a global one, and it
> is **not** gap-free after purges. Never derive "how many audios exist" from the highest code.

### Why the API addresses records by code

Business codes are stable, meaningful to archivists, printed on physical labels and embedded
in every audit row (`audio_audit_logs.audio_code`, `maqam_audit_logs.maqam_code`, …). Every
content endpoint therefore takes the code as its path variable — `GET /api/audio/{audioCode}`,
`PATCH /api/project/{projectCode}`, `DELETE /api/physical-media/{pmCode}`,
`GET /api/guest/audio/{audioCode}/stream`. That keeps a URL readable and lets an audit row be
resolved back to a record even after the row has been purged.

### Is the numeric id ever exposed?

**Yes, in response bodies — but never as an addressable key for content.**

- `AudioResponseDTO` (and its siblings) serialize `id`, `projectId` and `personId`;
  `GuestAudioDTO` serializes `id`. They are informational.
- No content endpoint accepts a numeric id in the path.
- Four surfaces *do* address by numeric id, because those entities have no business code:
  `GET|DELETE /api/physical-media/types/{id}` (`physical_media_types.id`),
  `/api/admin/users/{userId}/…` (`users_tbl.user_id`), `/api/admin/warnings/{warningId}`
  (`user_warnings.id`), and the maqam admin paths that take `{teacherUserId}`
  (`users_tbl.user_id`).

---

## 2. Soft delete / trash

`DELETE` on a content endpoint does not remove the row. It stamps a trash marker. Only an
admin purge deletes bytes.

| Table | Marker column | Companion | Index |
|---|---|---|---|
| `audios` | `removed_at` | `removed_by varchar(120)` | `idx_audio_removed_at` |
| `videos` | `removed_at` | `removed_by varchar(120)` | `idx_video_removed_at` |
| `images` | `removed_at` | `removed_by varchar(120)` | `idx_image_removed_at` |
| `texts` | `removed_at` | `removed_by varchar(120)` | `idx_text_removed_at` |
| `projects` | `removed_at` | `removed_by varchar(120)` | `idx_project_removed_at` |
| `person` | `removed_at` | `removed_by varchar(120)` | `idx_person_removed_at` |
| `categories` | `removed_at` | `removed_by varchar(120)` | `idx_category_removed_at` |
| `list_of_maqam` | `removed_at` | `removed_by varchar(120)` | `idx_maqam_removed_at` |
| `physical_media` | `removed_at` | `removed_by varchar(120)` | `idx_pm_removed_at` |
| `guest_corrections` | `removed_at` | `removed_by varchar(80)` | composite `idx_gc_media`, `idx_gc_status` |
| `user_warnings` | `removed_at` | — | composite `idx_user_warnings_target`, `idx_user_warnings_acknowledged` |

Tables with **no** soft delete: `physical_media_types`, `khi_logo`, `users_tbl`, `sessions`,
`token_blacklist`, `maqam_teacher_votes`, `maqam_audio_listen_sessions`,
`guest_interaction_logs`, `guest_search_logs`, and all twelve `*_audit_logs` tables.

### The rule

```sql
-- "active" means exactly this, everywhere:
WHERE removed_at IS NULL

-- "in the trash" means exactly this:
WHERE removed_at IS NOT NULL
```

**Every read query must apply the active predicate unless it is deliberately a trash listing.**
The repositories encode it in their method names — `findByAudioCodeAndRemovedAtIsNull`,
`findAllByRemovedAtIsNull`, `findAllByRemovedAtIsNotNull` — and every hand-written native query
repeats it. From `TagSuggestRepository`:

```sql
SELECT LOWER(t.tag) AS value
  FROM audio_tags t
  JOIN audios a ON a.id = t.audio_id
 WHERE a.removed_at IS NULL
   AND t.tag IS NOT NULL AND t.tag <> ''
```

Note the join: for a child collection table you must gate on the **parent's** `removed_at`,
because collection tables carry no marker of their own.

### Cascade

Trashing a project cascades to its media only (not to person or category). The bulk update
is a `@Modifying` JPQL statement on each media repository, quoted here from
`AudioRepository`:

```sql
UPDATE Audio a SET a.removedAt = :removedAt, a.removedBy = :removedBy,
       a.version = COALESCE(a.version, 0) + 1
 WHERE a.project = :project AND a.removedAt IS NULL
```

Restore is the mirror image (`removedAt = NULL, removedBy = NULL`), also bumping `version` so a
concurrent edit surfaces a stale-version error.

---

## 3. Visibility

Two independent flags gate anonymous access. Both default to `TRUE` and both are declared
`NOT NULL` with a column default.

| Table | Column | Declaration |
|---|---|---|
| `audios` | `is_public` | `@Column(name = "is_public", nullable = false, columnDefinition = "BOOLEAN NOT NULL DEFAULT TRUE")` |
| `videos` | `is_public` | same |
| `images` | `is_public` | same |
| `texts` | `is_public` | same |
| `projects` | `is_visible_to_public` | `@Column(name = "is_visible_to_public", nullable = false, columnDefinition = "BOOLEAN NOT NULL DEFAULT TRUE")` |

`person`, `categories`, `list_of_maqam` and `physical_media` have **no** visibility flag —
maqam and physical media are staff-only surfaces, and person/category records reach guests only
through the media that reference them.

### How they interact

A media record is publicly visible only when **both** its own flag and its project's flag allow
it. The authority is `GuestSearchService`:

```java
private static boolean isProjectPubliclyVisible(Project project) {
    return project != null
            && project.getRemovedAt() == null
            && !Boolean.FALSE.equals(project.getIsVisibleToPublic());
}

private static boolean isPubliclyVisible(Audio audio) {
    return audio != null
            && audio.getRemovedAt() == null
            && !Boolean.FALSE.equals(audio.getIsPublic())
            && isProjectPubliclyVisible(audio.getProject());
}
```

Note `!Boolean.FALSE.equals(...)`: **NULL counts as public.** Legacy rows written before the
column existed have no value and must still appear. The repository count queries agree —
`AudioRepository.countActivePublic()` is
`... AND (a.isPublic IS NULL OR a.isPublic = true)`.

### The exact predicate an anonymous read applies

```sql
WHERE a.removed_at IS NULL
  AND (a.is_public IS NULL OR a.is_public = TRUE)
  AND p.removed_at IS NULL
  AND (p.is_visible_to_public IS NULL OR p.is_visible_to_public = TRUE)
```

…where `a` is the media table and `p` is the joined `projects` row. All four clauses are
required. Dropping the project half is the single most common way to leak a hidden record.

### The optional cascade

Toggling `projects.is_visible_to_public` does **not** by default rewrite the media flags. The
`PATCH /api/project/{projectCode}/visibility` payload carries `visibilityCascade`, and only when
it equals `CASCADE` (case-insensitive) does `ProjectService` run the bulk update on all four
media repositories:

```sql
UPDATE Audio a SET a.isPublic = :isPublic,
       a.updatedAt = :updatedAt, a.updatedBy = :updatedBy,
       a.version = COALESCE(a.version, 0) + 1
 WHERE a.project = :project AND a.removedAt IS NULL
   AND (a.isPublic IS NULL OR a.isPublic <> :isPublic)
```

Only rows whose value actually differs are touched, so `version` and `updated_at` are bumped
only on real changes. With `visibilityCascade` absent or `NONE`, the two levels drift apart
intentionally — a project can be hidden while its media rows still read `is_public = TRUE`.
That is why the anonymous predicate must check both levels rather than trusting either one.
Cookbook query 7 finds the drift.

---

## 4. Timestamps and time zones

### The columns

| Column | Where | Java type | Meaning |
|---|---|---|---|
| `created_at` | all 9 content tables, `physical_media_types`, `khi_logo`, `maqam_audio_listen_sessions`, `users_tbl`, `guest_corrections`, `user_warnings` | `Instant` | `@PrePersist` sets it if null on the 9 content tables, `physical_media_types` and `maqam_audio_listen_sessions`; `khi_logo` overwrites it unconditionally; `users_tbl`, `guest_corrections` and `user_warnings` have no lifecycle callback and are stamped by their services |
| `updated_at` | same set minus `user_warnings`, plus `maqam_teacher_votes` | `Instant` | Overwritten by `@PreUpdate` on every save wherever a callback exists — i.e. everywhere except `users_tbl`, `guest_corrections` and `maqam_teacher_votes`, which their services stamp by hand |
| `removed_at` | see [section 2](#2-soft-delete--trash) | `Instant` | Trash marker |
| `occurred_at` | all twelve `*_audit_logs` tables | `Instant` `NOT NULL` | When the audited action happened |
| `interacted_at` | `guest_interaction_logs` | `Instant` `NOT NULL` | Guest view event |
| `searched_at` | `guest_search_logs` | `Instant` `NOT NULL` | Guest search event |
| `date_created`, `date_modified`, `date_published`, `date_copyrighted` | `audios`, `videos`, `images`, `texts` | `Instant` | Archival metadata about the *work*, not the row |
| `print_date` | `texts` | `Instant` | Archival metadata |
| `voted_at`, `last_listen_at`, `assigned_at`, `updated_at` | `maqam_teacher_votes` | `Instant` | No `created_at` on this table |
| `started_at`, `ended_at` | `maqam_audio_listen_sessions` | `Instant` | |
| `login_timestamp`, `expires_at`, `logout_timestamp` | `sessions` | `Instant` | |
| `blacklisted_at`, `expires_at` | `token_blacklist` | `Instant` | |
| `forwarded_at`, `resolved_at` | `guest_corrections` | `Instant` | |
| `acknowledged_at` | `user_warnings` | `Instant` | |
| `lock_time`, `password_expiry_date` | `users_tbl` | `Instant` | |
| `date_of_birth`, `date_of_death` | `person` | `LocalDate` | Calendar date, no zone |
| `digitize_date` | `physical_media` | `LocalDate` | Calendar date, no zone |

Only three columns in the whole schema are zone-free calendar dates: `person.date_of_birth`,
`person.date_of_death` and `physical_media.digitize_date`. `PhysicalMedia` documents why — the source
spreadsheet carries day precision only and "the importer must not invent a time-zone".

### The two zones

`src/main/resources/application.yaml` declares both of these, and they are **different** — but only
one of them actually takes effect:

```yaml
spring:
  jpa:
    jdbc:
      time_zone: UTC          # persistence side — INERT, see below
  jackson:
    time-zone: Asia/Baghdad   # serialization side — in effect
  mvc:
    format:
      date: yyyy-MM-dd
      date-time: yyyy-MM-dd HH:mm:ss
```

> **`spring.jpa.jdbc.time_zone` is inert (verified).** `spring.jpa.jdbc` is not a property path
> Spring Boot binds — the real path is `spring.jpa.properties.hibernate.jdbc.time_zone`. Unknown
> keys are dropped silently, so Hibernate never receives `UTC` and binds timestamps in the **JVM
> default zone** of whichever host is running. On a host set to `Asia/Baghdad` the persistence side
> is Baghdad, not UTC; on a UTC container it happens to be right by accident. This is one of eight
> inert Hibernate keys — a live application defect, with the corrected YAML in
> [Indexes and performance](./indexes-and-performance.md#eight-hibernate-keys-are-inert-verified).
>
> The serialization side is unaffected: `spring.jackson.time-zone: Asia/Baghdad` is read by the
> Jackson 3 mapper Spring Boot 4 auto-configures for HTTP message conversion and does take effect.

A third zone constant lives in code — `ArchiveTime.ARCHIVE_ZONE = ZoneId.of("Asia/Baghdad")`,
used to resolve bare `YYYY-MM-DD` audit filters (`createdFrom`, `updatedTo`, `removedFrom`, …)
into instants. Its javadoc records the offset: UTC+3, and Iraq has observed no DST since 2007,
so the offset is stable year-round.

### The trap

**The instant you read out of psql and the instant you read out of a JSON response are three
hours apart, and neither one is labeled.**

`created_at` is stored as an absolute instant, which psql renders in UTC (or in your session's
`TimeZone`, which is worse — it varies per operator). Jackson renders the same instant in
`Asia/Baghdad`, three hours ahead. A record created at `2026-08-26T09:00:00Z` appears as
`09:00` in your SQL client and `12:00` in the API response.

Three concrete failure modes:

1. **Copying a timestamp from an API response into a SQL literal.** `WHERE created_at >
   '2026-08-26 12:00:00'` against a Baghdad-rendered value silently shifts your window by three
   hours. Convert explicitly: `WHERE created_at >= TIMESTAMPTZ '2026-08-26 12:00:00+03'`.
2. **Day bucketing.** `DATE_TRUNC('day', occurred_at)` — which is what `AnalyticsService` does —
   buckets by **UTC** day. Activity between 00:00 and 03:00 Baghdad time lands in the previous
   UTC day. The analytics endpoints and a naive hand-written daily report will disagree at the
   margins unless you write `DATE_TRUNC('day', occurred_at AT TIME ZONE 'Asia/Baghdad')`.
3. **Reproducing an API date filter in SQL.** The API resolves `createdFrom=2026-07-29` through
   `ArchiveTime.startOfDay`, i.e. `2026-07-29T00:00:00+03:00` = `2026-07-28T21:00:00Z`.
   `WHERE created_at >= '2026-07-29'` in psql is a different, three-hour-wider window.

Cookbook queries 4 and 10 show the Baghdad-correct form.

> **A fourth zone, unannounced.** Because `spring.jpa.jdbc.time_zone` is inert (see
> [The two zones](#the-two-zones)), Hibernate binds in the JVM default zone rather than UTC. The
> advice above is unchanged — a `timestamptz` column stores an absolute instant either way, and
> `AT TIME ZONE` conversions still work — but it adds a fourth zone to keep track of, and it means
> two application hosts with different system zones are not guaranteed to agree. Pin the JVM zone
> explicitly (`-Duser.timezone=UTC`) until the YAML nesting is corrected.

---

## 5. Ownership and attribution

| Column | SQL type | Tables |
|---|---|---|
| `created_by` | `varchar(120)` | `audios`, `videos`, `images`, `texts`, `projects`, `person`, `categories`, `list_of_maqam`, `physical_media`, `physical_media_types` |
| `updated_by` | `varchar(120)` | same set |
| `removed_by` | `varchar(120)` | same set except `physical_media_types` (which has no soft delete) |
| `removed_by` | `varchar(80)` | `guest_corrections` |
| `record_created_by` | `varchar(120)` | `guest_corrections` — snapshot of the employee who created the media record being corrected |
| `assigned_by` | `varchar(120)` | `maqam_teacher_votes` |
| `forwarded_by`, `resolved_by` | `varchar(80)` | `guest_corrections` |
| `actor_username` | `varchar(255)` | all twelve `*_audit_logs` tables |
| `created_by` on `physical_media` also pairs with `source` | `varchar(20)` | `MANUAL` (default, set in `@PrePersist`) or `IMPORT` |

### How they are populated

They hold the **username string**, not a foreign key. There is no `@CreatedBy` auditing
listener; each service sets the value explicitly from the Spring Security principal:

```java
private String resolveActorUsername(Authentication authentication) {
    return authentication == null ? "anonymous" : authentication.getName();
}
```

`authentication.getName()` is `users_tbl.username`. On create both `created_by` and
`updated_by` are stamped with the same actor; on update only `updated_by` moves; on soft delete
`removed_by` is set; on restore `removed_by` is cleared to `NULL` and `updated_by` is stamped.

Consequences when querying:

- **Join to `users_tbl` on `username`, not on an id.** `JOIN users_tbl u ON u.username =
  a.created_by`. There is no FK constraint, so a renamed or deleted account leaves a dangling
  string — which is deliberate, so history stays readable.
- **The value `'anonymous'` is possible** where an unauthenticated code path writes a row.
- **`created_by` is not a permission check.** It records who typed the record in; it does not
  restrict who may edit it.

The audit tables go further and snapshot the whole actor context per row — `actor_user_id`,
`actor_username`, `actor_display_name`, `actor_authorities` (TEXT), `actor_permissions` (TEXT),
plus `device_info`, `ip_address`, `session_id`, `session_login_timestamp`, `session_expires_at`,
`session_is_active`, `request_method`, `request_path` and `details`.

---

## 6. Enum-backed columns

The convention is `@Enumerated(EnumType.STRING)` with an explicit `length` — the column stores
the enum constant's **name**, so `SELECT` output is readable and stable against enum reordering.

| Table | Column | SQL type | Java enum | Values |
|---|---|---|---|---|
| `audio_audit_logs` | `action` | `varchar(20)` `NOT NULL` | `AudioAuditAction` | `CREATE, READ, LIST, SEARCH, UPDATE, REMOVE, DELETE, RESTORE, PURGE` |
| `video_audit_logs` | `action` | `varchar(20)` `NOT NULL` | `VideoAuditAction` | same shape |
| `image_audit_logs` | `action` | `varchar(20)` `NOT NULL` | `ImageAuditAction` | same shape |
| `text_audit_logs` | `action` | `varchar(20)` `NOT NULL` | `TextAuditAction` | same shape |
| `project_audit_logs` | `action` | `varchar(20)` `NOT NULL` | `ProjectAuditAction` | same shape |
| `category_audit_logs` | `action` | `varchar(20)` `NOT NULL` | `CategoryAuditAction` | same shape |
| `person_audit_logs` | `action` | `varchar(20)` `NOT NULL` | `PersonAuditAction` | same shape |
| `physical_media_audit_logs` | `action` | `varchar(20)` `NOT NULL` | `PhysicalMediaAuditAction` | the 9 above plus `IMPORT`, `TYPE_CREATE`, `TYPE_UPDATE`, `TYPE_DELETE` |
| `maqam_audit_logs` | `action` | `varchar(30)` `NOT NULL` | `MaqamAuditAction` | 18 values — the 9 above plus `TEACHER_ASSIGNED`, `TEACHER_REMOVED`, `VOTE_CAST`, `VOTE_UPDATED`, `VOTE_DELETED`, `STREAM`, `LISTEN_STARTED`, `LISTEN_PROGRESS`, `LISTEN_ENDED` |
| `analytics_audit_logs` | `action` | `varchar(32)` `NOT NULL` | `AnalyticsAuditAction` | 16 read-only view actions: `VIEW_OVERVIEW, VIEW_USER, VIEW_USERS, VIEW_FEED, VIEW_ACTIONS, VIEW_DAILY, VIEW_WEEKLY, VIEW_MONTHLY, VIEW_YEARLY, VIEW_ENTITY_STATS, VIEW_ACTION_CATALOG, VIEW_INVENTORY, VIEW_VISIBILITY, VIEW_MAQAM_OVERVIEW, VIEW_MAQAM_TEACHERS, VIEW_MAQAM_TEACHER` |
| `guest_correction_audit_logs` | `action` | `varchar(30)` `NOT NULL` | `GuestCorrectionAuditAction` | `SUBMIT, VIEW, LIST, FORWARD, RESOLVE, REJECT, REMOVE` |
| `user_audit_logs` | `action` | `varchar(32)` `NOT NULL` | `UserAuditAction` | `CREATE, UPDATE, DELETE, ROLE_CHANGE, GRANT_PERMISSIONS, REVOKE_PERMISSIONS, ACTIVATE, DEACTIVATE, READ, LIST, WARNING_SENT, WARNING_REVOKED, WARNING_ACKNOWLEDGED` |
| `physical_media` | `digitization` | `varchar(20)` | `DigitizationStatus` | `NOT_DIGITIZED` (0), `DIGITIZED` (1), `DUPLICATED` (2) |
| `guest_corrections` | `media_type` | `varchar(10)` `NOT NULL` | `CorrectionMediaType` | `AUDIO, VIDEO, IMAGE, TEXT` |
| `guest_corrections` | `status` | `varchar(20)` `NOT NULL` | `CorrectionStatus` | `PENDING, FORWARDED, RESOLVED, REJECTED` |
| `guest_correction_audit_logs` | `media_type` | `varchar(10)` | `CorrectionMediaType` | as above |
| `person` | `date_of_birth_precision` | `varchar(20)` | `DatePrecision` | `FULL, MONTH_ONLY, YEAR_ONLY` |
| `person` | `date_of_death_precision` | `varchar(20)` | `DatePrecision` | as above |
| `users_tbl` | `role` | `varchar(30)` `NOT NULL` | `Role` | `GUEST, EMPLOYEE, TEACHER, ADMIN` |
| `user_warnings` | `severity` | `varchar(16)` `NOT NULL` | `WarningSeverity` | `INFO, WARNING, CRITICAL` (default `WARNING`) |

**One exception.** `person.gender` is declared `@Column(name = "gender", length = 50)` with
**no** `@Enumerated` annotation, so it falls back to the JPA default of `EnumType.ORDINAL` —
the column holds the ordinal integer of `Gender` (`MALE`=0, `FEMALE`=1), not the name. Do not
write `WHERE gender = 'MALE'` against it. The exact DDL type Hibernate chose for this column is
not declared in source.

### The generated CHECK constraint, and the rule for adding a value

Hibernate writes a `CHECK (col IN ('A','B',…))` constraint **once**, when it first creates the
column. Under `ddl-auto=update` it never refreshes that constraint. Adding a value to the Java
enum therefore breaks inserts with a constraint violation, on a constraint that no migration
file mentions.

The fix is a boot-time re-sync bean. `MaqamAuditActionConstraintInitializer`, quoted in full
for its SQL:

```sql
-- 1. find every CHECK constraint on the column
SELECT con.conname
FROM pg_constraint con
JOIN pg_class c ON c.oid = con.conrelid
JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = ANY(con.conkey)
WHERE c.relname = 'maqam_audit_logs'
  AND con.contype = 'c'
  AND a.attname = 'action';

-- 2. drop each one
ALTER TABLE maqam_audit_logs DROP CONSTRAINT IF EXISTS "<conname>";

-- 3. rebuild it from the live enum
ALTER TABLE maqam_audit_logs ADD CONSTRAINT maqam_audit_logs_action_check
  CHECK (action IN ('CREATE','READ',...));
```

The value list is generated from `Stream.of(MaqamAuditAction.values())`, so it can never drift
from the enum. The same pattern, verbatim except for table/column/enum, exists in:

| Initializer | Table | Column | Constraint name |
|---|---|---|---|
| `MaqamAuditActionConstraintInitializer` | `maqam_audit_logs` | `action` | `maqam_audit_logs_action_check` |
| `AnalyticsAuditActionConstraintInitializer` | `analytics_audit_logs` | `action` | `analytics_audit_logs_action_check` |
| `GuestCorrectionAuditActionConstraintInitializer` | `guest_correction_audit_logs` | `action` | `guest_correction_audit_logs_action_check` |
| `PhysicalMediaAuditActionConstraintInitializer` | `physical_media_audit_logs` | `action` | `physical_media_audit_logs_action_check` |
| `PhysicalMediaDigitizationConstraintInitializer` | `physical_media` | `digitization` | `physical_media_digitization_check` |
| `UserAuditActionConstraintInitializer` | `user_audit_logs` | `action` | `user_audit_logs_action_check` |
| `UserRoleConstraintInitializer` | `users_tbl` | `role` | `users_tbl_role_check` |

The digitization variant additionally tolerates NULL:

```sql
ALTER TABLE physical_media ADD CONSTRAINT physical_media_digitization_check
  CHECK (digitization IS NULL OR digitization IN ('NOT_DIGITIZED','DIGITIZED','DUPLICATED'));
```

All of these run on `ApplicationReadyEvent` and swallow failures with a warning log, so a boot
never fails because of them — but a missed re-sync surfaces later as an insert error.

> **The rule: adding a value to any enum in the table above requires the matching initializer to
> exist and to cover that table + column.** If you add an enum with a new persisted column and no
> initializer, the first extra value you add later will break production inserts.

Five media/project audit tables take the opposite approach — `MediaSearchIndexInitializer`
drops their CHECK constraints outright and relies on the Java enum for validation:

```sql
ALTER TABLE image_audit_logs DROP CONSTRAINT IF EXISTS image_audit_logs_action_check
```

covering `image_audit_logs`, `text_audit_logs`, `video_audit_logs`, `audio_audit_logs` and
`project_audit_logs`. Expect no CHECK on `action` in those five tables.

---

## 7. Free-text collections: tags and keywords

### Where they live

Tags and keywords are `@ElementCollection` child tables, one row per value, joined by the parent
id. All value columns are `TEXT`.

| Parent | Tag table | Tag column | Keyword table | Keyword column | Join column |
|---|---|---|---|---|---|
| `audios` | `audio_tags` | `tag` | `audio_keywords` | `keyword` | `audio_id` |
| `videos` | `video_tags` | `tag` | `video_keywords` | `keyword` | `video_id` |
| `images` | `image_tags` | `tag` | `image_keywords` | `keyword` | `image_id` |
| `texts` | `text_tags` | `tag` | `text_keywords` | `keyword` | `text_id` |
| `projects` | `project_tags` | `tag` | `project_keywords` | `keyword` | `project_id` |
| `categories` | — | — | `category_keywords` | `keyword` | `category_id` |

Five tag tables, six keyword tables — exactly the sets unioned by `TagSuggestRepository` and
`KeywordSuggestRepository` behind `GET /api/tags/suggest` and `GET /api/keywords/suggest`.

**Two entities break the pattern and store free text in a single column instead:**

| Table | Column | SQL type | Shape |
|---|---|---|---|
| `person` | `tag` | `TEXT` | One free-text string (singular column name), **not** a collection |
| `person` | `keywords` | `TEXT` | One free-text string, not a collection |
| `physical_media` | `tags` | `TEXT` | "comma/slash-separated as found" in the source spreadsheet |

Those three are **not** canonicalized and are **not** part of the suggest unions. Do not
`UNION` them with the collection tables expecting comparable values.

Other multi-valued collection tables follow the same join-column convention and are worth
knowing: `audio_genres(genre)`, `audio_subjects(subject)`, `audio_contributors(contributor)`,
`video_subjects`, `video_genres`, `video_colors(color)`, `video_usages(usage_context)`,
`image_subjects`, `image_genres`, `image_colors`, `image_usages`, `text_subjects`,
`text_genres`, `person_person_type(person_type)`, and `user_permissions(permission)`.

### The canonicalization rule applied on save

`platform/service/common/Tags.java` holds the single algorithm; `Keywords.java` reuses it with
a different cap. Every service runs user input through it before persisting:

```java
if (dto.getTags() != null) {
    audio.setTags(Tags.canonical(dto.getTags()));
}
if (dto.getKeywords() != null) {
    audio.setKeywords(Keywords.canonical(dto.getKeywords()));
}
```

The algorithm, verbatim:

```java
static String canonicalOne(String raw, int maxLen) {
    if (raw == null) return null;
    String s = Normalizer.normalize(raw, Normalizer.Form.NFKC);
    s = s.replace('‌', ' ').replace('‍', ' '); // zero-width joiners → space
    s = s.trim().replaceAll("\\s+", " ");
    if (s.isEmpty() || s.length() > maxLen) return null;
    return s.toLowerCase(Locale.ROOT);
}
```

In order: **NFKC** Unicode normalization; zero-width joiner/non-joiner replaced by a space
(this matters for Kurdish/Arabic script input); trim and collapse internal whitespace to a
single space; reject if empty or over the cap; lower-case with `Locale.ROOT`. The list form
then **deduplicates, first occurrence wins**, via a `LinkedHashSet`, and returns an empty list
(never null) for null/empty input so JPA's collection-table delete semantics still fire.

### The real length caps — verified

| Constant | Value | Applies to |
|---|---|---|
| `Tags.MAX_TAG_LENGTH` | **64** | every `*_tags.tag` value |
| `Keywords.MAX_KEYWORD_LENGTH` | **200** | every `*_keywords.keyword` value |

These are **application-level caps, not column constraints.** The columns are declared
`columnDefinition = "TEXT"` and accept any length; the cap is enforced in Java. A value longer
than the cap is **rejected silently — dropped from the list, not truncated** — because, as
`Tags` documents, "a truncated tag would silently collide with an unrelated one".

> **Consequence for SQL:** values written through the API are always lower-case, single-spaced
> and within the cap. Values that predate the canonicalizer may not be. The suggest queries
> defensively `LOWER()` anyway; do the same in yours. And when hunting for bad data,
> `WHERE LENGTH(tag) > 64` finds rows that bypassed the service layer.

Both suggest caches (`tags:suggest`, `keywords:suggest` in `CacheConfig`) are evicted whenever
the corresponding entity read cache is evicted.

### Indexes

`MediaSearchIndexInitializer` builds three indexes per tag/keyword table, e.g. for `audio_tags`:

```sql
CREATE INDEX IF NOT EXISTS idx_audio_tags_tag_trgm ON audio_tags USING GIN (LOWER(tag) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_audio_tags_tag_pat  ON audio_tags (LOWER(tag) text_pattern_ops);
CREATE INDEX IF NOT EXISTS idx_audio_tags_audio_id ON audio_tags (audio_id);
```

It also runs `CREATE EXTENSION IF NOT EXISTS pg_trgm` first. Write your `LIKE`/`ILIKE`
predicates against `LOWER(tag)` so they can use these.

---

## 8. Media URL columns

### What is actually stored

**A full virtual-hosted-style S3 URL, not a bare key.** `S3Service.getPublicUrl` builds it:

```java
public String getPublicUrl(String key) {
    return "https://" + bucket + ".s3." + region + ".amazonaws.com/" + key;
}
```

and the key itself is
`baseFolder + "/" + folder + "/" + UUID.randomUUID() + "-" + safeName`, where `safeName` is
the original filename with `[^a-zA-Z0-9._-]` replaced by `_`. Bucket, region and base folder
come from `aws.s3.bucket`, `aws.s3.region` and `aws.s3.base-folder` in `application.yaml`
(default base folder `khi-archive-platform-folders`). To go back the other way,
`S3Service.extractKeyFromUrl` parses the URI path and strips a leading bucket segment.

| Table | Column | SQL type | Nullability |
|---|---|---|---|
| `audios` | `audio_file_url` | `varchar(1000)` | nullable |
| `videos` | `video_file_url` | `varchar(1000)` | nullable |
| `images` | `image_file_url` | `varchar(1000)` | nullable |
| `texts` | `text_file_url` | `varchar(1000)` | nullable |
| `texts` | `cover_image_url` | `varchar(1000)` | nullable |
| `list_of_maqam` | `audio_file_url` | `varchar(1000)` | `NOT NULL` |
| `person` | `media_portrait` | `varchar(255)` | nullable |
| `khi_logo` | `image_url` | `varchar(500)` | `NOT NULL` |
| `users_tbl` | `profile_image` | `varchar(500)` | nullable |
| `users_tbl` | `image_url` | `varchar(500)` | nullable |

`list_of_maqam` additionally stores `audio_file_name varchar(500)`,
`audio_content_type varchar(120)`, `audio_file_size_bytes bigint` and
`audio_duration_seconds bigint` — the content type is what the range-streaming endpoint puts on
every `Content-Type` header.

### Why the stored value never reaches a browser

Every response mapper **replaces** the stored S3 URL with a relative API proxy path before
serialization. The database column is internal; the JSON field of the same name is a route.

| Surface | Written into the DTO | Source |
|---|---|---|
| Staff audio | `/api/audio/{audioCode}/stream` | `AudioService` |
| Staff video | `/api/video/{videoCode}/stream` | `VideoService` |
| Staff image | `/api/image/{imageCode}/view` | `ImageService` |
| Staff text | `/api/text/{textCode}/read` and `/api/text/{textCode}/cover` | `TextService` |
| Guest audio | `/api/guest/audio/{audioCode}/stream` | `GuestMapper` |
| Guest video | `/api/guest/video/{videoCode}/stream` | `GuestMapper` |
| Guest image | `/api/guest/image/{imageCode}/view` | `GuestMapper` |
| Guest text | `/api/guest/text/{textCode}/read`, `/api/guest/text/{textCode}/cover` | `GuestMapper` |
| Maqam audio | `/api/maqam/{maqamCode}/stream` | Never serialized at all |

`GuestAudioDTO` states the contract in its own javadoc: "The actual S3 URL is never exposed;
all bytes are proxied through the backend."

Three reasons this matters:

1. **Access control.** A raw S3 URL is a bearer capability — anyone holding it reads the object
   forever, past a trash, past an `is_public = FALSE` toggle. Proxying re-checks
   `removed_at`/`is_public`/project visibility on every byte range.
2. **Accountability.** `ListOfMaqam` documents the maqam case explicitly: playback goes through
   a range-aware endpoint "without a download disposition", implementing the no-downloads rule
   and keeping `maqam_audio_listen_sessions` "accountable for every byte served".
3. **Portability.** Bucket, region and folder layout are configuration. Nothing outside
   `S3Service` should depend on their current values.

**One documented exception:** `person.media_portrait` is passed through raw. `GuestMapper`
writes `.mediaPortrait(p.getMediaPortrait())` and `.personMediaPortrait(...)` without rewriting,
and the field's javadoc calls it "S3 public URL of the profile portrait image". Portraits are
the only media the platform serves directly from S3.

> **Consequence for SQL:** `SELECT audio_file_url FROM audios` returns
> `https://<bucket>.s3.<region>.amazonaws.com/...`, which is **not** what the API returned to
> you. Do not join or match the two. If you need the proxy path, build it from the code column.

---

## 9. Numeric and quality fields

| Table | Column | SQL type | Range / precision |
|---|---|---|---|
| `audios` | `audio_quality_out_of_10` | `integer` | Name implies 0–10. **No `CHECK`, no `@Min`/`@Max`** — the scale is a naming convention only. Nullable. |
| `audios`, `videos`, `images`, `texts` | `version_number` | `integer` | Nullable. Part of the business code. No constraint. |
| `audios`, `videos`, `images`, `texts` | `copy_number` | `integer` | Nullable. Part of the business code. No constraint. |
| `texts` | `page_count` | `integer` | Nullable, no constraint |
| `physical_media` | `row_number` | `integer` | Sheet row ordinal (`No.` column) |
| `physical_media` | `inventory_number` | `integer` | **Per-media-type** contiguous 1..N counter, not global. On manual create the service mints `max(Number)+1` for that media type and ignores any client value. |
| `physical_media` | `year` | `integer` | Year printed on the artifact label. No range constraint. |
| `physical_media` | `duration_min` | `integer` | Runtime in minutes |
| `physical_media` | `track_numbers` | `integer` | Count of tracks |
| `list_of_maqam` | `audio_file_size_bytes` | `bigint` | Nullable |
| `list_of_maqam` | `audio_duration_seconds` | `bigint` | Nullable — "best-effort"; when missing, listen tracking still records absolute seconds but cannot compute "% heard" |
| `maqam_teacher_votes` | `total_listen_seconds` | `bigint` `NOT NULL` | Defaults to `0`. Rolling aggregate mirroring `SUM(seconds_listened)` |
| `maqam_teacher_votes` | `max_position_seconds` | `bigint` `NOT NULL` | Defaults to `0` |
| `maqam_audio_listen_sessions` | `seconds_listened` | `bigint` `NOT NULL` | Defaults to `0`. Time the audio element actually advanced — **not** wall-clock since start |
| `maqam_audio_listen_sessions` | `last_position_seconds` | `bigint` `NOT NULL` | Defaults to `0` |
| `users_tbl` | `failed_attempts` | `integer` `NOT NULL` | Java primitive `int`, defaults to `0` |
| all content tables | `version` | `bigint` `NOT NULL` | `@ColumnDefault("0")` — see [section 10](#10-optimistic-locking-version) |

**There is no `NUMERIC`/`DECIMAL` column anywhere in the model.** Everything that looks like a
measured quantity but is not an integer count is stored as free text — `file_size`,
`duration`, `bit_rate`, `bit_depth`, `sample_rate`, `frame_rate`, `resolution`, `dimension`,
`dpi` are all `varchar(100)`, and `physical_media.size_gb` is `varchar(200)`
holding values "e.g. `4.7`, `4.7 GB`, `700 MB`". Aggregating them requires parsing, and there
is no guarantee of a consistent unit.

`maqam_teacher_votes.total_listen_seconds` is a denormalized cache of the session rows. If the
two disagree, `maqam_audio_listen_sessions` is the ground truth — cookbook query 9 compares
them.

---

## 10. Optimistic locking (`version`)

Every content entity carries:

```java
@jakarta.persistence.Version
@org.hibernate.annotations.ColumnDefault("0")
@Column(name = "version", nullable = false)
private Long version;
```

`bigint NOT NULL`. JPA bumps it on every save; a concurrent update reading a stale value trips
`ObjectOptimisticLockingFailureException`, translated to HTTP `409`.

Present in exactly that form on: `audios`, `videos`, `images`, `texts`, `projects`, `person`,
`categories`, `list_of_maqam`, `maqam_teacher_votes`, `physical_media`,
`physical_media_types`. Every one of those entities except `MaqamTeacherVote` also defends the
column in `@PrePersist` (`if (version == null) version = 0L;`); `MaqamTeacherVote` has no
lifecycle callback and leans on the `ColumnDefault("0")` alone.

`guest_corrections` also has a `version` column, but declared as a bare
`@Version private Long version;` — no `@Column`, so the name comes from the implicit naming
strategy and there is no `NOT NULL` or `ColumnDefault("0")` on it.

> **Never `UPDATE` these tables by hand without incrementing `version`.** A raw SQL update that
> leaves `version` untouched is invisible to any JPA session holding the row and will be
> overwritten by the next application save. The bulk repository queries model the correct form:
> `a.version = COALESCE(a.version, 0) + 1`.

---

## Query cookbook

Every column name below was read from the entity classes. Copy freely.

### 1. Active, publicly visible audio

The full anonymous-read predicate from [section 3](#3-visibility), both levels.

```sql
SELECT a.audio_code,
       a.origin_title,
       a.central_kurdish_title,
       p.project_code,
       pe.person_code,
       a.created_at
  FROM audios a
  JOIN projects p  ON p.id  = a.project_id
  LEFT JOIN person pe ON pe.id = p.person_id
 WHERE a.removed_at IS NULL
   AND (a.is_public IS NULL OR a.is_public = TRUE)
   AND p.removed_at IS NULL
   AND (p.is_visible_to_public IS NULL OR p.is_visible_to_public = TRUE)
 ORDER BY a.created_at DESC
 LIMIT 50;
```

### 2. Resolve one record by its business code

The canonical lookup — always paired with the active predicate, exactly as
`findByAudioCodeAndRemovedAtIsNull` does.

```sql
SELECT a.id, a.audio_code, a.origin_title, a.is_public,
       a.audio_file_url,          -- internal S3 URL, NOT what the API returns
       a.created_by, a.updated_by, a.version
  FROM audios a
 WHERE a.audio_code = 'HZI_AUD_RAW_V1_Copy(1)_000001'
   AND a.removed_at IS NULL;
```

### 3. Trash listing across all four media types

What the admin trash screens read. Note `removed_at IS NOT NULL` — the inverse of the rule.

```sql
SELECT 'audio' AS kind, audio_code AS code, origin_title  AS title, removed_at, removed_by
  FROM audios WHERE removed_at IS NOT NULL
UNION ALL
SELECT 'video', video_code, original_title, removed_at, removed_by
  FROM videos WHERE removed_at IS NOT NULL
UNION ALL
SELECT 'image', image_code, original_title, removed_at, removed_by
  FROM images WHERE removed_at IS NOT NULL
UNION ALL
SELECT 'text',  text_code,  original_title, removed_at, removed_by
  FROM texts  WHERE removed_at IS NOT NULL
 ORDER BY removed_at DESC
 LIMIT 100;
```

### 4. Per-user activity across the audit tables

A trimmed version of `AnalyticsService.ALL_LOGS_CTE`, which unions ten of the twelve
`*_audit_logs` tables (it omits `analytics_audit_logs` and `guest_correction_audit_logs`).
Every branch exposes the same column shape; the day bucket is Baghdad-corrected per
[section 4](#4-timestamps-and-time-zones).

```sql
WITH all_logs AS (
    SELECT 'audio'   AS entity, action::text AS action, audio_code   AS entity_code,
           actor_username, occurred_at FROM audio_audit_logs
    UNION ALL
    SELECT 'video',   action::text, video_code,   actor_username, occurred_at FROM video_audit_logs
    UNION ALL
    SELECT 'image',   action::text, image_code,   actor_username, occurred_at FROM image_audit_logs
    UNION ALL
    SELECT 'text',    action::text, text_code,    actor_username, occurred_at FROM text_audit_logs
    UNION ALL
    SELECT 'project', action::text, project_code, actor_username, occurred_at FROM project_audit_logs
    UNION ALL
    SELECT 'person',  action::text, person_code,  actor_username, occurred_at FROM person_audit_logs
    UNION ALL
    SELECT 'category',action::text, category_code,actor_username, occurred_at FROM category_audit_logs
    UNION ALL
    SELECT 'maqam',   action::text, maqam_code,   actor_username, occurred_at FROM maqam_audit_logs
    UNION ALL
    SELECT 'physical_media', action::text, physical_media_code,
           actor_username, occurred_at FROM physical_media_audit_logs
    UNION ALL
    SELECT 'user',    action::text, target_username, actor_username, occurred_at FROM user_audit_logs
)
SELECT actor_username,
       DATE_TRUNC('day', occurred_at AT TIME ZONE 'Asia/Baghdad') AS baghdad_day,
       entity,
       action,
       COUNT(*) AS events
  FROM all_logs
 WHERE occurred_at >= NOW() - INTERVAL '30 days'
   AND action <> 'LIST'          -- AnalyticsService.EXCLUDE_LIST_PREDICATE
   AND actor_username IS NOT NULL
 GROUP BY actor_username, baghdad_day, entity, action
 ORDER BY baghdad_day DESC, events DESC;
```

`user_audit_logs` has no entity code pair — it aliases `target_username` into that slot, the
same trick `AnalyticsService` uses.

Indexes exist for exactly this access pattern, built by `AuditLogIndexInitializer` on eleven of
the twelve audit tables — every one except `guest_correction_audit_logs`:
`(actor_username, occurred_at DESC)`, `(occurred_at DESC)` and `(action, occurred_at DESC)`,
named `idx_<table>_actor_occurred`, `idx_<table>_occurred` and `idx_<table>_action_occurred`.

### 5. Tag frequency across all five tag tables

The `all_tags` CTE from `TagSuggestRepository`, aggregated. Values are already canonical;
`LOWER()` is defensive for pre-canonicalizer rows.

```sql
WITH all_tags AS (
    SELECT LOWER(t.tag) AS value FROM audio_tags   t JOIN audios   a ON a.id = t.audio_id
     WHERE a.removed_at IS NULL AND t.tag IS NOT NULL AND t.tag <> ''
    UNION ALL
    SELECT LOWER(t.tag) FROM video_tags   t JOIN videos   v ON v.id = t.video_id
     WHERE v.removed_at IS NULL AND t.tag IS NOT NULL AND t.tag <> ''
    UNION ALL
    SELECT LOWER(t.tag) FROM image_tags   t JOIN images   i ON i.id = t.image_id
     WHERE i.removed_at IS NULL AND t.tag IS NOT NULL AND t.tag <> ''
    UNION ALL
    SELECT LOWER(t.tag) FROM text_tags    t JOIN texts    x ON x.id = t.text_id
     WHERE x.removed_at IS NULL AND t.tag IS NOT NULL AND t.tag <> ''
    UNION ALL
    SELECT LOWER(t.tag) FROM project_tags t JOIN projects p ON p.id = t.project_id
     WHERE p.removed_at IS NULL AND t.tag IS NOT NULL AND t.tag <> ''
)
SELECT value, COUNT(*) AS usage_count
  FROM all_tags
 GROUP BY value
 ORDER BY usage_count DESC, value ASC
 LIMIT 100;
```

Swap the five tag tables for the six keyword tables (`audio_keywords`, `video_keywords`,
`image_keywords`, `text_keywords`, `project_keywords`, `category_keywords` joined to
`categories c ON c.id = k.category_id`) to get keyword frequency.

### 6. Visibility split per project

Which projects are hidden, and how much active media sits under each.

```sql
SELECT p.project_code,
       p.project_name,
       p.is_visible_to_public,
       COUNT(*) FILTER (WHERE a.id IS NOT NULL)                      AS active_audios,
       COUNT(*) FILTER (WHERE a.is_public = FALSE)                   AS hidden_audios,
       COUNT(*) FILTER (WHERE a.is_public IS NULL OR a.is_public)    AS public_audios
  FROM projects p
  LEFT JOIN audios a ON a.project_id = p.id AND a.removed_at IS NULL
 WHERE p.removed_at IS NULL
 GROUP BY p.project_code, p.project_name, p.is_visible_to_public
 ORDER BY p.is_visible_to_public ASC, active_audios DESC;
```

### 7. Visibility drift after a non-cascading toggle

Media still flagged public underneath a hidden project. Correct behavior — the anonymous
predicate hides them anyway — but this is the list a `visibilityCascade=CASCADE` run would
rewrite.

```sql
SELECT p.project_code, a.audio_code, a.is_public, p.is_visible_to_public
  FROM audios a
  JOIN projects p ON p.id = a.project_id
 WHERE a.removed_at IS NULL
   AND p.removed_at IS NULL
   AND p.is_visible_to_public = FALSE
   AND (a.is_public IS NULL OR a.is_public = TRUE)
 ORDER BY p.project_code, a.audio_code;
```

### 8. Physical-media inventory by type and digitization status

```sql
SELECT pm.physical_media_type,
       pm.media_category,
       pm.digitization,                                       -- varchar, enum name
       COUNT(*)                                       AS items,
       COUNT(*) FILTER (WHERE pm.need_to_clear)       AS need_clearing,
       MAX(pm.inventory_number)                       AS highest_number,
       COUNT(*) FILTER (WHERE pm.source = 'IMPORT')   AS from_xlsx_import
  FROM physical_media pm
 WHERE pm.removed_at IS NULL
 GROUP BY pm.physical_media_type, pm.media_category, pm.digitization
 ORDER BY pm.physical_media_type, pm.digitization;
```

### 9. Maqam vote panel with listen accountability

Compares the denormalized aggregate against the session rows that back it.

```sql
SELECT m.maqam_code,
       m.song_name,
       m.producer,
       v.teacher_username,
       v.maqam_type,
       v.voted_at,
       v.total_listen_seconds                    AS aggregate_seconds,
       COALESCE(SUM(s.seconds_listened), 0)      AS session_seconds,
       m.audio_duration_seconds
  FROM list_of_maqam m
  JOIN maqam_teacher_votes v ON v.list_of_maqam_id = m.id
  LEFT JOIN maqam_audio_listen_sessions s
         ON s.list_of_maqam_id = m.id
        AND s.teacher_user_id  = v.teacher_user_id
 WHERE m.removed_at IS NULL
 GROUP BY m.maqam_code, m.song_name, m.producer,
          v.teacher_username, v.maqam_type, v.voted_at,
          v.total_listen_seconds, m.audio_duration_seconds
 ORDER BY m.maqam_code, v.teacher_username;
```

`maqam_teacher_votes` is unique on `(list_of_maqam_id, teacher_user_id)`
(`uk_maqam_teacher_one_vote_per_song`); the 1–3 teachers-per-record cap
(`ListOfMaqam.MIN_TEACHERS` / `MAX_TEACHERS`) is enforced in the service layer, not by the
database.

### 10. Guest corrections awaiting action, by media

```sql
SELECT gc.status,
       gc.media_type,
       gc.media_code,
       gc.media_title,
       gc.target_field,
       gc.current_value,
       gc.suggested_value,
       gc.guest_username,
       gc.record_created_by,
       gc.forwarded_by,
       gc.forward_note,
       DATE_TRUNC('day', gc.created_at AT TIME ZONE 'Asia/Baghdad') AS baghdad_day
  FROM guest_corrections gc
 WHERE gc.removed_at IS NULL
   AND gc.status IN ('PENDING', 'FORWARDED')
 ORDER BY gc.created_at DESC;
```

The remaining resolution columns are `resolved_by`, `resolved_at` and `resolve_note`.

Backing indexes: `idx_gc_media (media_type, media_code, removed_at)`,
`idx_gc_status (status, removed_at)`, `idx_gc_guest (guest_user_id)`,
`idx_gc_created_at (created_at)`.

---

## Notes

**Naming rules applied when a name had to be inferred.** Explicit `@Table(name=…)`,
`@CollectionTable(name=…)`, `@JoinTable(name=…)` and `@Column(name=…)` values were copied
verbatim and account for nearly every name in this document. Where an annotation gave no name,
Hibernate's implicit strategy applies — Spring Boot configures
`CamelCaseToUnderscoresNamingStrategy`, which lower-cases the Java identifier and inserts an
underscore before each internal capital. The names inferred that way:

- `users_tbl.user_id` — from the field `private Long userId` on `User`, which carries `@Id` but
  no `@Column(name=…)`. Corroborated by `MaqamTeacherVote`'s javadoc, which names the target as
  "`users_tbl.user_id`".
- `id` — every entity's primary-key field is named `id` with no `@Column`, so the column is
  `id`. Unchanged by the conversion.
- `guest_corrections.version` — `@Version private Long version` with no `@Column`.

**SQL types.** Types in this document follow from the Java type plus any
`columnDefinition`/`length`: `String` with `columnDefinition = "TEXT"` → `TEXT`; `String` with
`length = N` → `varchar(N)`; `String` with neither → `varchar(255)` (Hibernate's default);
`Long` → `bigint`; `Integer`/`int` → `integer`; `Boolean`/`boolean` → `boolean`; `LocalDate` →
`date`. For `java.time.Instant` the exact DDL is Hibernate's choice and is not declared in
source — treat those columns as timestamps holding an absolute instant, and read
[section 4](#4-timestamps-and-time-zones) before comparing them to anything.

**Primary-key generation.** Every entity uses `GenerationType.IDENTITY` except `Session`, which
uses `GenerationType.AUTO`. The generator Hibernate selects for `AUTO` on PostgreSQL, and any
sequence name it implies, are _not documented in source._

**A legacy column exists that no entity maps.** `PhysicalMediaSizeColumnMigrationInitializer`
backfills the current column from an older one:

```sql
UPDATE physical_media SET physical_size = size
 WHERE physical_size IS NULL AND size IS NOT NULL
```

So a `physical_media.size` column may still be present in a database that predates the rename.
`PhysicalMedia` maps `physical_size` (material size) and `size_gb` (digital file size,
repurposed from a retired `sub_type` column); it does not map `size`.

**Table names that are easy to get wrong.** `person` is singular while `audios`, `videos`,
`images`, `texts`, `projects` and `categories` are plural. The user table is `users_tbl`, not
`users`. The maqam table is `list_of_maqam`. The join table between projects and categories is
`project_categories (project_id, category_id)`.

**Audit-table column asymmetry.** `audio_audit_logs` has `category_code varchar(120)`
(singular), while `video_audit_logs`, `image_audit_logs`, `text_audit_logs` and
`project_audit_logs` have `category_codes TEXT` (plural, comma-joined). `person_audit_logs` has
neither. `category_audit_logs` has `category_code varchar(120)`, but as its *own* entity-code
column — not a category attributed to some other record — so it is not comparable to the
`audio_audit_logs` column of the same name. Check before you union.

---

## Related

- [Database documentation index](./README.md)
- [Tags and keywords API](../content/tags-and-keywords.md) — the admin surface over the
  collection tables in [section 7](#7-free-text-collections-tags-and-keywords)
- [Audio API](../content/audio.md) — codes, trash, visibility and the stream proxy in practice
- [Project API](../content/project.md) — the visibility cascade described in
  [section 3](#3-visibility)
- [Person API](../content/person.md) — the one entity whose media URL is served raw
- [Items API](../content/items.md) — the merged cross-media list and its filters
- [Error codes](../../external/02-errors.md) — including the `409` raised by the `version`
  column
