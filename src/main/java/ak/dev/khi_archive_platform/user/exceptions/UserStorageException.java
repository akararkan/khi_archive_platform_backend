package ak.dev.khi_archive_platform.user.exceptions;

public class UserStorageException extends RuntimeException {
    public UserStorageException(String message) {
        super(message);
    }

    public UserStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}

