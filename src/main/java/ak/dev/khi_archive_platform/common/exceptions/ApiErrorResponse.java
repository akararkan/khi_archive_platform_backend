package ak.dev.khi_archive_platform.common.exceptions;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

/**
 * Uniform error envelope returned by every {@code @RestControllerAdvice} and
 * security filter in the application.
 *
 * <p>Fields are deliberately stable so the frontend can switch on either
 * {@link #error} (machine code, e.g. {@code AUDIO_NOT_FOUND}) or
 * {@link #category} (broad family, e.g. {@code NOT_FOUND}) without parsing
 * the human-readable {@link #message}.</p>
 *
 * <ul>
 *   <li>{@code timestamp} — server clock when the error was produced (UTC)</li>
 *   <li>{@code status} — HTTP status code (200..599)</li>
 *   <li>{@code error} — machine-readable error code, SCREAMING_SNAKE</li>
 *   <li>{@code category} — broad family ({@link ErrorCategory})</li>
 *   <li>{@code message} — user-facing message, safe to display</li>
 *   <li>{@code hint} — optional recovery hint ("reload and retry", "check X")</li>
 *   <li>{@code path} — the request URI that produced the error</li>
 *   <li>{@code traceId} — correlation id pulled from MDC when present</li>
 *   <li>{@code details} — error-specific structured payload (field errors,
 *       required authority, conflicting id, etc.)</li>
 * </ul>
 *
 * <p>{@link JsonInclude.Include#NON_NULL} keeps the wire format compact —
 * absent fields are simply omitted, never serialised as {@code null}.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String category,
        String message,
        String hint,
        String path,
        String traceId,
        Map<String, Object> details
) {

    /**
     * Backwards-compatible constructor — existing call sites that pre-date the
     * {@code category}/{@code hint}/{@code traceId} fields keep working.
     */
    public ApiErrorResponse(Instant timestamp,
                            int status,
                            String error,
                            String message,
                            String path,
                            Map<String, Object> details) {
        this(timestamp, status, error, null, message, null, path, null, details);
    }
}
