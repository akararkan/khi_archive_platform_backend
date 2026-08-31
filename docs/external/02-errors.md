# Errors

> **Audience:** every API client · **Applies to:** every response under `/api/**` ·
> **Source:** `common/exceptions/ApiErrorResponse.java`, `common/exceptions/ErrorCode.java`,
> `common/exceptions/ErrorCategory.java`, `common/exceptions/ApiErrorResponses.java`,
> `platform/exceptions/ApiExceptionHandler.java`, `user/exceptions/GlobalExceptionHandler.java`,
> `user/exceptions/JwtAuthenticationEntryPoint.java`, `user/exceptions/JwtAccessDeniedHandler.java`,
> `user/jwt/JWTAuthenticationFilter.java`

Every failing request in this API answers with one JSON shape: `ApiErrorResponse`. A client never
has to parse a human message — it switches on `error` for the exact condition, or on `category`
for the broad family. This file is the complete reference for both sets.

---

## 1. The envelope

`ApiErrorResponse` is a Java `record` annotated `@JsonInclude(JsonInclude.Include.NON_NULL)`, so
**absent fields are omitted from the JSON entirely** — they are never serialized as `null`. Do not
write client code that expects `hint`, `traceId` or `details` to be present.

| Field | JSON type | Always present | Meaning |
|---|---|---|---|
| `timestamp` | string (`java.time.Instant`, ISO-8601) | yes | Server clock when the error was produced. The record javadoc documents this as UTC. |
| `status` | number | yes | HTTP status code, mirrors the response status line. |
| `error` | string | yes | Machine-readable code in SCREAMING_SNAKE. Closed set — see [section 5](#5-complete-error-code-reference). |
| `category` | string | yes in practice | Broad family, one of the [`ErrorCategory`](#7-error-categories) names. Omitted only if an envelope is built with the legacy 6-argument constructor. |
| `message` | string | yes | User-facing text, safe to display as-is. |
| `hint` | string | no | Recovery suggestion ("Sign in again to continue."). Omitted when the handler supplies none. |
| `path` | string | yes | `request.getRequestURI()` — the URI that produced the error. |
| `traceId` | string | no | Correlation id, read from the MDC keys `traceId`, `trace_id`, `X-Trace-Id`, `requestId` (first non-blank wins). Omitted when no trace id is in scope. |
| `details` | object | no | Error-specific structured payload. Omitted when the handler passes `null` **or an empty map** — `ApiErrorResponses.of` nulls empty maps out. |

`spring.jackson.serialization.indent-output` is `true`, so error bodies raised by the
`@RestControllerAdvice` handlers arrive pretty-printed. The `401` / `403` bodies written by the
security layer (`JWTAuthenticationFilter`, `JwtAuthenticationEntryPoint`, `JwtAccessDeniedHandler`)
are the exception: those three serialize through a separate `ObjectMapper` that does not read
`spring.jackson.*`, so they arrive **compact**. The examples below are formatted for readability
either way. Field names, types and semantics are identical — only the whitespace differs, so do not
branch on it.

### Real example — `401` from an expired token

```json
{
  "timestamp": "2026-08-26T09:41:12.483Z",
  "status": 401,
  "error": "TOKEN_EXPIRED",
  "category": "AUTHENTICATION",
  "message": "Your session has expired.",
  "hint": "Sign in again to continue.",
  "path": "/api/audio",
  "details": {
    "reason": "expired"
  }
}
```

`traceId` is absent here because no trace id was in the MDC — that is the normal, not the
exceptional, case.

### Real example — `400` field validation

```json
{
  "timestamp": "2026-08-26T09:44:02.117Z",
  "status": 400,
  "error": "VALIDATION_ERROR",
  "category": "VALIDATION",
  "message": "One or more fields failed validation. See 'details' for the per-field reason.",
  "hint": "Fix the highlighted fields and resubmit the request.",
  "path": "/api/auth/register",
  "details": {
    "username": "Username is required",
    "email": "Email must be a valid address with a domain (e.g. user@example.com)"
  }
}
```

The keys inside `details` for `VALIDATION_ERROR` are the rejected field names and the values are
`FieldError.getDefaultMessage()` — the `message` configured on the violated constraint, which in
this codebase is almost always a custom string rather than the framework default. Both come
straight from `BindingResult.getFieldErrors()`. Treat the text as display copy, not as a code.

### Real example — `403` authorization failure

```json
{
  "timestamp": "2026-08-26T09:47:55.902Z",
  "status": 403,
  "error": "ACCESS_DENIED",
  "category": "AUTHORIZATION",
  "message": "You don't have permission to perform this action. Required authority: 'audio:create'.",
  "hint": "Ask an administrator to grant 'audio:create' or to assign a role that includes it.",
  "path": "/api/audio",
  "details": {
    "requiredAuthority": "audio:create",
    "actor": "sara",
    "actorAuthorities": ["audio:read", "image:read", "video:read"],
    "requestMethod": "POST"
  }
}
```

`requiredAuthority` is present only when the denial reached the controller advice **and** the
handler method (or its class) carries a `@PreAuthorize` matching `hasAuthority('…')` or
`hasRole('…')`. Denials caught at the filter layer never carry it — see
[section 3](#3-which-producer-answers-which-request).

---

## 2. The legacy `UserApiErrorResponse`

`user/exceptions/UserApiErrorResponse.java` still exists and declares the pre-`category` shape:

```java
public record UserApiErrorResponse(
        Instant timestamp, int status, String error,
        String message, String path, Map<String, Object> details) {}
```

It has no `category`, no `hint`, no `traceId`, and no `@JsonInclude`.

**No endpoint emits it.** The record is referenced nowhere outside its own declaration —
`GlobalExceptionHandler` (the user-package advice) returns `ResponseEntity<ApiErrorResponse>` from
every handler, exactly like the platform advice. Treat `UserApiErrorResponse` as dead code, not as
a second wire format to support.

The one place the older shape can still surface is `ApiErrorResponse`'s backwards-compatible
6-argument constructor, which sets `category`, `hint` and `traceId` to `null`; under `NON_NULL`
those three keys are then simply absent. No production call site in `src/main` uses it — every
envelope goes through `ApiErrorResponses.of(...)`, which always fills `category`.

---

## 3. Which producer answers which request

All five producers emit the identical `ApiErrorResponse` envelope. They differ only in which
requests they can intercept and which codes they can emit.

| Producer | Layer | Requests it can answer | Codes it emits |
|---|---|---|---|
| `JWTAuthenticationFilter` | servlet filter | Everything **except** the paths its `shouldNotFilter` skips: any URI starting `/api/guest/`, plus exactly `/api/auth/login`, `/api/auth/register`, `/api/auth/register-with-image` | `TOKEN_EXPIRED`, `TOKEN_INVALID_SIGNATURE`, `TOKEN_INVALID`, `TOKEN_MALFORMED`, `TOKEN_REVOKED`, `INTERNAL_SERVER_ERROR` |
| `JwtAuthenticationEntryPoint` | Spring Security entry point | Any protected path reached with no usable authentication | `TOKEN_MISSING`, `AUTHENTICATION_FAILED` |
| `JwtAccessDeniedHandler` | Spring Security denial handler | Denials raised inside the filter chain, before the controller | `ACCESS_DENIED` |
| `ApiExceptionHandler` | `@RestControllerAdvice(basePackages = "ak.dev.khi_archive_platform.platform")` | Controllers in the `platform` package | Content, guest, correction, maqam, physical-media, analytics codes |
| `GlobalExceptionHandler` | `@RestControllerAdvice(basePackages = "ak.dev.khi_archive_platform.user")` | Controllers in the `user` package | Auth, account-state, user, warning, permission codes |

Base paths served by each advice, copied from the `@RequestMapping` on each controller class:

| Advice | Base paths |
|---|---|
| `ApiExceptionHandler` (platform) | `/api/guest`, `/api/audio`, `/api/video`, `/api/image`, `/api/text`, `/api/person`, `/api/project`, `/api/category`, `/api/items`, `/api/tags`, `/api/keywords`, `/api/maqam`, `/api/physical-media`, `/api/physical-media/types`, `/api/corrections`, `/api/khi-logo`, `/api/analytics`, `/api/analytics/maqam`, `/api/admin/tags`, `/api/admin/keywords`, `/api/admin/maqam`, `/api/admin/physical-media`, `/api/admin/corrections` |
| `GlobalExceptionHandler` (user) | `/api/auth`, `/api/auth/sessions`, `/api/user`, `/api/warnings`, `/api/admin/users`, `/api/admin/users/audit-logs`, `/api/admin/warnings` |

Two consequences worth planning for:

- **`/api/guest/**` never returns a token error.** The JWT filter skips those URIs entirely, so a
  stale or expired auth cookie cannot turn a public catalog read into a `401`.
- **Account-state codes are user-package only.** `ACCOUNT_LOCKED` (`423`), `ACCOUNT_DISABLED` and
  `CREDENTIALS_EXPIRED` are raised by `GlobalExceptionHandler`; a `platform` endpoint will not
  produce them.

---

## 4. `details` payloads by code

| Code(s) | Keys inside `details` |
|---|---|
| `VALIDATION_ERROR` | one key per rejected field → its validation message |
| `CONSTRAINT_VIOLATION` | one key per violated property path → its message |
| `AUDIO_VALIDATION_ERROR` and the other seven `*_VALIDATION_ERROR` codes | the exception's own field-error map, field name → reason |
| `JSON_PARSE_ERROR` | `field` (dotted path, only when Jackson can resolve one), `location` |
| `MISSING_PARAMETER` | `parameter`, `expectedType` |
| `MISSING_REQUEST_PART` | `part` |
| `TYPE_MISMATCH` | `parameter`, `rejectedValue`, `expectedType` (omitted when the required type is unknown) |
| `METHOD_NOT_ALLOWED` | `method`, `supportedMethods` (array) |
| `UNSUPPORTED_MEDIA_TYPE` | `received`, `supported` (array, when non-empty) |
| `NOT_ACCEPTABLE` | `supported` (array, when non-empty) |
| `UPLOAD_TOO_LARGE` | `maxBytes` (only when the cause is `MaxUploadSizeExceededException`) |
| `STALE_VERSION` | `entity` (simple class name of the record that lost the race) |
| `ACCESS_DENIED` | `requiredAuthority` (when resolvable), `actor`, `actorAuthorities` (sorted, de-duplicated array), `requestMethod` |
| `UNKNOWN_PERMISSION` | `unknown` (the rejected permission codes), `catalog` (the literal string `/api/admin/users/catalog/permissions`) |
| `TOKEN_EXPIRED`, `TOKEN_INVALID_SIGNATURE`, `TOKEN_MALFORMED`, `TOKEN_REVOKED`, `TOKEN_INVALID` | `reason` — one of `expired`, `signature_mismatch`, `algorithm_mismatch`, `invalid_claim`, `malformed`, `revoked`. Omitted on the filter's generic verification failure. |
| everything else | no `details` key at all |

`TOKEN_MISSING` carries no `details`: the entry point passes an empty map, and empty maps are
dropped by `ApiErrorResponses.of`.

---

## 5. Complete error-code reference

Every constant declared in `common/exceptions/ErrorCode.java`, grouped by HTTP status class. The
"Status" column is the status the emitting handler actually sets.

### 5.1 `400 Bad Request`

| `error` code | Status | `category` | When it occurs |
|---|---|---|---|
| `BAD_REQUEST` | `400` | `BAD_REQUEST` | An `IllegalArgumentException` escaped a service (both advices). Also used with `MEDIA` category when a `MultipartException` cannot be parsed (platform advice only — the user advice maps `MultipartException` to `UPLOAD_TOO_LARGE` instead), and with `BAD_REQUEST` category for any 4xx `ResponseStatusException` other than `404` (platform advice only) — in that case `status` is whatever the exception carried, not necessarily `400`. |
| `JSON_PARSE_ERROR` | `400` | `BAD_REQUEST` | Request body is not readable as JSON (`HttpMessageNotReadableException`). |
| `VALIDATION_ERROR` | `400` | `VALIDATION` | Bean validation failed on `@Valid` body (`MethodArgumentNotValidException`) or query/form binding failed (`BindException`). |
| `MISSING_PARAMETER` | `400` | `BAD_REQUEST` | A required `@RequestParam` was absent. |
| `MISSING_REQUEST_PART` | `400` | `BAD_REQUEST` | A multipart request omitted a required part. |
| `TYPE_MISMATCH` | `400` | `BAD_REQUEST` | A path variable or query parameter could not be converted to the declared type. |
| `CONSTRAINT_VIOLATION` | `400` | `VALIDATION` | A method-level `jakarta.validation` constraint was violated. |
| `UNKNOWN_PERMISSION` | `400` | `VALIDATION` | An admin permission grant/revoke named a code that is not in the permission catalog. User advice only. |
| `AUDIO_VALIDATION_ERROR` | `400` | `VALIDATION` | Domain validation of an audio payload failed. Platform advice only. |
| `VIDEO_VALIDATION_ERROR` | `400` | `VALIDATION` | Domain validation of a video payload failed. |
| `IMAGE_VALIDATION_ERROR` | `400` | `VALIDATION` | Domain validation of an image payload failed. |
| `TEXT_VALIDATION_ERROR` | `400` | `VALIDATION` | Domain validation of a text payload failed. |
| `PERSON_VALIDATION_ERROR` | `400` | `VALIDATION` | Domain validation of a person payload failed. |
| `MAQAM_VALIDATION_ERROR` | `400` | `VALIDATION` | Domain validation of a maqam record failed. |
| `PROJECT_VALIDATION_ERROR` | `400` | `VALIDATION` | Domain validation of a project payload failed. |
| `PHYSICAL_MEDIA_VALIDATION_ERROR` | `400` | `VALIDATION` | Domain validation of a physical-media row failed (including spreadsheet import rows). |

### 5.2 `401 Unauthorized`

| `error` code | Status | `category` | When it occurs |
|---|---|---|---|
| `AUTHENTICATION_FAILED` | `401` | `AUTHENTICATION` | A generic `AuthenticationException` reached either advice, or the entry point fired while some credential material was present. |
| `BAD_CREDENTIALS` | `401` | `AUTHENTICATION` | Wrong username or password (`BadCredentialsException`). Message is always `"Username or password is incorrect."` — it never reveals which half was wrong. |
| `TOKEN_MISSING` | `401` | `AUTHENTICATION` | Protected endpoint reached with no credentials at all — an `InsufficientAuthenticationException`, or no `Authorization` header and no cookies on the request. |
| `TOKEN_EXPIRED` | `401` | `AUTHENTICATION` | The JWT's expiry has passed. The filter also clears the auth cookie. |
| `TOKEN_MALFORMED` | `401` | `AUTHENTICATION` | The token could not be decoded as a JWT at all. Cookie cleared. |
| `TOKEN_INVALID_SIGNATURE` | `401` | `AUTHENTICATION` | Signature verification failed (`reason: signature_mismatch`) or the token used an unexpected signing algorithm (`reason: algorithm_mismatch`). Cookie cleared. |
| `TOKEN_REVOKED` | `401` | `AUTHENTICATION` | The token is blacklisted — logout or a forced session kill. Cookie cleared. |
| `TOKEN_INVALID` | `401` | `AUTHENTICATION` | An invalid claim (`reason: invalid_claim`) or any other verification failure. Cookie cleared. |
| `CREDENTIALS_EXPIRED` | `401` | `ACCOUNT_STATE` | `CredentialsExpiredException` — the password itself has expired. Note the category is `ACCOUNT_STATE`, not `AUTHENTICATION`. User advice only. |

### 5.3 `403 Forbidden` and account state

| `error` code | Status | `category` | When it occurs |
|---|---|---|---|
| `ACCESS_DENIED` | `403` | `AUTHORIZATION` | Authenticated but the required authority is missing. Emitted by both advices (with `requiredAuthority` when the `@PreAuthorize` can be parsed) and by `JwtAccessDeniedHandler` (without it). |
| `INSUFFICIENT_AUTHORITY` | — | — | Declared in `ErrorCode` but **never emitted** by any handler in `src/main`. Do not branch on it. |
| `ACCOUNT_DISABLED` | `403` | `ACCOUNT_STATE` | `DisabledException` ("This account is disabled.") or `AccountExpiredException` ("This account has expired."). Both map to the same code. User advice only. |
| `ACCOUNT_LOCKED` | `423` | `ACCOUNT_STATE` | `LockedException`. Note the status is **423 Locked**, not `403`. User advice only. |
| `CREDENTIALS_EXPIRED` | `401` | `ACCOUNT_STATE` | See [5.2](#52-401-unauthorized). |
| `MAQAM_PANEL_ACCESS_DENIED` | `403` | `AUTHORIZATION` | The maqam voting panel rejected the caller: only accounts whose role is `TEACHER` may cast a vote or track a listening session, a TEACHER may only touch records they are on the vote panel of, and an unauthenticated caller is rejected outright. Platform advice only. |

### 5.4 `404 Not Found`

All entries below are `404` with `category` `NOT_FOUND`.

| `error` code | Emitted by | When it occurs |
|---|---|---|
| `NOT_FOUND` | both advices | No handler matched the URL (`NoHandlerFoundException` / `NoResourceFoundException`); the message is `"Endpoint not found: <METHOD> <URI>"`. The platform advice also maps any `ResponseStatusException` carrying `404` to this code — which is how the media stream and proxy controllers signal a missing or trashed record. |
| `USER_NOT_FOUND` | user advice | `UserNotFoundException` or `UsernameNotFoundException`. |
| `WARNING_NOT_FOUND` | user advice | The warning id does not exist, or the warning was revoked (revoked warnings are soft-deleted). |
| `VIDEO_NOT_FOUND` | platform advice | No active video with that identifier. |
| `AUDIO_NOT_FOUND` | platform advice | No active audio with that identifier. |
| `IMAGE_NOT_FOUND` | platform advice | No active image with that identifier. |
| `TEXT_NOT_FOUND` | platform advice | No active text with that identifier. |
| `CATEGORY_NOT_FOUND` | platform advice | The referenced category does not exist. |
| `PROJECT_NOT_FOUND` | platform advice | The referenced project does not exist; trashed projects must be restored before use. |
| `PERSON_NOT_FOUND` | platform advice | The referenced person record does not exist. |
| `MAQAM_NOT_FOUND` | platform advice | The referenced maqam record does not exist. |
| `PHYSICAL_MEDIA_NOT_FOUND` | platform advice | The referenced physical-media row does not exist. |
| `CORRECTION_NOT_FOUND` | platform advice | The referenced correction submission does not exist; processed corrections may have been archived. |
| `KHI_LOGO_NOT_FOUND` | platform advice | The referenced KHI logo record does not exist. |

### 5.5 `405`, `406`, `413`, `415`

| `error` code | Status | `category` | When it occurs |
|---|---|---|---|
| `METHOD_NOT_ALLOWED` | `405` | `BAD_REQUEST` | The HTTP method is not mapped on that path. `details.supportedMethods` lists what is. Note the category is `BAD_REQUEST`, not `MEDIA`. |
| `NOT_ACCEPTABLE` | `406` | `MEDIA` | The `Accept` header excludes every representation the server can produce. Platform advice only. |
| `UPLOAD_TOO_LARGE` | `413` | `MEDIA` | The upload exceeded the multipart limit (`spring.servlet.multipart.max-file-size` is `5GB`, `max-request-size` is `6GB`). `details.maxBytes` reports the cap, and is present only for `MaxUploadSizeExceededException`. The user advice also routes every other `MultipartException` here, without `details`. |
| `UNSUPPORTED_MEDIA_TYPE` | `415` | `MEDIA` | The request `Content-Type` is not consumable by the handler. |

### 5.6 `409 Conflict`

All entries below are `409` with `category` `CONFLICT`.

| `error` code | Emitted by | When it occurs |
|---|---|---|
| `CONFLICT` | both advices | A `DataIntegrityViolationException` — a unique key, foreign key or `NOT NULL` constraint blocked the write. `message` is the deepest cause message from the driver. |
| `STALE_VERSION` | platform advice | Optimistic-locking failure: somebody else saved the same record first. `details.entity` names the entity. Reload and re-apply. |
| `USER_ALREADY_EXISTS` | user advice | Registration, a profile self-update, or an admin user create/update hit an existing username or email. `message` is English from `UserService`/`AdminUserService` but Kurdish (Sorani) from the `PUT /api/user/profile` path — switch on `error`, never on the text. |
| `AUDIO_ALREADY_EXISTS` | platform advice | An audio record with the same identifying fields already exists. |
| `VIDEO_ALREADY_EXISTS` | platform advice | A video record with the same identifying fields already exists. |
| `IMAGE_ALREADY_EXISTS` | platform advice | An image record with the same identifying fields already exists. |
| `TEXT_ALREADY_EXISTS` | platform advice | A text record with the same identifying fields already exists. |
| `CATEGORY_ALREADY_EXISTS` | platform advice | A category with that name already exists. |
| `PROJECT_ALREADY_EXISTS` | platform advice | A project with that name already exists. |
| `PERSON_ALREADY_EXISTS` | platform advice | A person with those identifying fields already exists. |
| `CATEGORY_IN_USE` | platform advice | Delete refused — media still reference this category. |
| `PROJECT_IN_USE` | platform advice | Delete refused — media still reference this project. |
| `CORRECTION_ALREADY_PROCESSED` | platform advice | The correction was already accepted or rejected. |

### 5.7 `429`

| `error` code | Status | `category` | When it occurs |
|---|---|---|---|
| `RATE_LIMITED` | — | — | Declared in `ErrorCode` but **never emitted** by any handler in `src/main`. No rate limiting is wired up. |

### 5.8 `5xx`

| `error` code | Status | `category` | When it occurs |
|---|---|---|---|
| `DATABASE_ERROR` | `500` | `DATABASE` | A `DataAccessException` reached either advice. The real cause is logged server-side and never returned. |
| `STORAGE_ERROR` | `500` | `STORAGE` | An `IOException` during request handling (both advices), or a `UserStorageException` while writing a profile image (user advice). |
| `TIMEOUT` | `504` | `DATABASE` | `QueryTimeoutException` — the database query ran too long. Note the status is **504 Gateway Timeout**, and the category is `DATABASE`, not `EXTERNAL_SERVICE`. Platform advice only. |
| `INTERNAL_SERVER_ERROR` | `500` | `SERVER_ERROR` | The catch-all for any unhandled `Exception` in either advice, or an unexpected failure inside the JWT filter. From the catch-all, `message` is the fixed string `"An unexpected error occurred."` — internals are never leaked. The platform advice also maps a `ResponseStatusException` carrying a non-4xx status to this code; there `status` mirrors the exception's status rather than `500`, and `message` is the exception's reason (or the status reason phrase). |
| `EXTERNAL_SERVICE_ERROR` | — | — | Declared in `ErrorCode` but **never emitted** by any handler in `src/main`. |
| `SERVICE_UNAVAILABLE` | — | — | Declared in `ErrorCode` but **never emitted** by any handler in `src/main`. |

### 5.9 Codes that are not in `ErrorCode`

`IllegalAdminOperationException` carries its own `errorCode` string, chosen at the throw site
rather than taken from the `ErrorCode` catalog. When it is thrown from a **`user`-package**
controller it becomes `409 CONFLICT` / category `CONFLICT`, with the hint _"This operation is
structurally forbidden by the admin rules — pick a different target or change scope."_

| `error` code | Raised when |
|---|---|
| `ADMIN_PERMISSIONS_LOCKED` | Trying to grant or revoke per-user permissions on an ADMIN — admins hold every permission through the role. |
| `LAST_ADMIN` | Trying to delete the only remaining ADMIN. |
| `SELF_WARNING` | An admin tried to send a warning to their own account. |
| `WARNING_NOT_FOR_YOU` | Trying to acknowledge a warning addressed to someone else. |
| `UNAUTHENTICATED` | A warning-management call ran with no authenticated actor. |
| `SELF_DEACTIVATE`, `SELF_LOCK`, `SELF_FORCE_LOGOUT`, `SELF_DELETE`, `SELF_DEMOTION`, `SELF_USER_MGMT_REVOKE` | An admin targeted their own account with an operation that would lock them out. |

**Important caveat for the correction endpoints.** `GuestCorrectionService` also throws
`IllegalAdminOperationException` — `CORRECTION_NOT_YOURS` and `UNAUTHENTICATED` from
`/api/corrections`, `EMPLOYEE_NOT_FOUND` from the admin forward action on
`/api/admin/corrections` — but both controllers live in the `platform` package, and
`ApiExceptionHandler` has no handler for that exception type. Those throws therefore fall through
to the `@ExceptionHandler(Exception.class)` catch-all and reach the client as
`500` / `INTERNAL_SERVER_ERROR` / `SERVER_ERROR` with the generic message, **not** as `409` and not
with those codes. Do not write client branches on `CORRECTION_NOT_YOURS`.

---

## 6. Auth and token codes — what the client should do

Everything in this table arrives as `401` with `category` `AUTHENTICATION` unless noted.

| `error` code | Produced by | Cookie cleared by server | What the client should do |
|---|---|---|---|
| `TOKEN_MISSING` | `JwtAuthenticationEntryPoint` | no | Not signed in. Send the user to the login screen; do not retry. |
| `TOKEN_EXPIRED` | `JWTAuthenticationFilter` | yes | Session elapsed. Drop any cached identity and prompt for a fresh login — `/api/auth` exposes no token-refresh endpoint, only `register`, `register-with-image`, `login`, `logout` and `logout-all`. |
| `TOKEN_REVOKED` | `JWTAuthenticationFilter` | yes | The session was invalidated — logout elsewhere, or an admin forced a session kill. Clear local state and prompt for login. Treat as intentional, not as a bug. |
| `TOKEN_MALFORMED` | `JWTAuthenticationFilter` | yes | The stored credential is garbage. Clear the `Authorization` header and any locally held token, then log in again. |
| `TOKEN_INVALID_SIGNATURE` | `JWTAuthenticationFilter` | yes | Token was tampered with, signed with a different key, or uses an unexpected algorithm (`details.reason` distinguishes `signature_mismatch` from `algorithm_mismatch`). Force a fresh login; if it recurs for every user, the server's `JWT_SECRET` was rotated. |
| `TOKEN_INVALID` | `JWTAuthenticationFilter` | yes | An invalid claim or any other verification failure. Force a fresh login. |
| `AUTHENTICATION_FAILED` | entry point, both advices | no | Generic authentication failure. Sign in and retry. |
| `BAD_CREDENTIALS` | both advices | no | Wrong username or password on the login form. Show an inline form error; do not log the user out of anything. |
| `ACCESS_DENIED` (`403`, `AUTHORIZATION`) | access-denied handler, both advices | no | The user is signed in but lacks the authority. Do **not** redirect to login — that will loop. Show a permission message; `details.requiredAuthority` versus `details.actorAuthorities` is enough to render "you have X, you need Y". |
| `ACCOUNT_DISABLED` (`403`, `ACCOUNT_STATE`) | user advice | no | Terminal for the client — direct the user to an administrator. Retrying will not help. |
| `ACCOUNT_LOCKED` (`423`, `ACCOUNT_STATE`) | user advice | no | Show the message verbatim (it may carry the lock reason) and stop. Handle `423` explicitly — a client that only branches on `401`/`403` will fall through to a generic error. |
| `CREDENTIALS_EXPIRED` (`401`, `ACCOUNT_STATE`) | user advice | no | Route to a password reset, not to the normal login form. |

Two behaviors worth building around:

- **The server clears the cookie for you on every token failure from the filter.** It calls
  `jwtCookieService.clearAuthCookie(response)` before writing the body, so the browser will not
  keep re-sending a dead token. The entry point and the advices do not clear it.
- **CORS headers survive the short-circuit.** The filter stamps `Access-Control-Allow-Origin`,
  `Access-Control-Allow-Credentials` and `Vary: Origin` on error responses, but only when the
  request's `Origin` is in the configured allow-list. If it is not, the browser reports an opaque
  network/CORS failure and your handler never sees the JSON — check `CORS_ALLOWED_ORIGINS` before
  debugging the error body.

---

## 7. Error categories

Complete list of `ErrorCategory` values with the UX treatment each one's javadoc calls for.

| `category` | Javadoc definition | Suggested UX treatment |
|---|---|---|
| `BAD_REQUEST` | Malformed request, JSON parse failure, bad parameter, missing required field. 4xx. | Developer-facing bug in most cases — generic toast, log it, do not blame the user. |
| `VALIDATION` | Bean-validation or domain-rule field errors. 400. | "Maps to inline form errors" — bind each `details` key to its form field. |
| `AUTHENTICATION` | No / expired / revoked / malformed credentials. 401. | "The user must (re)authenticate" — redirect to login. |
| `AUTHORIZATION` | Authenticated but lacking the required role/authority/ownership. 403. | Permission message, not a login redirect. |
| `ACCOUNT_STATE` | Account state prevents login (locked / disabled / expired). 423/403. | Terminal message pointing at an administrator or a password reset. |
| `NOT_FOUND` | Target entity does not exist. 404. | Empty state or "not found" page; drop the id from any cache. |
| `CONFLICT` | Conflict with current resource state: duplicate, in-use, already-processed, stale version. 409. | Reload the current state and let the user re-decide; safe to offer a retry after refresh. |
| `MEDIA` | Request entity / upload too large, unsupported media type. 413/415. | Inline error on the file picker; surface `details.maxBytes` or `details.supported`. |
| `RATE_LIMIT` | Too many requests, throttling. 429. | Back off and retry later. No handler currently produces this category. |
| `DATABASE` | Persistence layer error — DB unavailable, query failed, constraint mismatch. 5xx. | Generic failure toast with a retry button; show `traceId` if present. |
| `STORAGE` | File / object storage failure (S3, disk). 5xx. | Same as `DATABASE` — retry, then escalate with the `traceId`. |
| `EXTERNAL_SERVICE` | Downstream service (S3, mail, third-party API) failed or timed out. 5xx. | Retry with backoff. No handler currently produces this category. |
| `SERVER_ERROR` | Catch-all for unexpected server errors — "never expose internals to the user". 500. | Generic failure screen; report the `traceId` to support. |

---

## 8. Handling errors in a client

Branch on `category` for the default behavior, then override with specific `error` codes only
where the UX genuinely differs.

```js
async function apiFetch(path, init = {}) {
  const res = await fetch(`${BASE_URL}${path}`, { credentials: "include", ...init });
  if (res.ok) return res.json();

  // Every failure in this API is JSON, but a proxy or CORS failure may not be.
  let err;
  try {
    err = await res.json();
  } catch {
    throw { status: res.status, error: "INTERNAL_SERVER_ERROR", category: "SERVER_ERROR",
            message: "The server returned an unreadable response." };
  }

  switch (err.category) {
    case "AUTHENTICATION":
      // TOKEN_MISSING | TOKEN_EXPIRED | TOKEN_REVOKED | TOKEN_MALFORMED
      // | TOKEN_INVALID_SIGNATURE | TOKEN_INVALID | AUTHENTICATION_FAILED | BAD_CREDENTIALS
      if (err.error === "BAD_CREDENTIALS") showFormError("password", err.message);
      else redirectToLogin();
      break;

    case "AUTHORIZATION":
      // Never redirect to login here — the user is already signed in.
      showPermissionDialog(err.message, err.details?.requiredAuthority);
      break;

    case "ACCOUNT_STATE":
      // ACCOUNT_DISABLED (403) | ACCOUNT_LOCKED (423) | CREDENTIALS_EXPIRED (401)
      if (err.error === "CREDENTIALS_EXPIRED") redirectToPasswordReset();
      else showBlockingMessage(err.message, err.hint);
      break;

    case "VALIDATION":
      // details is field -> message
      for (const [field, reason] of Object.entries(err.details ?? {})) showFormError(field, reason);
      break;

    case "NOT_FOUND":
      showEmptyState(err.message);
      break;

    case "CONFLICT":
      // STALE_VERSION carries details.entity; reload before retrying.
      await reloadCurrentRecord();
      showToast(err.message, err.hint);
      break;

    case "MEDIA":
      showFileError(err.message, err.details?.maxBytes ?? err.details?.supported);
      break;

    case "RATE_LIMIT":
      scheduleRetryWithBackoff();
      break;

    case "BAD_REQUEST":
    case "DATABASE":
    case "STORAGE":
    case "EXTERNAL_SERVICE":
    case "SERVER_ERROR":
    default:
      showToast(err.message, err.hint, err.traceId);
      break;
  }
  throw err;
}
```

Rules that keep this robust:

- Switch on `category` first, `error` second. `category` is a short closed enum; `error` grows as
  new entity codes are added.
- Always have a `default` branch — new `ErrorCode` constants are added without a version bump.
- Never key layout on `hint`, `traceId` or `details` being present. Under `NON_NULL` they are
  frequently absent.
- Handle `423` explicitly (`ACCOUNT_LOCKED`) — it is the one status outside the usual
  `400/401/403/404/405/406/409/413/415/500` set, alongside `504` for `TIMEOUT`.

### Reproducing an error with curl

```bash
# 401 TOKEN_MISSING — protected endpoint, no credentials at all
curl -s -i "{{BASE_URL}}/api/audio?page=0&size=20"

# 401 TOKEN_EXPIRED / TOKEN_MALFORMED — a dead cookie on a protected endpoint
curl -s "{{BASE_URL}}/api/audio" \
  -H "Cookie: khi_auth_token=not-a-real-jwt"

# 403 ACCESS_DENIED — signed in, but without the authority the handler requires
# (DELETE /api/audio/{audioCode} is annotated @PreAuthorize("hasAuthority('audio:delete')"))
curl -s -X DELETE "{{BASE_URL}}/api/audio/AUD-001" \
  -H "Cookie: khi_auth_token=$TOKEN"

# 404 NOT_FOUND — no handler matches, message echoes the method and URI.
# The cookie is required: everything under /api/** is authenticated(), so without
# credentials this answers 401 TOKEN_MISSING before routing is ever attempted.
curl -s "{{BASE_URL}}/api/does-not-exist" \
  -H "Cookie: khi_auth_token=$TOKEN"
```

Note that the same missing-cookie request against a public guest path succeeds rather than
erroring: `SecurityConfig` marks `/api/guest/**` `permitAll()`, and the JWT filter skips those
URIs entirely so a stale cookie cannot produce a token error either:

```bash
curl -s "{{BASE_URL}}/api/guest/feed?page=0&size=20"
```

---

## Related

- [External API index](./README.md) — the folder README and endpoint map.
- [Conventions](./01-conventions.md) — base URL, paging envelope, date and time-zone formats,
  and the cookie-authentication conventions these examples rely on.
