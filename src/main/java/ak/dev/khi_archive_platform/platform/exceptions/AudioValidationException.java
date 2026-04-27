package ak.dev.khi_archive_platform.platform.exceptions;

import java.util.Map;

public class AudioValidationException extends RuntimeException {

    private final Map<String, String> fieldErrors;

    public AudioValidationException(String message) {
        super(message);
        this.fieldErrors = null;
    }

    public AudioValidationException(String message, Map<String, String> fieldErrors) {
        super(message);
        this.fieldErrors = fieldErrors;
    }

    public Map<String, String> getFieldErrors() {
        return fieldErrors;
    }
}
