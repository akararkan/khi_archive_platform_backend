package ak.dev.khi_archive_platform.user.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Tiny payload that drives the top-bar warning badge. Returned by
 * {@code GET /api/warnings/me/count}. Kept as a DTO (rather than a raw long)
 * so future fields like "criticalUnacknowledged" don't break the contract.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UnacknowledgedWarningCountDTO implements Serializable {
    private long unacknowledged;
}
