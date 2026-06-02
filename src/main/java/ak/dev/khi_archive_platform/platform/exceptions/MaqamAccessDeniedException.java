package ak.dev.khi_archive_platform.platform.exceptions;

/**
 * Distinct from Spring's {@code AccessDeniedException} because the message is
 * domain-specific: this is raised when a teacher tries to read or write a
 * maqam record they are not assigned to. The exception handler maps it to
 * HTTP 403 with a maqam-specific error code so the front-end can tell the
 * teacher "you're not on this record's panel" rather than the generic
 * "missing authority X" message that backs ACCESS_DENIED.
 */
public class MaqamAccessDeniedException extends RuntimeException {
    public MaqamAccessDeniedException(String message) {
        super(message);
    }
}
