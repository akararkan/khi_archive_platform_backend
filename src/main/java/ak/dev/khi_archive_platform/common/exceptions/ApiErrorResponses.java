package ak.dev.khi_archive_platform.common.exceptions;

import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.Map;

/**
 * Small builder helpers shared by {@code @RestControllerAdvice} classes,
 * the JWT filter, and the auth entry-point so every error envelope on the
 * wire is shaped the same way.
 *
 * <p>Pulls the trace id from {@code MDC} keys {@code traceId} / {@code X-Trace-Id}
 * when present — keeps the helper framework-agnostic. If no trace id is in
 * scope the field is simply omitted (thanks to {@code NON_NULL} on the record).</p>
 */
public final class ApiErrorResponses {

    private ApiErrorResponses() {}

    public static ResponseEntity<ApiErrorResponse> build(HttpStatus status,
                                                         String error,
                                                         ErrorCategory category,
                                                         String message,
                                                         String hint,
                                                         String path,
                                                         Map<String, Object> details) {
        return ResponseEntity.status(status).body(of(status, error, category, message, hint, path, details));
    }

    public static ApiErrorResponse of(HttpStatus status,
                                      String error,
                                      ErrorCategory category,
                                      String message,
                                      String hint,
                                      String path,
                                      Map<String, Object> details) {
        return new ApiErrorResponse(
                Instant.now(),
                status.value(),
                error,
                category == null ? null : category.name(),
                message,
                hint,
                path,
                currentTraceId(),
                details == null || details.isEmpty() ? null : details
        );
    }

    /** First non-blank value among the conventional MDC keys, or {@code null}. */
    public static String currentTraceId() {
        for (String key : new String[]{"traceId", "trace_id", "X-Trace-Id", "requestId"}) {
            String value = MDC.get(key);
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    /** Walks the cause chain and returns the deepest non-null message. */
    public static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() != null ? current.getMessage() : throwable.getMessage();
    }
}
