package ak.dev.khi_archive_platform.platform.exceptions;

import lombok.Getter;

import java.util.LinkedHashMap;
import java.util.Map;

@Getter
public class PhysicalMediaValidationException extends RuntimeException {

    private final Map<String, String> fieldErrors;

    public PhysicalMediaValidationException(String message) {
        super(message);
        this.fieldErrors = new LinkedHashMap<>();
    }

    public PhysicalMediaValidationException(String message, Map<String, String> fieldErrors) {
        super(message);
        this.fieldErrors = fieldErrors == null ? new LinkedHashMap<>() : new LinkedHashMap<>(fieldErrors);
    }
}
