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
 * Built from a single UNION ALL query across the seven {@code *_audit_logs}
 * tables and cached in Redis for sub-millisecond hot reads.
 *
 * <p>Field shapes:
 * <ul>
 *   <li>{@code byEntity} — keyed on entity name ("audio", "video", …);
 *       value is the per-entity action breakdown.</li>
 *   <li>{@code daily} — one bucket per day in the window, ordered newest
 *       first. Days with zero activity are omitted.</li>
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
    private List<RecentActivityItemDTO> recent;
}
