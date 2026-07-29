package ak.dev.khi_archive_platform.platform.dto.person;

import ak.dev.khi_archive_platform.platform.enums.Gender;

import java.time.LocalDate;
import java.util.List;

/**
 * Filter + sort parameters for the person listing.
 *
 * Sort:
 *   sortBy        — fullName | createdAt | updatedAt | dateOfBirth | dateOfDeath
 *                   (synonyms: name/alpha, added/dateAdded, modified/dateModified, dob, dod)
 *   sortDirection — asc | desc (default asc)
 *
 * Filter:
 *   gender               — exact (MALE|FEMALE)
 *   personType           — match against Person.personType list (case-insensitive)
 *   personTypeMatch      — any (default) | all
 *   region               — case-insensitive contains
 *   dobFrom / dobTo      — inclusive LocalDate range over dateOfBirth
 *   dodFrom / dodTo      — inclusive LocalDate range over dateOfDeath
 *   placeOfBirth         — case-insensitive contains
 *   placeOfDeath         — case-insensitive contains
 *   tags                 — match against Person.tag (case-insensitive)
 *   tagMatch             — any (default) | all
 *   keywords             — match against Person.keywords (case-insensitive)
 *   keywordMatch         — any (default) | all
 *   createdFrom/To       — inclusive YYYY-MM-DD range over createdAt (archive zone Asia/Baghdad)
 *   updatedFrom/To       — inclusive YYYY-MM-DD range over updatedAt (archive zone Asia/Baghdad)
 */
public record PersonFilterParams(
        String sortBy,
        String sortDirection,
        Gender gender,
        List<String> personType,
        String personTypeMatch,
        String region,
        LocalDate dobFrom,
        LocalDate dobTo,
        LocalDate dodFrom,
        LocalDate dodTo,
        String placeOfBirth,
        String placeOfDeath,
        List<String> tags,
        String tagMatch,
        List<String> keywords,
        String keywordMatch,
        LocalDate createdFrom,
        LocalDate createdTo,
        LocalDate updatedFrom,
        LocalDate updatedTo
) {

    public static final PersonFilterParams EMPTY = new PersonFilterParams(
            null, null, null, null, null, null,
            null, null, null, null, null, null,
            null, null, null, null,
            null, null, null, null);

    public boolean isEmpty() {
        return blank(sortBy) && blank(sortDirection)
                && gender == null
                && (personType == null || personType.isEmpty()) && blank(personTypeMatch)
                && blank(region)
                && dobFrom == null && dobTo == null
                && dodFrom == null && dodTo == null
                && blank(placeOfBirth) && blank(placeOfDeath)
                && (tags == null || tags.isEmpty()) && blank(tagMatch)
                && (keywords == null || keywords.isEmpty()) && blank(keywordMatch)
                && createdFrom == null && createdTo == null
                && updatedFrom == null && updatedTo == null;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
