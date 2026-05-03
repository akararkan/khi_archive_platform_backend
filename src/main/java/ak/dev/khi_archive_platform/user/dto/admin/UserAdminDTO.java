package ak.dev.khi_archive_platform.user.dto.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.Set;

/**
 * Full admin view of a user: identity, role, extra permissions, the
 * effective authority set (what Spring Security sees), and audit fields.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserAdminDTO implements Serializable {
    private Long userId;
    private String username;
    private String name;
    private String email;
    private String profileImage;
    private String role;
    private Boolean isActivated;
    private Boolean isLocked;
    private Instant lockTime;
    private int failedAttempts;
    /** Permissions granted directly to this user (on top of the role's defaults). */
    private Set<String> extraPermissions;
    /** Full effective authority set (role authorities ∪ extraPermissions). */
    private Set<String> effectiveAuthorities;
    private Instant createdAt;
    private Instant updatedAt;

    /** JSON-only alias for {@link #userId} so frontends that expect {@code id}
     *  (the more common convention) work without backend rewrites. */
    @JsonProperty("id")
    public Long getId() {
        return userId;
    }
}
