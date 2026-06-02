package ak.dev.khi_archive_platform.platform.dto.maqam;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;

/**
 * Per-teacher listen aggregate for a maqam record. Renders the "how engaged
 * is each teacher" widget on the admin record view in one row apiece without
 * iterating every session log.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MaqamListenSummaryDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long teacherUserId;
    private String teacherUsername;
    private String teacherDisplayName;

    private Long totalSeconds;
    private Long maxPositionSeconds;
    private Long sessionCount;
    private Instant firstListenAt;
    private Instant lastListenAt;

    /** Coverage = totalSeconds / audioDurationSeconds, capped at 1.0. Null
     *  when the record has no recorded duration. */
    private Double coverageRatio;
}
