package ak.dev.khi_archive_platform.platform.dto.analytics;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Live-inventory row counts for one item type (e.g. audio, video, project).
 *
 * <p>Unlike the audit-activity DTOs, these numbers come from the operational
 * tables themselves (e.g. {@code audios}), not from the {@code *_audit_logs}
 * union. {@code active} = {@code removed_at IS NULL}; {@code trashed} =
 * {@code removed_at IS NOT NULL}; {@code total} = active + trashed.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventoryTypeCountDTO implements Serializable {
    /** Rows still live (not soft-trashed). */
    private long active;
    /** Rows soft-trashed (removed_at set) but not yet purged. */
    private long trashed;
    /** active + trashed. */
    private long total;
}
