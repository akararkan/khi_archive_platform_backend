package ak.dev.khi_archive_platform.platform.dto.analytics;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Aggregate breakdown for a single action across all (or a filtered set of)
 * entities. Used by {@code GET /api/analytics/actions} to power per-action
 * widgets like a CREATE/UPDATE/DELETE pie chart, or a "top actions" bar.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActionStatsDTO implements Serializable {
    private String action;
    private long total;
    private long distinctActors;
    private long distinctEntities;
}
