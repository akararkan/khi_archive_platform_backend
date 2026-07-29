package ak.dev.khi_archive_platform.platform.dto.items;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/**
 * Filter / search / sort parameters for GET /api/items. Every field is
 * optional — the empty params object returns the union of all active media
 * across the four caches.
 *
 * <p>Algorithm notes:
 *  <ul>
 *    <li>{@code types} short-circuits whole-bucket reads — if the caller asks
 *        for only AUDIO, we skip pulling the other three caches entirely.</li>
 *    <li>{@code q} is matched case-insensitively as a substring against the
 *        per-row searchable text (title, code, project, person, tags,
 *        keywords). No DB round-trip — the read-caches already hold the
 *        active rows, so we filter in-memory in a single linear pass.</li>
 *    <li>List filters ({@code projectCodes}, {@code personCodes},
 *        {@code categoryCodes}, {@code languages}) are pre-normalized into
 *        lower-case sets once before the loop so every row check is O(1).</li>
 *  </ul>
 */
@Data
@NoArgsConstructor
@SuppressWarnings("unused")
public class ItemFilterParams {

    /** Free-text query — matches code, title, project, person, tags, keywords. */
    private String q;

    /** Limit to a subset of media types. Empty/null = all four. */
    private List<ItemType> types;

    /** Restrict to specific project codes. */
    private List<String> projectCodes;

    /** Restrict to specific person codes. Null person (untitled) is treated as the literal "UNTITLED". */
    private List<String> personCodes;

    /** Restrict to projects that join any of these category codes. */
    private List<String> categoryCodes;

    /** Restrict by language (case-insensitive). */
    private List<String> languages;

    /** If non-null, keep only rows whose own isPublic matches. */
    private Boolean isPublic;

    /** If non-null, keep only rows whose project's isVisibleToPublic matches. */
    private Boolean projectVisibleToPublic;

    /** createdAt range (inclusive). */
    private LocalDate createdFrom;
    private LocalDate createdTo;

    /** updatedAt range (inclusive). */
    private LocalDate updatedFrom;
    private LocalDate updatedTo;

    /**
     * Sort field. Accepts (case-insensitive): {@code createdAt},
     * {@code updatedAt}, {@code title}, {@code code}, {@code projectName},
     * {@code personName}, {@code type}. Default when null: {@code updatedAt}.
     */
    private String sortBy;

    /** {@code asc} or {@code desc}. Default: {@code desc} for date sorts, {@code asc} otherwise. */
    private String sortDirection;

    public boolean isEmpty() {
        return isBlank(q)
                && isEmptyList(types)
                && isEmptyList(projectCodes)
                && isEmptyList(personCodes)
                && isEmptyList(categoryCodes)
                && isEmptyList(languages)
                && isPublic == null
                && projectVisibleToPublic == null
                && createdFrom == null && createdTo == null
                && updatedFrom == null && updatedTo == null
                && isBlank(sortBy)
                && isBlank(sortDirection);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static boolean isEmptyList(List<?> l) {
        return l == null || l.isEmpty();
    }
}
