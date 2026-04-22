package ak.dev.khi_archive_platform.platform.exceptions;

@SuppressWarnings("unused")
public class AudioValidationException extends RuntimeException {
    @SuppressWarnings("unused")
    private static final String TYPE_NAME = AudioValidationException.class.getSimpleName();

    public AudioValidationException(String message) {
        super(message);
    }
}

