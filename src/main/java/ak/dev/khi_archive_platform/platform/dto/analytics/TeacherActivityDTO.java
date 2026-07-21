package ak.dev.khi_archive_platform.platform.dto.analytics;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

/**
 * One maqam teacher's activity roll-up across every active record they are
 * assigned to. Sourced from {@code maqam_teacher_votes} (assignment + vote +
 * rolled-up listen aggregates) and {@code maqam_audio_listen_sessions}
 * (granular play sessions), NOT from the audit-log union.
 *
 * <p>The "assignment IS a vote row" model means {@link #assignedRecords} counts
 * the vote rows on active records; {@link #votesCast} counts those whose
 * {@code voted_at} is set; {@link #pendingVotes} is the difference (records the
 * teacher was assigned but has not yet classified).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TeacherActivityDTO implements Serializable {
    private Long teacherUserId;
    private String teacherUsername;
    private String teacherDisplayName;

    /** Active records this teacher is assigned to (one vote row each). */
    private long assignedRecords;
    /** Of those, how many the teacher has actually voted on (voted_at set). */
    private long votesCast;
    /** assignedRecords − votesCast: assigned but not yet classified. */
    private long pendingVotes;
    /** Distinct non-null maqam_type values this teacher has voted. */
    private long distinctMaqamTypes;

    /** Sum of listen seconds rolled up onto this teacher's vote rows. */
    private long totalListenSeconds;
    /** Furthest playback offset reached across this teacher's records. */
    private long maxPositionSeconds;
    /** Number of play sessions (rows in maqam_audio_listen_sessions). */
    private long listenSessions;
    /** Distinct records this teacher has actually listened to. */
    private long recordsListened;

    /** First vote this teacher ever cast (min voted_at). */
    private Instant firstVotedAt;
    /** Most recent listen across all this teacher's records (max last_listen_at). */
    private Instant lastListenAt;
}
