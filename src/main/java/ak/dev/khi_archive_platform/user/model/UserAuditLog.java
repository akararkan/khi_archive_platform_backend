package ak.dev.khi_archive_platform.user.model;

import ak.dev.khi_archive_platform.user.enums.UserAuditAction;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * One row per admin user-management action. Mirrors the column shape of the
 * platform's seven {@code *_audit_logs} tables so future analytics rollups
 * can union it in alongside them.
 */
@Entity
@Table(name = "user_audit_logs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 32)
    private UserAuditAction action;

    // ── Target user ────────────────────────────────────────────
    @Column(name = "target_user_id")
    private Long targetUserId;

    @Column(name = "target_username")
    private String targetUsername;

    @Column(name = "target_display_name")
    private String targetDisplayName;

    @Column(name = "target_email")
    private String targetEmail;

    @Column(name = "previous_role", length = 30)
    private String previousRole;

    @Column(name = "new_role", length = 30)
    private String newRole;

    /** Comma-separated permission strings affected by this action. */
    @Column(name = "permissions_changed", columnDefinition = "TEXT")
    private String permissionsChanged;

    // ── Acting admin ───────────────────────────────────────────
    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(name = "actor_username")
    private String actorUsername;

    @Column(name = "actor_display_name")
    private String actorDisplayName;

    @Column(name = "actor_authorities", columnDefinition = "TEXT")
    private String actorAuthorities;

    @Column(name = "actor_permissions", columnDefinition = "TEXT")
    private String actorPermissions;

    // ── Request / session ──────────────────────────────────────
    @Column(name = "device_info")
    private String deviceInfo;

    @Column(name = "ip_address")
    private String ipAddress;

    @Column(name = "session_id")
    private String sessionId;

    @Column(name = "session_login_timestamp")
    private Instant sessionLoginTimestamp;

    @Column(name = "session_expires_at")
    private Instant sessionExpiresAt;

    @Column(name = "session_is_active")
    private Boolean sessionActive;

    @Column(name = "request_method")
    private String requestMethod;

    @Column(name = "request_path")
    private String requestPath;

    @Column(name = "details", columnDefinition = "TEXT")
    private String details;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;
}
