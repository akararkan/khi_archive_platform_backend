package ak.dev.khi_archive_platform.platform.exceptions;

public class ProjectAlreadyExistsException extends RuntimeException {
    public ProjectAlreadyExistsException(String message) {
        super(message);
    }
}
