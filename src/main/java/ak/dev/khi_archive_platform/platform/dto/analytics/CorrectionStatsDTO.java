package ak.dev.khi_archive_platform.platform.dto.analytics;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

/**
 * Guest correction suggestion statistics shown in the analytics overview.
 * Counts are across all time (not filtered by the analytics window) so
 * admins always see total backlog.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CorrectionStatsDTO implements Serializable {

    private long total;
    private long pending;
    private long forwarded;
    private long resolved;
    private long rejected;

    /** Breakdown by media type: "AUDIO" → count, "VIDEO" → count, etc. */
    private Map<String, Long> byMediaType;
}
