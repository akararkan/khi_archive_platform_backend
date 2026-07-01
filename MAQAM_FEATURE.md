# List-of-Maqam Feature — Implementation Notes & OpenAPI Spec

A new domain that lets three subject-matter teachers vote on the maqam type
of curated song recordings. Employees/admins prepare the song fields (song
name, producer/singer, audio file, archive note); 1–3 teachers per record
listen to the audio and submit their classification + note. Every second a
teacher listens is tracked.

## 1. What was added

### 1.1 Roles & Permissions

* New role `TEACHER` (in `Role` enum).
  * Default permissions on first promotion: `maqam:read`, `maqam:vote`.
  * Admins may grant/revoke more via the existing
    `/api/admin/users/{id}/permissions` endpoint.
* New permissions (in `Permission` enum):
  * `maqam:read`, `maqam:create`, `maqam:update`, `maqam:remove`, `maqam:delete`
  * `maqam:vote` — held by the TEACHER role baseline only.
  * `maqam:teacher_manage` — admin-only authority used by the roster endpoint.
* `EMPLOYEE_DEFAULT_PERMISSIONS` now includes `maqam:read`, `maqam:create`,
  `maqam:update`, so existing employees can prepare maqam records the same way
  they prepare audio.

### 1.2 Entities (`platform.model.maqam`)

| Entity | Table | Purpose |
| --- | --- | --- |
| `ListOfMaqam` | `list_of_maqam` | The song record (id, code, song name, producer, audio file URL, audio metadata, archive note, soft-delete columns, optimistic version). |
| `MaqamTeacherVote` | `maqam_teacher_votes` | One row per (record, teacher). Holds vote, note, assigned_at/by, plus the rolling listen aggregates `totalListenSeconds` and `maxPositionSeconds`. Unique on `(list_of_maqam_id, teacher_user_id)`. |
| `MaqamAudioListenSession` | `maqam_audio_listen_sessions` | One row per play session per teacher per record. Tracks `secondsListened`, `lastPositionSeconds`, IP/UA, session_key. |
| `MaqamAuditLog` | `maqam_audit_logs` | Shape-aligned with every other `*_audit_logs` table; extends it with teacher/vote/listen context. |

### 1.3 Audit & Analytics

* `MaqamAuditAction` enum covers record CRUD plus three teacher-side action
  families (`TEACHER_*`, `VOTE_*`, `LISTEN_*`, `STREAM`).
* `MaqamAuditActionConstraintInitializer` keeps the Postgres CHECK constraint
  in sync with the enum.
* `AuditLogIndexInitializer` now includes `maqam_audit_logs`.
* `AnalyticsService`:
  * adds `"maqam"` to `ENTITY_KEYS`,
  * adds an 8th `UNION ALL` branch over `maqam_audit_logs`,
  * extends `ACTION_KEYS` with `TEACHER_ASSIGNED`, `TEACHER_REMOVED`,
    `VOTE_CAST`, `VOTE_UPDATED`, `VOTE_DELETED`, `STREAM`,
    `LISTEN_STARTED`, `LISTEN_PROGRESS`, `LISTEN_ENDED` so admins can filter
    on them in `/api/analytics`.

### 1.4 Storage rules

* Uploaded audio is stored under the S3 prefix `khi-archive-platform-folders/maqam-audio/`.
* The S3 URL **is never returned** by any API response — only a
  `streamUrl` pointing at the backend's range-aware streaming endpoint.
* The streaming endpoint sets `Content-Disposition: inline`, `Cache-Control:
  no-store, private`, and `X-Content-Type-Options: nosniff`. Combined with
  the front-end's `<audio controlsList="nodownload">`, this implements the
  "no downloads anywhere in the project" rule.
* Every range request is logged as a `STREAM` audit row, so admins can see
  who played what and how often.

### 1.5 Admin activities (summary)

* **Roster management** — `PUT /api/admin/maqam/{maqamCode}/teachers`
  replaces the teacher panel; enforces 1–3 distinct TEACHER-role users.
* **Trash workflow** — `GET /api/admin/maqam/trash`, `POST .../restore`,
  `DELETE .../purge`. `DELETE /api/maqam/{maqamCode}` soft-trashes.
* **Vote moderation** — `DELETE /api/admin/maqam/{maqamCode}/votes/{teacherUserId}`
  clears one teacher's vote without removing them from the panel.
* **Teacher engagement reports** —
  `GET /api/maqam/{maqamCode}/listen-summary` (per-teacher aggregate),
  `GET /api/maqam/{maqamCode}/sessions` (per-session log),
  `GET /api/admin/maqam/teachers/{teacherUserId}/sessions` (every session a
  teacher ever recorded; PII visible only to ADMIN).
* **Cross-entity analytics** — all maqam audit rows flow through the standard
  `/api/analytics/*` endpoints via the new UNION branch.

### 1.6 Role × Operation matrix

| Operation | ADMIN | EMPLOYEE (default perms) | TEACHER |
| --- | --- | --- | --- |
| Create / update song fields | ✓ | ✓ | ✗ |
| Soft-trash, restore, purge | ✓ | ✗ | ✗ |
| Assign / unassign teachers | ✓ | ✗ | ✗ |
| Clear another teacher's vote | ✓ | ✗ | ✗ |
| Read records | ✓ (all) | ✓ (all) | ✓ (only assigned) |
| Cast / update own vote | ✗ | ✗ | ✓ |
| Cast / update another teacher's vote | ✗ | ✗ | ✗ |
| Stream audio | ✓ | ✓ | ✓ (only assigned) |
| Per-record listen summary / sessions | ✓ | ✓ | ✓ (assigned record) |
| Sessions across all records for a teacher | ✓ | ✗ | ✗ |
| PII (IP / user-agent) on session rows | ✓ | ✗ | ✗ |

---

## 2. OpenAPI 3.1 Specification

```yaml
openapi: 3.1.0
info:
  title: KHI Archive Platform — Maqam API
  version: 1.0.0
  description: |
    Endpoints introduced by the List-of-Maqam feature. The audio file is
    served only through the streaming endpoint; the raw S3 URL is never
    exposed in any response.

servers:
  - url: https://{host}
    variables:
      host:
        default: localhost:8080

security:
  - bearerAuth: []
  - cookieAuth: []

components:
  securitySchemes:
    bearerAuth:
      type: http
      scheme: bearer
      bearerFormat: JWT
    cookieAuth:
      type: apiKey
      in: cookie
      name: khi_auth_token

  schemas:
    MaqamCreateRequest:
      type: object
      required: [songName, producer]
      properties:
        songName:    { type: string, maxLength: 1000 }
        producer:    { type: string, maxLength: 500, description: "The singer." }
        archiveNote: { type: string, maxLength: 10000, nullable: true }
        teacherUserIds:
          type: array
          items: { type: integer, format: int64 }
          minItems: 1
          maxItems: 3
          nullable: true
          description: |
            Optional at create. When provided, every id must belong to an
            active TEACHER user. Without it, admins assign teachers later
            via PUT /api/admin/maqam/{maqamCode}/teachers.

    MaqamUpdateRequest:
      type: object
      properties:
        songName:    { type: string, maxLength: 1000, nullable: true }
        producer:    { type: string, maxLength: 500, nullable: true }
        archiveNote: { type: string, maxLength: 10000, nullable: true }

    MaqamTeacherAssignmentRequest:
      type: object
      required: [teacherUserIds]
      properties:
        teacherUserIds:
          type: array
          items: { type: integer, format: int64 }
          minItems: 1
          maxItems: 3

    MaqamVoteRequest:
      type: object
      required: [maqamType]
      properties:
        maqamType:    { type: string, maxLength: 1000 }
        teacherNote:  { type: string, maxLength: 10000, nullable: true }

    MaqamListenStartRequest:
      type: object
      required: [sessionKey]
      properties:
        sessionKey:           { type: string, maxLength: 100 }
        startPositionSeconds: { type: integer, format: int64, minimum: 0, nullable: true }

    MaqamListenProgressRequest:
      type: object
      required: [sessionKey, addSeconds, positionSeconds]
      properties:
        sessionKey:      { type: string, maxLength: 100 }
        addSeconds:      { type: integer, format: int64, minimum: 0 }
        positionSeconds: { type: integer, format: int64, minimum: 0 }

    MaqamListenEndRequest:
      type: object
      required: [sessionKey]
      properties:
        sessionKey:      { type: string, maxLength: 100 }
        addSeconds:      { type: integer, format: int64, minimum: 0, nullable: true }
        positionSeconds: { type: integer, format: int64, minimum: 0, nullable: true }

    MaqamTeacherVoteView:
      type: object
      properties:
        voteId:             { type: integer, format: int64 }
        teacherUserId:      { type: integer, format: int64 }
        teacherUsername:    { type: string }
        teacherDisplayName: { type: string, nullable: true }
        maqamType:          { type: string, nullable: true }
        teacherNote:        { type: string, nullable: true }
        votedAt:            { type: string, format: date-time, nullable: true }
        updatedAt:          { type: string, format: date-time, nullable: true }
        assignedAt:         { type: string, format: date-time, nullable: true }
        assignedBy:         { type: string, nullable: true }
        totalListenSeconds: { type: integer, format: int64 }
        maxPositionSeconds: { type: integer, format: int64 }
        lastListenAt:       { type: string, format: date-time, nullable: true }

    MaqamResponse:
      type: object
      properties:
        id:                   { type: integer, format: int64 }
        maqamCode:            { type: string, example: "MAQAM_000042" }
        songName:             { type: string }
        producer:             { type: string }
        audioFileName:        { type: string, nullable: true }
        audioContentType:     { type: string, nullable: true }
        audioFileSizeBytes:   { type: integer, format: int64, nullable: true }
        audioDurationSeconds: { type: integer, format: int64, nullable: true }
        streamUrl:            { type: string, format: uri,
                                description: "Backend streaming URL (the raw S3 URL is never exposed)." }
        archiveNote:          { type: string, nullable: true }
        teacherVotes:
          type: array
          items: { $ref: "#/components/schemas/MaqamTeacherVoteView" }
        createdAt: { type: string, format: date-time }
        updatedAt: { type: string, format: date-time }
        removedAt: { type: string, format: date-time, nullable: true }
        createdBy: { type: string }
        updatedBy: { type: string }
        removedBy: { type: string, nullable: true }

    MaqamListenSession:
      type: object
      properties:
        id:                  { type: integer, format: int64 }
        maqamId:             { type: integer, format: int64 }
        maqamCode:           { type: string }
        teacherUserId:       { type: integer, format: int64 }
        teacherUsername:     { type: string }
        sessionKey:          { type: string }
        startedAt:           { type: string, format: date-time }
        endedAt:             { type: string, format: date-time, nullable: true }
        secondsListened:     { type: integer, format: int64 }
        lastPositionSeconds: { type: integer, format: int64 }
        ipAddress:           { type: string, nullable: true,
                               description: "Only present for ADMIN callers." }
        userAgent:           { type: string, nullable: true,
                               description: "Only present for ADMIN callers." }

    MaqamListenSummary:
      type: object
      properties:
        teacherUserId:      { type: integer, format: int64 }
        teacherUsername:    { type: string }
        teacherDisplayName: { type: string, nullable: true }
        totalSeconds:       { type: integer, format: int64 }
        maxPositionSeconds: { type: integer, format: int64 }
        sessionCount:       { type: integer, format: int64 }
        firstListenAt:      { type: string, format: date-time, nullable: true }
        lastListenAt:       { type: string, format: date-time, nullable: true }
        coverageRatio:
          type: number
          format: double
          nullable: true
          description: "totalSeconds / audioDurationSeconds, capped at 1.0. Null when duration is unknown."

    ApiErrorResponse:
      type: object
      properties:
        timestamp: { type: string, format: date-time }
        status:    { type: integer }
        error:     { type: string, example: "MAQAM_VALIDATION_ERROR" }
        message:   { type: string }
        path:      { type: string }
        details:   { type: object, additionalProperties: true, nullable: true }

    PageMeta:
      type: object
      properties:
        totalElements: { type: integer, format: int64 }
        totalPages:    { type: integer }
        size:          { type: integer }
        number:        { type: integer }

    MaqamPage:
      allOf:
        - $ref: "#/components/schemas/PageMeta"
        - type: object
          properties:
            content:
              type: array
              items: { $ref: "#/components/schemas/MaqamResponse" }

    SessionPage:
      allOf:
        - $ref: "#/components/schemas/PageMeta"
        - type: object
          properties:
            content:
              type: array
              items: { $ref: "#/components/schemas/MaqamListenSession" }

  responses:
    Unauthorized:
      description: Missing or invalid authentication.
      content:
        application/json:
          schema: { $ref: "#/components/schemas/ApiErrorResponse" }
    Forbidden:
      description: Authenticated but lacks the required authority, or not on the teacher panel.
      content:
        application/json:
          schema: { $ref: "#/components/schemas/ApiErrorResponse" }
    NotFound:
      description: No such maqam record (or it was trashed).
      content:
        application/json:
          schema: { $ref: "#/components/schemas/ApiErrorResponse" }
    Validation:
      description: Validation error on the request payload.
      content:
        application/json:
          schema: { $ref: "#/components/schemas/ApiErrorResponse" }

paths:

  # ─── Read endpoints (maqam:read) ──────────────────────────────────────

  /api/maqam:
    get:
      summary: List active maqam records (paged)
      description: |
        For ADMIN/EMPLOYEE this returns every active record. For TEACHER
        callers it returns only records they are assigned to.
      tags: [Maqam · Read]
      security:
        - bearerAuth: []
      parameters:
        - in: query
          name: page
          schema: { type: integer, minimum: 0, default: 0 }
        - in: query
          name: size
          schema: { type: integer, minimum: 1, maximum: 500, default: 50 }
        - in: query
          name: sort
          schema: { type: string, example: "createdAt,desc" }
      responses:
        "200":
          description: OK
          content:
            application/json:
              schema: { $ref: "#/components/schemas/MaqamPage" }
        "401": { $ref: "#/components/responses/Unauthorized" }
        "403": { $ref: "#/components/responses/Forbidden" }

    post:
      summary: Create a maqam record with audio
      description: |
        `data` is a JSON part conforming to `MaqamCreateRequest`, `file` is
        the audio (max 5 GB). The audio is uploaded to S3 under the
        `maqam-audio` folder; the URL is computed server-side and never
        accepted from the client.
      tags: [Maqam · Write]
      security:
        - bearerAuth: []
      requestBody:
        required: true
        content:
          multipart/form-data:
            schema:
              type: object
              required: [data, file]
              properties:
                data:
                  type: string
                  format: json
                  description: A `MaqamCreateRequest` serialised as JSON.
                file:
                  type: string
                  format: binary
                  description: Audio file (audio/* MIME type).
      responses:
        "200":
          description: Created
          content:
            application/json:
              schema: { $ref: "#/components/schemas/MaqamResponse" }
        "400": { $ref: "#/components/responses/Validation" }
        "401": { $ref: "#/components/responses/Unauthorized" }
        "403": { $ref: "#/components/responses/Forbidden" }

  /api/maqam/search:
    get:
      summary: Free-text search by song name / producer / maqam code
      tags: [Maqam · Read]
      security: [{ bearerAuth: [] }]
      parameters:
        - in: query
          name: q
          required: true
          schema: { type: string, minLength: 1 }
        - in: query
          name: limit
          schema: { type: integer, minimum: 1, maximum: 100, default: 20 }
      responses:
        "200":
          description: OK
          content:
            application/json:
              schema:
                type: array
                items: { $ref: "#/components/schemas/MaqamResponse" }
        "401": { $ref: "#/components/responses/Unauthorized" }
        "403": { $ref: "#/components/responses/Forbidden" }

  /api/maqam/{maqamCode}:
    parameters:
      - in: path
        name: maqamCode
        required: true
        schema: { type: string, example: "MAQAM_000001" }

    get:
      summary: Get a single active maqam record
      tags: [Maqam · Read]
      security: [{ bearerAuth: [] }]
      responses:
        "200":
          description: OK
          content:
            application/json:
              schema: { $ref: "#/components/schemas/MaqamResponse" }
        "401": { $ref: "#/components/responses/Unauthorized" }
        "403": { $ref: "#/components/responses/Forbidden" }
        "404": { $ref: "#/components/responses/NotFound" }

    patch:
      summary: Update song fields, audio file, or archive note
      description: |
        Send `data` (a `MaqamUpdateRequest` as JSON) plus an optional `file`
        part. Omitting `file` leaves the existing audio in place.
      tags: [Maqam · Write]
      security: [{ bearerAuth: [] }]
      requestBody:
        required: true
        content:
          multipart/form-data:
            schema:
              type: object
              required: [data]
              properties:
                data: { type: string, format: json }
                file: { type: string, format: binary, nullable: true }
      responses:
        "200":
          description: OK
          content:
            application/json:
              schema: { $ref: "#/components/schemas/MaqamResponse" }
        "400": { $ref: "#/components/responses/Validation" }
        "401": { $ref: "#/components/responses/Unauthorized" }
        "403": { $ref: "#/components/responses/Forbidden" }
        "404": { $ref: "#/components/responses/NotFound" }

    delete:
      summary: Soft-trash a maqam record (ADMIN only)
      tags: [Maqam · Write]
      security: [{ bearerAuth: [] }]
      responses:
        "204": { description: No Content }
        "401": { $ref: "#/components/responses/Unauthorized" }
        "403": { $ref: "#/components/responses/Forbidden" }
        "404": { $ref: "#/components/responses/NotFound" }

  /api/maqam/{maqamCode}/stream:
    parameters:
      - in: path
        name: maqamCode
        required: true
        schema: { type: string }
      - in: header
        name: Range
        required: false
        schema: { type: string, example: "bytes=0-65535" }
    get:
      summary: Stream the audio bytes (range-aware)
      description: |
        Returns the audio with `Content-Disposition: inline` and
        `Cache-Control: no-store, private`. Honours HTTP `Range` requests
        from the `<audio>` element. Every call writes a `STREAM` row to
        `maqam_audit_logs`. The S3 URL is never returned.
      tags: [Maqam · Stream]
      security: [{ bearerAuth: [] }]
      responses:
        "200":
          description: Full body
          headers:
            Content-Type:
              schema: { type: string, example: "audio/mpeg" }
            Accept-Ranges:
              schema: { type: string, example: "bytes" }
            Content-Disposition:
              schema: { type: string, example: "inline" }
            X-Content-Type-Options:
              schema: { type: string, example: "nosniff" }
            X-Maqam-Code:
              schema: { type: string }
          content:
            audio/*:
              schema: { type: string, format: binary }
        "206":
          description: Partial content (range response)
          headers:
            Content-Range:
              schema: { type: string, example: "bytes 0-65535/1048576" }
          content:
            audio/*:
              schema: { type: string, format: binary }
        "401": { $ref: "#/components/responses/Unauthorized" }
        "403": { $ref: "#/components/responses/Forbidden" }
        "404": { $ref: "#/components/responses/NotFound" }

  # ─── Voting (maqam:vote, TEACHER only) ────────────────────────────────

  /api/maqam/{maqamCode}/vote:
    parameters:
      - in: path
        name: maqamCode
        required: true
        schema: { type: string }
    post:
      summary: Cast or update the current teacher's vote
      description: |
        Upsert semantics. The first call records `VOTE_CAST` and stamps
        `votedAt`. Subsequent calls record `VOTE_UPDATED` and bump
        `updatedAt`. Teachers may NOT modify another teacher's vote.
      tags: [Maqam · Vote]
      security: [{ bearerAuth: [] }]
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: "#/components/schemas/MaqamVoteRequest" }
      responses:
        "200":
          description: OK
          content:
            application/json:
              schema: { $ref: "#/components/schemas/MaqamResponse" }
        "400": { $ref: "#/components/responses/Validation" }
        "401": { $ref: "#/components/responses/Unauthorized" }
        "403":
          description: |
            Returned with error code `MAQAM_PANEL_ACCESS_DENIED` when the
            caller is not on the teacher panel for this record.
          content:
            application/json:
              schema: { $ref: "#/components/schemas/ApiErrorResponse" }
        "404": { $ref: "#/components/responses/NotFound" }

  # ─── Listen tracking (maqam:vote, TEACHER only) ───────────────────────

  /api/maqam/{maqamCode}/listen/start:
    post:
      summary: Open a listen session
      description: |
        Client mints a `sessionKey` (UUID v4) and reuses it on every
        `progress` / `end` ping for the same play session.
      tags: [Maqam · Listen Tracking]
      security: [{ bearerAuth: [] }]
      parameters:
        - in: path
          name: maqamCode
          required: true
          schema: { type: string }
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: "#/components/schemas/MaqamListenStartRequest" }
      responses:
        "200":
          description: OK
          content:
            application/json:
              schema: { $ref: "#/components/schemas/MaqamListenSession" }
        "400": { $ref: "#/components/responses/Validation" }
        "401": { $ref: "#/components/responses/Unauthorized" }
        "403": { $ref: "#/components/responses/Forbidden" }
        "404": { $ref: "#/components/responses/NotFound" }

  /api/maqam/{maqamCode}/listen/progress:
    post:
      summary: Ping a delta of listened audio time
      description: |
        `addSeconds` is the delta since the previous ping, capped server-side
        at 60s per call to harden against tampering. Updates the per-teacher
        `totalListenSeconds` aggregate on the vote row.
      tags: [Maqam · Listen Tracking]
      security: [{ bearerAuth: [] }]
      parameters:
        - in: path
          name: maqamCode
          required: true
          schema: { type: string }
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: "#/components/schemas/MaqamListenProgressRequest" }
      responses:
        "200":
          description: OK
          content:
            application/json:
              schema: { $ref: "#/components/schemas/MaqamListenSession" }
        "400": { $ref: "#/components/responses/Validation" }
        "401": { $ref: "#/components/responses/Unauthorized" }
        "403": { $ref: "#/components/responses/Forbidden" }
        "404": { $ref: "#/components/responses/NotFound" }

  /api/maqam/{maqamCode}/listen/end:
    post:
      summary: Close the listen session
      tags: [Maqam · Listen Tracking]
      security: [{ bearerAuth: [] }]
      parameters:
        - in: path
          name: maqamCode
          required: true
          schema: { type: string }
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: "#/components/schemas/MaqamListenEndRequest" }
      responses:
        "200":
          description: OK
          content:
            application/json:
              schema: { $ref: "#/components/schemas/MaqamListenSession" }
        "400": { $ref: "#/components/responses/Validation" }
        "401": { $ref: "#/components/responses/Unauthorized" }
        "403": { $ref: "#/components/responses/Forbidden" }
        "404": { $ref: "#/components/responses/NotFound" }

  /api/maqam/{maqamCode}/listen-summary:
    get:
      summary: Per-teacher engagement aggregate
      tags: [Maqam · Listen Tracking]
      security: [{ bearerAuth: [] }]
      parameters:
        - in: path
          name: maqamCode
          required: true
          schema: { type: string }
      responses:
        "200":
          description: OK
          content:
            application/json:
              schema:
                type: array
                items: { $ref: "#/components/schemas/MaqamListenSummary" }
        "401": { $ref: "#/components/responses/Unauthorized" }
        "403": { $ref: "#/components/responses/Forbidden" }
        "404": { $ref: "#/components/responses/NotFound" }

  /api/maqam/{maqamCode}/sessions:
    get:
      summary: Per-session listen log for a record
      tags: [Maqam · Listen Tracking]
      security: [{ bearerAuth: [] }]
      parameters:
        - in: path
          name: maqamCode
          required: true
          schema: { type: string }
        - in: query
          name: teacherUserId
          required: false
          schema: { type: integer, format: int64 }
        - in: query
          name: page
          schema: { type: integer, minimum: 0, default: 0 }
        - in: query
          name: size
          schema: { type: integer, minimum: 1, maximum: 500, default: 100 }
      responses:
        "200":
          description: OK
          content:
            application/json:
              schema: { $ref: "#/components/schemas/SessionPage" }
        "401": { $ref: "#/components/responses/Unauthorized" }
        "403": { $ref: "#/components/responses/Forbidden" }
        "404": { $ref: "#/components/responses/NotFound" }

  # ─── Admin surface (maqam:teacher_manage / maqam:delete / ROLE_ADMIN) ─

  /api/admin/maqam/{maqamCode}/teachers:
    put:
      summary: Replace the teacher panel (1–3 distinct TEACHER users)
      tags: [Maqam · Admin]
      security: [{ bearerAuth: [] }]
      parameters:
        - in: path
          name: maqamCode
          required: true
          schema: { type: string }
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: "#/components/schemas/MaqamTeacherAssignmentRequest" }
      responses:
        "200":
          description: OK
          content:
            application/json:
              schema: { $ref: "#/components/schemas/MaqamResponse" }
        "400": { $ref: "#/components/responses/Validation" }
        "401": { $ref: "#/components/responses/Unauthorized" }
        "403": { $ref: "#/components/responses/Forbidden" }
        "404": { $ref: "#/components/responses/NotFound" }

  /api/admin/maqam/trash:
    get:
      summary: List soft-trashed records
      tags: [Maqam · Admin]
      security: [{ bearerAuth: [] }]
      parameters:
        - in: query
          name: page
          schema: { type: integer, minimum: 0, default: 0 }
        - in: query
          name: size
          schema: { type: integer, minimum: 1, maximum: 500, default: 100 }
      responses:
        "200":
          description: OK
          content:
            application/json:
              schema: { $ref: "#/components/schemas/MaqamPage" }
        "401": { $ref: "#/components/responses/Unauthorized" }
        "403": { $ref: "#/components/responses/Forbidden" }

  /api/admin/maqam/{maqamCode}/restore:
    post:
      summary: Restore a soft-trashed record
      tags: [Maqam · Admin]
      security: [{ bearerAuth: [] }]
      parameters:
        - in: path
          name: maqamCode
          required: true
          schema: { type: string }
      responses:
        "200":
          description: OK
          content:
            application/json:
              schema: { $ref: "#/components/schemas/MaqamResponse" }
        "401": { $ref: "#/components/responses/Unauthorized" }
        "403": { $ref: "#/components/responses/Forbidden" }
        "404": { $ref: "#/components/responses/NotFound" }

  /api/admin/maqam/{maqamCode}/purge:
    delete:
      summary: Permanently delete a trashed record (including its S3 file)
      tags: [Maqam · Admin]
      security: [{ bearerAuth: [] }]
      parameters:
        - in: path
          name: maqamCode
          required: true
          schema: { type: string }
      responses:
        "204": { description: No Content }
        "401": { $ref: "#/components/responses/Unauthorized" }
        "403": { $ref: "#/components/responses/Forbidden" }
        "404": { $ref: "#/components/responses/NotFound" }

  /api/admin/maqam/{maqamCode}/votes/{teacherUserId}:
    delete:
      summary: Clear a specific teacher's vote on a record
      description: |
        Leaves the teacher on the panel — they may re-vote. To remove them
        from the panel entirely, call `PUT /api/admin/maqam/{code}/teachers`
        with a list that omits their id.
      tags: [Maqam · Admin]
      security: [{ bearerAuth: [] }]
      parameters:
        - in: path
          name: maqamCode
          required: true
          schema: { type: string }
        - in: path
          name: teacherUserId
          required: true
          schema: { type: integer, format: int64 }
      responses:
        "200":
          description: OK
          content:
            application/json:
              schema: { $ref: "#/components/schemas/MaqamResponse" }
        "401": { $ref: "#/components/responses/Unauthorized" }
        "403": { $ref: "#/components/responses/Forbidden" }
        "404": { $ref: "#/components/responses/NotFound" }

  /api/admin/maqam/teachers/{teacherUserId}/sessions:
    get:
      summary: Every listen session a teacher ever recorded (ADMIN only)
      description: |
        Includes `ipAddress` and `userAgent` fields. Gated on `ROLE_ADMIN`
        so other authorities cannot reach the PII columns even if granted
        `maqam:read`.
      tags: [Maqam · Admin]
      security: [{ bearerAuth: [] }]
      parameters:
        - in: path
          name: teacherUserId
          required: true
          schema: { type: integer, format: int64 }
        - in: query
          name: page
          schema: { type: integer, minimum: 0, default: 0 }
        - in: query
          name: size
          schema: { type: integer, minimum: 1, maximum: 500, default: 100 }
      responses:
        "200":
          description: OK
          content:
            application/json:
              schema: { $ref: "#/components/schemas/SessionPage" }
        "401": { $ref: "#/components/responses/Unauthorized" }
        "403": { $ref: "#/components/responses/Forbidden" }
```

---

## 3. Error codes

| Code | HTTP | When |
| --- | --- | --- |
| `MAQAM_VALIDATION_ERROR` | 400 | Bean-validation or business-rule failure (e.g. teacher panel out of 1–3 range, non-audio MIME). |
| `MAQAM_NOT_FOUND` | 404 | No active record for `maqamCode`. |
| `MAQAM_PANEL_ACCESS_DENIED` | 403 | Teacher tried to read / vote / stream on a record they are not assigned to. |
| `ACCESS_DENIED` | 403 | Missing the required authority (e.g. employee called `maqam:vote`). |
| `STALE_VERSION` | 409 | Concurrent edit detected on `list_of_maqam` or `maqam_teacher_votes`. |

## 4. Sample curl

```bash
# Create a record (admin or employee with maqam:create)
curl -s -X POST http://localhost:8080/api/maqam \
  -H "Authorization: Bearer $JWT" \
  -F 'data={"songName":"Yare Lawane","producer":"Hassan Zirek","teacherUserIds":[14,17,22]};type=application/json' \
  -F 'file=@./samples/yare-lawane.mp3'

# Teacher casts a vote
curl -s -X POST http://localhost:8080/api/maqam/MAQAM_000001/vote \
  -H "Authorization: Bearer $TEACHER_JWT" \
  -H "Content-Type: application/json" \
  -d '{"maqamType":"Husseini","teacherNote":"Strong descending tetrachord at 1:42."}'

# Start tracking a play session
curl -s -X POST http://localhost:8080/api/maqam/MAQAM_000001/listen/start \
  -H "Authorization: Bearer $TEACHER_JWT" \
  -H "Content-Type: application/json" \
  -d '{"sessionKey":"6f2c…","startPositionSeconds":0}'

# Stream the audio (range-aware) — what the <audio> element calls
curl -s -i http://localhost:8080/api/maqam/MAQAM_000001/stream \
  -H "Authorization: Bearer $TEACHER_JWT" \
  -H "Range: bytes=0-65535"
```
