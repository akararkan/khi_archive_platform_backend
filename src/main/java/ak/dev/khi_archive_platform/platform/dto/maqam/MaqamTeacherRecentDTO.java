package ak.dev.khi_archive_platform.platform.dto.maqam;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;

/**
 * One row of a teacher's "recent activity" feed — one entry per
 * {@code ListOfMaqam} record they are assigned to, carrying their personal
 * vote state, listen progress, and the composite {@link #lastActivityAt}
 * timestamp that drives the newest-first ordering.
 *
 * <p>Designed for the elderly-teacher UX: every field they need to remember
 * <em>where they left off</em> is here in one row — the song they were on,
 * how far into the audio they got, whether they already voted, what they
 * voted, and how long ago they touched it. The frontend can render a list,
 * a "Resume" button (using {@link #maxPositionSeconds}), and a progress bar
 * (using {@link #coverageRatio}) directly from this payload.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MaqamTeacherRecentDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    // ── Identifiers ──────────────────────────────────────────────────────────
    private Long voteId;
    private Long maqamId;
    private String maqamCode;

    // ── Record fields (so the teacher knows WHICH song) ──────────────────────
    private String songName;
    private String producer;
    private String archiveNote;
    private String audioFileName;
    private Long audioDurationSeconds;
    /** Authenticated stream URL — never the S3 link, per the no-downloads rule. */
    private String streamUrl;

    // ── This teacher's own vote ──────────────────────────────────────────────
    /** Null until they cast their first vote. */
    private String maqamType;
    private String teacherNote;
    /** {@code maqamType != null} — convenience flag for "Voted" / "Not voted yet" badges. */
    private Boolean hasVoted;

    private Instant votedAt;
    private Instant updatedAt;
    private Instant assignedAt;
    private String assignedBy;

    // ── Listen progress (resume support) ─────────────────────────────────────
    private Long totalListenSeconds;
    private Long maxPositionSeconds;
    private Instant lastListenAt;
    /** {@code totalListenSeconds / audioDurationSeconds}, clamped 0..1. Null when duration unknown. */
    private Double coverageRatio;

    // ── Sort key ─────────────────────────────────────────────────────────────
    /**
     * The most recent of {@link #lastListenAt}, {@link #updatedAt},
     * {@link #votedAt}, {@link #assignedAt}. This is what the list is sorted
     * by — newest first. The frontend can show this as "Last worked on …".
     */
    private Instant lastActivityAt;

    // ── Record context ───────────────────────────────────────────────────────
    private Instant recordCreatedAt;
    private Instant recordUpdatedAt;
}
