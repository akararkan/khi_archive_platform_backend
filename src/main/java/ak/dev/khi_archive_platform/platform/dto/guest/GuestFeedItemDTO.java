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
 * Slim card row for the unified /api/guest/feed endpoint.
 *
 * <p>Returned by the DB-side UNION ALL query (one row per matched media,
 * already paged at PostgreSQL level). Carries just enough to render a
 * thumbnail card: title, person, project, categories, language/region,
 * date, plus the kind + business code so the frontend can deep-link into
 * the per-type detail endpoint.
 *
 * <p>Full per-type detail (lyrics, contributors, technical metadata) is
 * fetched on demand from {@code /api/guest/audios/{code}} etc. — keeps
 * the feed page payload small and the query fast.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GuestFeedItemDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** image | video | audio | text — the feed is media-only. */
    private String kind;

    /** DB primary key — useful for stable client-side keys. */
    private Long id;

    /**
     * Business code — used to deep-link into the matching detail endpoint by
     * kind: image → /api/guest/images/{code}, video → /api/guest/videos/{code},
     * audio → /api/guest/audios/{code}, text → /api/guest/texts/{code}.
     */
    private String code;

    /** Best-available title: origin → alter → central-kurdish → romanized → code. */
    private String title;

    private String projectCode;
    private String projectName;

    private String personCode;
    private String personName;
    private String personMediaPortrait;

    private List<GuestCategorySummaryDTO> categories;

    private String language;
    private String dialect;
    private String region;

    private Instant dateCreated;
    private Instant datePublished;

    /** Public S3 URL of the asset (audioFileUrl / videoFileUrl / imageFileUrl / textFileUrl). */
    private String fileUrl;

    /** Relevance score when q is present (higher = better). 0 when no q. */
    private Double score;

    // ── Trending metadata (injected after the page query) ──
    private boolean isTrending;
    private Integer trendingRank;
    private Double trendingScore;
}
