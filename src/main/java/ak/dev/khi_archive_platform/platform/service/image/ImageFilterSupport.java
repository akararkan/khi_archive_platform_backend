package ak.dev.khi_archive_platform.platform.service.image;

import ak.dev.khi_archive_platform.platform.dto.image.ImageFilterParams;
import ak.dev.khi_archive_platform.platform.dto.image.ImageResponseDTO;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * In-memory filter + sort over the read-cache list of active images.
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
final class ImageFilterSupport {

    private ImageFilterSupport() {}

    static List<ImageResponseDTO> applyFiltersAndSort(
            List<ImageResponseDTO> source,
            ImageFilterParams params) {

        if (source == null || source.isEmpty()) {
            return List.of();
        }
        if (params == null || params.isEmpty()) {
            return source;
        }

        // ── Pre-normalize ──────────────────────────────────────────────────────
        String form = lower(params.getForm());
        String imageStatus = lower(params.getImageStatus());
        String imageVersion = lower(params.getImageVersion());
        String audience = lower(params.getAudience());
        String extension = lower(params.getExtension());
        String orientation = lower(params.getOrientation());
        String dimension = lower(params.getDimension());
        String bitDepth = lower(params.getBitDepth());
        String dpi = lower(params.getDpi());
        String manufacturer = lower(params.getManufacturer());
        String model = lower(params.getModel());
        String lens = lower(params.getLens());
        String accrualMethod = lower(params.getAccrualMethod());
        String lccClassification = lower(params.getLccClassification());
        String availability = lower(params.getAvailability());
        String licenseType = lower(params.getLicenseType());

        String event = lower(params.getEvent());
        String location = lower(params.getLocation());
        String description = lower(params.getDescription());
        String personShownInImage = lower(params.getPersonShownInImage());
        String creatorArtistPhotographer = lower(params.getCreatorArtistPhotographer());
        String contributor = lower(params.getContributor());
        String provenance = lower(params.getProvenance());
        String photostory = lower(params.getPhotostory());
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
        Set<String> wantedColors = normalize(params.getColorOfImage());
        boolean colorAll = "all".equalsIgnoreCase(params.getColorMatch());
        Set<String> wantedUsages = normalize(params.getWhereThisImageUsed());
        boolean usageAll = "all".equalsIgnoreCase(params.getUsageMatch());
        Set<String> wantedTags = normalize(params.getTags());
        boolean tagAll = "all".equalsIgnoreCase(params.getTagMatch());
        Set<String> wantedKeywords = normalize(params.getKeywords());
        boolean keywordAll = "all".equalsIgnoreCase(params.getKeywordMatch());

        // ── Linear scan ─────────────────────────────────────────────────────────
        List<ImageResponseDTO> filtered = new ArrayList<>(Math.min(source.size(), 256));
        for (ImageResponseDTO i : source) {

            // Cheapest: boolean equality
            if (params.getPhysicalAvailability() != null
                    && !params.getPhysicalAvailability().equals(i.getPhysicalAvailability())) continue;

            // Numeric ranges
            if (!withinIntRange(i.getVersionNumber(), params.getVersionNumberMin(), params.getVersionNumberMax())) continue;
            if (!withinIntRange(i.getCopyNumber(), params.getCopyNumberMin(), params.getCopyNumberMax())) continue;

            // Date ranges (entity-side dates first; audit fields last)
            if (!withinInstantRange(i.getDateCreated(), params.getDateCreatedFrom(), params.getDateCreatedTo())) continue;
            if (!withinInstantRange(i.getDateModified(), params.getDateModifiedFrom(), params.getDateModifiedTo())) continue;
            if (!withinInstantRange(i.getDatePublished(), params.getDatePublishedFrom(), params.getDatePublishedTo())) continue;
            if (!withinInstantRange(i.getDateCopyrighted(), params.getDateCopyrightedFrom(), params.getDateCopyrightedTo())) continue;

            // Categorical equality (case-insensitive equals on already-lowered needles)
            if (form != null && !equalsLower(i.getForm(), form)) continue;
            if (imageStatus != null && !equalsLower(i.getImageStatus(), imageStatus)) continue;
            if (imageVersion != null && !equalsLower(i.getImageVersion(), imageVersion)) continue;
            if (audience != null && !equalsLower(i.getAudience(), audience)) continue;
            if (extension != null && !equalsLower(i.getExtension(), extension)) continue;
            if (orientation != null && !equalsLower(i.getOrientation(), orientation)) continue;
            if (dimension != null && !equalsLower(i.getDimension(), dimension)) continue;
            if (bitDepth != null && !equalsLower(i.getBitDepth(), bitDepth)) continue;
            if (dpi != null && !equalsLower(i.getDpi(), dpi)) continue;
            if (manufacturer != null && !equalsLower(i.getManufacturer(), manufacturer)) continue;
            if (model != null && !equalsLower(i.getModel(), model)) continue;
            if (lens != null && !equalsLower(i.getLens(), lens)) continue;
            if (accrualMethod != null && !equalsLower(i.getAccrualMethod(), accrualMethod)) continue;
            if (lccClassification != null && !equalsLower(i.getLccClassification(), lccClassification)) continue;
            if (availability != null && !equalsLower(i.getAvailability(), availability)) continue;
            if (licenseType != null && !equalsLower(i.getLicenseType(), licenseType)) continue;

            // Long-text contains (more expensive than equals; runs after categoricals filter most rows out)
            if (event != null && !containsLower(i.getEvent(), event)) continue;
            if (location != null && !containsLower(i.getLocation(), location)) continue;
            if (description != null && !containsLower(i.getDescription(), description)) continue;
            if (personShownInImage != null && !containsLower(i.getPersonShownInImage(), personShownInImage)) continue;
            if (creatorArtistPhotographer != null && !containsLower(i.getCreatorArtistPhotographer(), creatorArtistPhotographer)) continue;
            if (contributor != null && !containsLower(i.getContributor(), contributor)) continue;
            if (provenance != null && !containsLower(i.getProvenance(), provenance)) continue;
            if (photostory != null && !containsLower(i.getPhotostory(), photostory)) continue;
            if (archiveCataloging != null && !containsLower(i.getArchiveCataloging(), archiveCataloging)) continue;
            if (physicalLabel != null && !containsLower(i.getPhysicalLabel(), physicalLabel)) continue;
            if (locationInArchiveRoom != null && !containsLower(i.getLocationInArchiveRoom(), locationInArchiveRoom)) continue;
            if (note != null && !containsLower(i.getNote(), note)) continue;
            if (copyright != null && !containsLower(i.getCopyright(), copyright)) continue;
            if (rightOwner != null && !containsLower(i.getRightOwner(), rightOwner)) continue;
            if (usageRights != null && !containsLower(i.getUsageRights(), usageRights)) continue;
            if (owner != null && !containsLower(i.getOwner(), owner)) continue;
            if (publisher != null && !containsLower(i.getPublisher(), publisher)) continue;

            // Collections — last (per-row work)
            if (!wantedSubjects.isEmpty()
                    && !matchList(i.getSubject(), wantedSubjects, subjectAll)) continue;
            if (!wantedGenres.isEmpty()
                    && !matchList(i.getGenre(), wantedGenres, genreAll)) continue;
            if (!wantedColors.isEmpty()
                    && !matchList(i.getColorOfImage(), wantedColors, colorAll)) continue;
            if (!wantedUsages.isEmpty()
                    && !matchList(i.getWhereThisImageUsed(), wantedUsages, usageAll)) continue;
            if (!wantedTags.isEmpty()
                    && !matchList(i.getTags(), wantedTags, tagAll)) continue;
            if (!wantedKeywords.isEmpty()
                    && !matchList(i.getKeywords(), wantedKeywords, keywordAll)) continue;

            // Audit-time ranges last
            if (!withinInstantRange(i.getCreatedAt(), params.getCreatedFrom(), params.getCreatedTo())) continue;
            if (!withinInstantRange(i.getUpdatedAt(), params.getUpdatedFrom(), params.getUpdatedTo())) continue;

            filtered.add(i);
        }

        Comparator<ImageResponseDTO> comparator = comparatorFor(params.getSortBy());
        if (comparator != null) {
            if ("desc".equalsIgnoreCase(params.getSortDirection())) {
                comparator = comparator.reversed();
            }
            filtered.sort(comparator);
        }

        return filtered;
    }

    // ─── Predicates ───────────────────────────────────────────────────────────────

    static boolean withinInstantRange(Instant value, Instant from, Instant to) {
        if (from == null && to == null) return true;
        if (value == null) return false;
        if (from != null && value.isBefore(from)) return false;
        if (to != null && value.isAfter(to)) return false;
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
        return value != null && value.toLowerCase(Locale.ROOT).equals(needleLower);
    }

    static boolean containsLower(String value, String needleLower) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needleLower);
    }

    /**
     * Match a list-of-strings field against a wanted set, case-insensitively.
     * "any" runs zero-allocation; "all" uses a tiny counter sized to the wanted count.
     */
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
        String t = s.trim();
        return t.isEmpty() ? null : t.toLowerCase(Locale.ROOT);
    }

    // ─── Sort ─────────────────────────────────────────────────────────────────────

    private static Comparator<ImageResponseDTO> comparatorFor(String sortBy) {
        if (sortBy == null || sortBy.isBlank()) return null;
        return switch (sortBy.toLowerCase(Locale.ROOT)) {
            case "imagecode", "code" -> Comparator.comparing(
                    ImageResponseDTO::getImageCode,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "originaltitle", "title", "name", "alpha", "alphabet", "alphabetical" -> Comparator.comparing(
                    ImageResponseDTO::getOriginalTitle,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "createdat", "created", "added", "dateadded", "date_added" -> Comparator.comparing(
                    ImageResponseDTO::getCreatedAt,
                    Comparator.nullsLast(Instant::compareTo));
            case "updatedat", "updated", "modified", "datemodified", "date_modified" -> Comparator.comparing(
                    ImageResponseDTO::getUpdatedAt,
                    Comparator.nullsLast(Instant::compareTo));
            case "datecreated", "date_created" -> Comparator.comparing(
                    ImageResponseDTO::getDateCreated,
                    Comparator.nullsLast(Instant::compareTo));
            case "datemodifiedfield", "datemod" -> Comparator.comparing(
                    ImageResponseDTO::getDateModified,
                    Comparator.nullsLast(Instant::compareTo));
            case "datepublished", "date_published", "published" -> Comparator.comparing(
                    ImageResponseDTO::getDatePublished,
                    Comparator.nullsLast(Instant::compareTo));
            case "datecopyrighted", "copyrighted" -> Comparator.comparing(
                    ImageResponseDTO::getDateCopyrighted,
                    Comparator.nullsLast(Instant::compareTo));
            case "versionnumber", "version" -> Comparator.comparing(
                    ImageResponseDTO::getVersionNumber,
                    Comparator.nullsLast(Integer::compareTo));
            case "copynumber", "copy" -> Comparator.comparing(
                    ImageResponseDTO::getCopyNumber,
                    Comparator.nullsLast(Integer::compareTo));
            default -> null;
        };
    }
}
