package ak.dev.khi_archive_platform.platform.dto.analytics;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * Page of activity rows. Mirrors the shape of {@code Page<T>} but is a plain
 * value object so we don't leak Spring's Page across the network and can
 * serialise it to Redis cleanly.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeedPageDTO implements Serializable {
    private List<RecentActivityItemDTO> items;
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;
    private boolean hasNext;
    private boolean hasPrevious;
}
