package ak.dev.khi_archive_platform.user.exceptions;

import lombok.Getter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Thrown when an admin tries to perform an operation that is structurally
 * forbidden by the user-management rules — not a permission problem (handled
 * by Spring Security) and not a bad-input problem (use {@link IllegalArgumentException}),
 * but a domain rule like "you cannot demote yourself" or "ADMINs already hold
 * every permission".
 *
 * Mapped to {@code 409 CONFLICT} by {@code GlobalExceptionHandler} with a
 * stable {@link #errorCode} so the frontend can show the right message
 * regardless of locale.
 */
@Getter
public class IllegalAdminOperationException extends RuntimeException {

    private final String errorCode;
    private final Map<String, Object> details;

    public IllegalAdminOperationException(String errorCode, String message) {
        this(errorCode, message, Map.of());
    }

    public IllegalAdminOperationException(String errorCode, String message, Map<String, Object> details) {
        super(message);
        this.errorCode = errorCode;
        this.details = details == null ? Map.of() : new LinkedHashMap<>(details);
    }
}
