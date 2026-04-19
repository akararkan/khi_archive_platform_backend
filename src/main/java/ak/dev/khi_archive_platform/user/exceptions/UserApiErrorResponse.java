package ak.dev.khi_archive_platform.user.exceptions;

import java.time.Instant;
import java.util.Map;

public record UserApiErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        Map<String, Object> details
) {
}

