package ak.dev.khi_archive_platform.user.dto.admin;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.Set;

@Data
public class PermissionsChangeRequestDTO {
    /** Permission strings, e.g. {@code "audio:delete"}, {@code "user:update"}.
     *  Must be present in the {@code Permission} enum (which holds both
     *  resource permissions and user-management permissions). */
    @NotEmpty(message = "permissions list cannot be empty")
    private Set<String> permissions;
}
