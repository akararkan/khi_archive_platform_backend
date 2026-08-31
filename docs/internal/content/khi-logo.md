# KHI Logo API

> **Audience:** Staff (ADMIN by default) · **Base path:** `/api/khi-logo` · **Source:** `platform/api/khilogo/KhiLogoAPI.java`

Manages the KHI site-branding logo image. A logo record is a thin row — an S3 image URL plus
create/update timestamps — uploaded, replaced, fetched and hard-deleted through four endpoints.
This is site configuration rather than archive content: there is no trash/restore, no visibility
flag, no tags, and no audit trail.

## Access

| Requirement | Value |
|---|---|
| Authentication | required |
| Authority | per-method `@PreAuthorize`: `khi_logo:create`, `khi_logo:read`, `khi_logo:update`, `khi_logo:delete` |
| Roles that hold it by default | ADMIN only |

`KhiLogoAPI` carries **no class-level `@PreAuthorize`** — every method declares its own
`hasAuthority('khi_logo:<action>')`, and the exact authority is repeated in each endpoint section
below.

The four `khi_logo:*` authorities exist in `user/enums/Permission.java`
(`KHI_LOGO_READ`, `KHI_LOGO_CREATE`, `KHI_LOGO_UPDATE`, `KHI_LOGO_DELETE`). ADMIN holds all of
them through the role itself (`Role.ADMIN` = `EnumSet.allOf(Permission.class)`). They are **not**
seeded into `EMPLOYEE_DEFAULT_PERMISSIONS` or `TEACHER_DEFAULT_PERMISSIONS`, so an employee or
teacher has none of them unless an admin grants the specific authority through the per-user
permission-grant endpoint.

That omission is deliberate. The logo is **site branding** rather than archive content — changing
it changes what every visitor sees on every page — so the capability stayed with ADMIN even though
`EMPLOYEE_DEFAULT_PERMISSIONS` hands employees create/update rights over audio, video, image, text,
category, person, project, maqam and physical-media records. Treat the absence in `Role.java` as
load-bearing: adding `khi_logo:*` to the default sets would hand every content curator the site's
chrome.

The decision is reversible per user without promoting anyone to ADMIN — an admin grants the
individual authorities through the per-user grants endpoint:

```bash
curl -s -X POST "{{BASE_URL}}/api/admin/users/42/permissions" \
  -H "Cookie: khi_auth_token=$TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"permissions":["khi_logo:read","khi_logo:create","khi_logo:update"]}'
```

`DELETE /api/admin/users/{userId}/permissions` with the same body shape takes them back. Both calls
require `ROLE_ADMIN` (class-level on `AdminUserAPI`) plus `user:update`, both are recorded to
`user_audit_logs` (`GRANT_PERMISSIONS` / `REVOKE_PERMISSIONS`), and granting to a user who is
already ADMIN is rejected with `409 ADMIN_PERMISSIONS_LOCKED` — see
[Admin users and permissions](../admin/users-and-permissions.md).

Note there is no `khi_logo:remove` — the resource has four actions, not the five used by the media
entities, because `DELETE` here is a hard delete rather than a soft-trash.

Authentication failures are produced by `user/jwt/JWTAuthenticationFilter.java` and
`user/exceptions/JwtAuthenticationEntryPoint.java`, not by the platform exception advice, and are
identical on all four endpoints: `401` with `TOKEN_MISSING` when no credentials were sent at all
(no `Authorization` header and no cookie); `TOKEN_EXPIRED`, `TOKEN_MALFORMED`,
`TOKEN_INVALID_SIGNATURE`, `TOKEN_INVALID` or `TOKEN_REVOKED` when the filter classifies a supplied
token; and `AUTHENTICATION_FAILED` when credentials were present but no authentication was
established. The per-endpoint tables below repeat these rows in compact form.

## Endpoints

| Method | Path | Authority | Purpose |
|---|---|---|---|
| `POST` | `/api/khi-logo` | `khi_logo:create` | Upload a new logo image and create its record |
| `GET` | `/api/khi-logo/{id}` | `khi_logo:read` | Fetch one logo record by id |
| `PATCH` | `/api/khi-logo/{id}` | `khi_logo:update` | Replace the image on an existing record |
| `DELETE` | `/api/khi-logo/{id}` | `khi_logo:delete` | Hard-delete the record and its S3 object |

There is no list endpoint and no "current logo" endpoint — a client must already know the `id`.
`KhiLogoRepository` is a plain `JpaRepository<KhiLogo, Long>` with no extra query methods, and
nothing in `KhiLogoService` enforces a single row, so repeated `POST`s create additional records.

## Intended use — one logo row

The feature was designed around a **single** logo record: one uploaded image that is "the site
logo". Since neither the schema nor the service enforces that, it is a convention callers have to
keep:

- `POST` once, when an environment is first set up, and keep the returned `id`.
- Change the logo with `PATCH /api/khi-logo/{id}` on that same id, never with a second `POST`.
  Replacing in place is what keeps the id stable and what gets the superseded S3 object cleaned up;
  a second `POST` leaves the previous row and its object behind, with no endpoint that can list
  either of them.
- Reserve `DELETE` for decommissioning. There is no trash and no restore, so a deleted row is gone
  and every client still pointing at that id starts getting `404 KHI_LOGO_NOT_FOUND`.

Frontends therefore treat the logo id as configuration: pin it once (build-time constant or
environment value) and call `GET /api/khi-logo/{id}` to resolve the current `imageUrl`. That read
is gated on `khi_logo:read`, and `/api/khi-logo/**` is not in the `permitAll` set of
`SecurityConfig` — there is no `/api/guest` route for the logo. An anonymous public page therefore
cannot fetch the record itself; it needs the resolved `imageUrl` handed to it by an authenticated
surface or baked into its own configuration.

If the id is ever lost, it has to be recovered directly from the database
(`SELECT id, image_url FROM khi_logo ORDER BY created_at DESC`) — no API route enumerates the
table.

## How the image bytes are served

`KhiLogoService` uploads the multipart part through `S3Service.upload(file, "khi_logo")` and stores
whatever that returns in `KhiLogo.imageUrl` (`image_url`, `VARCHAR(500)`, `NOT NULL`). `S3Service`
builds the key as `<base-folder>/khi_logo/<uuid>-<sanitized-filename>` and returns
`S3Service.getPublicUrl(key)` — an **absolute S3 URL** of the form:

```text
https://<aws.s3.bucket>.s3.<aws.s3.region>.amazonaws.com/<aws.s3.base-folder>/khi_logo/<uuid>-<file>
```

With the configured defaults (`AWS_S3_BUCKET:khi-archive-platform`, `AWS_REGION:us-east-1`,
`AWS_S3_BASE_FOLDER:khi-archive-platform-folders`) that resolves to, for example:

```text
https://khi-archive-platform.s3.us-east-1.amazonaws.com/khi-archive-platform-folders/khi_logo/8f3c1e2a-4b77-4d5e-9a10-2c6f0b9d4e11-khi-logo.png
```

Consequences worth knowing:

| Aspect | Behavior |
|---|---|
| Byte delivery | The client loads `imageUrl` **directly from S3**. Unlike audio/video/image/text, the logo has **no API proxy or stream endpoint** — no such route exists anywhere in source. |
| Authentication on the bytes | The JSON record is behind `khi_logo:read`; the S3 URL it contains is not gated by this API. Whether the object itself is publicly readable depends on bucket/object policy — _Not documented in source._ |
| Filename | Sanitized by `S3Service.sanitizeFilename` — every character outside `[a-zA-Z0-9._-]` becomes `_` — and prefixed with a random UUID, so uploads never collide. |
| Upload path | Files at or below 16 MB go through a single `PutObject`; larger files use an S3 multipart upload with 16 MB parts (`S3Service.MULTIPART_PART_SIZE`). |
| Content type | Taken from the multipart part's own `Content-Type` and set on the S3 object. The API does **not** validate that the upload is an image — any file type is accepted. |
| Caching | No `@Cacheable` anywhere in `KhiLogoService`, and `CacheConfig` defines no khi-logo cache. Every request hits PostgreSQL. |

## Response object — `KhiLogoResponseDTO`

| Field | Type | Description |
|---|---|---|
| `id` | number | Generated identity primary key |
| `imageUrl` | string | Absolute S3 URL of the logo image |
| `createdAt` | string | Set by `@PrePersist` when the record is first saved |
| `updatedAt` | string | Set by `@PrePersist`, refreshed by `@PreUpdate` |

`createdAt` / `updatedAt` are `java.time.Instant`. The auto-configured Jackson 3 response mapper
writes them as ISO-8601 instants in UTC (`2026-08-26T09:15:42.318Z`), not as the
`yyyy-MM-dd HH:mm:ss` pattern used for `spring.mvc.format` request binding. (`JacksonConfig`'s
`ObjectMapper` bean is a separate Jackson 2 instance used to parse the multipart `data` part; it
does not serialize responses.) Null fields are omitted from responses
(`spring.jackson.default-property-inclusion=non_null`); in practice all four fields are always
populated by `KhiLogoService.toResponse`.

---

### `POST /api/khi-logo`

Uploads a logo image to S3 and creates a new `khi_logo` row pointing at it.

**Authority:** `khi_logo:create`

**Query parameters** — none.

**Request body** — `multipart/form-data` (the method declares
`consumes = MediaType.MULTIPART_FORM_DATA_VALUE`).

| Part | Type | Required | Description |
|---|---|---|---|
| `file` | file | yes | The logo image. Rejected with `400` if absent or empty. |

There is no JSON `data` part on this endpoint — `file` is the only part the controller binds.

**Response** `200 OK` (`produces = application/json`)

```json
{
  "id": 1,
  "imageUrl": "https://khi-archive-platform.s3.us-east-1.amazonaws.com/khi-archive-platform-folders/khi_logo/8f3c1e2a-4b77-4d5e-9a10-2c6f0b9d4e11-khi-logo.png",
  "createdAt": "2026-08-26T09:15:42.318Z",
  "updatedAt": "2026-08-26T09:15:42.318Z"
}
```

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `MISSING_REQUEST_PART` | The `file` part is not present in the multipart body |
| `400` | `BAD_REQUEST` | `file` is present but empty — `KhiLogoService.requireFile` throws `IllegalArgumentException("Logo image file is required.")` |
| `400` | `BAD_REQUEST` | Multipart body could not be parsed (`MultipartException`, category `MEDIA`) |
| `401` | `TOKEN_MISSING` | No `khi_auth_token` cookie and no `Authorization` header |
| `401` | `TOKEN_EXPIRED` / `TOKEN_REVOKED` / `TOKEN_MALFORMED` / `TOKEN_INVALID_SIGNATURE` / `TOKEN_INVALID` / `AUTHENTICATION_FAILED` | Credentials supplied but rejected — see **Access** above |
| `403` | `ACCESS_DENIED` | Caller lacks `khi_logo:create`; `details.requiredAuthority` echoes it back |
| `405` | `METHOD_NOT_ALLOWED` | Wrong HTTP method on `/api/khi-logo` |
| `409` | `CONFLICT` | A database constraint blocked the insert — e.g. the generated URL exceeds `image_url`'s 500-character limit |
| `413` | `UPLOAD_TOO_LARGE` | Beyond `spring.servlet.multipart` limits (`max-file-size: 5GB`, `max-request-size: 6GB`); `details.maxBytes` carries the cap |
| `415` | `UNSUPPORTED_MEDIA_TYPE` | Request `Content-Type` is not `multipart/form-data` |
| `500` | `INTERNAL_SERVER_ERROR` | S3 rejected the upload — `S3Service` throws `UserStorageException`, which falls through to the catch-all handler |
| `500` | `DATABASE_ERROR` | `DataAccessException` while saving the row |
| `504` | `TIMEOUT` | `QueryTimeoutException` from the database |

**Example**

```bash
curl -s -X POST "{{BASE_URL}}/api/khi-logo" \
  -H "Cookie: khi_auth_token=$TOKEN" \
  -F "file=@./khi-logo.png"
```

**Notes** — the whole method runs inside `@Transactional`; the S3 upload happens before the row is
saved, so a failed database commit leaves an orphaned object in the `khi_logo/` prefix. Nothing is
written to any `*_audit_logs` table for this endpoint.

---

### `GET /api/khi-logo/{id}`

Returns one logo record.

**Authority:** `khi_logo:read`

**Path parameters**

| Name | Type | Description |
|---|---|---|
| `id` | Long | Primary key of the `khi_logo` row |

**Query parameters** — none. This endpoint takes no filter, sort or paging parameters; there is no
`@ModelAttribute` filter-params class for this resource.

**Response** `200 OK`

```json
{
  "id": 1,
  "imageUrl": "https://khi-archive-platform.s3.us-east-1.amazonaws.com/khi-archive-platform-folders/khi_logo/8f3c1e2a-4b77-4d5e-9a10-2c6f0b9d4e11-khi-logo.png",
  "createdAt": "2026-08-26T09:15:42.318Z",
  "updatedAt": "2026-08-26T09:15:42.318Z"
}
```

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `TYPE_MISMATCH` | `{id}` is not a valid `Long`; `details.rejectedValue` echoes what was sent |
| `401` | `TOKEN_MISSING` | No `khi_auth_token` cookie and no `Authorization` header |
| `401` | `TOKEN_EXPIRED` / `TOKEN_REVOKED` / `TOKEN_MALFORMED` / `TOKEN_INVALID_SIGNATURE` / `TOKEN_INVALID` / `AUTHENTICATION_FAILED` | Credentials supplied but rejected — see **Access** above |
| `403` | `ACCESS_DENIED` | Caller lacks `khi_logo:read` |
| `404` | `KHI_LOGO_NOT_FOUND` | No `khi_logo` row with that id |
| `500` | `DATABASE_ERROR` | `DataAccessException` while loading the row |
| `504` | `TIMEOUT` | `QueryTimeoutException` from the database |

**Example**

```bash
curl -s "{{BASE_URL}}/api/khi-logo/1" \
  -H "Cookie: khi_auth_token=$TOKEN"
```

**Notes** — served with `@Transactional(readOnly = true)`. Uncached.

---

### `PATCH /api/khi-logo/{id}`

Replaces the image on an existing logo record; the record's `id` is preserved.

**Authority:** `khi_logo:update`

**Path parameters**

| Name | Type | Description |
|---|---|---|
| `id` | Long | Primary key of the `khi_logo` row to update |

**Query parameters** — none.

**Request body** — `multipart/form-data`.

| Part | Type | Required | Description |
|---|---|---|---|
| `file` | file | yes | Replacement logo image. Rejected with `400` if absent or empty. |

The file part is mandatory: there is no metadata-only update, because `imageUrl` is the only
mutable column on the entity.

**Response** `200 OK`

```json
{
  "id": 1,
  "imageUrl": "https://khi-archive-platform.s3.us-east-1.amazonaws.com/khi-archive-platform-folders/khi_logo/b21d77c4-9e30-41aa-8f52-0d6b3ac71f88-khi-logo-v2.png",
  "createdAt": "2026-08-26T09:15:42.318Z",
  "updatedAt": "2026-08-26T09:15:42.318Z"
}
```

`imageUrl` is the only field whose value changes in this response. `createdAt` keeps its original
value, and `updatedAt` still carries the timestamp that was loaded from the row: the entity's
`@PreUpdate` hook runs when Hibernate flushes the persistence context at commit, which is after
`KhiLogoService.toResponse` has already read the entity (`khiLogoRepository.save` on an already
managed entity is a `merge`, not a flush). The refreshed `updatedAt` is therefore visible on the
next `GET /api/khi-logo/{id}`, not in the `PATCH` response itself.

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `TYPE_MISMATCH` | `{id}` is not a valid `Long` |
| `400` | `MISSING_REQUEST_PART` | The `file` part is not present in the multipart body |
| `400` | `BAD_REQUEST` | `file` is present but empty (`IllegalArgumentException`), or the multipart body could not be parsed |
| `401` | `TOKEN_MISSING` | No `khi_auth_token` cookie and no `Authorization` header |
| `401` | `TOKEN_EXPIRED` / `TOKEN_REVOKED` / `TOKEN_MALFORMED` / `TOKEN_INVALID_SIGNATURE` / `TOKEN_INVALID` / `AUTHENTICATION_FAILED` | Credentials supplied but rejected — see **Access** above |
| `403` | `ACCESS_DENIED` | Caller lacks `khi_logo:update` |
| `404` | `KHI_LOGO_NOT_FOUND` | No `khi_logo` row with that id |
| `405` | `METHOD_NOT_ALLOWED` | e.g. `PUT` on this path — only `GET`, `PATCH` and `DELETE` are mapped |
| `409` | `CONFLICT` | A database constraint blocked the update (e.g. `image_url` longer than 500 characters) |
| `413` | `UPLOAD_TOO_LARGE` | Beyond the configured multipart limits |
| `415` | `UNSUPPORTED_MEDIA_TYPE` | Request `Content-Type` is not `multipart/form-data` |
| `500` | `INTERNAL_SERVER_ERROR` | S3 rejected the replacement upload (`UserStorageException`) |
| `500` | `DATABASE_ERROR` | `DataAccessException` while saving the row |
| `504` | `TIMEOUT` | `QueryTimeoutException` from the database |

**Example**

```bash
curl -s -X PATCH "{{BASE_URL}}/api/khi-logo/1" \
  -H "Cookie: khi_auth_token=$TOKEN" \
  -F "file=@./khi-logo-v2.png"
```

**Notes** — order of operations in `KhiLogoService.update`: the file part is validated, the record
is loaded, the old URL is captured, the **new** file is uploaded to S3, the row is saved with the
new URL, and only then is the previous object deleted — and only if
`S3Service.isOurS3Url(oldImageUrl)` is true (the URL is non-null and contains both the configured
bucket name and `.s3.`). A failed upload therefore aborts before anything is destroyed. The old
object is removed through `S3Service.deleteFile(oldImageUrl)`, which delegates to `deleteByUrl` →
`deleteByKey`; a failure there is logged and swallowed (`deleteByKey` returns `false`, and
`deleteFile` discards it), so a stale S3 object can outlive a successful update.

---

### `DELETE /api/khi-logo/{id}`

Hard-deletes the logo record and best-effort removes its S3 object.

**Authority:** `khi_logo:delete`

**Path parameters**

| Name | Type | Description |
|---|---|---|
| `id` | Long | Primary key of the `khi_logo` row to delete |

**Query parameters** — none.

**Response** `204 No Content` — empty body (`ResponseEntity<Void>`).

**Errors**

| Status | `error` code | When |
|---|---|---|
| `400` | `TYPE_MISMATCH` | `{id}` is not a valid `Long` |
| `401` | `TOKEN_MISSING` | No `khi_auth_token` cookie and no `Authorization` header |
| `401` | `TOKEN_EXPIRED` / `TOKEN_REVOKED` / `TOKEN_MALFORMED` / `TOKEN_INVALID_SIGNATURE` / `TOKEN_INVALID` / `AUTHENTICATION_FAILED` | Credentials supplied but rejected — see **Access** above |
| `403` | `ACCESS_DENIED` | Caller lacks `khi_logo:delete` |
| `404` | `KHI_LOGO_NOT_FOUND` | No `khi_logo` row with that id |
| `500` | `DATABASE_ERROR` | `DataAccessException` while deleting the row |
| `504` | `TIMEOUT` | `QueryTimeoutException` from the database |

**Example**

```bash
curl -s -i -X DELETE "{{BASE_URL}}/api/khi-logo/1" \
  -H "Cookie: khi_auth_token=$TOKEN"
```

**Notes** — this is a **hard delete**. The row is physically removed; there is no `isDeleted` flag,
no trash listing and no restore path for `khi_logo`, unlike the media entities. The S3 object is
removed with `S3Service.deleteFile(logo.getImageUrl())` after `khiLogoRepository.delete(logo)` but
still inside the transaction, and only when `S3Service.isOurS3Url` recognizes the stored URL; an S3
failure is logged rather than thrown, so a `204` does not guarantee the object is gone. Conversely,
because the object is removed before the transaction commits, a commit failure can leave a
surviving row whose image no longer exists.

## Error envelope

Every error above is the shared `ApiErrorResponse` envelope produced by
`platform/exceptions/ApiExceptionHandler.java` (or, for `401`s, by the JWT filter and
`JwtAuthenticationEntryPoint`):

```json
{
  "timestamp": "2026-08-26T11:22:04.551Z",
  "status": 404,
  "error": "KHI_LOGO_NOT_FOUND",
  "category": "NOT_FOUND",
  "message": "Khi logo not found: 7",
  "hint": "Confirm the khi logo id.",
  "path": "/api/khi-logo/7"
}
```

`traceId` is included only when one is present in the MDC, and `details` only when the handler
attaches structured data (field errors, `requiredAuthority`, `maxBytes`, `supportedMethods`).

## Related

- [Internal API index](../README.md)
- [Image API](./image.md) — the archive's image content type, which unlike the logo is
  soft-trashable, cached, and served through an API proxy rather than a raw S3 URL
