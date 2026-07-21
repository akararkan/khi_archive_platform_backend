package ak.dev.khi_archive_platform.platform.dto.analytics;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Team-level maqam classification analytics: how the whole teacher panel is
 * progressing plus a per-teacher leaderboard. Counts ACTIVE records only
 * (parent {@code list_of_maqam.removed_at IS NULL}). Sourced from
 * {@code maqam_teacher_votes} + {@code maqam_audio_listen_sessions} + the
 * live {@code list_of_maqam} table, NOT from the audit-log union.
 *
 * <p>Record classification buckets (mutually exclusive over active records):
 * <ul>
 *   <li>{@code recordsUnclassified} — 0 votes cast (nobody has voted yet).</li>
 *   <li>{@code recordsPartiallyVoted} — some but not all assigned teachers voted.</li>
 *   <li>{@code recordsFullyVoted} — every assigned teacher voted.</li>
 * </ul>
 * Of the fully-voted records, {@code recordsWithConsensus} had a single
 * distinct maqam_type and {@code recordsWithDisagreement} had two or more.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MaqamTeacherOverviewDTO implements Serializable {
    /** Distinct teachers currently assigned to at least one active record. */
    private long totalTeachers;
    /** Active maqam records in the archive. */
    private long totalActiveRecords;
    /** Total teacher assignments (vote rows) on active records. */
    private long totalAssignments;
    /** Assignments whose vote has been cast. */
    private long totalVotesCast;
    /** Assignments still awaiting a vote. */
    private long totalPendingVotes;

    private long recordsUnclassified;
    private long recordsPartiallyVoted;
    private long recordsFullyVoted;
    private long recordsWithConsensus;
    private long recordsWithDisagreement;

    /** Total play sessions across all teachers. */
    private long totalListenSessions;
    /** Total listen seconds across all teachers. */
    private long totalListenSeconds;

    /** Frequency of each voted maqam_type across active records, most-common
     *  first, keyed by the free-form type string. */
    private Map<String, Long> maqamTypeDistribution;

    /** Per-teacher leaderboard, ordered by votes cast then listen seconds. */
    private List<TeacherActivityDTO> teachers;

    /** When this snapshot was computed (server clock). */
    private Instant generatedAt;
}
