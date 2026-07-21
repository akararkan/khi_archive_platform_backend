package ak.dev.khi_archive_platform.platform.dto.analytics;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import ak.dev.khi_archive_platform.platform.dto.analytics.CorrectionStatsDTO;

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
    /** One bucket per ISO week in the window, ordered newest first.
     *  Weeks with zero activity are omitted. */
    private List<WeeklyBucketDTO> weekly;
    /** One bucket per calendar month in the window, ordered newest first.
     *  Months with zero activity are omitted. */
    private List<MonthlyBucketDTO> monthly;
    /** One bucket per calendar year in the window, ordered newest first.
     *  Years with zero activity are omitted. */
    private List<YearlyBucketDTO> yearly;

    /** Guest correction suggestion totals across all time. Null when not loaded. */
    private CorrectionStatsDTO corrections;
}
