package ak.dev.khi_archive_platform.platform.dto.guest;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.List;

/**
 * Response shape for GET /api/guest/trending.
 *
 * Frontend usage guide:
 *
 *  1. Trending carousel / banner
 *     Use `trendingItems` (up to 20 items, mixed types, ranked by score).
 *     Display the top 5 as a "Trending Now" hero section.
 *     Any item with rank <= 10 should show a "🔥 Trending" badge.
 *
 *  2. Per-type trending rows
 *     Use `trendingByType.audio`, `.video`, `.text`, `.image` for
 *     "Trending Audios", "Trending Videos" etc. horizontal scroll rows.
 *
 *  3. Popular searches tag cloud
 *     Use `topSearches` to render clickable search chips.
 *
 *  4. Marking items as trending on other pages
 *     Store the Set of trending codes from `trendingItems` in app state.
 *     When rendering any entity card elsewhere, check:
 *       trendingCodes.has(item.code) → show the trending badge.
 *
 *  5. Cache TTL
 *     This response is cached for 5 minutes server-side.
 *     The frontend may cache it locally for the same duration.
 *     Include `generatedAt` in the UI tooltip: "Updated X minutes ago".
 */
@Getter
@Builder
public class GuestTrendingDTO {

    /** Server-side timestamp when this snapshot was computed. */
    private Instant generatedAt;

    /**
     * Top 20 trending items across all media types, ranked by score.
     * Score is computed with time-decay: last-hour views worth 3×,
     * last-day views 2×, last-week views 1×.
     */
    private List<TrendingItem> trendingItems;

    /** Top 10 search queries in the last 24 hours, most frequent first. */
    private List<TopSearch> topSearches;

    /** Top 5 trending items per media type — for type-specific rows. */
    private ByType trendingByType;

    // ── Nested types ──────────────────────────────────────────────────────

    @Getter
    @Builder
    public static class TrendingItem {
        /** 1-based rank. Use rank <= 10 to show the "🔥 Trending" badge. */
        private int rank;
        /** Computed score (higher = hotter). Expose in tooltip if desired. */
        private double score;
        /** "audio" | "video" | "text" | "image" | "project" | "person" */
        private String kind;
        /** The entity's code — use this as the unique identifier. */
        private String code;
        /** Best available display title. */
        private String title;
        /** Thumbnail / portrait URL — may be null. */
        private String thumbnail;
        /** Link context */
        private String projectCode;
        private String projectName;
        private String personCode;
        private String personName;
        /** Full entity DTO — only one will be non-null depending on kind. */
        private GuestAudioDTO   audio;
        private GuestVideoDTO   video;
        private GuestTextDTO    text;
        private GuestImageDTO   image;
        private GuestProjectDTO project;
        private GuestPersonDTO  person;
    }

    @Getter
    @Builder
    public static class TopSearch {
        private String query;
        private long   count;
    }

    @Getter
    @Builder
    public static class ByType {
        private List<TrendingItem> audio;
        private List<TrendingItem> video;
        private List<TrendingItem> text;
        private List<TrendingItem> image;
    }
}
