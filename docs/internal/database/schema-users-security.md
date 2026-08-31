# Schema — Users, Sessions and Security Tables

> **Audience:** Backend / DBA · **Source:** `user/model/`, `user/enums/`, `user/configs/`

The five tables below hold every account, every authenticated session, the JWT revocation list
and the admin-to-employee warning feed. They are created by Hibernate (`ddl-auto=update`) and
patched at boot by `ApplicationReadyEvent` initializers in `user/configs/` that run raw SQL
through `JdbcTemplate` — there is no Flyway/Liquibase migration history to consult.

Two of these tables are on the hot path of **every** authenticated request: `sessions` and
`token_blacklist` decide whether a token is still alive, and `users_tbl` + `user_permissions`
are re-read to rebuild the caller's authority set. Treat any schema change here as a
request-latency change.

## Tables at a glance

| Table | Java entity | Purpose | Rows grow with |
|---|---|---|---|
| `users_tbl` | `ak.dev.khi_archive_platform.user.model.User` | Account identity, credentials, role, lock and password-expiry state | One row per account |
| `user_permissions` | `@ElementCollection` on `User.extraPermissions` | Per-user permission grants layered on top of the role | Accounts × granted permissions (29 rows per seeded EMPLOYEE) |
| `sessions` | `ak.dev.khi_archive_platform.user.model.Session` | One row per successful login; the authority for "is this token still valid" | One row per login, never auto-purged |
| `token_blacklist` | `ak.dev.khi_archive_platform.user.model.TokenBlacklist` | Explicitly revoked raw JWTs (logout, forced revocation) | One row per logout/revocation, never auto-purged |
| `user_warnings` | `ak.dev.khi_archive_platform.user.model.UserWarning` | Admin-issued in-app warnings and their acknowledgement state | One row per warning issued |

---

## `users_tbl`

**Entity:** `ak.dev.khi_archive_platform.user.model.User`

| Column | Type | Null | Default | Description |
|---|---|---|---|---|
| `user_id` | `bigint` (identity) | no | identity | **PK.** `@GeneratedValue(strategy = IDENTITY)`. Column name inferred from the field `userId` (see Notes) |
| `name` | `varchar(120)` | no | — | Full display name of the user |
| `profile_image` | `varchar(500)` | yes | — | Stored profile image URL or path. Holds the full S3 URL written by `UserProfileService` |
| `username` | `varchar(80)` | no | — | Unique login name used for authentication. `@Column(unique = true)` **and** table-level `uk_users_username` |
| `email` | `varchar(160)` | no | — | Unique email used for login and notifications. `@Column(unique = true)` **and** table-level `uk_users_email` |
| `password` | `varchar(255)` | no | — | Encrypted account password (BCrypt hash). `@JsonIgnore` on the entity — **never expose** |
| `role` | `varchar(30)` | no | — | `@Enumerated(STRING)` over `user/enums/Role`: `GUEST`, `EMPLOYEE`, `TEACHER`, `ADMIN`. Guarded by `users_tbl_role_check` |
| `is_activated` | `boolean` | no | `true` (entity-side `@Builder.Default`) | Whether the account is active and allowed to sign in. Backs `UserDetails.isEnabled()` |
| `created_at` | `timestamp(6) with time zone` | yes | — | Set by the service layer on register/admin-create, not by a DB default |
| `updated_at` | `timestamp(6) with time zone` | yes | — | Rewritten by every service-layer mutation, including failed-login bookkeeping |
| `failed_attempts` | `integer` | no | `0` (entity-side `@Builder.Default`) | Consecutive failed logins. Primitive `int`, so `nullable = false` matches the Java type |
| `lock_time` | `timestamp(6) with time zone` | yes | — | When the account was locked. `@JsonIgnore` on the entity |
| `is_locked` | `boolean` | no | `false` (entity-side `@Builder.Default`) | Whether the account is currently locked. Backs `UserDetails.isAccountNonLocked()` |
| `password_expiry_date` | `timestamp(6) with time zone` | yes | — | Password expiry instant. Services set it to `now + 90 days` (`PASSWORD_EXPIRY`) |
| `provider` | `varchar(30)` | yes | — | Account source label; the register flows write `"local"` |
| `provider_id` | `varchar(120)` | yes | — | External account identifier from the source provider |
| `image_url` | `varchar(500)` | yes | — | Profile image URL supplied by an external account source |

No `@Version` column exists on this entity — there is **no optimistic locking** on user rows.

**Keys and constraints**

| Kind | Name | Definition |
|---|---|---|
| Primary key | Hibernate-generated | `(user_id)` |
| Unique | `uk_users_username` | `(username)` — declared in `@Table(uniqueConstraints = …)` |
| Unique | `uk_users_email` | `(email)` — declared in `@Table(uniqueConstraints = …)` |
| Unique | Hibernate-generated | `username` and `email` also carry `@Column(unique = true)`, which Hibernate emits as its own constraint — expect one auto-named unique constraint per column **in addition** to the two named ones |
| Check | `users_tbl_role_check` | `CHECK (role IN ('GUEST','EMPLOYEE','TEACHER','ADMIN'))` — dropped and recreated on every boot, see [Role CHECK constraint maintenance](#role-check-constraint-maintenance) |

**Indexes**

No `@Table(indexes = …)` is declared and no initializer in `platform/config` or `user/configs`
creates an index on this table. The only indexes present are the ones PostgreSQL creates
implicitly for the primary key and each unique constraint above. `AuditLogIndexInitializer`
touches only the `*_audit_logs` tables, not `users_tbl`.

Those implicit unique indexes are what `UserRepository.findByUsernameOrEmailExact` relies on;
the case-insensitive fallback `findByUsernameOrEmailIgnoreCase` wraps both columns in `LOWER()`
and therefore **cannot** use them — it is a sequential scan.

**Relationships**

| Association | Type | Fetch | Cascade / orphanRemoval | Table |
|---|---|---|---|---|
| `User.extraPermissions` | `@ElementCollection` of `String` | `EAGER` | Managed by Hibernate (collection rows deleted with the owner) | `user_permissions` (join column `user_id`) |

`Session.user` is the owning side of a `@ManyToOne` back to this table; `User` declares no
inverse `@OneToMany`, so there is no `mappedBy` collection of sessions. `user_warnings` and the
audit tables reference `user_id` values **without** a database foreign key.

**Notes**

- The field `userId` has no `@Column(name = …)`, so the column name comes from Hibernate's
  implicit naming strategy (CamelCase → snake_case) — `userId` → `user_id`. This inference is
  confirmed by the literal SQL in the backfill initializers, which select `u.user_id FROM
  users_tbl u`.
- `Instant` fields carry no `columnDefinition`, so their SQL type is Hibernate's default
  mapping for `java.time.Instant` on PostgreSQL (`timestamp(6) with time zone`). Databases
  first created by an older Hibernate may hold `timestamp without time zone` instead —
  check the live column type before writing time-zone-sensitive SQL.
- `loadUserByUsername` is `@Cacheable("users:details", key = "#username")`; the Caffeine spec
  in `CacheConfig` is `build("users:details", 500, 1)` — 500 entries, 1-minute TTL. Every
  admin mutation in `AdminUserService`/`UserService` is annotated
  `@CacheEvict(value = "users:details", allEntries = true)`, so direct SQL updates to this
  table stay invisible for up to one minute.
- Login bookkeeping writes to this table on the read path: `recordFailedLoginAttempt` bumps
  `failed_attempts` and sets `is_locked`/`lock_time` after `MAX_FAILED_ATTEMPTS = 5`, and
  `unlockIfLockExpired` clears all three once `lock_time + LOCK_DURATION_MINUTES (1)` has
  passed. A "read-only" login attempt is therefore an `UPDATE`.
- Deleting a row directly in SQL will fail while `sessions` rows reference it — the FK declares
  no `ON DELETE` action. The application deletes the user's sessions first
  (`UserService`, `UserProfileService`, `AdminUserService`).

---

## `user_permissions`

**Entity:** `@ElementCollection` on `ak.dev.khi_archive_platform.user.model.User#extraPermissions`
(`@CollectionTable(name = "user_permissions")`)

| Column | Type | Null | Default | Description |
|---|---|---|---|---|
| `user_id` | `bigint` | no | — | **FK** → `users_tbl.user_id`. Join column of the element collection |
| `permission` | `varchar(100)` | no | — | One authority string from `user/enums/Permission`, e.g. `audio:read`, `maqam:vote`, `physical_media:import` |

**Keys and constraints**

| Kind | Name | Definition |
|---|---|---|
| Primary key | — | None. Hibernate does not create a surrogate PK for an `@ElementCollection` table |
| Unique | `uk_user_permissions_user_perm` | `(user_id, permission)` — declared on `@CollectionTable(uniqueConstraints = …)` |
| Foreign key | Hibernate-generated | `(user_id)` → `users_tbl(user_id)`; no `ON DELETE` action is declared in source |

**Indexes**

None declared. PostgreSQL creates an implicit unique index for
`uk_user_permissions_user_perm`, which also covers `user_id`-prefixed lookups. No initializer
adds anything here.

**Relationships**

Owned entirely by `User.extraPermissions` (`@ElementCollection(fetch = FetchType.EAGER)`).
There is no separate entity class — Hibernate loads and rewrites the whole set per user.

**Notes**

- The collection is **EAGER** by design (see the Javadoc on the field): the JWT filter reloads
  the user on every authenticated request, so a lazy collection would cost a second round trip
  during authorization.
- JPA persists the set by delete-then-insert semantics for the owning user. Concurrent
  `grantPermissions`/`revokePermissions` calls against the same user are last-writer-wins;
  there is no `@Version` guard.
- `uk_user_permissions_user_perm` is not just a data guard — the two backfill initializers name
  it explicitly in `ON CONFLICT ON CONSTRAINT uk_user_permissions_user_perm DO NOTHING`.
  Renaming or dropping the constraint makes those initializers fail at boot (they log a warning
  and continue, so the failure is silent apart from the log line).
- Stored values are always lowercase `<resource>:<action>` strings: `sanitiseAndValidate` trims
  and `toLowerCase(Locale.ROOT)`s every incoming string before it reaches the entity, and the
  seeded constants come from `Permission.getPermission()`, which is lowercase by definition.
  Match on exact lowercase in SQL — no `ILIKE` needed.
- ADMIN accounts normally hold **no** rows here: `AdminUserService.grantPermissions` /
  `revokePermissions` reject ADMIN targets with `ADMIN_PERMISSIONS_LOCKED`, because ADMIN
  authorities come from the role enum itself.

---

## `sessions`

**Entity:** `ak.dev.khi_archive_platform.user.model.Session`

| Column | Type | Null | Default | Description |
|---|---|---|---|---|
| `id` | `bigint` | no | sequence | **PK.** `@GeneratedValue(strategy = AUTO)` — sequence-backed on PostgreSQL; the generator/sequence name is not declared in source |
| `session_id` | `varchar(255)` | no | — | Unique session identifier. A `UUID.randomUUID().toString()` written by `JwtTokenProvider.generateToken`, and echoed into the JWT's `sessionId` claim |
| `user_id` | `bigint` | no | — | **FK** → `users_tbl.user_id` (`@JoinColumn(name = "user_id", nullable = false)`) |
| `device_info` | `varchar(255)` | yes | — | Raw `User-Agent` header captured at login |
| `ip_address` | `varchar(255)` | yes | — | `request.getRemoteAddr()` at login |
| `login_timestamp` | `timestamp(6) with time zone` | no | — | When the session row was created |
| `expires_at` | `timestamp(6) with time zone` | no | — | Login time + `jwt.expiration-ms`, which `application.yaml` binds to `${JWT_EXPIRATION_MS:259200000}` — 72 h unless the env var overrides it. `JwtTokenProvider`'s own `@Value` fallback (`86400000`) only applies if the key is missing from the config entirely |
| `is_active` | `boolean` | no | `true` (entity-side `@Builder.Default`) | Cleared on logout, force-logout and per-session revocation |
| `logout_timestamp` | `timestamp(6) with time zone` | yes | — | Set when `is_active` flips to false |

**Keys and constraints**

| Kind | Name | Definition |
|---|---|---|
| Primary key | Hibernate-generated | `(id)` |
| Unique | Hibernate-generated | `(session_id)` — from `@Column(unique = true)`; no explicit name in source |
| Foreign key | Hibernate-generated | `(user_id)` → `users_tbl(user_id)`; no `ON DELETE` action declared |

**Indexes**

No `@Table(indexes = …)`, no initializer. Only the implicit PK index and the implicit unique
index on `session_id` exist. That unique index is what makes the per-request
`findBySessionId` lookup cheap.

**Relationships**

| Association | Type | Fetch | Cascade / orphanRemoval | Table |
|---|---|---|---|---|
| `Session.user` | `@ManyToOne` | `LAZY` | none declared | FK column `user_id` on `sessions` |

There is no inverse collection on `User`; session lists are fetched through
`SessionRepository.findByUser` / `findByUserAndIsActive`.

**Notes**

- `user_id` has **no** index. `findByUser` and `findByUserAndIsActive` — used by the sessions
  API, force-logout and account deletion — sequential-scan the table. Add an index on
  `(user_id, is_active)` before this table grows large.
- Nothing purges expired rows: no `@Scheduled` job in the codebase targets `sessions` (the only
  scheduled job in the app is `GuestTrendingService`'s 03:00 trending recompute). Rows are
  removed only when the owning account is deleted, where the services call
  `sessionRepository.deleteAll(...)` explicitly.
- Revocation flips `is_active`/`logout_timestamp`; it never deletes. A logged-out session stays
  queryable for audit purposes, and `UserAuditService` resolves audit rows back through
  `findBySessionId`.
- Because `expires_at` is stored per session as well as inside the JWT, shortening a session by
  updating this column takes effect for everyone within the `TokenService` cache TTL
  (2 minutes) without touching the token itself.

---

## `token_blacklist`

**Entity:** `ak.dev.khi_archive_platform.user.model.TokenBlacklist`

| Column | Type | Null | Default | Description |
|---|---|---|---|---|
| `id` | `bigint` (identity) | no | identity | **PK.** `@GeneratedValue(strategy = IDENTITY)` |
| `token` | `varchar(512)` | no | — | The **raw, still-signed JWT** string. Unique |
| `blacklisted_at` | `timestamp(6) with time zone` | no | — | When `TokenService.blacklistToken` recorded the revocation |
| `expires_at` | `timestamp(6) with time zone` | no | — | The token's own `exp` claim (`getExpirationDateFromToken`), or `blacklisted_at` when the claim cannot be read |

**Keys and constraints**

| Kind | Name | Definition |
|---|---|---|
| Primary key | Hibernate-generated | `(id)` |
| Unique | Hibernate-generated | `(token)` — from `@Column(unique = true)`; no explicit name in source |

**Indexes**

None declared, and no initializer adds any. The implicit unique index on `token` serves the
per-request `findByToken` lookup.

**Relationships**

None. The table stores no `user_id` and has no foreign key — a blacklisted token is matched by
its literal string only.

**Notes**

- `token` is capped at `varchar(512)`. The tokens minted by `JwtTokenProvider` carry an
  `authorities` array claim containing every effective authority, so a heavily-granted
  EMPLOYEE's token is long. If a token ever exceeds 512 characters, PostgreSQL rejects the
  insert and the logout silently fails to blacklist it — the session row's `is_active = false`
  is then the only thing revoking that token.
- Nothing deletes rows after `expires_at` passes; there is no purge job in source. Plan a
  manual `DELETE FROM token_blacklist WHERE expires_at < now()` job for long-running
  deployments.
- Rows are **not** removed when the owning account is deleted (no `user_id` to key off).
- `expires_at` here is informational for cleanup only — the request path never reads it. See
  the next section for what is actually checked.

---

## `user_warnings`

**Entity:** `ak.dev.khi_archive_platform.user.model.UserWarning`

| Column | Type | Null | Default | Description |
|---|---|---|---|---|
| `id` | `bigint` (identity) | no | identity | **PK.** `@GeneratedValue(strategy = IDENTITY)` |
| `target_user_id` | `bigint` | no | — | Recipient. FK-style reference to `users_tbl.user_id` with **no** database constraint (per the `@Comment`: "no constraint to keep deletes flexible") |
| `target_username` | `varchar(80)` | yes | — | Snapshot of the recipient's username at warning time |
| `actor_user_id` | `bigint` | yes | — | Admin who sent it. Again a plain column, no FK |
| `actor_username` | `varchar(80)` | yes | — | Snapshot of the sender's username |
| `actor_display_name` | `varchar(120)` | yes | — | Snapshot of the sender's display name |
| `severity` | `varchar(16)` | no | `WARNING` (entity-side `@Builder.Default`) | `@Enumerated(STRING)` over `user/enums/WarningSeverity`: `INFO`, `WARNING`, `CRITICAL` |
| `title` | `varchar(200)` | no | — | Short headline shown in the warning list |
| `message` | `text` | no | — | Body of the warning; `columnDefinition = "TEXT"`. HTML-escaped on write (`HtmlUtils`) |
| `acknowledged` | `boolean` | no | `false` (entity-side `@Builder.Default`) | True once the recipient confirmed they read it |
| `acknowledged_at` | `timestamp(6) with time zone` | yes | — | When the recipient acknowledged |
| `created_at` | `timestamp(6) with time zone` | no | — | Set by `UserWarningService.send` |
| `removed_at` | `timestamp(6) with time zone` | yes | — | Soft-delete marker. Set when an admin revokes the warning; the recipient stops seeing it |

**Keys and constraints**

| Kind | Name | Definition |
|---|---|---|
| Primary key | Hibernate-generated | `(id)` |
| Check | Hibernate-generated (typically `user_warnings_severity_check`) | `CHECK (severity IN ('INFO','WARNING','CRITICAL'))` — emitted once when Hibernate first creates the column |
| Foreign key | — | None. `target_user_id` and `actor_user_id` are unconstrained `bigint` columns |

**Indexes** — all four are declared on `@Table(indexes = …)`; no initializer is involved.

| Index | Columns |
|---|---|
| `idx_user_warnings_target` | `(target_user_id, removed_at)` |
| `idx_user_warnings_actor` | `(actor_user_id)` |
| `idx_user_warnings_created_at` | `(created_at)` |
| `idx_user_warnings_acknowledged` | `(target_user_id, acknowledged, removed_at)` |

**Relationships**

None mapped in JPA. The link to `users_tbl` is by id value only, so a warning survives the
deletion of either party — which is why the username/display-name snapshots exist.

**Notes**

- Every query on the recipient path filters `removed_at IS NULL`
  (`findByIdAndRemovedAtIsNull`, `findActiveForUser`,
  `countByTargetUserIdAndAcknowledgedFalseAndRemovedAtIsNull`). Any new query must do the same
  or it will resurrect revoked warnings.
- `findActiveForUser` orders by `acknowledged ASC, createdAt DESC`; the admin search is
  `JpaSpecificationExecutor`-driven and sorts `createdAt DESC, id DESC`.
- The `severity` CHECK constraint is **not** re-synced by any initializer. Because
  `ddl-auto=update` never refreshes a Hibernate-generated enum CHECK, adding a value to
  `WarningSeverity` requires dropping and recreating `user_warnings_severity_check` by hand —
  or adding an initializer modeled on `UserRoleConstraintInitializer`.
- Every mutation also writes a row to `user_audit_logs` (`WARNING_SENT`, `WARNING_REVOKED`,
  `WARNING_ACKNOWLEDGED`, `UPDATE`) through `UserAuditService`.

---

## How JWT revocation is represented in the data

Revocation is not a claim inside the token — it is DB state. `JWTAuthenticationFilter` calls
`TokenService.isTokenBlacklisted(token)` on every request that carries a token, and that method
treats a token as revoked when **any** of the following is true:

| # | Check | Columns read |
|---|---|---|
| 1 | A row exists for the raw token string | `token_blacklist.token` |
| 2 | The token has no `sessionId` claim | — (token only) |
| 3 | No session row matches the `sessionId` claim | `sessions.session_id` |
| 4 | The session is not active | `sessions.is_active` |
| 5 | The session's expiry is null or already past | `sessions.expires_at` |

So the columns consulted on the hot path are exactly: `token_blacklist.token`,
`sessions.session_id`, `sessions.is_active`, `sessions.expires_at`.

`TokenService` fronts those two queries with its **own** private Caffeine cache — not one of the
`CacheConfig` caches — keyed by the raw JWT, `maximumSize(10_000)`,
`expireAfterWrite(2, TimeUnit.MINUTES)`, storing `true` for blacklisted and `false` for valid.
Consequences for anyone changing this data by hand:

- Flipping `sessions.is_active = false` in SQL takes up to **2 minutes** to be observed, because
  a cached `false` (valid) verdict may still be live. `TokenService.blacklistToken` avoids the
  delay only because it writes `true` into the cache itself.
- `blacklistToken` performs both halves of a revocation: it upserts `token_blacklist` (guarded
  by `findByToken` first, so re-blacklisting is idempotent) and then flips the matching
  `sessions` row to `is_active = false` with a `logout_timestamp`.
- After the revocation check passes, the filter reloads the account through
  `UserDetailsService.loadUserByUsername` on every request, so `users_tbl.role`,
  `users_tbl.is_locked`, `users_tbl.lock_time`, `users_tbl.password_expiry_date` and the
  `user_permissions` rows take effect within the `users:details` TTL (1 minute) without
  reissuing the token. The authority list in the token itself is never trusted for
  authorization.
- Requests to `/api/guest/**`, `/api/auth/login`, `/api/auth/register` and
  `/api/auth/register-with-image` skip the filter entirely (`shouldNotFilter`), so they touch
  none of these tables.

## Role CHECK constraint maintenance

Hibernate emits `CHECK (role IN (...))` when it first creates `users_tbl.role`, and with
`ddl-auto=update` it never refreshes that constraint again — adding `TEACHER` to the `Role` enum
would otherwise produce `violates check constraint "users_tbl_role_check"` at runtime.
`user/configs/UserRoleConstraintInitializer` fixes this on every boot
(`@EventListener(ApplicationReadyEvent.class)`), in three steps.

1. Find every CHECK constraint attached to the `role` column:

```sql
SELECT con.conname
FROM pg_constraint con
JOIN pg_class c ON c.oid = con.conrelid
JOIN pg_attribute a
  ON a.attrelid = c.oid AND a.attnum = ANY(con.conkey)
WHERE c.relname = 'users_tbl'
  AND con.contype = 'c'
  AND a.attname = 'role'
```

2. Drop each one it found:

```sql
ALTER TABLE users_tbl DROP CONSTRAINT IF EXISTS "<conname>"
```

3. Recreate a single constraint from the live enum values (the value list is built in Java as
   `Stream.of(Role.values()).map(r -> "'" + r.name() + "'").collect(joining(","))`):

```sql
ALTER TABLE users_tbl ADD CONSTRAINT users_tbl_role_check
CHECK (role IN ('GUEST','EMPLOYEE','TEACHER','ADMIN'))
```

The whole body is wrapped in `try/catch`: a failure logs
`Could not re-sync users_tbl_role_check: …` at WARN and boot continues, so a stale constraint
only shows up later as a write failure. On success it logs
`users_tbl_role_check re-synced with Role enum: …`.

Note that the drop step removes **any** CHECK on `role`, including one you added by hand — put
custom rules on a different column or a different constraint target.

## How seeded per-user permission grants are stored

Grants live as plain `(user_id, permission)` rows in `user_permissions`. There is no separate
"role defaults" table: the defaults are Java constants that get **copied into rows**.

- `Role.EMPLOYEE_DEFAULT_PERMISSIONS` — READ/CREATE/UPDATE for audio, video, image, text,
  category, person, project and maqam, plus `maqam:teacher_manage` and
  `physical_media:read|create|update|import`. No REMOVE and no DELETE.
- `Role.TEACHER_DEFAULT_PERMISSIONS` — `maqam:read` and `maqam:vote` only.
- `Role.defaultExtraPermissions(role)` returns the matching set for EMPLOYEE and TEACHER, and an
  empty set for GUEST and ADMIN.

`User.applyRoleDefaults()` copies that set into `extraPermissions` **only when the set is
currently empty**, so an admin's curated grants are never overwritten. It runs on user creation
(`UserService`, `AdminUserService`) and on role transitions (`AdminUserService.changeRole`,
and the update path). Effective authorities are computed in `User.getAuthorities()` as
`role.getAuthorities()` (which also adds `ROLE_<NAME>`) ∪ `extraPermissions`.

Because seeding fires only on creation/transition, already-provisioned employees are topped up
by two boot-time backfills in `user/configs/`, both idempotent:

- `EmployeePhysicalMediaPermissionBackfillInitializer` — one statement per permission in
  `physical_media:read|create|update|import`.
- `EmployeeMaqamTeacherManageBackfillInitializer` — one statement for
  `maqam:teacher_manage`.

Both execute the same shape (parameters bound to the permission string and `Role.EMPLOYEE`):

```sql
INSERT INTO user_permissions (user_id, permission)
SELECT u.user_id, ? FROM users_tbl u
WHERE u.role = ?
ON CONFLICT ON CONSTRAINT uk_user_permissions_user_perm DO NOTHING
```

Admin edits go through `AdminUserService.grantPermissions` / `revokePermissions`, which validate
each string against the permission catalog (`sanitiseAndValidate`, raising
`UnknownPermissionException` for unknown values), reject ADMIN targets, and auto-promote a
GUEST to EMPLOYEE on the first grant. Both are `@CacheEvict(value = "users:details",
allEntries = true)`. Manual `INSERT`s into `user_permissions` bypass that validation and the
audit trail — the grant will still work, but nothing appears in `user_audit_logs`.

## Columns that must never be exposed through an API response

| Column | Why |
|---|---|
| `users_tbl.password` | BCrypt hash of the credential. `@JsonIgnore` on the entity; absent from `UserResponseDTO` and `UserAdminDTO`. Keep it out of every projection and every log line |
| `token_blacklist.token` | A complete, still-signed JWT. Until its `exp` passes it is a usable credential if the blacklist row is ever dropped — never return it, never log it, and treat DB dumps of this table as credential material |
| `users_tbl.lock_time` | `@JsonIgnore` on the entity. `UserAdminDTO` deliberately re-exposes it, so it is admin-only — never surface it on a self-service or public response |
| `users_tbl.provider_id` | External account identifier. No DTO in `user/dto` maps it; keep it that way |
| `sessions.ip_address`, `sessions.device_info` | Personal data. `SessionDTO` exposes them, but only to the session's own owner and to admins — never in a listing keyed by another user |
| `sessions.session_id` | Doubles as the revocation handle used by the session API. Return it only to the session's owner; it must never appear in a cross-user listing |
| `user_permissions.permission` | Fine for admins (`UserAdminDTO.extraPermissions` / `effectiveAuthorities`), but publishing a user's authority set to non-admins hands out a map of the back office |

The `User` entity implements `UserDetails` and is serializable, so it can be returned directly by
accident. Always map to `UserResponseDTO` or `UserAdminDTO` instead of returning `User`.

## Related

- [Database documentation index](./README.md)
- [External API overview](../../external/00-overview.md) — the register/login/logout surface that
  writes `sessions` and `token_blacklist`
- Source of truth for this file: `src/main/java/ak/dev/khi_archive_platform/user/model/`
  (`User`, `Session`, `TokenBlacklist`, `UserWarning`),
  `user/enums/` (`Role`, `Permission`, `WarningSeverity`), and `user/configs/`
  (`UserRoleConstraintInitializer`, `EmployeePhysicalMediaPermissionBackfillInitializer`,
  `EmployeeMaqamTeacherManageBackfillInitializer`)
- `user_audit_logs` (entity `user/model/UserAuditLog`) is referenced throughout this file but is
  documented with the other audit-log tables, not here
