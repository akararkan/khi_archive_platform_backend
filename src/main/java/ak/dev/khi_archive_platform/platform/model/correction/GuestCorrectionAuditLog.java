package ak.dev.khi_archive_platform.platform.model.correction;

import ak.dev.khi_archive_platform.platform.enums.CorrectionMediaType;
import ak.dev.khi_archive_platform.platform.enums.GuestCorrectionAuditAction;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "guest_correction_audit_logs",
        indexes = {
                @Index(name = "idx_gcal_correction", columnList = "correction_id"),
                @Index(name = "idx_gcal_action", columnList = "action"),
                @Index(name = "idx_gcal_actor", columnList = "actor_username"),
                @Index(name = "idx_gcal_occurred_at", columnList = "occurred_at")
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GuestCorrectionAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "correction_id")
    private Long correctionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "media_type", length = 10)
    private CorrectionMediaType mediaType;

    @Column(name = "media_code", length = 255)
    private String mediaCode;

    @Column(name = "media_title")
    private String mediaTitle;

    @Column(name = "target_field", length = 100)
    private String targetField;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 30)
    private GuestCorrectionAuditAction action;

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
