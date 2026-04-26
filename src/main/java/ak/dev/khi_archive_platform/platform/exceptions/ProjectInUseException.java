package ak.dev.khi_archive_platform.platform.exceptions;

public class ProjectInUseException extends RuntimeException {
    public ProjectInUseException(String message) {
        super(message);
    }
}
