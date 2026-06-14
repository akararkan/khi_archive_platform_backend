package ak.dev.khi_archive_platform.platform.dto.guest;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GuestCategoryDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private String categoryCode;
    private String name;
    private String description;
    private List<String> keywords;
    private long projectCount;
    private Instant createdAt;

    // ── Trending metadata ──────────────────────────────────────────────────
    private boolean isTrending;
    private Integer trendingRank;
    private Double  trendingScore;
}
