# Khi Logo Feature

A single uploaded image (the site/app logo). Simple CRUD, no soft-delete,
no audit trail, no caching — the record just holds the S3 URL of the
current logo image.

## 1. What was added

### 1.1 Entity

| Entity | Table | Purpose |
| --- | --- | --- |
| `KhiLogo` | `khi_logo` | id, `imageUrl` (S3 URL), `createdAt`, `updatedAt`. |

Package layout:

```
platform/model/khilogo/KhiLogo.java
platform/repo/khilogo/KhiLogoRepository.java
platform/dto/khilogo/KhiLogoResponseDTO.java
platform/service/khilogo/KhiLogoService.java
platform/api/khilogo/KhiLogoAPI.java
platform/exceptions/KhiLogoNotFoundException.java
```

### 1.2 Permissions

New permissions in the `Permission` enum:

* `khi_logo:read`
* `khi_logo:create`
* `khi_logo:update`
* `khi_logo:delete`

Not seeded into `EMPLOYEE_DEFAULT_PERMISSIONS` or `TEACHER_DEFAULT_PERMISSIONS`
— the logo is site branding, so only `ADMIN` (which holds every permission)
can manage it by default. An admin can still grant any of the four
individually via `PUT /api/admin/users/{id}/permissions`.

### 1.3 Storage

Files are uploaded through the shared `S3Service` under the folder
`khi_logo/`. The entity stores only the resulting public S3 URL — the
file itself is never stored on disk or in the database. On `update` or
`delete`, the previous S3 object is removed automatically.

## 2. API

Base path: `/api/khi-logo`

| Method | Path | Authority | Body | Description |
| --- | --- | --- | --- | --- |
| `POST` | `/api/khi-logo` | `khi_logo:create` | multipart, part `file` | Upload a new logo image. |
| `GET` | `/api/khi-logo/{id}` | `khi_logo:read` | — | Get a logo record by id. |
| `PATCH` | `/api/khi-logo/{id}` | `khi_logo:update` | multipart, part `file` | Replace the image on an existing record. |
| `DELETE` | `/api/khi-logo/{id}` | `khi_logo:delete` | — | Delete the record and its S3 file. |

### Response shape (`KhiLogoResponseDTO`)

```json
{
  "id": 1,
  "imageUrl": "https://.../khi_logo/....png",
  "createdAt": "2026-07-26T10:00:00Z",
  "updatedAt": "2026-07-26T10:00:00Z"
}
```

### Error codes

| Code | HTTP | When |
| --- | --- | --- |
| `KHI_LOGO_NOT_FOUND` | 404 | No record for the given id. |
| `BAD_REQUEST` | 400 | Missing or empty `file` part. |
| `ACCESS_DENIED` | 403 | Caller lacks the required `khi_logo:*` authority. |

## 3. Sample curl

```bash
# Upload a new logo (ADMIN or a user granted khi_logo:create)
curl -s -X POST http://localhost:8080/api/khi-logo \
  -H "Authorization: Bearer $JWT" \
  -F 'file=@./logo.png'

# Get a logo record
curl -s http://localhost:8080/api/khi-logo/1 \
  -H "Authorization: Bearer $JWT"

# Replace the image
curl -s -X PATCH http://localhost:8080/api/khi-logo/1 \
  -H "Authorization: Bearer $JWT" \
  -F 'file=@./new-logo.png'

# Delete the record (also deletes the S3 file)
curl -s -X DELETE http://localhost:8080/api/khi-logo/1 \
  -H "Authorization: Bearer $JWT"
```
