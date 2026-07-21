package ak.dev.khi_archive_platform.platform.dto.analytics;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Visible-vs-hidden split for one item type, counted over ACTIVE rows only
 * ({@code removed_at IS NULL}).
 *
 * <p>For the four media types the visibility flag is {@code is_public}; a NULL
 * flag is treated as public (matching the guest-API convention
 * {@code isPublic IS NULL OR isPublic = true}). For projects the flag is
 * {@code is_visible_to_public}. {@code publicCount} is the visible bucket,
 * {@code privateCount} the hidden bucket.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VisibilityTypeCountDTO implements Serializable {
    /** Active rows visible to the public (is_public / is_visible_to_public true or null). */
    private long publicCount;
    /** Active rows hidden from the public (flag explicitly false). */
    private long privateCount;
    /** publicCount + privateCount (i.e. all active rows of this type). */
    private long total;
}
