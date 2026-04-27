package ak.dev.khi_archive_platform.platform.exceptions;

import java.util.Map;

public class ImageValidationException extends RuntimeException {

    private final Map<String, String> fieldErrors;

    public ImageValidationException(String message) {
        super(message);
        this.fieldErrors = null;
    }

    public ImageValidationException(String message, Map<String, String> fieldErrors) {
        super(message);
        this.fieldErrors = fieldErrors;
    }

    public Map<String, String> getFieldErrors() {
        return fieldErrors;
    }
}
