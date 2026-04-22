package ak.dev.khi_archive_platform.platform.exceptions;

@SuppressWarnings("unused")
public class AudioAlreadyExistsException extends RuntimeException {
    @SuppressWarnings("unused")
    private static final String TYPE_NAME = AudioAlreadyExistsException.class.getSimpleName();

    public AudioAlreadyExistsException(String message) {
        super(message);
    }
}

