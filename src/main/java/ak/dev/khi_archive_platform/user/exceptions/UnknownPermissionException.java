package ak.dev.khi_archive_platform.user.exceptions;

import lombok.Getter;

import java.util.Set;
import java.util.TreeSet;

/**
 * Thrown when a permission grant/revoke request lists a string that is not
 * in the {@code Permission} catalog. Carries the offending strings so the
 * frontend can highlight them inline.
 */
@Getter
public class UnknownPermissionException extends RuntimeException {

    private final Set<String> unknown;

    public UnknownPermissionException(Set<String> unknown) {
        super("Unknown permission(s): " + unknown
                + ". Use GET /api/admin/users/catalog/permissions for the full catalog.");
        this.unknown = unknown == null ? Set.of() : new TreeSet<>(unknown);
    }
}
