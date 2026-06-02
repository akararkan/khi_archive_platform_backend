package ak.dev.khi_archive_platform.platform.model.maqam;

import ak.dev.khi_archive_platform.platform.enums.MaqamAuditAction;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Shape-aligned with every other {@code *_audit_logs} table so the analytics
 * UNION ALL across them stays valid. Adds the maqam-specific fields the
 * cross-entity feed doesn't need (teacher_*, session_key, listen seconds)
 * for the teacher-engagement views.
 */
@Entity
@Table(name = "maqam_audit_logs",
        indexes = {
                @Index(name = "idx_mal_maqam", columnList = "maqam_id"),
                @Index(name = "idx_mal_action", columnList = "action"),
                @Index(name = "idx_mal_actor", columnList = "actor_username"),
                @Index(name = "idx_mal_teacher", columnList = "teacher_user_id"),
                @Index(name = "idx_mal_occurred_at", columnList = "occurred_at")
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MaqamAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "maqam_id")
    private Long maqamId;

    @Column(name = "maqam_code", length = 100)
    private String maqamCode;

    /** Snapshot of the song name at the time of the action, for fast feed
     *  rendering without joining back to {@code list_of_maqam}. */
    @Column(name = "song_name")
    private String songName;

    @Column(name = "producer")
    private String producer;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 30)
    private MaqamAuditAction action;

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

    // ── Teacher context (only set for vote / listen / assign actions) ──
    @Column(name = "teacher_user_id")
    private Long teacherUserId;

    @Column(name = "teacher_username", length = 80)
    private String teacherUsername;

    @Column(name = "teacher_display_name", length = 120)
    private String teacherDisplayName;

    // ── Vote context ───────────────────────────────────────────────────
    @Column(name = "maqam_type", columnDefinition = "TEXT")
    private String maqamType;

    // ── Listen context ─────────────────────────────────────────────────
    @Column(name = "session_key", length = 100)
    private String sessionKey;

    @Column(name = "seconds_listened")
    private Long secondsListened;

    @Column(name = "position_seconds")
    private Long positionSeconds;

    // ── Standard request envelope (mirrors other audit tables) ─────────
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
