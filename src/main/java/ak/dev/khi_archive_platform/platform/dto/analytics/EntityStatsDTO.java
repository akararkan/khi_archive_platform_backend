package ak.dev.khi_archive_platform.platform.dto.analytics;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Per-entity activity counts for one user (or the team in overview mode).
 *
 * <p>"created" / "updated" / "deleted" / "restored" / "purged" are counts of
 * the corresponding audit-log rows. "deleted" combines DELETE and the legacy
 * REMOVE action so old audit rows still surface correctly.
 *
 * <p>"viewed" is READ; "searched" is SEARCH; "listed" is LIST. "total" is
 * every audit row regardless of action — the simplest "did this user touch
 * this resource" metric.
 *
 * <p>"distinctEntities" is COUNT(DISTINCT entity_id) — how many unique
 * resources the user actually interacted with (vs. raw action count).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EntityStatsDTO implements Serializable {
    private long created;
    private long updated;
    private long deleted;
    private long restored;
    private long purged;
    private long viewed;
    private long searched;
    private long listed;
    private long total;
    private long distinctEntities;
}
