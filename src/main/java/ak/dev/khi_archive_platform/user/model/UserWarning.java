package ak.dev.khi_archive_platform.user.model;

import ak.dev.khi_archive_platform.user.enums.WarningSeverity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Comment;

import java.time.Instant;

/**
 * One row per admin-issued warning targeting an employee. Persistent
 * in-app notification: the recipient sees it on every authenticated request
 * via {@code GET /api/warnings/me} until they acknowledge it.
 *
 * <p>Soft-deleted via {@code removedAt} so audit trail is preserved if an
 * admin retracts a warning.
 */
@Entity
@Table(name = "user_warnings",
        indexes = {
                @Index(name = "idx_user_warnings_target", columnList = "target_user_id, removed_at"),
                @Index(name = "idx_user_warnings_actor", columnList = "actor_user_id"),
                @Index(name = "idx_user_warnings_created_at", columnList = "created_at"),
                @Index(name = "idx_user_warnings_acknowledged", columnList = "target_user_id, acknowledged, removed_at")
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserWarning {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ── Target (recipient) ────────────────────────────────────────
    @Column(name = "target_user_id", nullable = false)
    @Comment("FK-style reference to users_tbl.user_id (no constraint to keep deletes flexible)")
    private Long targetUserId;

    @Column(name = "target_username", length = 80)
    @Comment("Snapshot of the recipient's username at warning time")
    private String targetUsername;

    // ── Actor (admin who sent it) ─────────────────────────────────
    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(name = "actor_username", length = 80)
    private String actorUsername;

    @Column(name = "actor_display_name", length = 120)
    private String actorDisplayName;

    // ── Body ──────────────────────────────────────────────────────
    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 16)
    @Builder.Default
    private WarningSeverity severity = WarningSeverity.WARNING;

    @Column(name = "title", nullable = false, length = 200)
    @Comment("Short headline shown in the warning list")
    private String title;

    @Column(name = "message", columnDefinition = "TEXT", nullable = false)
    @Comment("Body of the warning. HTML-escaped on write.")
    private String message;

    // ── State ─────────────────────────────────────────────────────
    @Column(name = "acknowledged", nullable = false)
    @Builder.Default
    @Comment("True once the recipient has confirmed they read the warning")
    private Boolean acknowledged = false;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "removed_at")
    @Comment("Set when an admin revokes the warning; the recipient stops seeing it")
    private Instant removedAt;
}
