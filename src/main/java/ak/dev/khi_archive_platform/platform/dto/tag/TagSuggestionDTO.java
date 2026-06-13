package ak.dev.khi_archive_platform.platform.dto.tag;

import java.io.Serial;
import java.io.Serializable;

/**
 * One row of the {@code GET /api/tags/suggest} autocomplete response.
 *
 * <ul>
 *   <li>{@code value} — the canonical tag string (already lower-cased and
 *       deduped — safe to insert verbatim).</li>
 *   <li>{@code usageCount} — how many records (across audio + video + image +
 *       text + project) currently use this tag. Drives the
 *       "Sulaimaniyah (42)" UX and ranking ties.</li>
 *   <li>{@code matchRank} — sort key for the UI: {@code 0 = exact match},
 *       {@code 1 = prefix match}, {@code 2 = substring match}. Lower = more
 *       relevant; the server already returns the list pre-sorted, but exposing
 *       the rank lets the UI group results into "Best matches" /
 *       "More results" sections if it wants.</li>
 * </ul>
 */
public record TagSuggestionDTO(
        String value,
        long usageCount,
        int matchRank
) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
}
