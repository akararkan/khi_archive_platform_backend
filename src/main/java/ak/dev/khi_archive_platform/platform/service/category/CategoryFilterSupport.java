package ak.dev.khi_archive_platform.platform.service.category;

import ak.dev.khi_archive_platform.platform.dto.category.CategoryFilterParams;
import ak.dev.khi_archive_platform.platform.dto.category.CategoryResponseDTO;
import ak.dev.khi_archive_platform.platform.service.common.ArchiveTime;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * In-memory filter + sort over the read-cache list of active categories.
 *
 * The cache already holds every active category as a DTO, so applying the
 * predicates in Java avoids a DB round-trip and keeps the listing endpoint
 * sub-millisecond. O(N) over a list that is typically in the thousands.
 */
final class CategoryFilterSupport {

    private CategoryFilterSupport() {}

    static List<CategoryResponseDTO> applyFiltersAndSort(
            List<CategoryResponseDTO> source,
            CategoryFilterParams params) {

        if (source == null || source.isEmpty()) {
            return List.of();
        }
        if (params == null || params.isEmpty()) {
            return source;
        }

        Set<String> normalizedTags = normalizeTags(params.tags());
        boolean tagMatchAll = "all".equalsIgnoreCase(params.tagMatch());

        boolean dateFilter =
                params.createdFrom() != null || params.createdTo() != null
                        || params.updatedFrom() != null || params.updatedTo() != null;
        boolean tagFilter = !normalizedTags.isEmpty();

        List<CategoryResponseDTO> filtered;
        if (dateFilter || tagFilter) {
            filtered = new ArrayList<>(source.size());
            for (CategoryResponseDTO c : source) {
                if (dateFilter
                        && (!withinRange(c.getCreatedAt(), params.createdFrom(), params.createdTo())
                            || !withinRange(c.getUpdatedAt(), params.updatedFrom(), params.updatedTo()))) {
                    continue;
                }
                if (tagFilter && !matchesTags(c.getKeywords(), normalizedTags, tagMatchAll)) {
                    continue;
                }
                filtered.add(c);
            }
        } else {
            filtered = new ArrayList<>(source);
        }

        Comparator<CategoryResponseDTO> comparator = comparatorFor(params.sortBy());
        if (comparator != null) {
            if ("desc".equalsIgnoreCase(params.sortDirection())) {
                comparator = comparator.reversed();
            }
            filtered.sort(comparator);
        }

        return filtered;
    }

    /** Backend-owned timezone: YYYY-MM-DD range against an Instant column,
     *  resolved to archive-zone (Asia/Baghdad) day bounds via {@link ArchiveTime}. */
    private static boolean withinRange(Instant value, LocalDate from, LocalDate to) {
        Instant fromI = ArchiveTime.startOfDay(from);
        Instant toI = ArchiveTime.endOfDay(to);
        if (fromI == null && toI == null) return true;
        if (value == null) return false;
        if (fromI != null && value.isBefore(fromI)) return false;
        if (toI != null && value.isAfter(toI)) return false;
        return true;
    }

    private static boolean matchesTags(List<String> keywords, Set<String> wantedTags, boolean matchAll) {
        if (keywords == null || keywords.isEmpty()) return false;

        // "any" path: zero-allocation — walk the row's list once, probe the wanted set.
        if (!matchAll) {
            for (String k : keywords) {
                if (k == null) continue;
                if (wantedTags.contains(k.trim().toLowerCase(Locale.ROOT))) return true;
            }
            return false;
        }

        // "all" path: tiny counter sized to the wanted count.
        int needed = wantedTags.size();
        if (keywords.size() < needed) return false;
        Set<String> seen = new HashSet<>(needed * 2);
        int found = 0;
        for (String k : keywords) {
            if (k == null) continue;
            String n = k.trim().toLowerCase(Locale.ROOT);
            if (wantedTags.contains(n) && seen.add(n)) {
                if (++found >= needed) return true;
            }
        }
        return false;
    }

    private static Set<String> normalizeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) return Set.of();
        Set<String> out = new HashSet<>(tags.size());
        for (String t : tags) {
            if (t == null) continue;
            String n = t.trim().toLowerCase(Locale.ROOT);
            if (!n.isEmpty()) out.add(n);
        }
        return out;
    }

    private static Comparator<CategoryResponseDTO> comparatorFor(String sortBy) {
        if (sortBy == null || sortBy.isBlank()) return null;
        return switch (sortBy.toLowerCase(Locale.ROOT)) {
            case "name", "alpha", "alphabet", "alphabetical" -> Comparator.comparing(
                    CategoryResponseDTO::getName,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "createdat", "created", "added", "dateadded", "date_added" -> Comparator.comparing(
                    CategoryResponseDTO::getCreatedAt,
                    Comparator.nullsLast(Instant::compareTo));
            case "updatedat", "updated", "modified", "datemodified", "date_modified" -> Comparator.comparing(
                    CategoryResponseDTO::getUpdatedAt,
                    Comparator.nullsLast(Instant::compareTo));
            default -> null;
        };
    }
}
