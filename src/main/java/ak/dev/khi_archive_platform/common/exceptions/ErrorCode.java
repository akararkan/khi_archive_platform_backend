package ak.dev.khi_archive_platform.common.exceptions;

/**
 * Single source of truth for the {@code error} field in {@link ApiErrorResponse}.
 * Keep entries grouped by HTTP class so that both controller advices stay in
 * sync — adding a new entity-specific code below should be the only change
 * needed to surface it consistently to the frontend.
 *
 * <p>Naming rule: {@code <ENTITY>_<CONDITION>} in SCREAMING_SNAKE — the
 * frontend treats these strings as a closed set and switches on them.</p>
 */
public final class ErrorCode {

    private ErrorCode() {}

    // ── 400 Bad Request ─────────────────────────────────────────────────────
    public static final String BAD_REQUEST            = "BAD_REQUEST";
    public static final String JSON_PARSE_ERROR       = "JSON_PARSE_ERROR";
    public static final String VALIDATION_ERROR       = "VALIDATION_ERROR";
    public static final String MISSING_PARAMETER      = "MISSING_PARAMETER";
    public static final String MISSING_REQUEST_PART   = "MISSING_REQUEST_PART";
    public static final String TYPE_MISMATCH          = "TYPE_MISMATCH";
    public static final String CONSTRAINT_VIOLATION   = "CONSTRAINT_VIOLATION";
    public static final String UNKNOWN_PERMISSION     = "UNKNOWN_PERMISSION";

    // Entity-specific validation codes
    public static final String AUDIO_VALIDATION_ERROR           = "AUDIO_VALIDATION_ERROR";
    public static final String VIDEO_VALIDATION_ERROR           = "VIDEO_VALIDATION_ERROR";
    public static final String IMAGE_VALIDATION_ERROR           = "IMAGE_VALIDATION_ERROR";
    public static final String TEXT_VALIDATION_ERROR            = "TEXT_VALIDATION_ERROR";
    public static final String PERSON_VALIDATION_ERROR          = "PERSON_VALIDATION_ERROR";
    public static final String MAQAM_VALIDATION_ERROR           = "MAQAM_VALIDATION_ERROR";
    public static final String PROJECT_VALIDATION_ERROR         = "PROJECT_VALIDATION_ERROR";
    public static final String PHYSICAL_MEDIA_VALIDATION_ERROR  = "PHYSICAL_MEDIA_VALIDATION_ERROR";

    // ── 401 Unauthorized ────────────────────────────────────────────────────
    public static final String AUTHENTICATION_FAILED  = "AUTHENTICATION_FAILED";
    public static final String BAD_CREDENTIALS        = "BAD_CREDENTIALS";
    public static final String TOKEN_MISSING          = "TOKEN_MISSING";
    public static final String TOKEN_EXPIRED          = "TOKEN_EXPIRED";
    public static final String TOKEN_MALFORMED        = "TOKEN_MALFORMED";
    public static final String TOKEN_INVALID_SIGNATURE = "TOKEN_INVALID_SIGNATURE";
    public static final String TOKEN_REVOKED          = "TOKEN_REVOKED";
    public static final String TOKEN_INVALID          = "TOKEN_INVALID";

    // ── 403 Forbidden / Account State ───────────────────────────────────────
    public static final String ACCESS_DENIED          = "ACCESS_DENIED";
    public static final String INSUFFICIENT_AUTHORITY = "INSUFFICIENT_AUTHORITY";
    public static final String ACCOUNT_DISABLED       = "ACCOUNT_DISABLED";
    public static final String ACCOUNT_LOCKED         = "ACCOUNT_LOCKED";
    public static final String CREDENTIALS_EXPIRED    = "CREDENTIALS_EXPIRED";
    public static final String MAQAM_PANEL_ACCESS_DENIED = "MAQAM_PANEL_ACCESS_DENIED";

    // ── 404 Not Found ───────────────────────────────────────────────────────
    public static final String NOT_FOUND              = "NOT_FOUND";
    public static final String USER_NOT_FOUND         = "USER_NOT_FOUND";
    public static final String WARNING_NOT_FOUND      = "WARNING_NOT_FOUND";
    public static final String VIDEO_NOT_FOUND        = "VIDEO_NOT_FOUND";
    public static final String AUDIO_NOT_FOUND        = "AUDIO_NOT_FOUND";
    public static final String IMAGE_NOT_FOUND        = "IMAGE_NOT_FOUND";
    public static final String TEXT_NOT_FOUND         = "TEXT_NOT_FOUND";
    public static final String CATEGORY_NOT_FOUND     = "CATEGORY_NOT_FOUND";
    public static final String PROJECT_NOT_FOUND      = "PROJECT_NOT_FOUND";
    public static final String PERSON_NOT_FOUND       = "PERSON_NOT_FOUND";
    public static final String MAQAM_NOT_FOUND        = "MAQAM_NOT_FOUND";
    public static final String PHYSICAL_MEDIA_NOT_FOUND = "PHYSICAL_MEDIA_NOT_FOUND";
    public static final String CORRECTION_NOT_FOUND   = "CORRECTION_NOT_FOUND";

    // ── 405 / 415 / 413 ─────────────────────────────────────────────────────
    public static final String METHOD_NOT_ALLOWED     = "METHOD_NOT_ALLOWED";
    public static final String UNSUPPORTED_MEDIA_TYPE = "UNSUPPORTED_MEDIA_TYPE";
    public static final String NOT_ACCEPTABLE         = "NOT_ACCEPTABLE";
    public static final String UPLOAD_TOO_LARGE       = "UPLOAD_TOO_LARGE";

    // ── 409 Conflict ────────────────────────────────────────────────────────
    public static final String CONFLICT               = "CONFLICT";
    public static final String STALE_VERSION          = "STALE_VERSION";
    public static final String USER_ALREADY_EXISTS    = "USER_ALREADY_EXISTS";
    public static final String AUDIO_ALREADY_EXISTS   = "AUDIO_ALREADY_EXISTS";
    public static final String VIDEO_ALREADY_EXISTS   = "VIDEO_ALREADY_EXISTS";
    public static final String IMAGE_ALREADY_EXISTS   = "IMAGE_ALREADY_EXISTS";
    public static final String TEXT_ALREADY_EXISTS    = "TEXT_ALREADY_EXISTS";
    public static final String CATEGORY_ALREADY_EXISTS = "CATEGORY_ALREADY_EXISTS";
    public static final String PROJECT_ALREADY_EXISTS = "PROJECT_ALREADY_EXISTS";
    public static final String PERSON_ALREADY_EXISTS  = "PERSON_ALREADY_EXISTS";
    public static final String CATEGORY_IN_USE        = "CATEGORY_IN_USE";
    public static final String PROJECT_IN_USE         = "PROJECT_IN_USE";
    public static final String CORRECTION_ALREADY_PROCESSED = "CORRECTION_ALREADY_PROCESSED";

    // ── 429 ─────────────────────────────────────────────────────────────────
    public static final String RATE_LIMITED           = "RATE_LIMITED";

    // ── 5xx ─────────────────────────────────────────────────────────────────
    public static final String DATABASE_ERROR         = "DATABASE_ERROR";
    public static final String STORAGE_ERROR          = "STORAGE_ERROR";
    public static final String EXTERNAL_SERVICE_ERROR = "EXTERNAL_SERVICE_ERROR";
    public static final String INTERNAL_SERVER_ERROR  = "INTERNAL_SERVER_ERROR";
    public static final String SERVICE_UNAVAILABLE    = "SERVICE_UNAVAILABLE";
    public static final String TIMEOUT                = "TIMEOUT";
}
