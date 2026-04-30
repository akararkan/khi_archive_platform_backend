package ak.dev.khi_archive_platform.platform.dto.analytics;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDate;

/**
 * One day's slice of activity. Time bucket is a calendar day in the server's
 * timezone (Postgres {@code DATE_TRUNC('day', occurred_at)}).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DailyBucketDTO implements Serializable {
    private LocalDate date;
    private long total;
    private long created;
    private long updated;
    private long deleted;
    private long restored;
    private long purged;
}
