package ak.dev.khi_archive_platform.platform.dto.analytics;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDate;

/**
 * One month's slice of activity. Time bucket is the first day of a calendar
 * month in the server's timezone (Postgres {@code DATE_TRUNC('month',
 * occurred_at)}). LIST rows are never counted.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MonthlyBucketDTO implements Serializable {
    /** First day of the month (e.g. {@code 2026-05-01} for May 2026). */
    private LocalDate month;
    /** Cached "YYYY-MM" string so the UI doesn't have to format on every row. */
    private String label;
    private long total;
    private long created;
    private long updated;
    private long deleted;
    private long restored;
    private long purged;
    private long viewed;
    private long searched;
    /** Distinct users active in this month — useful for "team activity"
     *  monthly charts. Zero for single-user queries. */
    private long activeUsers;
}
