# Admin — Users and Permissions API

> **Audience:** Staff (ADMIN) · **Base path:** `/api/admin/users` · **Source:** `src/main/java/ak/dev/khi_archive_platform/user/api/AdminUserAPI.java`

The back-office surface for the `users_tbl` account register: create accounts, edit their fields,
move an account between roles, grant and revoke individual `<resource>:<action>` authorities on top
of the role, park an account (deactivate / lock / force-logout) and hard-delete it. Every mutation
writes one row to `user_audit_logs`, and every authority handed out here is validated against the
`Permission` catalog before it reaches the database.

## Access

| Requirement | Value |
|---|---|
| Authentication | required (JWT in the `khi_auth_token` HttpOnly cookie, or `Authorization: Bearer …`) |
| Authority | per-method `hasAuthority('user:read' \| 'user:create' \| 'user:update' \| 'user:delete')`; the class-level `hasRole('ADMIN')` applies only to the methods that declare none |
| Roles that hold `user:*` by default | ADMIN only |

`AdminUserAPI` carries a **class-level** `@PreAuthorize("hasRole('ADMIN')")`, and 15 of its handlers
also carry their own method-level `@PreAuthorize("hasAuthority('user:…')")`. The two annotations do
**not** stack. Spring Security resolves the *most specific* `@PreAuthorize` for a handler: when a
method declares one, that expression replaces the class-level expression rather than being AND-ed
with it. The class-level `hasRole('ADMIN')` is therefore the effective gate only on the handlers
that declare nothing of their own — the `/catalog/*` endpoints.

The practical consequence: a non-ADMIN who has been granted `user:read` through
`POST /api/admin/users/{userId}/permissions` **can** call the 15 annotated handlers, and will be
rejected only by the `/catalog/*` endpoints. That is the delegation the `Permission.USER_READ`
javadoc describes — "user-listing without full ADMIN power" — and it works precisely because the
method annotation wins.

> The class javadoc describes the two levels as additive "defence-in-depth". That is the intent,
> not the runtime behavior. If defence-in-depth is what you actually want here, the class-level
> annotation has to be repeated into each method expression, e.g.
> `@PreAuthorize("hasRole('ADMIN') and hasAuthority('user:read')")`.

The exact authority is repeated in every endpoint section below.

`user:remove` (`Permission.USER_REMOVE`) exists in the catalog but **no endpoint in this file — or
anywhere else in the codebase — uses it**. There is no soft-remove / trash / restore flow for user
accounts: `DELETE /api/admin/users/{userId}` is a hard delete of the row. Deactivating
(`/deactivate`) or locking (`/lock`) an account is the closest equivalent to "park without erasing".

Three failures can occur on any endpoint in this file. The two `401`s are written by the security
filter chain — `JwtAuthenticationEntryPoint` when the request carried no credentials at all, and
`JWTAuthenticationFilter` when a token was present but rejected. The `403` raised by a
`@PreAuthorize` is written by `GlobalExceptionHandler.handleAccessDenied` instead, which is why it
carries a `details.requiredAuthority` (`JwtAccessDeniedHandler` covers the same code for denials
that happen at the filter layer, without that field):

| Status | `error` code | When |
|---|---|---|
| `401` | `TOKEN_MISSING` | No `Authorization` header and no cookie on the request |
| `401` | `TOKEN_EXPIRED` / `TOKEN_REVOKED` / `TOKEN_MALFORMED` / `TOKEN_INVALID_SIGNATURE` / `TOKEN_INVALID` | Token present but rejected by `JWTAuthenticationFilter` |
| `403` | `ACCESS_DENIED` | Caller is not ADMIN, or lacks the method-level `user:*` authority |

`details.requiredAuthority` is pulled off the annotation by a regex that reads the **method-level**
`@PreAuthorize` first and only falls back to the class-level one, so a caller rejected by
`hasRole('ADMIN')` still sees the method's `user:*` string there. `details.actor` and
`details.actorAuthorities` show what the caller actually holds.

They are repeated in the per-endpoint tables only where the wording differs.

## Endpoints

| Method | Path | Authority | Purpose |
|---|---|---|---|
| `GET` | `/api/admin/users` | `user:read` | Every user, as a plain array (no paging, no filters) |
| `POST` | `/api/admin/users` | `user:create` | Create an account with a role and seeded permissions |
| `GET` | `/api/admin/users/{userId}` | `user:read` | One account (writes a `READ` audit row) |
| `PUT` | `/api/admin/users/{userId}` | `user:update` | Update name / username / email / password / role / activation |
| `PUT` | `/api/admin/users/{userId}/role` | `user:update` | Change role only |
| `POST` | `/api/admin/users/{userId}/permissions` | `user:update` | Grant extra per-user permissions |
| `DELETE` | `/api/admin/users/{userId}/permissions` | `user:update` | Revoke previously-granted extra permissions |
| `POST` | `/api/admin/users/{userId}/activate` | `user:update` | Enable the account (`isActivated = true`) |
| `POST` | `/api/admin/users/{userId}/deactivate` | `user:update` | Disable the account (`isActivated = false`) |
| `POST` | `/api/admin/users/{userId}/lock` | `user:update` | Lock the account and stamp `lockTime` |
| `POST` | `/api/admin/users/{userId}/unlock` | `user:update` | Unlock and clear the failed-login counter |
| `POST` | `/api/admin/users/{userId}/reset-failed-attempts` | `user:update` | Clear the counter without touching lock state |
| `POST` | `/api/admin/users/{userId}/force-logout` | `user:update` | Deactivate every `sessions` row for the user |
| `DELETE` | `/api/admin/users/{userId}` | `user:delete` | Hard-delete the user and their sessions |
| `GET` | `/api/admin/users/{userId}/audit-logs` | `user:read` | Paged `user_audit_logs` history for one user |
| `GET` | `/api/admin/users/catalog/roles` | `hasRole('ADMIN')` (class-level only) | Every role and the authorities it grants |
| `GET` | `/api/admin/users/catalog/permissions` | `hasRole('ADMIN')` (class-level only) | Every permission string the system accepts |

The audit-log **collection** endpoints (`/api/admin/users/audit-logs`, `/audit-logs/{id}`,
`/audit-logs/actions`, `/audit-logs/actors`) live on a separate controller, `UserAuditLogAPI`, and are
documented in [`./sessions-and-audit-logs.md`](./sessions-and-audit-logs.md) rather than here. Only
the per-user convenience view
(`/api/admin/users/{userId}/audit-logs`, declared on `AdminUserAPI`) is covered below.

---

## Roles

Source: `user/enums/Role.java`. Four values; the CHECK constraint on `users_tbl.role` is re-synced
from this enum at every boot by `user/configs/UserRoleConstraintInitializer.java`, because Hibernate's
`ddl-auto=update` never refreshes a CHECK constraint it generated earlier.

| Role | Authorities from the role itself | Seeded into `extraPermissions` on first transition |
|---|---|---|
| `GUEST` | `ROLE_GUEST` only — `Role.GUEST(Set.of())` | none |
| `EMPLOYEE` | `ROLE_EMPLOYEE` only — `Role.EMPLOYEE(Set.of())` | `EMPLOYEE_DEFAULT_PERMISSIONS` (29 strings) |
| `TEACHER` | `ROLE_TEACHER` only — `Role.TEACHER(Set.of())` | `TEACHER_DEFAULT_PERMISSIONS` (2 strings) |
| `ADMIN` | `ROLE_ADMIN` **plus every `Permission`** — `Role.ADMIN(EnumSet.allOf(Permission.class))` | none |

`User.getAuthorities()` returns `role.getAuthorities()` ∪ `extraPermissions`. That is why EMPLOYEE and
TEACHER permissions are fully editable (they are per-user rows in `user_permissions`) while ADMIN
permissions are not (they come from the enum and cannot be revoked — see
`ADMIN_PERMISSIONS_LOCKED` below).

### Seeded default permission sets

`User.applyRoleDefaults()` copies the role's default set into `extraPermissions` **only when
`extraPermissions` is currently empty**, so an admin who has already curated a user's permissions is
never overwritten by a later role change. It is a no-op for GUEST and ADMIN (both have an empty
default set). It is called by `createUserAsAdmin`, by `changeRole`, and by the role branch of
`updateUserAsAdmin`.

`EMPLOYEE_DEFAULT_PERMISSIONS` — READ + CREATE + UPDATE across the content resources, plus the maqam
teacher-roster and physical-media import rights. No REMOVE and no DELETE:

```text
audio:read       audio:create       audio:update
video:read       video:create       video:update
image:read       image:create       image:update
text:read        text:create        text:update
category:read    category:create    category:update
person:read      person:create      person:update
project:read     project:create     project:update
maqam:read       maqam:create       maqam:update
maqam:teacher_manage
physical_media:read   physical_media:create
physical_media:update physical_media:import
```

`TEACHER_DEFAULT_PERMISSIONS` — deliberately narrow, only what a maqam-panel teacher needs:

```text
maqam:read   maqam:vote
```

Seeding only fires on creation and on role transition, so two `ApplicationReadyEvent` initializers in
`user/configs/` top up already-provisioned EMPLOYEE rows when the seed set grows. Both run one
`JdbcTemplate.update` per permission — `INSERT INTO user_permissions (user_id, permission) SELECT
u.user_id, ? FROM users_tbl u WHERE u.role = ? ON CONFLICT ON CONSTRAINT
uk_user_permissions_user_perm DO NOTHING`, with the permission string and `EMPLOYEE` bound as the two
parameters — which makes them idempotent across boots:

| Initializer | Permissions backfilled |
|---|---|
| `EmployeeMaqamTeacherManageBackfillInitializer` | `maqam:teacher_manage` |
| `EmployeePhysicalMediaPermissionBackfillInitializer` | `physical_media:read`, `physical_media:create`, `physical_media:update`, `physical_media:import` |

Because they insert unconditionally for every EMPLOYEE row, a permission an admin had deliberately
revoked from one employee is restored on the next boot.

## Permission catalog

Source: `user/enums/Permission.java`. 66 constants, all shaped `<resource>:<action>`. ADMIN holds
every one of them through the role. The "EMPLOYEE seed" and "TEACHER seed" columns mark membership in
the two default sets above — an admin can grant or revoke any of them per user afterwards, so the
columns describe the starting point, not a permanent state.

`GET /api/admin/users/catalog/permissions` returns exactly the string column of this table.

| Constant | String value | ADMIN | EMPLOYEE seed | TEACHER seed |
|---|---|---|---|---|
| `AUDIO_READ` | `audio:read` | yes | yes | — |
| `AUDIO_CREATE` | `audio:create` | yes | yes | — |
| `AUDIO_UPDATE` | `audio:update` | yes | yes | — |
| `AUDIO_REMOVE` | `audio:remove` | yes | — | — |
| `AUDIO_DELETE` | `audio:delete` | yes | — | — |
| `VIDEO_READ` | `video:read` | yes | yes | — |
| `VIDEO_CREATE` | `video:create` | yes | yes | — |
| `VIDEO_UPDATE` | `video:update` | yes | yes | — |
| `VIDEO_REMOVE` | `video:remove` | yes | — | — |
| `VIDEO_DELETE` | `video:delete` | yes | — | — |
| `IMAGE_READ` | `image:read` | yes | yes | — |
| `IMAGE_CREATE` | `image:create` | yes | yes | — |
| `IMAGE_UPDATE` | `image:update` | yes | yes | — |
| `IMAGE_REMOVE` | `image:remove` | yes | — | — |
| `IMAGE_DELETE` | `image:delete` | yes | — | — |
| `TEXT_READ` | `text:read` | yes | yes | — |
| `TEXT_CREATE` | `text:create` | yes | yes | — |
| `TEXT_UPDATE` | `text:update` | yes | yes | — |
| `TEXT_REMOVE` | `text:remove` | yes | — | — |
| `TEXT_DELETE` | `text:delete` | yes | — | — |
| `CATEGORY_READ` | `category:read` | yes | yes | — |
| `CATEGORY_CREATE` | `category:create` | yes | yes | — |
| `CATEGORY_UPDATE` | `category:update` | yes | yes | — |
| `CATEGORY_REMOVE` | `category:remove` | yes | — | — |
| `CATEGORY_DELETE` | `category:delete` | yes | — | — |
| `PERSON_READ` | `person:read` | yes | yes | — |
| `PERSON_CREATE` | `person:create` | yes | yes | — |
| `PERSON_UPDATE` | `person:update` | yes | yes | — |
| `PERSON_REMOVE` | `person:remove` | yes | — | — |
| `PERSON_DELETE` | `person:delete` | yes | — | — |
| `PROJECT_READ` | `project:read` | yes | yes | — |
| `PROJECT_CREATE` | `project:create` | yes | yes | — |
| `PROJECT_UPDATE` | `project:update` | yes | yes | — |
| `PROJECT_REMOVE` | `project:remove` | yes | — | — |
| `PROJECT_DELETE` | `project:delete` | yes | — | — |
| `USER_READ` | `user:read` | yes | — | — |
| `USER_CREATE` | `user:create` | yes | — | — |
| `USER_UPDATE` | `user:update` | yes | — | — |
| `USER_REMOVE` | `user:remove` | yes | — | — |
| `USER_DELETE` | `user:delete` | yes | — | — |
| `WARNING_READ` | `warning:read` | yes | — | — |
| `WARNING_CREATE` | `warning:create` | yes | — | — |
| `WARNING_UPDATE` | `warning:update` | yes | — | — |
| `WARNING_REMOVE` | `warning:remove` | yes | — | — |
| `WARNING_DELETE` | `warning:delete` | yes | — | — |
| `CORRECTION_READ` | `correction:read` | yes | — | — |
| `CORRECTION_UPDATE` | `correction:update` | yes | — | — |
| `CORRECTION_REMOVE` | `correction:remove` | yes | — | — |
| `MAQAM_READ` | `maqam:read` | yes | yes | yes |
| `MAQAM_CREATE` | `maqam:create` | yes | yes | — |
| `MAQAM_UPDATE` | `maqam:update` | yes | yes | — |
| `MAQAM_REMOVE` | `maqam:remove` | yes | — | — |
| `MAQAM_DELETE` | `maqam:delete` | yes | — | — |
| `MAQAM_VOTE` | `maqam:vote` | yes | — | yes |
| `MAQAM_TEACHER_MANAGE` | `maqam:teacher_manage` | yes | yes | — |
| `PHYSICAL_MEDIA_READ` | `physical_media:read` | yes | yes | — |
| `PHYSICAL_MEDIA_CREATE` | `physical_media:create` | yes | yes | — |
| `PHYSICAL_MEDIA_UPDATE` | `physical_media:update` | yes | yes | — |
| `PHYSICAL_MEDIA_REMOVE` | `physical_media:remove` | yes | — | — |
| `PHYSICAL_MEDIA_DELETE` | `physical_media:delete` | yes | — | — |
| `PHYSICAL_MEDIA_IMPORT` | `physical_media:import` | yes | yes | — |
| `PHYSICAL_MEDIA_TYPE_MANAGE` | `physical_media:type_manage` | yes | — | — |
| `KHI_LOGO_READ` | `khi_logo:read` | yes | — | — |
| `KHI_LOGO_CREATE` | `khi_logo:create` | yes | — | — |
| `KHI_LOGO_UPDATE` | `khi_logo:update` | yes | — | — |
| `KHI_LOGO_DELETE` | `khi_logo:delete` | yes | — | — |

Grant and revoke requests are checked against `AdminUserService.KNOWN_PERMISSIONS`, a `Set.copyOf` of
every `Permission.getPermission()` value computed once at class load. Anything outside it is rejected
with `UNKNOWN_PERMISSION` — see the grant endpoint.

## When a grant takes effect

Grants apply on the **next request**, not only after the user logs in again. Three pieces make that
true:

1. `SecurityConfig` sets `SessionCreationPolicy.STATELESS`. With no HTTP session there is no
   `SecurityContext` cached between requests, so an `Authentication` built at login time is never
   replayed on later requests. The config comment states the failure this avoids: without STATELESS,
   "role/permission grants only take effect after logout+login".
2. `JWTAuthenticationFilter` calls `userDetailsService.loadUserByUsername(username)` on **every**
   filtered request and rebuilds the authority list from the freshly-loaded `User` — role authorities
   ∪ `extraPermissions` — rather than reading authorities out of the JWT claims.
3. That lookup is `@Cacheable(value = "users:details", key = "#username")` against the Caffeine cache
   configured in `platform/config/CacheConfig.java` (`build("users:details", 500, 1)` — 500 entries,
   1-minute TTL). Every method in `AdminUserService` that can change what the cached `UserDetails`
   holds is annotated `@CacheEvict(value = "users:details", allEntries = true)`, so the stale entry
   is dropped at the moment of the change instead of waiting out the TTL.

Ten of the eleven mutating methods carry that annotation. The one **without** it is `forceLogoutAll`
— it changes `sessions` rows, not authorities, so the cached `UserDetails` stays correct.

The JWT itself is not reissued by any endpoint here. Changing a user's role does not invalidate their
existing token; the next request simply resolves a different authority set.

---

### `GET /api/admin/users`

Every row in `users_tbl`, mapped to the admin view.

**Authority:** `user:read` (plus class-level `hasRole('ADMIN')`)

**Query parameters** — none. `list()` declares only `Authentication` and `HttpServletRequest`; there
is no `@ModelAttribute` filter class, no `page`/`size`, no `sort` and no search parameter. The
service calls `userRepository.findAll()` and maps the whole table. The response is a plain JSON
array, not a Spring `Page` envelope.

**Response** `200 OK`

```json
[
  {
    "userId": 1,
    "username": "akar",
    "name": "Akar Arkan",
    "email": "akar.arkanf19@gmail.com",
    "role": "ADMIN",
    "isActivated": true,
    "isLocked": false,
    "failedAttempts": 0,
    "extraPermissions": [],
    "effectiveAuthorities": [
      "ROLE_ADMIN",
      "audio:create",
      "audio:delete",
      "audio:read",
      "audio:remove",
      "audio:update",
      "…",
      "warning:update"
    ],
    "createdAt": "2026-01-04T07:22:10.118Z",
    "updatedAt": "2026-08-26T08:41:02.774Z",
    "id": 1
  },
  {
    "userId": 42,
    "username": "sara_h",
    "name": "Sara Hama",
    "email": "sara.hama@example.com",
    "profileImage": "https://khi-archive-platform.s3.us-east-1.amazonaws.com/khi-archive-platform-folders/user_profile_images/9c1f-…-sara.png",
    "role": "EMPLOYEE",
    "isActivated": true,
    "isLocked": false,
    "failedAttempts": 0,
    "extraPermissions": ["audio:create", "audio:read", "audio:update"],
    "effectiveAuthorities": ["ROLE_EMPLOYEE", "audio:create", "audio:read", "audio:update"],
    "createdAt": "2026-08-20T09:12:03.441Z",
    "updatedAt": "2026-08-26T11:04:57.882Z",
    "id": 42
  }
]
```

`UserAdminDTO` fields: `userId`, `username`, `name`, `email`, `profileImage`, `role`, `isActivated`,
`isLocked`, `lockTime`, `failedAttempts`, `extraPermissions`, `effectiveAuthorities`, `createdAt`,
`updatedAt`, plus `id` — a `@JsonProperty("id")` alias for `userId` so frontends expecting the common
`id` name work unchanged. `spring.jackson.default-property-inclusion=non_null` drops null fields, so
`profileImage` and `lockTime` are simply absent on the first element above. `profileImage` is echoed
verbatim from `users_tbl.profile_image` and is not normalized by this API — self-service uploads
through `UserProfileService` store an absolute S3 URL, while `UserService.storeProfileImage` stores a
relative local path, so both forms can appear. `failedAttempts` is a
primitive `int` and is always present. `extraPermissions` and `effectiveAuthorities` are built from
`TreeSet`s, so both arrays are alphabetically sorted (uppercase `ROLE_*` sorts before the lowercase
permission strings). The `password` column is `@JsonIgnore` on the entity and is not on this DTO at
all.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `401` | `TOKEN_MISSING` | No `khi_auth_token` cookie and no `Authorization` header |
| `401` | `TOKEN_EXPIRED` / `TOKEN_REVOKED` / `TOKEN_MALFORMED` / `TOKEN_INVALID_SIGNATURE` / `TOKEN_INVALID` | Token present but rejected by the JWT filter |
| `403` | `ACCESS_DENIED` | Caller is not ADMIN, or lacks `user:read`; `details.requiredAuthority` echoes it back |
| `405` | `METHOD_NOT_ALLOWED` | Method other than `GET`/`POST` on `/api/admin/users` |
| `500` | `DATABASE_ERROR` | `DataAccessException` while reading `users_tbl` |
| `500` | `INTERNAL_SERVER_ERROR` | Anything else unhandled |

**Example**

```bash
curl -s "{{BASE_URL}}/api/admin/users" \
  -H "Cookie: khi_auth_token=$TOKEN"
```

**Notes** — listing is deliberately **not** audited. The service comment explains why: every admin
opens this on page load, and the `LIST` rows would drown out genuine state changes. `UserAuditAction`
declares a `LIST` value, but no endpoint in this file writes it.

---

### `POST /api/admin/users`

Admin-driven account creation.

**Authority:** `user:create` (plus class-level `hasRole('ADMIN')`)

**Request body** — `UserCreateRequestDTO`, `@Valid`, `application/json`

| Field | Type | Required | Constraints / default |
|---|---|---|---|
| `name` | string | yes | `@NotBlank`, max 120 chars |
| `username` | string | yes | `@NotBlank`, 3–80 chars, must match `^[A-Za-z0-9_]+$` |
| `email` | string | yes | `@NotBlank`, `@Email` against `^[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,10}$`, max 160 chars |
| `password` | string | yes | `@NotBlank`, 6–128 chars |
| `role` | enum | no | `GUEST` \| `EMPLOYEE` \| `TEACHER` \| `ADMIN`; defaults to `GUEST` when omitted |
| `isActivated` | boolean | no | defaults to `true` when omitted or null |

```json
{
  "name": "Sara Hama",
  "username": "sara_h",
  "email": "Sara.Hama@Example.com",
  "password": "archive2026",
  "role": "EMPLOYEE",
  "isActivated": true
}
```

Server-side handling on top of the annotations, in `AdminUserService.createUserAsAdmin`:

- `UserValidator.validateAndNormalizeEmail(email, targetRole)` trims and lower-cases the address,
  re-checks the regex and the 160-char cap, and rejects 27 disposable-mail domains
  (`mailinator.com`, `yopmail.com`, `10minutemail.com`, …). The DNS MX/A/AAAA deliverability check
  runs **only when the target role is `GUEST`** (`app.email.verify-mx`, default `true`); EMPLOYEE,
  TEACHER and ADMIN accounts bypass it so corporate domains are not second-guessed.
- `UserValidator.validatePassword` enforces 6–128 characters. No complexity rule is applied.
- Username and the normalized email must both be unused.
- The row is saved with `provider = "local"`, `failedAttempts = 0`, `isLocked = false`,
  `createdAt = updatedAt = now`, and `passwordExpiryDate = now + 90 days`.
- `applyRoleDefaults()` runs before the save, so an EMPLOYEE starts with the 29 seeded strings and a
  TEACHER with 2.

**Response** `200 OK` — `UserAdminDTO`, the same shape as the list element above. Note the status is
`200`, not `201`; the handler returns `ResponseEntity.ok(...)`.

```json
{
  "userId": 42,
  "username": "sara_h",
  "name": "Sara Hama",
  "email": "sara.hama@example.com",
  "role": "EMPLOYEE",
  "isActivated": true,
  "isLocked": false,
  "failedAttempts": 0,
  "extraPermissions": [
    "audio:create", "audio:read", "audio:update",
    "category:create", "category:read", "category:update",
    "image:create", "image:read", "image:update",
    "maqam:create", "maqam:read", "maqam:teacher_manage", "maqam:update",
    "person:create", "person:read", "person:update",
    "physical_media:create", "physical_media:import",
    "physical_media:read", "physical_media:update",
    "project:create", "project:read", "project:update",
    "text:create", "text:read", "text:update",
    "video:create", "video:read", "video:update"
  ],
  "effectiveAuthorities": [
    "ROLE_EMPLOYEE",
    "audio:create", "audio:read", "audio:update",
    "category:create", "category:read", "category:update",
    "image:create", "image:read", "image:update",
    "maqam:create", "maqam:read", "maqam:teacher_manage", "maqam:update",
    "person:create", "person:read", "person:update",
    "physical_media:create", "physical_media:import",
    "physical_media:read", "physical_media:update",
    "project:create", "project:read", "project:update",
    "text:create", "text:read", "text:update",
    "video:create", "video:read", "video:update"
  ],
  "createdAt": "2026-08-26T11:02:41.006Z",
  "updatedAt": "2026-08-26T11:02:41.006Z",
  "id": 42
}
```

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `VALIDATION_ERROR` | Bean validation failed; `details` maps each field to its message |
| `400` | `BAD_REQUEST` | `UserValidator` rejected the input — disposable domain, non-deliverable domain (GUEST only), bad email format, password shorter than 6 or longer than 128 |
| `400` | `JSON_PARSE_ERROR` | Body is not valid JSON, or `role` is not one of the four enum names |
| `401` | `TOKEN_MISSING` | No `khi_auth_token` cookie and no `Authorization` header |
| `403` | `ACCESS_DENIED` | Caller is not ADMIN, or lacks `user:create` |
| `409` | `USER_ALREADY_EXISTS` | Username is taken, or the normalized email is already registered |
| `409` | `CONFLICT` | A database constraint blocked the insert (`uk_users_username`, `uk_users_email`, the `users_tbl_role_check` CHECK) |
| `415` | `UNSUPPORTED_MEDIA_TYPE` | Body sent as something other than `application/json` |
| `500` | `DATABASE_ERROR` | `DataAccessException` during the insert |

**Example**

```bash
curl -s -X POST "{{BASE_URL}}/api/admin/users" \
  -H "Cookie: khi_auth_token=$TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
        "name": "Sara Hama",
        "username": "sara_h",
        "email": "sara.hama@example.com",
        "password": "archive2026",
        "role": "EMPLOYEE",
        "isActivated": true
      }'
```

**Notes** — audited as one `CREATE` row. `previousRole` is null, `newRole` is the assigned role,
`permissionsChanged` holds the comma-joined seeded set, and `details` reads
`Created user 'sara_h' (id=42) name='Sara Hama' email=sara.hama@example.com role=EMPLOYEE
activated=true seededPermissions=[…]` (HTML-escaped before storage). Evicts the whole
`users:details` cache.

---

### `GET /api/admin/users/{userId}`

One account by primary key.

**Authority:** `user:read` (plus class-level `hasRole('ADMIN')`)

**Path parameters**

| Name | Type | Description |
|---|---|---|
| `userId` | long | `users_tbl.user_id` of the account to fetch |

**Query parameters** — none.

**Response** `200 OK` — a single `UserAdminDTO` (shape as above).

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `TYPE_MISMATCH` | `{userId}` is not a valid `Long`; `details.rejectedValue` echoes what was sent |
| `401` | `TOKEN_MISSING` | No `khi_auth_token` cookie and no `Authorization` header |
| `403` | `ACCESS_DENIED` | Caller is not ADMIN, or lacks `user:read` |
| `404` | `USER_NOT_FOUND` | No row with that id — message is `User not found: id=<userId>` |
| `500` | `DATABASE_ERROR` | `DataAccessException` while reading the row |

**Example**

```bash
curl -s "{{BASE_URL}}/api/admin/users/42" \
  -H "Cookie: khi_auth_token=$TOKEN"
```

**Notes** — unlike the list endpoint, this **is** audited: one `READ` row per call with
`details = "Read user record"`. A UI that polls a user detail page will generate one audit row per
poll.

---

### `PUT /api/admin/users/{userId}`

Update arbitrary fields on an account. Only the fields present in the body are touched.

**Authority:** `user:update` (plus class-level `hasRole('ADMIN')`)

**Path parameters**

| Name | Type | Description |
|---|---|---|
| `userId` | long | Account to update |

**Request body** — `UserUpdateRequestDTO`, `@Valid`, `application/json`. Every field is optional.

| Field | Type | Constraints | Effect |
|---|---|---|---|
| `name` | string | max 120 chars | Sets the display name when different from the current value |
| `username` | string | 3–80 chars, must match `^$\|^[A-Za-z0-9_]+$` | Renames the account; must not already be taken |
| `email` | string | `@Email` regex, max 160 chars | Normalized then set; must not already be registered |
| `password` | string | 6–128 chars | Re-encodes the password and pushes `passwordExpiryDate` to now + 90 days |
| `role` | enum | `GUEST` \| `EMPLOYEE` \| `TEACHER` \| `ADMIN` | Changes the role and runs `applyRoleDefaults()` |
| `isActivated` | boolean | — | Enables or disables the account |
| `removeProfileImage` | boolean | — | **Bound but unused by this endpoint** — `updateUserAsAdmin` never reads it, so sending it has no effect here |

```json
{
  "name": "Sara Hama Rasul",
  "email": "sara.rasul@example.com",
  "role": "TEACHER"
}
```

**Response** `200 OK` — the updated `UserAdminDTO`.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `TYPE_MISMATCH` | `{userId}` is not a valid `Long` |
| `400` | `VALIDATION_ERROR` | Bean validation failed on one or more fields |
| `400` | `BAD_REQUEST` | `UserValidator` rejected the new email or password |
| `400` | `JSON_PARSE_ERROR` | Body is not valid JSON, or `role` is not one of the four enum names |
| `401` | `TOKEN_MISSING` | No `khi_auth_token` cookie and no `Authorization` header |
| `403` | `ACCESS_DENIED` | Caller is not ADMIN, or lacks `user:update` |
| `404` | `USER_NOT_FOUND` | No row with that id |
| `409` | `USER_ALREADY_EXISTS` | The requested username or email belongs to another account |
| `409` | `SELF_DEACTIVATE` | The caller passed `"isActivated": false` for their own account |
| `409` | `SELF_DEMOTION` | The caller is ADMIN and tried to move their own account off ADMIN |
| `409` | `CONFLICT` | A database constraint blocked the update |
| `500` | `DATABASE_ERROR` | `DataAccessException` during the update |

`SELF_DEACTIVATE` and `SELF_DEMOTION` are not `ErrorCode` constants — they are the `errorCode` field
of `IllegalAdminOperationException`, which `GlobalExceptionHandler` copies straight into the `error`
field of the envelope with `category: "CONFLICT"`. The same is true of `SELF_LOCK`,
`SELF_FORCE_LOGOUT`, `SELF_DELETE`, `SELF_USER_MGMT_REVOKE`, `ADMIN_PERMISSIONS_LOCKED` and
`LAST_ADMIN` further down.

**Example**

```bash
curl -s -X PUT "{{BASE_URL}}/api/admin/users/42" \
  -H "Cookie: khi_auth_token=$TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"Sara Hama Rasul","role":"TEACHER"}'
```

**Notes** — when nothing actually changes the method returns the current DTO and writes **no** audit
row and performs **no** save. Otherwise it writes one `UPDATE` row whose `details` is a per-field
diff, e.g. `Updated user fields. name='Sara Hama' -&gt; 'Sara Hama Rasul'; role=EMPLOYEE -&gt;
TEACHER` — `details` is passed through `HtmlUtils.htmlEscape` before storage, which is why `->`
appears as `-&gt;`. `previousRole` and `newRole` are populated only when the role actually changed;
the action stays `UPDATE` even then, so a role edit made here is **not** a `ROLE_CHANGE` row — only
`PUT …/role` writes that action. Passwords are never recorded; a reset shows up as
`password=(reset by admin)`. A transition **into** EMPLOYEE from any other role adds a
`seededPermissions=[…]` entry listing the user's whole `extraPermissions` set as it stands after
`applyRoleDefaults()` ran — which equals the seeded default set only when the user had no extras
beforehand.

---

### `PUT /api/admin/users/{userId}/role`

Change only the role.

**Authority:** `user:update` (plus class-level `hasRole('ADMIN')`)

**Path parameters**

| Name | Type | Description |
|---|---|---|
| `userId` | long | Account whose role changes |

**Request body** — `RoleChangeRequestDTO`, `@Valid`

| Field | Type | Required | Description |
|---|---|---|---|
| `role` | string | yes | `@NotBlank`. Parsed with `Role.valueOf(role.trim().toUpperCase(Locale.ROOT))`, so input is case-insensitive and surrounding whitespace is tolerated |

```json
{ "role": "employee" }
```

**Response** `200 OK` — the updated `UserAdminDTO`.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `TYPE_MISMATCH` | `{userId}` is not a valid `Long` |
| `400` | `VALIDATION_ERROR` | `role` missing or blank (`role is required`) |
| `400` | `BAD_REQUEST` | `role` is not a `Role` name — message lists the allowed values |
| `401` | `TOKEN_MISSING` | No `khi_auth_token` cookie and no `Authorization` header |
| `403` | `ACCESS_DENIED` | Caller is not ADMIN, or lacks `user:update` |
| `404` | `USER_NOT_FOUND` | No row with that id |
| `409` | `SELF_DEMOTION` | Caller is ADMIN and targeted their own account with a non-ADMIN role |
| `409` | `CONFLICT` | The `users_tbl_role_check` CHECK constraint rejected the value (should not happen — `UserRoleConstraintInitializer` re-syncs it at boot) |
| `500` | `DATABASE_ERROR` | `DataAccessException` during the update |

**Example**

```bash
curl -s -X PUT "{{BASE_URL}}/api/admin/users/42/role" \
  -H "Cookie: khi_auth_token=$TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"role":"EMPLOYEE"}'
```

**Notes** — requesting the role the user already holds is a no-op: the current DTO is returned with
no save and no audit row. On a real change the service calls `applyRoleDefaults()` (which seeds only
if `extraPermissions` is empty), then writes one `ROLE_CHANGE` row with `previousRole`, `newRole` and
`details = "Role changed from GUEST to EMPLOYEE"`. Evicts `users:details`.

---

### `POST /api/admin/users/{userId}/permissions`

Grant extra per-user permissions on top of the role.

**Authority:** `user:update` (plus class-level `hasRole('ADMIN')`)

**Path parameters**

| Name | Type | Description |
|---|---|---|
| `userId` | long | Account receiving the grants |

**Request body** — `PermissionsChangeRequestDTO`, `@Valid`

| Field | Type | Required | Description |
|---|---|---|---|
| `permissions` | string[] (a `Set`) | yes | `@NotEmpty` — "permissions list cannot be empty". Each entry is trimmed and lower-cased, then must be present in the `Permission` catalog |

```json
{ "permissions": ["audio:delete", "VIDEO:DELETE ", "physical_media:remove"] }
```

Because each entry is normalized with `trim().toLowerCase(Locale.ROOT)` before the catalog check, the
three values above are accepted and stored as `audio:delete`, `video:delete`,
`physical_media:remove`. Null and empty-after-trim entries are silently skipped.

**Response** `200 OK` — the updated `UserAdminDTO`; `extraPermissions` and `effectiveAuthorities`
already reflect the grant.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `TYPE_MISMATCH` | `{userId}` is not a valid `Long` |
| `400` | `VALIDATION_ERROR` | `permissions` absent or empty |
| `400` | `UNKNOWN_PERMISSION` | One or more strings are not in the `Permission` catalog |
| `400` | `BAD_REQUEST` | The set survived binding but is null/empty at the service layer |
| `401` | `TOKEN_MISSING` | No `khi_auth_token` cookie and no `Authorization` header |
| `403` | `ACCESS_DENIED` | Caller is not ADMIN, or lacks `user:update` |
| `404` | `USER_NOT_FOUND` | No row with that id |
| `409` | `ADMIN_PERMISSIONS_LOCKED` | The target's role is `ADMIN` — admins already hold every permission through the role, and per-user extras would survive a later demotion |
| `500` | `DATABASE_ERROR` | `DataAccessException` while writing `user_permissions` |

An `UNKNOWN_PERMISSION` response carries the offending strings and a pointer to the catalog:

```json
{
  "timestamp": "2026-08-26T11:09:12.554Z",
  "status": 400,
  "error": "UNKNOWN_PERMISSION",
  "category": "VALIDATION",
  "message": "Unknown permission(s): [audio:destroy]. Use GET /api/admin/users/catalog/permissions for the full catalog.",
  "hint": "Use the catalog endpoint to discover valid permission codes.",
  "path": "/api/admin/users/42/permissions",
  "details": {
    "unknown": ["audio:destroy"],
    "catalog": "/api/admin/users/catalog/permissions"
  }
}
```

Note that the strings echoed in `details.unknown` are the **raw** values as sent, not the normalized
ones, so the frontend can highlight the exact input the operator typed. The whole request is
rejected if any single entry is unknown — grants are all-or-nothing.

**Example**

```bash
curl -s -X POST "{{BASE_URL}}/api/admin/users/42/permissions" \
  -H "Cookie: khi_auth_token=$TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"permissions":["audio:delete","video:delete"]}'
```

**Notes**

- If every requested permission is already held the method returns the current DTO with no save and
  no audit row.
- **GUEST auto-promotion:** granting any permission to a `GUEST` also sets the role to `EMPLOYEE`,
  because a GUEST has no baseline authorities and the grant would otherwise be their only privilege.
  This writes a **second** audit row — a `ROLE_CHANGE` with
  `details = "Auto-promoted from GUEST to EMPLOYEE on permission grant"` — alongside the
  `GRANT_PERMISSIONS` row, whose `details` gains the suffix `(auto-promoted GUEST -> EMPLOYEE)`.
  `applyRoleDefaults()` is not invoked on this path, so the EMPLOYEE seed set is **not** added; the
  user ends up with exactly the permissions that were granted.
- The `GRANT_PERMISSIONS` audit row records only the newly-added strings in `permissionsChanged`,
  not the full resulting set.
- Evicts `users:details`, so the grant is live on the target's next request.

---

### `DELETE /api/admin/users/{userId}/permissions`

Revoke previously-granted extra permissions.

**Authority:** `user:update` (plus class-level `hasRole('ADMIN')`)

**Path parameters**

| Name | Type | Description |
|---|---|---|
| `userId` | long | Account losing the grants |

**Request body** — `PermissionsChangeRequestDTO`, identical shape to the grant endpoint. This is a
`DELETE` **with a JSON body**, so the request must carry `Content-Type: application/json`.

```json
{ "permissions": ["audio:delete", "video:delete"] }
```

**Response** `200 OK` — the updated `UserAdminDTO`.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `TYPE_MISMATCH` | `{userId}` is not a valid `Long` |
| `400` | `VALIDATION_ERROR` | `permissions` absent or empty |
| `400` | `UNKNOWN_PERMISSION` | One or more strings are not in the `Permission` catalog |
| `401` | `TOKEN_MISSING` | No `khi_auth_token` cookie and no `Authorization` header |
| `403` | `ACCESS_DENIED` | Caller is not ADMIN, or lacks `user:update` |
| `404` | `USER_NOT_FOUND` | No row with that id |
| `409` | `ADMIN_PERMISSIONS_LOCKED` | The target's role is `ADMIN` — ADMIN authorities come from the role, not from `user_permissions`, so a revoke here would silently do nothing. Change the role to EMPLOYEE first |
| `409` | `SELF_USER_MGMT_REVOKE` | The caller targeted their own account and the revoke set contains any `user:*` string |
| `500` | `DATABASE_ERROR` | `DataAccessException` while writing `user_permissions` |

**Example**

```bash
curl -s -X DELETE "{{BASE_URL}}/api/admin/users/42/permissions" \
  -H "Cookie: khi_auth_token=$TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"permissions":["audio:delete"]}'
```

**Notes** — the request is intersected with what the user actually holds, so revoking a permission
they never had is a no-op (current DTO, no save, no audit row). The `SELF_USER_MGMT_REVOKE` guard is
checked **after** that intersection, so it only fires when a `user:*` string would really have been
removed from the caller's own account. On success one `REVOKE_PERMISSIONS` row is written with the
removed strings in `permissionsChanged` and `details = "Revoked permissions: [audio:delete]"`.
Evicts `users:details`.

---

### `POST /api/admin/users/{userId}/activate`

Enable a disabled account.

**Authority:** `user:update` (plus class-level `hasRole('ADMIN')`)

**Path parameters**

| Name | Type | Description |
|---|---|---|
| `userId` | long | Account to enable |

**Request body** — none.

**Response** `200 OK` — the updated `UserAdminDTO` with `isActivated: true`.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `TYPE_MISMATCH` | `{userId}` is not a valid `Long` |
| `401` | `TOKEN_MISSING` | No `khi_auth_token` cookie and no `Authorization` header |
| `403` | `ACCESS_DENIED` | Caller is not ADMIN, or lacks `user:update` |
| `404` | `USER_NOT_FOUND` | No row with that id |
| `500` | `DATABASE_ERROR` | `DataAccessException` during the update |

**Example**

```bash
curl -s -X POST "{{BASE_URL}}/api/admin/users/42/activate" \
  -H "Cookie: khi_auth_token=$TOKEN"
```

**Notes** — already-active is a no-op (no save, no audit row). Otherwise one `ACTIVATE` row is
written with `details = "Activated user 'sara_h' (id=42) isActivated: false -&gt; true"`.
`isActivated` backs `UserDetails.isEnabled()`, so a disabled account is refused by the
authentication provider. Evicts `users:details`.

---

### `POST /api/admin/users/{userId}/deactivate`

Disable an account without deleting it.

**Authority:** `user:update` (plus class-level `hasRole('ADMIN')`)

**Path parameters**

| Name | Type | Description |
|---|---|---|
| `userId` | long | Account to disable |

**Request body** — none.

**Response** `200 OK` — the updated `UserAdminDTO` with `isActivated: false`.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `TYPE_MISMATCH` | `{userId}` is not a valid `Long` |
| `401` | `TOKEN_MISSING` | No `khi_auth_token` cookie and no `Authorization` header |
| `403` | `ACCESS_DENIED` | Caller is not ADMIN, or lacks `user:update` |
| `404` | `USER_NOT_FOUND` | No row with that id |
| `409` | `SELF_DEACTIVATE` | The caller targeted their own account — "You cannot deactivate your own account. Ask another admin to do it." |
| `500` | `DATABASE_ERROR` | `DataAccessException` during the update |

**Example**

```bash
curl -s -X POST "{{BASE_URL}}/api/admin/users/42/deactivate" \
  -H "Cookie: khi_auth_token=$TOKEN"
```

**Notes** — already-inactive is a no-op, and the self-guard is evaluated only when the flag would
really flip. Writes one `DEACTIVATE` row. This is the nearest thing to a soft-remove for a user; the
row and all its `user_permissions` stay intact. Evicts `users:details`.

---

### `POST /api/admin/users/{userId}/lock`

Lock the account and stamp `lockTime`.

**Authority:** `user:update` (plus class-level `hasRole('ADMIN')`)

**Path parameters**

| Name | Type | Description |
|---|---|---|
| `userId` | long | Account to lock |

**Request body** — none.

**Response** `200 OK` — `UserAdminDTO` with `isLocked: true` and a populated `lockTime`.

```json
{
  "userId": 42,
  "username": "sara_h",
  "name": "Sara Hama",
  "email": "sara.hama@example.com",
  "role": "EMPLOYEE",
  "isActivated": true,
  "isLocked": true,
  "lockTime": "2026-08-26T11:14:20.331Z",
  "failedAttempts": 0,
  "extraPermissions": ["audio:create", "audio:read", "audio:update"],
  "effectiveAuthorities": ["ROLE_EMPLOYEE", "audio:create", "audio:read", "audio:update"],
  "createdAt": "2026-08-20T09:12:03.441Z",
  "updatedAt": "2026-08-26T11:14:20.331Z",
  "id": 42
}
```

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `TYPE_MISMATCH` | `{userId}` is not a valid `Long` |
| `401` | `TOKEN_MISSING` | No `khi_auth_token` cookie and no `Authorization` header |
| `403` | `ACCESS_DENIED` | Caller is not ADMIN, or lacks `user:update` |
| `404` | `USER_NOT_FOUND` | No row with that id |
| `409` | `SELF_LOCK` | The caller targeted their own account |
| `500` | `DATABASE_ERROR` | `DataAccessException` during the update |

**Example**

```bash
curl -s -X POST "{{BASE_URL}}/api/admin/users/42/lock" \
  -H "Cookie: khi_auth_token=$TOKEN"
```

**Notes**

- The self-guard runs **before** the already-locked short-circuit, so an admin locking themselves
  gets `SELF_LOCK` even when the account is already locked. Locking an already-locked other account
  is a no-op.
- Audited as an `UPDATE` row (not a dedicated action — `UserAuditAction` has no `LOCK` value) with
  `details = "Locked user 'sara_h' (id=42) isLocked: false -&gt; true; lockTime=…"`.
- **The lock is time-boxed, not permanent.** `User.isAccountNonLocked()` returns true again once
  `SecurityConstants.LOCK_DURATION_MINUTES` (currently **1 minute**) has elapsed since `lockTime`,
  and `UserService.loadUserByUsername` calls `unlockIfLockExpired(...)`, which clears `isLocked`,
  `lockTime` and `failedAttempts` on the next lookup after that window. Whether an admin-initiated
  lock is meant to share the failed-login lock window, or to be indefinite, is
  _Not documented in source._ — `AdminUserService.lock` sets the same two columns the failed-login
  path sets, and nothing distinguishes the two. Use `/deactivate` when the intent is to keep the
  account out indefinitely.
- Evicts `users:details`.

---

### `POST /api/admin/users/{userId}/unlock`

Unlock the account and clear the failed-login counter.

**Authority:** `user:update` (plus class-level `hasRole('ADMIN')`)

**Path parameters**

| Name | Type | Description |
|---|---|---|
| `userId` | long | Account to unlock |

**Request body** — none.

**Response** `200 OK` — `UserAdminDTO` with `isLocked: false`, `failedAttempts: 0` and `lockTime`
omitted (it is set to null, and nulls are dropped from the response).

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `TYPE_MISMATCH` | `{userId}` is not a valid `Long` |
| `401` | `TOKEN_MISSING` | No `khi_auth_token` cookie and no `Authorization` header |
| `403` | `ACCESS_DENIED` | Caller is not ADMIN, or lacks `user:update` |
| `404` | `USER_NOT_FOUND` | No row with that id |
| `500` | `DATABASE_ERROR` | `DataAccessException` during the update |

**Example**

```bash
curl -s -X POST "{{BASE_URL}}/api/admin/users/42/unlock" \
  -H "Cookie: khi_auth_token=$TOKEN"
```

**Notes** — there is **no** self-guard here; an admin may unlock themselves. When the account is
neither locked nor carrying failed attempts the call is a no-op. Otherwise it clears `isLocked`,
`lockTime` and `failedAttempts` in one save and writes an `UPDATE` audit row reading
`Unlocked user 'sara_h' (id=42) isLocked: true -&gt; false; failedAttempts cleared (was
hadFailures=true)`. Evicts `users:details`.

---

### `POST /api/admin/users/{userId}/reset-failed-attempts`

Clear the failed-login counter without touching lock state.

**Authority:** `user:update` (plus class-level `hasRole('ADMIN')`)

**Path parameters**

| Name | Type | Description |
|---|---|---|
| `userId` | long | Account whose counter is cleared |

**Request body** — none.

**Response** `200 OK` — `UserAdminDTO` with `failedAttempts: 0`.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `TYPE_MISMATCH` | `{userId}` is not a valid `Long` |
| `401` | `TOKEN_MISSING` | No `khi_auth_token` cookie and no `Authorization` header |
| `403` | `ACCESS_DENIED` | Caller is not ADMIN, or lacks `user:update` |
| `404` | `USER_NOT_FOUND` | No row with that id |
| `500` | `DATABASE_ERROR` | `DataAccessException` during the update |

**Example**

```bash
curl -s -X POST "{{BASE_URL}}/api/admin/users/42/reset-failed-attempts" \
  -H "Cookie: khi_auth_token=$TOKEN"
```

**Notes** — the counter reaches `SecurityConstants.MAX_FAILED_ATTEMPTS` (5) before the login flow
locks an account by itself. A counter already at 0 is a no-op. Otherwise an `UPDATE` row is written
with `details = "Reset failed-login counter (was=3)"`. `isLocked` and `lockTime` are left alone —
use `/unlock` to clear both at once. Evicts `users:details`.

---

### `POST /api/admin/users/{userId}/force-logout`

Sign the user out of every device.

**Authority:** `user:update` (plus class-level `hasRole('ADMIN')`)

**Path parameters**

| Name | Type | Description |
|---|---|---|
| `userId` | long | Account whose sessions are revoked |

**Request body** — none.

**Response** `200 OK` — the user's `UserAdminDTO`, unchanged (this endpoint writes to `sessions`, not
to `users_tbl`).

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `TYPE_MISMATCH` | `{userId}` is not a valid `Long` |
| `401` | `TOKEN_MISSING` | No `khi_auth_token` cookie and no `Authorization` header |
| `403` | `ACCESS_DENIED` | Caller is not ADMIN, or lacks `user:update` |
| `404` | `USER_NOT_FOUND` | No row with that id |
| `409` | `SELF_FORCE_LOGOUT` | The caller targeted their own account — the message points at `POST /api/auth/logout-all` instead |
| `500` | `DATABASE_ERROR` | `DataAccessException` while updating `sessions` |

**Example**

```bash
curl -s -X POST "{{BASE_URL}}/api/admin/users/42/force-logout" \
  -H "Cookie: khi_auth_token=$TOKEN"
```

**Notes**

- Every `sessions` row for the user with `is_active = true` gets `is_active = false` and
  `logout_timestamp = now`. Rows already inactive are left as they are, and the audit row reports how
  many were actually revoked: `Force-logout: revoked 3 active session(s)`.
- The user's existing JWTs are not added to `token_blacklist`, but they stop working anyway:
  `TokenService.checkBlacklistedInDb` treats a token whose `sessionId` claim resolves to a missing,
  inactive or expired `sessions` row as blacklisted, and `JWTAuthenticationFilter` then answers
  `401 TOKEN_REVOKED`.
- There is a delay: `TokenService` keeps a 10 000-entry Caffeine cache of token→blacklisted decisions
  with a **2-minute** write TTL, so a token that was validated just before the force-logout can keep
  working until that entry expires.
- This is the only mutating method in `AdminUserService` **without**
  `@CacheEvict("users:details")` — it changes no authority, so the cached `UserDetails` stays valid.
- Audited as an `UPDATE` row.

---

### `DELETE /api/admin/users/{userId}`

Hard-delete a user and their sessions.

**Authority:** `user:delete` (plus class-level `hasRole('ADMIN')`)

**Path parameters**

| Name | Type | Description |
|---|---|---|
| `userId` | long | Account to remove permanently |

**Request body** — none.

**Response** `204 No Content` — empty body (`ResponseEntity<Void>`).

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `TYPE_MISMATCH` | `{userId}` is not a valid `Long` |
| `401` | `TOKEN_MISSING` | No `khi_auth_token` cookie and no `Authorization` header |
| `403` | `ACCESS_DENIED` | Caller is not ADMIN, or lacks `user:delete` |
| `404` | `USER_NOT_FOUND` | No row with that id |
| `409` | `SELF_DELETE` | The caller targeted their own account |
| `409` | `LAST_ADMIN` | The target is ADMIN and `countByRole(ADMIN) <= 1` — deleting them would lock everyone out of `/api/admin/**` |
| `409` | `CONFLICT` | A foreign key still references the user (`DataIntegrityViolationException`) |
| `500` | `DATABASE_ERROR` | `DataAccessException` during the delete |

**Example**

```bash
curl -s -i -X DELETE "{{BASE_URL}}/api/admin/users/42" \
  -H "Cookie: khi_auth_token=$TOKEN"
```

**Notes**

- Order of operations: guards → delete every `sessions` row for the user (so the FK on
  `sessions.user_id` does not block) → write the audit row → delete the user.
- The `DELETE` audit row is written **before** the row disappears and captures the target's identity,
  their role in `previousRole`, and their full `extraPermissions` set in `permissionsChanged`.
  `UserAuditService.record` runs with `Propagation.REQUIRES_NEW`, so the audit row survives even if
  the delete itself rolls back.
- `user_audit_logs` stores `target_user_id` as a plain column with no foreign key, so history rows
  outlive the deleted account.
- There is no restore. This is not a trash operation — see the note in **Access** about
  `user:remove` being unused.
- Evicts `users:details`.

---

### `GET /api/admin/users/{userId}/audit-logs`

Paged `user_audit_logs` history scoped to one target user. Convenience wrapper over the same service
that backs `UserAuditLogAPI`, with `targetUserId` taken from the path.

**Authority:** `user:read` (plus class-level `hasRole('ADMIN')`)

**Path parameters**

| Name | Type | Description |
|---|---|---|
| `userId` | long | Becomes the `targetUserId` filter; it is **not** the actor filter |

**Query parameters** — all optional, all AND-ed together

| Name | Type | Default | Description |
|---|---|---|---|
| `actor` | string | none | Exact admin username who performed the action, case-insensitive |
| `action` | string | none | One `UserAuditAction` name, case-insensitive and trimmed: `CREATE`, `UPDATE`, `DELETE`, `ROLE_CHANGE`, `GRANT_PERMISSIONS`, `REVOKE_PERMISSIONS`, `ACTIVATE`, `DEACTIVATE`, `READ`, `LIST`, `WARNING_SENT`, `WARNING_REVOKED`, `WARNING_ACKNOWLEDGED` |
| `from` | ISO-8601 date-time | none | Lower bound (inclusive) on `occurredAt` |
| `to` | ISO-8601 date-time | none | Upper bound (inclusive) on `occurredAt` |
| `q` | string | none | Case-insensitive substring matched against `details`, `targetUsername`, `actorUsername`, `targetEmail` or `permissionsChanged` |
| `page` | int | `0` | Zero-based page index; a negative value is clamped to `0` |
| `size` | int | `50` | Page size; `<= 0` falls back to 50 and anything above 200 is clamped to 200 |
| `sort` | string | `desc` | Direction only. The value must **end with** `asc` or `desc` (so `occurredAt,desc` also works); the field part is ignored — rows are always ordered by `occurredAt` then `id` |

The `targetUsername` filter that `UserAuditLogService.Filter` supports is **not** exposed on this
endpoint (it is passed as null); use `GET /api/admin/users/audit-logs` on `UserAuditLogAPI` — see
[`./sessions-and-audit-logs.md`](./sessions-and-audit-logs.md) — for that.

**Response** `200 OK` — a standard Spring `Page` envelope; see
[`../01-conventions.md`](../01-conventions.md). Each `content[]` element is a `UserAuditLogDTO`:

```json
{
  "content": [
    {
      "id": 918,
      "action": "GRANT_PERMISSIONS",
      "targetUserId": 42,
      "targetUsername": "sara_h",
      "targetDisplayName": "Sara Hama",
      "targetEmail": "sara.hama@example.com",
      "permissionsChanged": "audio:delete,video:delete",
      "actorUserId": 1,
      "actorUsername": "akar",
      "actorDisplayName": "Akar Arkan",
      "actorAuthorities": "ROLE_ADMIN,audio:create,audio:delete,user:update",
      "actorPermissions": "audio:create,audio:delete,user:update",
      "deviceInfo": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)",
      "ipAddress": "10.0.0.14",
      "sessionId": "0f0a5c31-2b8e-44f1-9a77-7d0b1c9e3a55",
      "sessionLoginTimestamp": "2026-08-26T08:41:02.774Z",
      "sessionExpiresAt": "2026-08-29T08:41:02.774Z",
      "sessionActive": true,
      "requestMethod": "POST",
      "requestPath": "/api/admin/users/42/permissions",
      "details": "Granted permissions: [audio:delete, video:delete]",
      "occurredAt": "2026-08-26T11:04:57.905Z",
      "logId": 918
    }
  ],
  "totalElements": 1,
  "totalPages": 1,
  "number": 0,
  "size": 50,
  "first": true,
  "last": true,
  "numberOfElements": 1,
  "empty": false
}
```

`logId` is a `@JsonProperty("logId")` alias for `id`. `permissionsChanged` is a comma-joined string
(the DB column is `TEXT`), not an array, and is null — hence omitted — when the action changed no
permissions. Every session column is null when the JWT's `sessionId` claim cannot be resolved against
the `sessions` table; in that case `deviceInfo` falls back to the `User-Agent` header and
`ipAddress` to the remote address. The user's password was never recorded in this table.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `TYPE_MISMATCH` | `{userId}`, `page` or `size` is not a valid number, or `from`/`to` is not an ISO-8601 date-time |
| `400` | `BAD_REQUEST` | `action` is not a `UserAuditAction` name (message points at `GET /api/admin/users/audit-logs/actions`), or `sort` does not end with `asc`/`desc` |
| `401` | `TOKEN_MISSING` | No `khi_auth_token` cookie and no `Authorization` header |
| `403` | `ACCESS_DENIED` | Caller is not ADMIN, or lacks `user:read` |
| `500` | `DATABASE_ERROR` | `DataAccessException` while running the query |

Note that a `{userId}` with no rows returns an **empty page**, not `404` — the handler never loads the
user, it only filters on the column.

**Example**

```bash
curl -s "{{BASE_URL}}/api/admin/users/42/audit-logs?action=GRANT_PERMISSIONS&size=20&sort=desc" \
  -H "Cookie: khi_auth_token=$TOKEN"
```

**Notes** — audit-log reads are deliberately not themselves audited, so refreshing this view does not
grow the table.

---

### `GET /api/admin/users/catalog/roles`

Every role together with the authorities it grants. Intended for populating role dropdowns.

**Authority:** `hasRole('ADMIN')` — the class-level annotation only. This handler declares **no**
method-level `@PreAuthorize`, so no `user:*` authority is required.

**Query parameters** — none.

**Response** `200 OK` — `List<RoleCatalogDTO>` (`name`, `authorities`), one element per `Role` value in
declaration order: `GUEST`, `EMPLOYEE`, `TEACHER`, `ADMIN`. `authorities` is a `TreeSet`, so it is
alphabetically sorted, and it reflects `Role.getAuthorities()` — the role's own permission set plus
the `ROLE_<NAME>` tag. It does **not** include the seeded `EMPLOYEE_DEFAULT_PERMISSIONS` /
`TEACHER_DEFAULT_PERMISSIONS`, which are per-user grants rather than role authorities, which is why
`EMPLOYEE` and `TEACHER` come back with a single entry each.

```json
[
  { "name": "GUEST",    "authorities": ["ROLE_GUEST"] },
  { "name": "EMPLOYEE", "authorities": ["ROLE_EMPLOYEE"] },
  { "name": "TEACHER",  "authorities": ["ROLE_TEACHER"] },
  {
    "name": "ADMIN",
    "authorities": [
      "ROLE_ADMIN",
      "audio:create", "audio:delete", "audio:read", "audio:remove", "audio:update",
      "category:create", "category:delete", "category:read", "category:remove", "category:update",
      "correction:read", "correction:remove", "correction:update",
      "image:create", "image:delete", "image:read", "image:remove", "image:update",
      "khi_logo:create", "khi_logo:delete", "khi_logo:read", "khi_logo:update",
      "maqam:create", "maqam:delete", "maqam:read", "maqam:remove",
      "maqam:teacher_manage", "maqam:update", "maqam:vote",
      "person:create", "person:delete", "person:read", "person:remove", "person:update",
      "physical_media:create", "physical_media:delete", "physical_media:import",
      "physical_media:read", "physical_media:remove", "physical_media:type_manage",
      "physical_media:update",
      "project:create", "project:delete", "project:read", "project:remove", "project:update",
      "text:create", "text:delete", "text:read", "text:remove", "text:update",
      "user:create", "user:delete", "user:read", "user:remove", "user:update",
      "video:create", "video:delete", "video:read", "video:remove", "video:update",
      "warning:create", "warning:delete", "warning:read", "warning:remove", "warning:update"
    ]
  }
]
```

**Errors**

| Status | `error` code | When |
|---|---|---|
| `401` | `TOKEN_MISSING` | No `khi_auth_token` cookie and no `Authorization` header |
| `403` | `ACCESS_DENIED` | Caller is not ADMIN; `details.requiredAuthority` carries `ADMIN`, extracted from the class-level `hasRole('ADMIN')` |
| `500` | `INTERNAL_SERVER_ERROR` | Anything unhandled |

**Example**

```bash
curl -s "{{BASE_URL}}/api/admin/users/catalog/roles" \
  -H "Cookie: khi_auth_token=$TOKEN"
```

**Notes** — computed from the enum on every call; nothing is cached and nothing is audited.

---

### `GET /api/admin/users/catalog/permissions`

Every permission string the grant/revoke endpoints will accept.

**Authority:** `hasRole('ADMIN')` — the class-level annotation only; no method-level
`@PreAuthorize`.

**Query parameters** — none.

**Response** `200 OK` — a JSON array of the 66 strings in `AdminUserService.KNOWN_PERMISSIONS`
(`Set<String>`).

```json
[
  "audio:create",
  "audio:delete",
  "audio:read",
  "audio:remove",
  "audio:update",
  "…",
  "warning:update"
]
```

The set is built by inserting every `Permission.getPermission()` into a `TreeSet` and then wrapping
it with `Set.copyOf(...)`. `Set.copyOf` returns an unmodifiable set whose **iteration order is not
specified**, so do not rely on the array coming back alphabetically sorted — sort client-side if the
UI needs a stable order.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `401` | `TOKEN_MISSING` | No `khi_auth_token` cookie and no `Authorization` header |
| `403` | `ACCESS_DENIED` | Caller is not ADMIN |
| `500` | `INTERNAL_SERVER_ERROR` | Anything unhandled |

**Example**

```bash
curl -s "{{BASE_URL}}/api/admin/users/catalog/permissions" \
  -H "Cookie: khi_auth_token=$TOKEN"
```

**Notes** — this is the set referenced by an `UNKNOWN_PERMISSION` error's `details.catalog`. It is a
`static final` field computed once at class load, so it never changes at runtime; nothing is cached
per-request and nothing is audited.

## Audit logging

Every mutating endpoint in this file writes one row to `user_audit_logs` through
`UserAuditService.record(...)`, which runs with `Propagation.REQUIRES_NEW` so the audit row commits
even if the surrounding transaction rolls back. The single exception is the GUEST auto-promotion
inside `POST …/permissions`, which writes two rows (`GRANT_PERMISSIONS` then `ROLE_CHANGE`).

| `UserAuditAction` | Written by |
|---|---|
| `CREATE` | `POST /api/admin/users` |
| `READ` | `GET /api/admin/users/{userId}` |
| `UPDATE` | `PUT /api/admin/users/{userId}` (including its role branch — see below), `/lock`, `/unlock`, `/reset-failed-attempts`, `/force-logout` |
| `ROLE_CHANGE` | `PUT /api/admin/users/{userId}/role`, and the GUEST auto-promotion inside `POST …/permissions` |
| `GRANT_PERMISSIONS` | `POST /api/admin/users/{userId}/permissions` |
| `REVOKE_PERMISSIONS` | `DELETE /api/admin/users/{userId}/permissions` |
| `ACTIVATE` | `POST /api/admin/users/{userId}/activate` |
| `DEACTIVATE` | `POST /api/admin/users/{userId}/deactivate` |
| `DELETE` | `DELETE /api/admin/users/{userId}` |
| `LIST` | never written by this controller — `GET /api/admin/users` is intentionally unaudited |
| `WARNING_SENT` / `WARNING_REVOKED` / `WARNING_ACKNOWLEDGED` | written by `UserWarningService`, not by this controller |

Changing a role through `PUT /api/admin/users/{userId}` does **not** produce a `ROLE_CHANGE` row.
`updateUserAsAdmin` always records the action as `UPDATE` and signals the transition through the
`previous_role` / `new_role` columns plus a `role=OLD -> NEW` entry in the `details` diff; only
`changeRole` (the dedicated `/role` endpoint) and the GUEST auto-promotion inside `grantPermissions`
write `ROLE_CHANGE`. A filter on `action=ROLE_CHANGE` therefore misses role edits made through the
general update endpoint.

Columns recorded per row (`user/model/UserAuditLog.java`): `id`, `action`, `target_user_id`,
`target_username`, `target_display_name`, `target_email`, `previous_role`, `new_role`,
`permissions_changed`, `actor_user_id`, `actor_username`, `actor_display_name`, `actor_authorities`,
`actor_permissions`, `device_info`, `ip_address`, `session_id`, `session_login_timestamp`,
`session_expires_at`, `session_is_active`, `request_method`, `request_path`, `details`,
`occurred_at`.

`actor_authorities` is the caller's full authority list, comma-joined; `actor_permissions` is the same
list with every `ROLE_*` entry dropped. Session columns come from the `sessions` row matching the
JWT's `sessionId` claim (resolved from `Authorization: Bearer …` first, then the `khi_auth_token`
cookie); when no session resolves, `device_info` falls back to the `User-Agent` header and
`ip_address` to the remote address. `details` is HTML-escaped with `HtmlUtils.htmlEscape` before it
is stored. Any operation that turns out to be a no-op returns the current DTO **without** writing a
row.

The CHECK constraint on `user_audit_logs.action` is re-synced from the enum at boot by
`user/configs/UserAuditActionConstraintInitializer.java`, the sibling of the role initializer.

## Related

- [Internal API index](../README.md)
- [Conventions — page envelope, timestamps, error shape](../01-conventions.md)
- [Admin — Sessions and User Audit Logs](./sessions-and-audit-logs.md) — the global
  `user_audit_logs` search behind `UserAuditLogAPI`, where every row written by the mutations
  documented here can be queried across all users
- [Admin — Warnings](./warnings.md) — the `warning:*` surface whose authorities are granted per user
  here, and which writes its own rows into the same `user_audit_logs` table
- [Person API](../content/person.md) — a `person:*`-gated surface unlocked by the grants made here
- [Project API](../content/project.md) — `project:*`; the seeded EMPLOYEE set covers read/create/update
- [Tags and Keywords API](../content/tags-and-keywords.md) — a vocabulary surface whose admin half is
  gated on `hasRole('ADMIN')` rather than on a `<resource>:<action>` authority granted here
- [KHI Logo API](../content/khi-logo.md) — `khi_logo:*`, held by ADMIN only and never seeded
