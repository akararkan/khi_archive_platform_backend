package ak.dev.khi_archive_platform.platform.model.maqam;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One teacher's vote + note on a {@link ListOfMaqam} record. The vote string
 * is whatever maqam-type the teacher identifies (free-form by design — the
 * organisation explicitly does not want a closed taxonomy of maqamat).
 *
 * <p>Uniqueness on {@code (list_of_maqam_id, teacher_user_id)} guarantees one
 * vote per teacher per song. The service layer enforces the
 * {@link ListOfMaqam#MAX_TEACHERS} cap on top of this constraint.
 *
 * <p>{@code totalListenSeconds} is a rolling aggregate updated by the
 * listen-tracking endpoints — it tells admins, at a glance, how long this
 * teacher actually listened before voting. The granular per-session history
 * lives on {@link MaqamAudioListenSession}.
 */
@Entity
@Table(name = "maqam_teacher_votes",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_maqam_teacher_one_vote_per_song",
                columnNames = {"list_of_maqam_id", "teacher_user_id"}),
        indexes = {
                @Index(name = "idx_mtv_maqam", columnList = "list_of_maqam_id"),
                @Index(name = "idx_mtv_teacher", columnList = "teacher_user_id"),
                @Index(name = "idx_mtv_voted_at", columnList = "voted_at")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MaqamTeacherVote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "list_of_maqam_id", nullable = false)
    private ListOfMaqam listOfMaqam;

    /** FK to {@code users_tbl.user_id}. We snapshot the username/display
     *  name so audit/log surfaces still read sensibly if the account is
     *  later renamed. */
    @Column(name = "teacher_user_id", nullable = false)
    private Long teacherUserId;

    @Column(name = "teacher_username", nullable = false, length = 80)
    private String teacherUsername;

    @Column(name = "teacher_display_name", length = 120)
    private String teacherDisplayName;

    /** Free-form maqam type the teacher identifies (e.g. "Rast", "Bayati"). */
    @Column(name = "maqam_type", columnDefinition = "TEXT")
    private String maqamType;

    /** The teacher's reasoning / supporting note. Optional. */
    @Column(name = "teacher_note", columnDefinition = "TEXT")
    private String teacherNote;

    /** First time this teacher saved a vote on this record. Frozen after
     *  the first VOTE_CAST; later edits bump {@link #updatedAt} only. */
    @Column(name = "voted_at")
    private Instant votedAt;

    /** Last update to the vote or note. */
    @Column(name = "updated_at")
    private Instant updatedAt;

    /** Aggregate seconds streamed of the audio file by this teacher across
     *  every session. Mirrors {@code SUM(seconds_listened)} from
     *  {@link MaqamAudioListenSession} for fast reads. */
    @Builder.Default
    @Column(name = "total_listen_seconds", nullable = false)
    private Long totalListenSeconds = 0L;

    /** Furthest playback offset the teacher reached, in seconds — supports
     *  the "resume where I left off" UX without scanning every session. */
    @Builder.Default
    @Column(name = "max_position_seconds", nullable = false)
    private Long maxPositionSeconds = 0L;

    @Column(name = "last_listen_at")
    private Instant lastListenAt;

    // ── Audit (assignment + creator) ─────────────────────────────────
    @Column(name = "assigned_at")
    private Instant assignedAt;

    @Column(name = "assigned_by", length = 120)
    private String assignedBy;

    @Version
    @org.hibernate.annotations.ColumnDefault("0")
    @Column(name = "version", nullable = false)
    private Long version;
}
