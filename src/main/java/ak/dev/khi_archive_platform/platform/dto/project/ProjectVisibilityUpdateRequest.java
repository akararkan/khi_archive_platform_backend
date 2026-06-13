package ak.dev.khi_archive_platform.platform.dto.project;

import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code PATCH /api/project/{code}/visibility} — the lightweight
 * sibling of the unified item visibility toggle. {@code visibilityCascade}
 * is optional and follows the same {@code CASCADE | NONE} contract as the
 * full update endpoint; when omitted it defaults to {@code NONE} (project
 * flag only — per-media flags are preserved).
 */
public record ProjectVisibilityUpdateRequest(
        @NotNull(message = "isVisibleToPublic is required") Boolean isVisibleToPublic,
        String visibilityCascade
) {}
