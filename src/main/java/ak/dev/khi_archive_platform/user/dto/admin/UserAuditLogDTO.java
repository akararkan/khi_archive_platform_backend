package ak.dev.khi_archive_platform.user.dto.admin;

import ak.dev.khi_archive_platform.user.enums.UserAuditAction;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

/**
 * Read-only projection of a {@code user_audit_logs} row, returned by the
 * user audit-log endpoints. Mirrors the entity 1-to-1 except that the
 * password column was never recorded; everything here is safe to expose to
 * an authenticated admin client.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserAuditLogDTO implements Serializable {

    private Long id;
    private UserAuditAction action;

    // ── Target user ────────────────────────────────────────────
    private Long targetUserId;
    private String targetUsername;
    private String targetDisplayName;
    private String targetEmail;
    private String previousRole;
    private String newRole;
    private String permissionsChanged;

    // ── Acting admin ───────────────────────────────────────────
    private Long actorUserId;
    private String actorUsername;
    private String actorDisplayName;
    private String actorAuthorities;
    private String actorPermissions;

    // ── Request / session ──────────────────────────────────────
    private String deviceInfo;
    private String ipAddress;
    private String sessionId;
    private Instant sessionLoginTimestamp;
    private Instant sessionExpiresAt;
    private Boolean sessionActive;
    private String requestMethod;
    private String requestPath;

    private String details;
    private Instant occurredAt;

    /** JSON-only alias for {@link #id} — matches the {@code id} convention used elsewhere. */
    @JsonProperty("logId")
    public Long getLogId() {
        return id;
    }
}
