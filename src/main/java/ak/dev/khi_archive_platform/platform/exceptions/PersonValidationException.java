package ak.dev.khi_archive_platform.platform.exceptions;

import java.util.Map;

public class PersonValidationException extends RuntimeException {

    private final Map<String, String> fieldErrors;

    public PersonValidationException(String message) {
        super(message);
        this.fieldErrors = null;
    }

    public PersonValidationException(String message, Map<String, String> fieldErrors) {
        super(message);
        this.fieldErrors = fieldErrors;
    }

    public Map<String, String> getFieldErrors() {
        return fieldErrors;
    }
}
