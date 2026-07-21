package ak.dev.khi_archive_platform.platform.dto.analytics;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.Map;

/**
 * Snapshot of how many items exist in the archive right now, broken down by
 * type. This is a LIVE inventory count against the operational tables — it is
 * NOT audit-activity and is unaffected by any date-window filter.
 *
 * <p>{@link #byType} is keyed by item type using the same lower-case keys as
 * {@code AnalyticsService.ENTITY_KEYS}: {@code audio, video, image, text,
 * maqam, physical_media, project, person, category}. Each value carries the
 * active / trashed / total split for that type.
 *
 * <p>{@code totalActive}, {@code totalTrashed} and {@code grandTotal} are the
 * roll-ups across every type so the UI can show one headline number.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventoryStatsDTO implements Serializable {
    /** Sum of active rows across every type. */
    private long totalActive;
    /** Sum of soft-trashed rows across every type. */
    private long totalTrashed;
    /** totalActive + totalTrashed. */
    private long grandTotal;
    /** Per-type active/trashed/total counts, keyed by lower-case type name. */
    private Map<String, InventoryTypeCountDTO> byType;
    /** When this snapshot was computed (server clock). */
    private Instant generatedAt;
}
