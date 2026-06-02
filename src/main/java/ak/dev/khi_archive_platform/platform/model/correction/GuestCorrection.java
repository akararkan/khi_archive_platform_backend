package ak.dev.khi_archive_platform.platform.model.correction;

import ak.dev.khi_archive_platform.platform.enums.CorrectionMediaType;
import ak.dev.khi_archive_platform.platform.enums.CorrectionStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Comment;

import java.time.Instant;

@Entity
@Table(name = "guest_corrections",
        indexes = {
                @Index(name = "idx_gc_media", columnList = "media_type, media_code, removed_at"),
                @Index(name = "idx_gc_status", columnList = "status, removed_at"),
                @Index(name = "idx_gc_guest", columnList = "guest_user_id"),
                @Index(name = "idx_gc_created_at", columnList = "created_at")
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GuestCorrection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "media_type", nullable = false, length = 10)
    private CorrectionMediaType mediaType;

    @Column(name = "media_code", nullable = false, length = 255)
    private String mediaCode;

    @Column(name = "media_title")
    private String mediaTitle;

    @Column(name = "target_field", nullable = false, length = 100)
    @Comment("The public field name the guest wants to correct")
    private String targetField;

    @Column(name = "current_value", columnDefinition = "TEXT")
    @Comment("Snapshot of the field value at submission time")
    private String currentValue;

    @Column(name = "suggested_value", nullable = false, columnDefinition = "TEXT")
    @Comment("The value the guest believes is correct")
    private String suggestedValue;

    @Column(name = "note", columnDefinition = "TEXT")
    @Comment("Optional explanation from the guest")
    private String note;

    // ── Guest (submitter) ────────────────────────────────────────
    @Column(name = "guest_user_id")
    private Long guestUserId;

    @Column(name = "guest_username", length = 80)
    private String guestUsername;

    @Column(name = "guest_display_name", length = 120)
    private String guestDisplayName;

    // ── Status ───────────────────────────────────────────────────
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private CorrectionStatus status = CorrectionStatus.PENDING;

    // ── Record creator (employee who added the media record) ─────
    @Column(name = "record_created_by", length = 120)
    @Comment("Username of the employee who originally created the media record")
    private String recordCreatedBy;

    // ── Forwarding ───────────────────────────────────────────────
    @Column(name = "forwarded_by", length = 80)
    private String forwardedBy;

    @Column(name = "forwarded_at")
    private Instant forwardedAt;

    @Column(name = "forward_note", columnDefinition = "TEXT")
    private String forwardNote;

    // ── Resolution ───────────────────────────────────────────────
    @Column(name = "resolved_by", length = 80)
    private String resolvedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolve_note", columnDefinition = "TEXT")
    private String resolveNote;

    // ── Timestamps & soft-delete ─────────────────────────────────
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "removed_at")
    private Instant removedAt;

    @Column(name = "removed_by", length = 80)
    private String removedBy;

    @Version
    private Long version;
}
