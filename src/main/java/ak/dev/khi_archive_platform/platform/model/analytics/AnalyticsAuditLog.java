package ak.dev.khi_archive_platform.platform.model.analytics;

import ak.dev.khi_archive_platform.platform.enums.AnalyticsAuditAction;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Records each analytics-console view. Mirrors the column shape of the seven
 * entity-level *_audit_logs tables so it can be rolled up alongside them in
 * future cross-cutting reports if needed.
 */
@Entity
@Table(name = "analytics_audit_logs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalyticsAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 32)
    private AnalyticsAuditAction action;

    /** The query that was executed, serialised as a stable cache-key form. */
    @Column(name = "filter_summary", columnDefinition = "TEXT")
    private String filterSummary;

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
