package ak.dev.khi_archive_platform.platform.dto.keyword;

import java.io.Serial;
import java.io.Serializable;

/**
 * One row of the {@code GET /api/keywords/suggest} autocomplete response.
 *
 * <p>Identical shape to
 * {@link ak.dev.khi_archive_platform.platform.dto.tag.TagSuggestionDTO} so the
 * frontend can reuse the same dropdown component for both pickers.</p>
 *
 * <ul>
 *   <li>{@code value} — the canonical keyword string (already NFKC-normalised,
 *       whitespace-collapsed and lower-cased — safe to insert verbatim).</li>
 *   <li>{@code usageCount} — how many records (across audio + video + image +
 *       text + project + category) currently use this keyword.</li>
 *   <li>{@code matchRank} — sort key: {@code 0 = exact}, {@code 1 = prefix},
 *       {@code 2 = substring}. Lower = more relevant.</li>
 * </ul>
 */
public record KeywordSuggestionDTO(
        String value,
        long usageCount,
        int matchRank
) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
}
