package ak.dev.khi_archive_platform.platform.model.physicalmedia;

import ak.dev.khi_archive_platform.platform.enums.PhysicalMediaAuditAction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Aligned column-by-column with the other {@code *_audit_logs} tables so the
 * analytics UNION ALL CTE keeps a uniform shape. Adds {@code physical_label}
 * and {@code physical_media_type} snapshots so feed rows are readable
 * without a join back to {@code physical_media}.
 */
@Entity
@Table(name = "physical_media_audit_logs",
        indexes = {
                @Index(name = "idx_pmal_pm", columnList = "physical_media_id"),
                @Index(name = "idx_pmal_action", columnList = "action"),
                @Index(name = "idx_pmal_actor", columnList = "actor_username"),
                @Index(name = "idx_pmal_occurred_at", columnList = "occurred_at")
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PhysicalMediaAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "physical_media_id")
    private Long physicalMediaId;

    /** Internal business key snapshot (PM_NNNNNN). */
    @Column(name = "physical_media_code", length = 60)
    private String physicalMediaCode;

    /** Sheet "Physical Label" snapshot — handy for feed display. */
    @Column(name = "physical_label", length = 200)
    private String physicalLabel;

    /** Snapshot of the artefact title at the time of the action. */
    @Column(name = "title")
    private String title;

    /** Snapshot of the media type (Audio Cassette, VHS, …). */
    @Column(name = "physical_media_type", length = 200)
    private String physicalMediaType;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 20)
    private PhysicalMediaAuditAction action;

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
