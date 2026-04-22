package ak.dev.khi_archive_platform.platform.model.audio;

import ak.dev.khi_archive_platform.platform.enums.AudioAuditAction;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "audio_audit_logs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AudioAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "audio_id")
    private Long audioId;

    @Column(name = "audio_code", length = 160)
    private String audioCode;

    @Column(name = "audio_title")
    private String audioTitle;

    @Column(name = "person_id")
    private Long personId;

    @Column(name = "person_code", length = 50)
    private String personCode;

    @Column(name = "person_name")
    private String personName;

    @Column(name = "object_id")
    private Long objectId;

    @Column(name = "object_code", length = 160)
    private String objectCode;

    @Column(name = "object_name")
    private String objectName;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 20)
    private AudioAuditAction action;

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

