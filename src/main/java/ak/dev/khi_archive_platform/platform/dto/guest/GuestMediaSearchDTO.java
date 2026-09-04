package ak.dev.khi_archive_platform.platform.dto.guest;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * Response of {@code GET /api/guest/media/search} — one keyword, every media
 * kind, ranked together.
 *
 * <p>Everything a search page needs arrives in a single call:
 * <ul>
 *   <li>{@link #counts} — the tab bar ("All 312 · Audio 148 · Video 22 · …"),
 *       always for the whole match set, never only for the selected tab;</li>
 *   <li>{@link #content} — the merged, relevance-ranked page of results;</li>
 *   <li>{@link #groups} — the same results split per kind, when the caller
 *       asks for {@code groupBy=type};</li>
 *   <li>{@link #facets} — refine-panel counts computed over the matched set,
 *       not over the whole archive.</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GuestMediaSearchDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** The trimmed query that was actually run. Empty string when browsing without a keyword. */
    private String query;

    /** Which tab the caller asked for: {@code all | audio | video | image | text}. */
    private String type;

    /** The sort that was actually applied after defaulting — see the API docs. */
    private String sort;

    /** Fixed public order of the kinds: audio, video, image, text. */
    private List<String> order;

    /** Per-kind totals for the tab bar. Independent of the selected {@link #type}. */
    private Counts counts;

    // ── The merged page ───────────────────────────────────────────────────────

    private List<GuestMediaHitDTO> content;

    private int page;
    private int size;
    private long totalElements;
    private int totalPages;
    private int numberOfElements;
    private boolean first;
    private boolean last;
    private boolean empty;
    private boolean hasNext;
    private boolean hasPrevious;

    // ── Optional extras ───────────────────────────────────────────────────────

    /** Per-kind sections. Null unless the caller asked for {@code groupBy=type}. */
    private Groups groups;

    /** Refine-panel counts over the matched set. Null unless {@code facets=true}. */
    private Facets facets;

    /**
     * True when at least one kind hit the per-kind scan cap, so the counts are
     * a floor rather than an exact total. Narrow the query or add filters.
     */
    private boolean truncated;

    // ── Nested shapes ─────────────────────────────────────────────────────────

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Counts implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private long total;
        private long audio;
        private long video;
        private long image;
        private long text;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Groups implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private Section audio;
        private Section video;
        private Section image;
        private Section text;
    }

    /** One media kind's own slice of the results, paged independently. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Section implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String kind;
        private List<GuestMediaHitDTO> content;
        private int page;
        private int size;
        private long totalElements;
        private int totalPages;
        private int numberOfElements;
        private boolean first;
        private boolean last;
        private boolean empty;
    }

    /**
     * Facet counts over every matched item — the numbers next to the refine
     * checkboxes. Each bucket's {@code label} is what the user sees and what
     * you send back as the corresponding filter param.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Facets implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private List<GuestFacetsDTO.Bucket> languages;
        private List<GuestFacetsDTO.Bucket> dialects;
        private List<GuestFacetsDTO.Bucket> regions;
        private List<GuestFacetsDTO.Bucket> subjects;
        private List<GuestFacetsDTO.Bucket> genres;
        private List<GuestFacetsDTO.Bucket> tags;
        private List<GuestFacetsDTO.Bucket> keywords;
        /** Bucket {@code code} carries the person code, ready for {@code personCode=}. */
        private List<GuestFacetsDTO.Bucket> persons;
        /** Bucket {@code code} carries the project code, ready for {@code projectCode=}. */
        private List<GuestFacetsDTO.Bucket> projects;
        /** Bucket {@code label} is the decade, e.g. {@code "1970s"}. */
        private List<GuestFacetsDTO.Bucket> decades;
    }
}
