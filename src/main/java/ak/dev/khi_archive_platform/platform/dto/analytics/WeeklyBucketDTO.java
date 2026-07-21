package ak.dev.khi_archive_platform.platform.dto.analytics;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDate;

/**
 * One ISO-week's slice of activity. Time bucket is the Monday that starts the
 * week (Postgres {@code DATE_TRUNC('week', occurred_at)} — weeks start Monday).
 * LIST rows are never counted. Mirrors {@link MonthlyBucketDTO} so weekly and
 * monthly charts share the same field shape.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WeeklyBucketDTO implements Serializable {
    /** Monday that starts the ISO week (e.g. {@code 2026-05-04}). */
    private LocalDate week;
    /** Cached "YYYY-Www" ISO-week string (e.g. {@code 2026-W19}) so the UI
     *  doesn't have to format on every row. */
    private String label;
    private long total;
    private long created;
    private long updated;
    private long deleted;
    private long restored;
    private long purged;
    private long viewed;
    private long searched;
    /** Distinct users active in this week — useful for "team activity"
     *  weekly charts. Zero for single-user queries. */
    private long activeUsers;
}
