package ak.dev.khi_archive_platform.user.exceptions;

/**
 * Thrown when a warning row is requested by id and either does not exist or
 * has been revoked (recipient-facing endpoints filter revoked rows out).
 * Mapped to {@code 404 NOT_FOUND} by {@code GlobalExceptionHandler}.
 */
public class UserWarningNotFoundException extends RuntimeException {
    public UserWarningNotFoundException(String message) {
        super(message);
    }
}
