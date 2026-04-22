package ak.dev.khi_archive_platform.platform.exceptions;

@SuppressWarnings("unused")
public class AudioNotFoundException extends RuntimeException {
    @SuppressWarnings("unused")
    private static final String TYPE_NAME = AudioNotFoundException.class.getSimpleName();

    public AudioNotFoundException(String message) {
        super(message);
    }
}

