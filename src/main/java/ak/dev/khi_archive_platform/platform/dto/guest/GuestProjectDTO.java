package ak.dev.khi_archive_platform.platform.dto.guest;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.List;

/**
 * Public-facing project (collection) shape. Strips internal/audit fields
 * (createdBy, removedAt, version) and trash metadata. Includes per-type
 * media counts so the browse page can show "12 audios · 3 videos".
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GuestProjectDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private String projectCode;
    private String projectName;
    private String description;
    private List<String> tags;
    private List<String> keywords;
    private GuestPersonSummaryDTO person;
    private List<GuestCategorySummaryDTO> categories;
    private MediaCounts mediaCounts;
    private Instant createdAt;
    private Instant updatedAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MediaCounts implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private long audios;
        private long videos;
        private long texts;
        private long images;
        public long total() { return audios + videos + texts + images; }
    }

    // ── Trending metadata ──────────────────────────────────────────────────
    private boolean isTrending;
    private Integer trendingRank;
    private Double  trendingScore;
}
