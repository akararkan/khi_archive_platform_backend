package ak.dev.khi_archive_platform.platform.dto.guest;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Query-string contract of {@code GET /api/guest/media/search}. Bound with
 * {@code @ModelAttribute}, the same style {@code AudioFilterParams} and
 * {@code MaqamFilterParams} use, so the website sends one flat query string and
 * nothing here needs a positional argument list.
 *
 * <p>Every field is optional. An empty request is a valid call: it returns the
 * newest public media of every kind.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GuestMediaSearchParams {

    /**
     * The search keyword — what the visitor typed, e.g. {@code Hasan Zirak}.
     * Matched across titles, codes, credits, the owning project and person,
     * tags, keywords, subjects, genres, places and free text on all four media
     * kinds. Blank or absent means "browse", not "no results".
     */
    private String q;

    /**
     * Which kinds to return: {@code all} (default) or any combination of
     * {@code audio}, {@code video}, {@code image}, {@code text}, either
     * repeated ({@code type=audio&type=video}) or comma-separated
     * ({@code type=audio,video}). Aliases: {@code photo(s)} → image,
     * {@code sound(s)} → audio, {@code file(s)}/{@code document(s)} → text.
     *
     * <p>This selects what {@code content} contains. It never changes
     * {@code counts}, which always covers all four kinds so the tab bar can
     * show every number at once.
     */
    private List<String> type;

    /**
     * {@code relevance} (default when {@code q} is present) | {@code newest}
     * (default otherwise) | {@code oldest} | {@code title} | {@code trending}.
     */
    private String sort;

    /**
     * {@code summary} (default) returns the flat card only.
     * {@code full} additionally attaches the complete kind-specific DTO on
     * {@code audio} / {@code video} / {@code image} / {@code text}.
     */
    private String include;

    /**
     * {@code none} (default) returns one merged, ranked list.
     * {@code type} additionally splits the same results into per-kind sections
     * under {@code groups}, each paged independently.
     */
    private String groupBy;

    /** When true, the response carries refine-panel counts over the matched set. */
    private Boolean facets;

    // ─── Scope filters ────────────────────────────────────────────────────────

    private String projectCode;
    private String categoryCode;
    private String personCode;

    private String language;
    private String dialect;
    private String region;

    /** Repeatable. Any-match against the item's subjects. */
    private List<String> subject;
    /** Repeatable. Any-match against the item's genres. */
    private List<String> genre;
    /** Repeatable. Any-match against the item's tags. */
    private List<String> tag;
    /** Repeatable. Any-match against the item's keywords. */
    private List<String> keyword;

    /** Inclusive lower bound on {@code dateCreated}. ISO date or instant. */
    private String dateFrom;
    /** Inclusive upper bound on {@code dateCreated}. A plain date covers the whole day. */
    private String dateTo;

    /**
     * Keeps only items created in this decade, e.g. {@code 1970} or
     * {@code 1970s} — the value the {@code decades} facet reports. Applied on
     * top of {@code dateFrom}/{@code dateTo}, and ignored when unparseable.
     */
    private String decade;
}
