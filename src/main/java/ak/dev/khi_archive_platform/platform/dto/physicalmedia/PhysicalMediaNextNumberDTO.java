package ak.dev.khi_archive_platform.platform.dto.physicalmedia;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response shape for {@code GET /api/physical-media/next-number?type=X}.
 * The frontend reads this when the user picks a type on the create form
 * so the {@code Number} input can be pre-filled with the next value.
 *
 * <p>The preview is best-effort and not a reservation: by the time the
 * user submits, the actual {@code POST /api/physical-media} re-mints
 * the number under a per-type advisory lock so concurrent creates can't
 * collide on the same value.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PhysicalMediaNextNumberDTO {
    private String physicalMediaType;
    private int nextInventoryNumber;
}
