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
 * Comprehensive activity picture for a single user over a time window.
 * Built from a single UNION ALL query across the {@code *_audit_logs} tables
 * (the nine entity domains plus {@code user_audit_logs} for admin
 * user-management actions) and cached in Caffeine for sub-millisecond hot reads.
 *
 * <p>Field shapes:
 * <ul>
 *   <li>{@code byEntity} — keyed on entity name ("audio", "video", …, "user");
 *       value is the per-entity action breakdown.</li>
 *   <li>{@code daily} / {@code weekly} / {@code monthly} / {@code yearly} —
 *       time-series buckets over the window at each granularity, ordered newest
 *       first. Empty buckets are omitted.</li>
 *   <li>{@code recent} — the most recent audit rows (default 50), already
 *       sorted across entities.</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserActivityDTO implements Serializable {
    private Long actorUserId;
    private String username;
    private String displayName;
    /** Most recently seen authorities (roles + permissions, comma-separated). */
    private String authorities;
    /** Most recently seen permissions (authorities minus ROLE_*). */
    private String permissions;

    private Instant from;
    private Instant to;
    private Instant firstSeen;
    private Instant lastSeen;
    private long totalActions;
    private Map<String, EntityStatsDTO> byEntity;
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
    /** Paginated slice of this user's activity, ordered per the request's
     *  {@code sort} parameter. Carries {@code page/size/totalElements/...} so
     *  the UI can render full pagination controls. */
    private FeedPageDTO recent;
}
