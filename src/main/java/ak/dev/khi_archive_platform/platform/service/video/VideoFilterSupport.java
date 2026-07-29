package ak.dev.khi_archive_platform.platform.service.video;

import ak.dev.khi_archive_platform.platform.dto.video.VideoFilterParams;
import ak.dev.khi_archive_platform.platform.dto.video.VideoResponseDTO;
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
 * In-memory filter + sort over the read-cache list of active videos.
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
final class VideoFilterSupport {

    private VideoFilterSupport() {}

    static List<VideoResponseDTO> applyFiltersAndSort(
            List<VideoResponseDTO> source,
            VideoFilterParams params) {

        if (source == null || source.isEmpty()) {
            return List.of();
        }
        if (params == null || params.isEmpty()) {
            return source;
        }

        // ── Pre-normalize ──────────────────────────────────────────────────────
        String videoVersion = lower(params.getVideoVersion());
        String videoStatus = lower(params.getVideoStatus());
        String audience = lower(params.getAudience());
        String extension = lower(params.getExtension());
        String orientation = lower(params.getOrientation());
        String dimension = lower(params.getDimension());
        String resolution = lower(params.getResolution());
        String duration = lower(params.getDuration());
        String bitDepth = lower(params.getBitDepth());
        String frameRate = lower(params.getFrameRate());
        String overallBitRate = lower(params.getOverallBitRate());
        String videoCodec = lower(params.getVideoCodec());
        String audioCodec = lower(params.getAudioCodec());
        String audioChannels = lower(params.getAudioChannels());
        String language = lower(params.getLanguage());
        String dialect = lower(params.getDialect());
        String subtitle = lower(params.getSubtitle());
        String accrualMethod = lower(params.getAccrualMethod());
        String lccClassification = lower(params.getLccClassification());
        String availability = lower(params.getAvailability());
        String licenseType = lower(params.getLicenseType());

        String event = lower(params.getEvent());
        String location = lower(params.getLocation());
        String description = lower(params.getDescription());
        String personShownInVideo = lower(params.getPersonShownInVideo());
        String creatorArtistDirector = lower(params.getCreatorArtistDirector());
        String producer = lower(params.getProducer());
        String contributor = lower(params.getContributor());
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
        Set<String> wantedColors = normalize(params.getColorOfVideo());
        boolean colorAll = "all".equalsIgnoreCase(params.getColorMatch());
        Set<String> wantedUsages = normalize(params.getWhereThisVideoUsed());
        boolean usageAll = "all".equalsIgnoreCase(params.getUsageMatch());
        Set<String> wantedTags = normalize(params.getTags());
        boolean tagAll = "all".equalsIgnoreCase(params.getTagMatch());
        Set<String> wantedKeywords = normalize(params.getKeywords());
        boolean keywordAll = "all".equalsIgnoreCase(params.getKeywordMatch());

        // ── Linear scan ─────────────────────────────────────────────────────────
        List<VideoResponseDTO> filtered = new ArrayList<>(Math.min(source.size(), 256));
        for (VideoResponseDTO v : source) {

            if (params.getPhysicalAvailability() != null
                    && !params.getPhysicalAvailability().equals(v.getPhysicalAvailability())) continue;

            if (!withinIntRange(v.getVersionNumber(), params.getVersionNumberMin(), params.getVersionNumberMax())) continue;
            if (!withinIntRange(v.getCopyNumber(), params.getCopyNumberMin(), params.getCopyNumberMax())) continue;

            if (!withinInstantRange(v.getDateCreated(), params.getDateCreatedFrom(), params.getDateCreatedTo())) continue;
            if (!withinInstantRange(v.getDateModified(), params.getDateModifiedFrom(), params.getDateModifiedTo())) continue;
            if (!withinInstantRange(v.getDatePublished(), params.getDatePublishedFrom(), params.getDatePublishedTo())) continue;
            if (!withinInstantRange(v.getDateCopyrighted(), params.getDateCopyrightedFrom(), params.getDateCopyrightedTo())) continue;

            if (videoVersion != null && !equalsLower(v.getVideoVersion(), videoVersion)) continue;
            if (videoStatus != null && !equalsLower(v.getVideoStatus(), videoStatus)) continue;
            if (audience != null && !equalsLower(v.getAudience(), audience)) continue;
            if (extension != null && !equalsLower(v.getExtension(), extension)) continue;
            if (orientation != null && !equalsLower(v.getOrientation(), orientation)) continue;
            if (dimension != null && !equalsLower(v.getDimension(), dimension)) continue;
            if (resolution != null && !equalsLower(v.getResolution(), resolution)) continue;
            if (duration != null && !equalsLower(v.getDuration(), duration)) continue;
            if (bitDepth != null && !equalsLower(v.getBitDepth(), bitDepth)) continue;
            if (frameRate != null && !equalsLower(v.getFrameRate(), frameRate)) continue;
            if (overallBitRate != null && !equalsLower(v.getOverallBitRate(), overallBitRate)) continue;
            if (videoCodec != null && !equalsLower(v.getVideoCodec(), videoCodec)) continue;
            if (audioCodec != null && !equalsLower(v.getAudioCodec(), audioCodec)) continue;
            if (audioChannels != null && !equalsLower(v.getAudioChannels(), audioChannels)) continue;
            if (language != null && !equalsLower(v.getLanguage(), language)) continue;
            if (dialect != null && !equalsLower(v.getDialect(), dialect)) continue;
            if (subtitle != null && !equalsLower(v.getSubtitle(), subtitle)) continue;
            if (accrualMethod != null && !equalsLower(v.getAccrualMethod(), accrualMethod)) continue;
            if (lccClassification != null && !equalsLower(v.getLccClassification(), lccClassification)) continue;
            if (availability != null && !equalsLower(v.getAvailability(), availability)) continue;
            if (licenseType != null && !equalsLower(v.getLicenseType(), licenseType)) continue;

            if (event != null && !containsLower(v.getEvent(), event)) continue;
            if (location != null && !containsLower(v.getLocation(), location)) continue;
            if (description != null && !containsLower(v.getDescription(), description)) continue;
            if (personShownInVideo != null && !containsLower(v.getPersonShownInVideo(), personShownInVideo)) continue;
            if (creatorArtistDirector != null && !containsLower(v.getCreatorArtistDirector(), creatorArtistDirector)) continue;
            if (producer != null && !containsLower(v.getProducer(), producer)) continue;
            if (contributor != null && !containsLower(v.getContributor(), contributor)) continue;
            if (provenance != null && !containsLower(v.getProvenance(), provenance)) continue;
            if (archiveCataloging != null && !containsLower(v.getArchiveCataloging(), archiveCataloging)) continue;
            if (physicalLabel != null && !containsLower(v.getPhysicalLabel(), physicalLabel)) continue;
            if (locationInArchiveRoom != null && !containsLower(v.getLocationInArchiveRoom(), locationInArchiveRoom)) continue;
            if (note != null && !containsLower(v.getNote(), note)) continue;
            if (copyright != null && !containsLower(v.getCopyright(), copyright)) continue;
            if (rightOwner != null && !containsLower(v.getRightOwner(), rightOwner)) continue;
            if (usageRights != null && !containsLower(v.getUsageRights(), usageRights)) continue;
            if (owner != null && !containsLower(v.getOwner(), owner)) continue;
            if (publisher != null && !containsLower(v.getPublisher(), publisher)) continue;

            if (!wantedSubjects.isEmpty()
                    && !matchList(v.getSubject(), wantedSubjects, subjectAll)) continue;
            if (!wantedGenres.isEmpty()
                    && !matchList(v.getGenre(), wantedGenres, genreAll)) continue;
            if (!wantedColors.isEmpty()
                    && !matchList(v.getColorOfVideo(), wantedColors, colorAll)) continue;
            if (!wantedUsages.isEmpty()
                    && !matchList(v.getWhereThisVideoUsed(), wantedUsages, usageAll)) continue;
            if (!wantedTags.isEmpty()
                    && !matchList(v.getTags(), wantedTags, tagAll)) continue;
            if (!wantedKeywords.isEmpty()
                    && !matchList(v.getKeywords(), wantedKeywords, keywordAll)) continue;

            if (!withinInstantRange(v.getCreatedAt(), params.getCreatedFrom(), params.getCreatedTo())) continue;
            if (!withinInstantRange(v.getUpdatedAt(), params.getUpdatedFrom(), params.getUpdatedTo())) continue;

            filtered.add(v);
        }

        Comparator<VideoResponseDTO> comparator = comparatorFor(params.getSortBy());
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

    private static Comparator<VideoResponseDTO> comparatorFor(String sortBy) {
        if (sortBy == null || sortBy.isBlank()) return null;
        return switch (sortBy.toLowerCase(Locale.ROOT)) {
            case "videocode", "code" -> Comparator.comparing(
                    VideoResponseDTO::getVideoCode,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "originaltitle", "title", "name", "alpha", "alphabet", "alphabetical" -> Comparator.comparing(
                    VideoResponseDTO::getOriginalTitle,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "language", "lang" -> Comparator.comparing(
                    VideoResponseDTO::getLanguage,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "createdat", "created", "added", "dateadded", "date_added" -> Comparator.comparing(
                    VideoResponseDTO::getCreatedAt,
                    Comparator.nullsLast(Instant::compareTo));
            case "updatedat", "updated", "modified", "datemodified", "date_modified" -> Comparator.comparing(
                    VideoResponseDTO::getUpdatedAt,
                    Comparator.nullsLast(Instant::compareTo));
            case "datecreated", "date_created" -> Comparator.comparing(
                    VideoResponseDTO::getDateCreated,
                    Comparator.nullsLast(Instant::compareTo));
            case "datemodifiedfield", "datemod" -> Comparator.comparing(
                    VideoResponseDTO::getDateModified,
                    Comparator.nullsLast(Instant::compareTo));
            case "datepublished", "date_published", "published" -> Comparator.comparing(
                    VideoResponseDTO::getDatePublished,
                    Comparator.nullsLast(Instant::compareTo));
            case "datecopyrighted", "copyrighted" -> Comparator.comparing(
                    VideoResponseDTO::getDateCopyrighted,
                    Comparator.nullsLast(Instant::compareTo));
            case "versionnumber", "version" -> Comparator.comparing(
                    VideoResponseDTO::getVersionNumber,
                    Comparator.nullsLast(Integer::compareTo));
            case "copynumber", "copy" -> Comparator.comparing(
                    VideoResponseDTO::getCopyNumber,
                    Comparator.nullsLast(Integer::compareTo));
            default -> null;
        };
    }
}
