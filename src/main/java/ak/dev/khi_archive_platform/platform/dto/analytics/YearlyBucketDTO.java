package ak.dev.khi_archive_platform.platform.dto.analytics;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDate;

/**
 * One calendar-year's slice of activity. Time bucket is the first day of a
 * calendar year (Postgres {@code DATE_TRUNC('year', occurred_at)}). LIST rows
 * are never counted. Mirrors {@link MonthlyBucketDTO} so yearly and monthly
 * charts share the same field shape.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class YearlyBucketDTO implements Serializable {
    /** First day of the year (e.g. {@code 2026-01-01} for 2026). */
    private LocalDate year;
    /** Cached "YYYY" string so the UI doesn't have to format on every row. */
    private String label;
    private long total;
    private long created;
    private long updated;
    private long deleted;
    private long restored;
    private long purged;
    private long viewed;
    private long searched;
    /** Distinct users active in this year — useful for "team activity"
     *  yearly charts. Zero for single-user queries. */
    private long activeUsers;
}
