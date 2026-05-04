package ak.dev.khi_archive_platform.platform.dto.category;

import java.time.Instant;
import java.util.List;

/**
 * Filter + sort parameters for category listing.
 *
 * Sort:
 *   sortBy        — name | createdAt | updatedAt (synonyms: alpha, added, modified)
 *   sortDirection — asc | desc (default asc)
 *
 * Filter:
 *   createdFrom/createdTo — inclusive range over Category.createdAt
 *   updatedFrom/updatedTo — inclusive range over Category.updatedAt
 *   tags                  — match against Category.keywords (case-insensitive)
 *   tagMatch              — any (default) | all
 */
public record CategoryFilterParams(
        String sortBy,
        String sortDirection,
        Instant createdFrom,
        Instant createdTo,
        Instant updatedFrom,
        Instant updatedTo,
        List<String> tags,
        String tagMatch
) {

    public static final CategoryFilterParams EMPTY =
            new CategoryFilterParams(null, null, null, null, null, null, null, null);

    public boolean isEmpty() {
        return blank(sortBy)
                && blank(sortDirection)
                && createdFrom == null && createdTo == null
                && updatedFrom == null && updatedTo == null
                && (tags == null || tags.isEmpty())
                && blank(tagMatch);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
