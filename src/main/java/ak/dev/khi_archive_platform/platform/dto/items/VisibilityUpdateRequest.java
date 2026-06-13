package ak.dev.khi_archive_platform.platform.dto.items;

import jakarta.validation.constraints.NotNull;

/**
 * Body of the lightweight visibility-toggle endpoints. Using boxed
 * {@link Boolean} so a missing/null field trips {@link NotNull} validation
 * instead of silently defaulting to {@code false}.
 */
public record VisibilityUpdateRequest(
        @NotNull(message = "isPublic is required") Boolean isPublic
) {}
