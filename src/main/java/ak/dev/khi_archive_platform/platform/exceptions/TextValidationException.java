package ak.dev.khi_archive_platform.platform.exceptions;

import java.util.Map;

public class TextValidationException extends RuntimeException {

    private final Map<String, String> fieldErrors;

    public TextValidationException(String message) {
        super(message);
        this.fieldErrors = null;
    }

    public TextValidationException(String message, Map<String, String> fieldErrors) {
        super(message);
        this.fieldErrors = fieldErrors;
    }

    public Map<String, String> getFieldErrors() {
        return fieldErrors;
    }
}
