package ak.dev.khi_archive_platform.platform.dto.analytics;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

/**
 * One user's activity totals — used in team-overview lists and "top users"
 * leaderboards. Tighter than {@link UserActivityDTO} because it skips the
 * per-entity / per-day / recent-feed detail.
 *
 * <p>Authority/permission strings are taken from the most recent audit row
 * for this user, so they reflect the user's current effective role set as
 * seen by the audit system.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserSummaryDTO implements Serializable {
    private Long actorUserId;
    private String username;
    private String displayName;
    private String authorities;
    private String permissions;

    private long totalActions;
    private long createCount;
    private long updateCount;
    private long deleteCount;
    private long restoreCount;
    private long purgeCount;
    private long readCount;
    private long listCount;
    private long searchCount;

    private Instant firstSeen;
    private Instant lastSeen;
}
