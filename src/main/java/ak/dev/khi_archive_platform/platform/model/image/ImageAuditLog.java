package ak.dev.khi_archive_platform.platform.model.image;

import ak.dev.khi_archive_platform.platform.enums.ImageAuditAction;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "image_audit_logs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImageAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "image_id")
    private Long imageId;

    @Column(name = "image_code", length = 255)
    private String imageCode;

    @Column(name = "image_title")
    private String imageTitle;

    @Column(name = "project_id")
    private Long projectId;

    @Column(name = "project_code", length = 200)
    private String projectCode;

    @Column(name = "project_name")
    private String projectName;

    @Column(name = "person_id")
    private Long personId;

    @Column(name = "person_code", length = 50)
    private String personCode;

    @Column(name = "person_name")
    private String personName;

    @Column(name = "category_codes", columnDefinition = "TEXT")
    private String categoryCodes;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 20)
    private ImageAuditAction action;

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
