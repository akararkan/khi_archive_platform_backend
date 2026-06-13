package ak.dev.khi_archive_platform.common.exceptions;

/**
 * Broad family an error belongs to. The frontend uses this to pick a generic
 * UX treatment (toast vs. inline field error vs. redirect to login) without
 * needing to know every specific {@link ErrorCode}.
 *
 * <p>One per HTTP semantic class — keeping this list short on purpose so it
 * stays useful for switch/case in the UI.</p>
 */
public enum ErrorCategory {

    /** Malformed request, JSON parse failure, bad parameter, missing required field. 4xx. */
    BAD_REQUEST,

    /** Bean-validation or domain-rule field errors. Maps to inline form errors. 400. */
    VALIDATION,

    /** No / expired / revoked / malformed credentials. The user must (re)authenticate. 401. */
    AUTHENTICATION,

    /** Authenticated but lacking the required role/authority/ownership. 403. */
    AUTHORIZATION,

    /** Account state prevents login (locked / disabled / expired). 423/403. */
    ACCOUNT_STATE,

    /** Target entity does not exist. 404. */
    NOT_FOUND,

    /** Conflict with current resource state: duplicate, in-use, already-processed, stale version. 409. */
    CONFLICT,

    /** Request entity / upload too large, unsupported media type. 413/415. */
    MEDIA,

    /** Too many requests, throttling. 429. */
    RATE_LIMIT,

    /** Persistence layer error — DB unavailable, query failed, constraint mismatch. 5xx. */
    DATABASE,

    /** File / object storage failure (S3, disk). 5xx. */
    STORAGE,

    /** Downstream service (S3, mail, third-party API) failed or timed out. 5xx. */
    EXTERNAL_SERVICE,

    /** Catch-all for unexpected server errors — never expose internals to the user. 500. */
    SERVER_ERROR
}
