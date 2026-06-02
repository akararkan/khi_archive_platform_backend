package ak.dev.khi_archive_platform.platform.dto.maqam;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;

/**
 * One teacher's vote on a maqam record as it appears in API responses. Other
 * teachers see this — including the vote string, the note, and the listen
 * counters — but cannot modify it. The presence of {@code maqamType} == null
 * is the canonical "this teacher hasn't voted yet" signal.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MaqamTeacherVoteDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long voteId;
    private Long teacherUserId;
    private String teacherUsername;
    private String teacherDisplayName;

    /** Null until the teacher records their first vote. */
    private String maqamType;
    private String teacherNote;

    private Instant votedAt;
    private Instant updatedAt;
    private Instant assignedAt;
    private String assignedBy;

    private Long totalListenSeconds;
    private Long maxPositionSeconds;
    private Instant lastListenAt;
}
