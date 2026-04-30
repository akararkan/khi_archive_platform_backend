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
 * Team-wide picture for an admin. Aggregates everyone's activity over a
 * window. Top-N users are sorted by total action count.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TeamOverviewDTO implements Serializable {
    private Instant from;
    private Instant to;
    private long totalActions;
    private long activeUsers;
    private Map<String, EntityStatsDTO> byEntity;
    private List<UserSummaryDTO> topUsers;
    private List<DailyBucketDTO> daily;
}
