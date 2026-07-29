package ak.dev.khi_archive_platform.platform.service.text;

import ak.dev.khi_archive_platform.platform.dto.text.TextFilterParams;
import ak.dev.khi_archive_platform.platform.dto.text.TextResponseDTO;
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
 * In-memory filter + sort over the read-cache list of active texts.
 *
 * Algorithm — single linear pass with cheap-first short-circuiting:
 *   1. wanted-token sets are normalized once outside the loop
 *   2. each row is checked against every active predicate in cost order
 *      (boolean equals → numeric range → date range → string equals →
 *       string contains → collection match)
 *   3. the "any" path on collections runs zero-allocation: it walks the row's
 *      list once and probes the pre-built wanted set per element
 *   4. the "all" path uses a small bounded counter sized to the wanted count
 *
 * For N≈thousands this is microseconds per query and far cheaper than
 * round-tripping the DB. With no params it collapses to identity (returns the
 * cached list as-is).
 */
final class TextFilterSupport {

    private TextFilterSupport() {}

    static List<TextResponseDTO> applyFiltersAndSort(
            List<TextResponseDTO> source,
            TextFilterParams params) {

        if (source == null || source.isEmpty()) {
            return List.of();
        }
        if (params == null || params.isEmpty()) {
            return source;
        }

        // ── Pre-normalize ──────────────────────────────────────────────────────
        String documentType = lower(params.getDocumentType());
        String script = lower(params.getScript());
        String edition = lower(params.getEdition());
        String volume = lower(params.getVolume());
        String series = lower(params.getSeries());
        String textVersion = lower(params.getTextVersion());
        String textStatus = lower(params.getTextStatus());
        String audience = lower(params.getAudience());
        String extension = lower(params.getExtension());
        String orientation = lower(params.getOrientation());
        String size = lower(params.getSize());
        String physicalDimensions = lower(params.getPhysicalDimensions());
        String language = lower(params.getLanguage());
        String dialect = lower(params.getDialect());
        String printingHouse = lower(params.getPrintingHouse());
        String accrualMethod = lower(params.getAccrualMethod());
        String lccClassification = lower(params.getLccClassification());
        String availability = lower(params.getAvailability());
        String licenseType = lower(params.getLicenseType());
        String isbn = lower(params.getIsbn());
        String assignmentNumber = lower(params.getAssignmentNumber());

        String description = lower(params.getDescription());
        String transcription = lower(params.getTranscription());
        String author = lower(params.getAuthor());
        String contributors = lower(params.getContributors());
        String provenance = lower(params.getProvenance());
        String archiveCataloging = lower(params.getArchiveCataloging());
        String physicalLabel = lower(params.getPhysicalLabel());
        String locationInArchiveRoom = lower(params.getLocationInArchiveRoom());
        String note = lower(params.getNote());
        String copyright = lower(params.getCopyright());
        String rightOwner = lower(params.getRightOwner());
        String usageRights = lower(params.getUsageRights());
        String owner = lower(params.getOwner());
        String publisher = lower(params.getPublisher());

        Set<String> wantedSubjects = normalize(params.getSubject());
        boolean subjectAll = "all".equalsIgnoreCase(params.getSubjectMatch());
        Set<String> wantedGenres = normalize(params.getGenre());
        boolean genreAll = "all".equalsIgnoreCase(params.getGenreMatch());
        Set<String> wantedTags = normalize(params.getTags());
        boolean tagAll = "all".equalsIgnoreCase(params.getTagMatch());
        Set<String> wantedKeywords = normalize(params.getKeywords());
        boolean keywordAll = "all".equalsIgnoreCase(params.getKeywordMatch());

        // ── Linear scan ─────────────────────────────────────────────────────────
        List<TextResponseDTO> filtered = new ArrayList<>(Math.min(source.size(), 256));
        for (TextResponseDTO t : source) {

            if (params.getPhysicalAvailability() != null
                    && !params.getPhysicalAvailability().equals(t.getPhysicalAvailability())) continue;

            if (!withinIntRange(t.getVersionNumber(), params.getVersionNumberMin(), params.getVersionNumberMax())) continue;
            if (!withinIntRange(t.getCopyNumber(), params.getCopyNumberMin(), params.getCopyNumberMax())) continue;
            if (!withinIntRange(t.getPageCount(), params.getPageCountMin(), params.getPageCountMax())) continue;

            if (!withinInstantRange(t.getDateCreated(), params.getDateCreatedFrom(), params.getDateCreatedTo())) continue;
            if (!withinInstantRange(t.getPrintDate(), params.getPrintDateFrom(), params.getPrintDateTo())) continue;
            if (!withinInstantRange(t.getDateModified(), params.getDateModifiedFrom(), params.getDateModifiedTo())) continue;
            if (!withinInstantRange(t.getDatePublished(), params.getDatePublishedFrom(), params.getDatePublishedTo())) continue;
            if (!withinInstantRange(t.getDateCopyrighted(), params.getDateCopyrightedFrom(), params.getDateCopyrightedTo())) continue;

            if (documentType != null && !equalsLower(t.getDocumentType(), documentType)) continue;
            if (script != null && !equalsLower(t.getScript(), script)) continue;
            if (edition != null && !equalsLower(t.getEdition(), edition)) continue;
            if (volume != null && !equalsLower(t.getVolume(), volume)) continue;
            if (series != null && !equalsLower(t.getSeries(), series)) continue;
            if (textVersion != null && !equalsLower(t.getTextVersion(), textVersion)) continue;
            if (textStatus != null && !equalsLower(t.getTextStatus(), textStatus)) continue;
            if (audience != null && !equalsLower(t.getAudience(), audience)) continue;
            if (extension != null && !equalsLower(t.getExtension(), extension)) continue;
            if (orientation != null && !equalsLower(t.getOrientation(), orientation)) continue;
            if (size != null && !equalsLower(t.getSize(), size)) continue;
            if (physicalDimensions != null && !equalsLower(t.getPhysicalDimensions(), physicalDimensions)) continue;
            if (language != null && !equalsLower(t.getLanguage(), language)) continue;
            if (dialect != null && !equalsLower(t.getDialect(), dialect)) continue;
            if (printingHouse != null && !equalsLower(t.getPrintingHouse(), printingHouse)) continue;
            if (accrualMethod != null && !equalsLower(t.getAccrualMethod(), accrualMethod)) continue;
            if (lccClassification != null && !equalsLower(t.getLccClassification(), lccClassification)) continue;
            if (availability != null && !equalsLower(t.getAvailability(), availability)) continue;
            if (licenseType != null && !equalsLower(t.getLicenseType(), licenseType)) continue;
            if (isbn != null && !equalsLower(t.getIsbn(), isbn)) continue;
            if (assignmentNumber != null && !equalsLower(t.getAssignmentNumber(), assignmentNumber)) continue;

            if (description != null && !containsLower(t.getDescription(), description)) continue;
            if (transcription != null && !containsLower(t.getTranscription(), transcription)) continue;
            if (author != null && !containsLower(t.getAuthor(), author)) continue;
            if (contributors != null && !containsLower(t.getContributors(), contributors)) continue;
            if (provenance != null && !containsLower(t.getProvenance(), provenance)) continue;
            if (archiveCataloging != null && !containsLower(t.getArchiveCataloging(), archiveCataloging)) continue;
            if (physicalLabel != null && !containsLower(t.getPhysicalLabel(), physicalLabel)) continue;
            if (locationInArchiveRoom != null && !containsLower(t.getLocationInArchiveRoom(), locationInArchiveRoom)) continue;
            if (note != null && !containsLower(t.getNote(), note)) continue;
            if (copyright != null && !containsLower(t.getCopyright(), copyright)) continue;
            if (rightOwner != null && !containsLower(t.getRightOwner(), rightOwner)) continue;
            if (usageRights != null && !containsLower(t.getUsageRights(), usageRights)) continue;
            if (owner != null && !containsLower(t.getOwner(), owner)) continue;
            if (publisher != null && !containsLower(t.getPublisher(), publisher)) continue;

            if (!wantedSubjects.isEmpty()
                    && !matchList(t.getSubject(), wantedSubjects, subjectAll)) continue;
            if (!wantedGenres.isEmpty()
                    && !matchList(t.getGenre(), wantedGenres, genreAll)) continue;
            if (!wantedTags.isEmpty()
                    && !matchList(t.getTags(), wantedTags, tagAll)) continue;
            if (!wantedKeywords.isEmpty()
                    && !matchList(t.getKeywords(), wantedKeywords, keywordAll)) continue;

            if (!withinInstantRange(t.getCreatedAt(), params.getCreatedFrom(), params.getCreatedTo())) continue;
            if (!withinInstantRange(t.getUpdatedAt(), params.getUpdatedFrom(), params.getUpdatedTo())) continue;

            filtered.add(t);
        }

        Comparator<TextResponseDTO> comparator = comparatorFor(params.getSortBy());
        if (comparator != null) {
            if ("desc".equalsIgnoreCase(params.getSortDirection())) {
                comparator = comparator.reversed();
            }
            filtered.sort(comparator);
        }

        return filtered;
    }

    // ─── Predicates ───────────────────────────────────────────────────────────────

    /**
     * Backend-owned timezone: a {@code YYYY-MM-DD} range against an Instant
     * column, resolved to archive-zone (Asia/Baghdad) day bounds via
     * {@link ArchiveTime}.
     */
    static boolean withinInstantRange(Instant value, LocalDate from, LocalDate to) {
        Instant fromI = ArchiveTime.startOfDay(from);
        Instant toI = ArchiveTime.endOfDay(to);
        if (fromI == null && toI == null) return true;
        if (value == null) return false;
        if (fromI != null && value.isBefore(fromI)) return false;
        if (toI != null && value.isAfter(toI)) return false;
        return true;
    }

    static boolean withinIntRange(Integer value, Integer min, Integer max) {
        if (min == null && max == null) return true;
        if (value == null) return false;
        if (min != null && value < min) return false;
        if (max != null && value > max) return false;
        return true;
    }

    static boolean equalsLower(String value, String needleLower) {
        return value != null && KurdishText.normalize(value).equals(needleLower);
    }

    static boolean containsLower(String value, String needleLower) {
        return value != null && KurdishText.normalize(value).contains(needleLower);
    }

    static boolean matchList(List<String> field, Set<String> wantedLowercase, boolean matchAll) {
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

    static Set<String> normalize(List<String> values) {
        if (values == null || values.isEmpty()) return Set.of();
        Set<String> out = new HashSet<>(values.size() * 2);
        for (String v : values) {
            if (v == null) continue;
            String n = v.trim().toLowerCase(Locale.ROOT);
            if (!n.isEmpty()) out.add(n);
        }
        return out;
    }

    static String lower(String s) {
        if (s == null) return null;
        String t = KurdishText.normalize(s);
        return t.isEmpty() ? null : t;
    }

    // ─── Sort ─────────────────────────────────────────────────────────────────────

    private static Comparator<TextResponseDTO> comparatorFor(String sortBy) {
        if (sortBy == null || sortBy.isBlank()) return null;
        return switch (sortBy.toLowerCase(Locale.ROOT)) {
            case "textcode", "code" -> Comparator.comparing(
                    TextResponseDTO::getTextCode,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "originaltitle", "title", "name", "alpha", "alphabet", "alphabetical" -> Comparator.comparing(
                    TextResponseDTO::getOriginalTitle,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "author" -> Comparator.comparing(
                    TextResponseDTO::getAuthor,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "language", "lang" -> Comparator.comparing(
                    TextResponseDTO::getLanguage,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "createdat", "created", "added", "dateadded", "date_added" -> Comparator.comparing(
                    TextResponseDTO::getCreatedAt,
                    Comparator.nullsLast(Instant::compareTo));
            case "updatedat", "updated", "modified", "datemodified", "date_modified" -> Comparator.comparing(
                    TextResponseDTO::getUpdatedAt,
                    Comparator.nullsLast(Instant::compareTo));
            case "datecreated", "date_created" -> Comparator.comparing(
                    TextResponseDTO::getDateCreated,
                    Comparator.nullsLast(Instant::compareTo));
            case "printdate", "print_date" -> Comparator.comparing(
                    TextResponseDTO::getPrintDate,
                    Comparator.nullsLast(Instant::compareTo));
            case "datemodifiedfield", "datemod" -> Comparator.comparing(
                    TextResponseDTO::getDateModified,
                    Comparator.nullsLast(Instant::compareTo));
            case "datepublished", "date_published", "published" -> Comparator.comparing(
                    TextResponseDTO::getDatePublished,
                    Comparator.nullsLast(Instant::compareTo));
            case "datecopyrighted", "copyrighted" -> Comparator.comparing(
                    TextResponseDTO::getDateCopyrighted,
                    Comparator.nullsLast(Instant::compareTo));
            case "versionnumber", "version" -> Comparator.comparing(
                    TextResponseDTO::getVersionNumber,
                    Comparator.nullsLast(Integer::compareTo));
            case "copynumber", "copy" -> Comparator.comparing(
                    TextResponseDTO::getCopyNumber,
                    Comparator.nullsLast(Integer::compareTo));
            case "pagecount", "pages", "page_count" -> Comparator.comparing(
                    TextResponseDTO::getPageCount,
                    Comparator.nullsLast(Integer::compareTo));
            default -> null;
        };
    }
}
