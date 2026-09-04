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
 * One search result, in a shape that is identical for every media kind.
 *
 * <p>This is the card model for the public website's search page: the four
 * media kinds (audio, video, image, text) all collapse onto the same fields,
 * so a single result component can render a mixed list without switching on
 * the type. Kind-specific extras that a card still needs — {@code duration}
 * for sounds and videos, {@code pageCount} / {@code documentType} for
 * files — are carried as optional fields and are simply {@code null} for the
 * kinds they do not apply to.
 *
 * <p>The full, kind-specific payload is available on demand: request
 * {@code include=full} and exactly one of {@link #audio}, {@link #video},
 * {@link #image}, {@link #text} is populated — the one matching
 * {@link #type}. By default all four are {@code null} and the response stays
 * small.
 *
 * @see GuestMediaSearchDTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GuestMediaHitDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    // ── Identity ──────────────────────────────────────────────────────────────

    /** {@code audio} | {@code video} | {@code image} | {@code text}. */
    private String type;

    /** The public code of the item — {@code audioCode}, {@code videoCode}, … */
    private String code;

    private Long id;

    // ── Headline ──────────────────────────────────────────────────────────────

    /** Best available title: original → Central Kurdish → romanized → alternative → code. */
    private String title;

    /** The next-best title, when it differs from {@link #title}. Null otherwise. */
    private String subtitle;

    private String titleInCentralKurdish;
    private String romanizedTitle;

    /** Description, trimmed to a card-friendly length and suffixed with "…" when cut. */
    private String description;

    /**
     * The person most responsible for the item, resolved per kind:
     * singer (falling back to speaker) for audio, director for video,
     * photographer for image, author for text.
     */
    private String creator;

    /** Which field {@link #creator} came from, e.g. {@code singer}, {@code author}. */
    private String creatorRole;

    // ── Context ───────────────────────────────────────────────────────────────

    private String projectCode;
    private String projectName;
    private GuestPersonSummaryDTO person;
    private List<GuestCategorySummaryDTO> categories;

    private String language;
    private String dialect;
    private String region;

    private List<String> subject;
    private List<String> genre;
    private List<String> tags;
    private List<String> keywords;

    // ── Kind-specific extras (null when not applicable) ───────────────────────

    /** Audio and video only. */
    private String duration;

    /** Text only. */
    private Integer pageCount;

    /** Text only. */
    private String documentType;

    // ── Dates ─────────────────────────────────────────────────────────────────

    private Instant dateCreated;
    private Instant datePublished;

    // ── Links (relative — prepend the API base URL) ───────────────────────────

    /**
     * The byte-proxy path for the media itself: audio/video stream, image view,
     * text read. The underlying S3 URL is never exposed.
     */
    private String mediaUrl;

    /**
     * A path that renders as a picture for this hit: the image itself, the text
     * cover, or — for sounds and videos, which have no still of their own — the
     * portrait of the project's person, when there is one. May be null.
     */
    private String thumbnailUrl;

    /** Canonical detail endpoint for this hit, e.g. {@code /api/guest/media/audio/AUD-001}. */
    private String detailUrl;

    // ── Ranking ───────────────────────────────────────────────────────────────

    /** Relevance score for the current query. 0 when the request carried no {@code q}. */
    private double score;

    /**
     * Which field groups the query matched, strongest first — e.g.
     * {@code ["title", "person", "tags"]}. Drives "matched in …" hints in the
     * result card. Empty when the request carried no {@code q}.
     */
    private List<String> matchedIn;

    // ── Trending ──────────────────────────────────────────────────────────────

    private boolean isTrending;
    private Integer trendingRank;
    private Double trendingScore;

    // ── Full payload (only when include=full) ─────────────────────────────────

    private GuestAudioDTO audio;
    private GuestVideoDTO video;
    private GuestImageDTO image;
    private GuestTextDTO text;
}
