package ak.dev.khi_archive_platform.platform.service.person;

import ak.dev.khi_archive_platform.platform.dto.person.PersonFilterParams;
import ak.dev.khi_archive_platform.platform.dto.person.PersonResponseDTO;
import ak.dev.khi_archive_platform.platform.service.common.ArchiveTime;
import ak.dev.khi_archive_platform.platform.service.common.KurdishText;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * In-memory filter + sort over the read-cache list of active persons.
 *
 * The cache already holds every active person as a DTO (with personType, tag,
 * keywords already split into lists), so applying these predicates in Java
 * avoids a DB round-trip and keeps the listing endpoint sub-millisecond.
 * O(N) over a list typically in the thousands.
 */
final class PersonFilterSupport {

    private PersonFilterSupport() {}

    static List<PersonResponseDTO> applyFiltersAndSort(
            List<PersonResponseDTO> source,
            PersonFilterParams params) {

        if (source == null || source.isEmpty()) {
            return List.of();
        }
        if (params == null || params.isEmpty()) {
            return source;
        }

        Set<String> wantedTypes = normalize(params.personType());
        boolean typeAll = "all".equalsIgnoreCase(params.personTypeMatch());

        Set<String> wantedTags = normalize(params.tags());
        boolean tagAll = "all".equalsIgnoreCase(params.tagMatch());

        Set<String> wantedKeywords = normalize(params.keywords());
        boolean keywordAll = "all".equalsIgnoreCase(params.keywordMatch());

        String region = lower(params.region());
        String pob = lower(params.placeOfBirth());
        String pod = lower(params.placeOfDeath());

        boolean anyFilter =
                params.gender() != null
                        || !wantedTypes.isEmpty()
                        || region != null
                        || params.dobFrom() != null || params.dobTo() != null
                        || params.dodFrom() != null || params.dodTo() != null
                        || pob != null || pod != null
                        || !wantedTags.isEmpty()
                        || !wantedKeywords.isEmpty()
                        || params.createdFrom() != null || params.createdTo() != null
                        || params.updatedFrom() != null || params.updatedTo() != null;

        List<PersonResponseDTO> filtered;
        if (anyFilter) {
            filtered = new ArrayList<>(source.size());
            for (PersonResponseDTO p : source) {
                if (params.gender() != null && p.getGender() != params.gender()) continue;

                if (!wantedTypes.isEmpty()
                        && !matchListAny(p.getPersonType(), wantedTypes, typeAll)) continue;

                if (region != null && !containsLower(p.getRegion(), region)) continue;

                if (!withinDateRange(p.getDateOfBirth(), params.dobFrom(), params.dobTo())) continue;
                if (!withinDateRange(p.getDateOfDeath(), params.dodFrom(), params.dodTo())) continue;

                if (pob != null && !containsLower(p.getPlaceOfBirth(), pob)) continue;
                if (pod != null && !containsLower(p.getPlaceOfDeath(), pod)) continue;

                if (!wantedTags.isEmpty()
                        && !matchListAny(p.getTag(), wantedTags, tagAll)) continue;
                if (!wantedKeywords.isEmpty()
                        && !matchListAny(p.getKeywords(), wantedKeywords, keywordAll)) continue;

                if (!withinInstantRange(p.getCreatedAt(), params.createdFrom(), params.createdTo())) continue;
                if (!withinInstantRange(p.getUpdatedAt(), params.updatedFrom(), params.updatedTo())) continue;

                filtered.add(p);
            }
        } else {
            filtered = new ArrayList<>(source);
        }

        Comparator<PersonResponseDTO> comparator = comparatorFor(params.sortBy());
        if (comparator != null) {
            if ("desc".equalsIgnoreCase(params.sortDirection())) {
                comparator = comparator.reversed();
            }
            filtered.sort(comparator);
        }

        return filtered;
    }

    // ─── Predicates ───────────────────────────────────────────────────────────────

    /** Backend-owned timezone: YYYY-MM-DD range against an Instant column,
     *  resolved to archive-zone (Asia/Baghdad) day bounds via {@link ArchiveTime}. */
    private static boolean withinInstantRange(Instant value, LocalDate from, LocalDate to) {
        Instant fromI = ArchiveTime.startOfDay(from);
        Instant toI = ArchiveTime.endOfDay(to);
        if (fromI == null && toI == null) return true;
        if (value == null) return false;
        if (fromI != null && value.isBefore(fromI)) return false;
        if (toI != null && value.isAfter(toI)) return false;
        return true;
    }

    private static boolean withinDateRange(LocalDate value, LocalDate from, LocalDate to) {
        if (from == null && to == null) return true;
        if (value == null) return false;
        if (from != null && value.isBefore(from)) return false;
        if (to != null && value.isAfter(to)) return false;
        return true;
    }

    private static boolean containsLower(String value, String needleLower) {
        if (value == null) return false;
        return KurdishText.normalize(value).contains(needleLower);
    }

    /**
     * Match a list-of-strings field against a wanted set, case-insensitively.
     * "any" runs zero-allocation; "all" uses a tiny counter sized to the wanted count.
     */
    private static boolean matchListAny(List<String> field, Set<String> wantedLowercase, boolean matchAll) {
        if (field == null || field.isEmpty()) return false;

        if (!matchAll) {
            for (String s : field) {
                if (s == null) continue;
                if (wantedLowercase.contains(s.trim().toLowerCase(Locale.ROOT))) return true;
            }
            return false;
        }

        int needed = wantedLowercase.size();
        if (field.size() < needed) return false;
        Set<String> seen = new HashSet<>(needed * 2);
        int found = 0;
        for (String s : field) {
            if (s == null) continue;
            String n = s.trim().toLowerCase(Locale.ROOT);
            if (wantedLowercase.contains(n) && seen.add(n)) {
                if (++found >= needed) return true;
            }
        }
        return false;
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────────

    private static Set<String> normalize(List<String> values) {
        if (values == null || values.isEmpty()) return Set.of();
        Set<String> out = new HashSet<>(values.size());
        for (String v : values) {
            if (v == null) continue;
            String n = v.trim().toLowerCase(Locale.ROOT);
            if (!n.isEmpty()) out.add(n);
        }
        return out;
    }

    private static String lower(String s) {
        if (s == null) return null;
        String t = KurdishText.normalize(s);
        return t.isEmpty() ? null : t;
    }

    // ─── Sort ─────────────────────────────────────────────────────────────────────

    private static Comparator<PersonResponseDTO> comparatorFor(String sortBy) {
        if (sortBy == null || sortBy.isBlank()) return null;
        return switch (sortBy.toLowerCase(Locale.ROOT)) {
            case "fullname", "name", "alpha", "alphabet", "alphabetical" -> Comparator.comparing(
                    PersonResponseDTO::getFullName,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "createdat", "created", "added", "dateadded", "date_added" -> Comparator.comparing(
                    PersonResponseDTO::getCreatedAt,
                    Comparator.nullsLast(Instant::compareTo));
            case "updatedat", "updated", "modified", "datemodified", "date_modified" -> Comparator.comparing(
                    PersonResponseDTO::getUpdatedAt,
                    Comparator.nullsLast(Instant::compareTo));
            case "dateofbirth", "dob", "birth", "date_of_birth" -> Comparator.comparing(
                    PersonResponseDTO::getDateOfBirth,
                    Comparator.nullsLast(LocalDate::compareTo));
            case "dateofdeath", "dod", "death", "date_of_death" -> Comparator.comparing(
                    PersonResponseDTO::getDateOfDeath,
                    Comparator.nullsLast(LocalDate::compareTo));
            default -> null;
        };
    }
}
