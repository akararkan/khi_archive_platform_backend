package ak.dev.khi_archive_platform.platform.service.audio;

import ak.dev.khi_archive_platform.platform.dto.audio.AudioFilterParams;
import ak.dev.khi_archive_platform.platform.dto.audio.AudioResponseDTO;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * In-memory filter + sort over the read-cache list of active audios.
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
final class AudioFilterSupport {

    private AudioFilterSupport() {}

    static List<AudioResponseDTO> applyFiltersAndSort(
            List<AudioResponseDTO> source,
            AudioFilterParams params) {

        if (source == null || source.isEmpty()) {
            return List.of();
        }
        if (params == null || params.isEmpty()) {
            return source;
        }

        // ── Pre-normalize ──────────────────────────────────────────────────────
        String form = lower(params.getForm());
        String typeOfBasta = lower(params.getTypeOfBasta());
        String typeOfMaqam = lower(params.getTypeOfMaqam());
        String language = lower(params.getLanguage());
        String dialect = lower(params.getDialect());
        String typeOfComposition = lower(params.getTypeOfComposition());
        String typeOfPerformance = lower(params.getTypeOfPerformance());
        String city = lower(params.getCity());
        String region = lower(params.getRegion());
        String audience = lower(params.getAudience());
        String audioChannel = lower(params.getAudioChannel());
        String fileExtension = lower(params.getFileExtension());
        String bitRate = lower(params.getBitRate());
        String bitDepth = lower(params.getBitDepth());
        String sampleRate = lower(params.getSampleRate());
        String lccClassification = lower(params.getLccClassification());
        String accrualMethod = lower(params.getAccrualMethod());
        String availability = lower(params.getAvailability());
        String licenseType = lower(params.getLicenseType());

        String speaker = lower(params.getSpeaker());
        String producer = lower(params.getProducer());
        String composer = lower(params.getComposer());
        String poet = lower(params.getPoet());
        String lyrics = lower(params.getLyrics());
        String recordingVenue = lower(params.getRecordingVenue());
        String locationArchive = lower(params.getLocationArchive());
        String degitizedBy = lower(params.getDegitizedBy());
        String degitizationEquipment = lower(params.getDegitizationEquipment());
        String provenance = lower(params.getProvenance());
        String copyright = lower(params.getCopyright());
        String rightOwner = lower(params.getRightOwner());
        String usageRights = lower(params.getUsageRights());
        String owner = lower(params.getOwner());
        String publisher = lower(params.getPublisher());

        Set<String> wantedGenres = normalize(params.getGenre());
        boolean genreAll = "all".equalsIgnoreCase(params.getGenreMatch());
        Set<String> wantedContributors = normalize(params.getContributors());
        boolean contributorAll = "all".equalsIgnoreCase(params.getContributorMatch());
        Set<String> wantedTags = normalize(params.getTags());
        boolean tagAll = "all".equalsIgnoreCase(params.getTagMatch());
        Set<String> wantedKeywords = normalize(params.getKeywords());
        boolean keywordAll = "all".equalsIgnoreCase(params.getKeywordMatch());

        // ── Linear scan ─────────────────────────────────────────────────────────
        List<AudioResponseDTO> filtered = new ArrayList<>(Math.min(source.size(), 256));
        for (AudioResponseDTO a : source) {

            // Cheapest: boolean equality
            if (params.getPhysicalAvailability() != null
                    && !params.getPhysicalAvailability().equals(a.getPhysicalAvailability())) continue;

            // Numeric ranges
            if (!withinIntRange(a.getAudioQualityOutOf10(), params.getAudioQualityMin(), params.getAudioQualityMax())) continue;
            if (!withinIntRange(a.getVersionNumber(), params.getVersionNumberMin(), params.getVersionNumberMax())) continue;
            if (!withinIntRange(a.getCopyNumber(), params.getCopyNumberMin(), params.getCopyNumberMax())) continue;

            // Date ranges (entity-side dates first; audit fields last)
            if (!withinInstantRange(a.getDateCreated(), params.getDateCreatedFrom(), params.getDateCreatedTo())) continue;
            if (!withinInstantRange(a.getDatePublished(), params.getDatePublishedFrom(), params.getDatePublishedTo())) continue;
            if (!withinInstantRange(a.getDateModified(), params.getDateModifiedFrom(), params.getDateModifiedTo())) continue;
            if (!withinInstantRange(a.getDateCopyrighted(), params.getDateCopyrightedFrom(), params.getDateCopyrightedTo())) continue;

            // Categorical equality (case-insensitive equals on already-lowered needles)
            if (form != null && !equalsLower(a.getForm(), form)) continue;
            if (typeOfBasta != null && !equalsLower(a.getTypeOfBasta(), typeOfBasta)) continue;
            if (typeOfMaqam != null && !equalsLower(a.getTypeOfMaqam(), typeOfMaqam)) continue;
            if (language != null && !equalsLower(a.getLanguage(), language)) continue;
            if (dialect != null && !equalsLower(a.getDialect(), dialect)) continue;
            if (typeOfComposition != null && !equalsLower(a.getTypeOfComposition(), typeOfComposition)) continue;
            if (typeOfPerformance != null && !equalsLower(a.getTypeOfPerformance(), typeOfPerformance)) continue;
            if (city != null && !equalsLower(a.getCity(), city)) continue;
            if (region != null && !equalsLower(a.getRegion(), region)) continue;
            if (audience != null && !equalsLower(a.getAudience(), audience)) continue;
            if (audioChannel != null && !equalsLower(a.getAudioChannel(), audioChannel)) continue;
            if (fileExtension != null && !equalsLower(a.getFileExtension(), fileExtension)) continue;
            if (bitRate != null && !equalsLower(a.getBitRate(), bitRate)) continue;
            if (bitDepth != null && !equalsLower(a.getBitDepth(), bitDepth)) continue;
            if (sampleRate != null && !equalsLower(a.getSampleRate(), sampleRate)) continue;
            if (lccClassification != null && !equalsLower(a.getLccClassification(), lccClassification)) continue;
            if (accrualMethod != null && !equalsLower(a.getAccrualMethod(), accrualMethod)) continue;
            if (availability != null && !equalsLower(a.getAvailability(), availability)) continue;
            if (licenseType != null && !equalsLower(a.getLicenseType(), licenseType)) continue;

            // Long-text contains (more expensive than equals; runs after categoricals filter most rows out)
            if (speaker != null && !containsLower(a.getSpeaker(), speaker)) continue;
            if (producer != null && !containsLower(a.getProducer(), producer)) continue;
            if (composer != null && !containsLower(a.getComposer(), composer)) continue;
            if (poet != null && !containsLower(a.getPoet(), poet)) continue;
            if (lyrics != null && !containsLower(a.getLyrics(), lyrics)) continue;
            if (recordingVenue != null && !containsLower(a.getRecordingVenue(), recordingVenue)) continue;
            if (locationArchive != null && !containsLower(a.getLocationArchive(), locationArchive)) continue;
            if (degitizedBy != null && !containsLower(a.getDegitizedBy(), degitizedBy)) continue;
            if (degitizationEquipment != null && !containsLower(a.getDegitizationEquipment(), degitizationEquipment)) continue;
            if (provenance != null && !containsLower(a.getProvenance(), provenance)) continue;
            if (copyright != null && !containsLower(a.getCopyright(), copyright)) continue;
            if (rightOwner != null && !containsLower(a.getRightOwner(), rightOwner)) continue;
            if (usageRights != null && !containsLower(a.getUsageRights(), usageRights)) continue;
            if (owner != null && !containsLower(a.getOwner(), owner)) continue;
            if (publisher != null && !containsLower(a.getPublisher(), publisher)) continue;

            // Collections — last (per-row work)
            if (!wantedGenres.isEmpty()
                    && !matchList(a.getGenre(), wantedGenres, genreAll)) continue;
            if (!wantedContributors.isEmpty()
                    && !matchList(a.getContributors(), wantedContributors, contributorAll)) continue;
            if (!wantedTags.isEmpty()
                    && !matchList(a.getTags(), wantedTags, tagAll)) continue;
            if (!wantedKeywords.isEmpty()
                    && !matchList(a.getKeywords(), wantedKeywords, keywordAll)) continue;

            // Audit-time ranges last
            if (!withinInstantRange(a.getCreatedAt(), params.getCreatedFrom(), params.getCreatedTo())) continue;
            if (!withinInstantRange(a.getUpdatedAt(), params.getUpdatedFrom(), params.getUpdatedTo())) continue;

            filtered.add(a);
        }

        Comparator<AudioResponseDTO> comparator = comparatorFor(params.getSortBy());
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

    private static Comparator<AudioResponseDTO> comparatorFor(String sortBy) {
        if (sortBy == null || sortBy.isBlank()) return null;
        return switch (sortBy.toLowerCase(Locale.ROOT)) {
            case "audiocode", "code" -> Comparator.comparing(
                    AudioResponseDTO::getAudioCode,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "origintitle", "title", "name", "alpha", "alphabet", "alphabetical" -> Comparator.comparing(
                    AudioResponseDTO::getOriginTitle,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "createdat", "created", "added", "dateadded", "date_added" -> Comparator.comparing(
                    AudioResponseDTO::getCreatedAt,
                    Comparator.nullsLast(Instant::compareTo));
            case "updatedat", "updated", "modified", "datemodified", "date_modified" -> Comparator.comparing(
                    AudioResponseDTO::getUpdatedAt,
                    Comparator.nullsLast(Instant::compareTo));
            case "datecreated", "date_created" -> Comparator.comparing(
                    AudioResponseDTO::getDateCreated,
                    Comparator.nullsLast(Instant::compareTo));
            case "datepublished", "date_published", "published" -> Comparator.comparing(
                    AudioResponseDTO::getDatePublished,
                    Comparator.nullsLast(Instant::compareTo));
            case "datemodifiedfield", "datemod" -> Comparator.comparing(
                    AudioResponseDTO::getDateModified,
                    Comparator.nullsLast(Instant::compareTo));
            case "datecopyrighted", "copyrighted" -> Comparator.comparing(
                    AudioResponseDTO::getDateCopyrighted,
                    Comparator.nullsLast(Instant::compareTo));
            case "audioquality", "audioqualityoutof10", "quality" -> Comparator.comparing(
                    AudioResponseDTO::getAudioQualityOutOf10,
                    Comparator.nullsLast(Integer::compareTo));
            case "versionnumber", "version" -> Comparator.comparing(
                    AudioResponseDTO::getVersionNumber,
                    Comparator.nullsLast(Integer::compareTo));
            case "copynumber", "copy" -> Comparator.comparing(
                    AudioResponseDTO::getCopyNumber,
                    Comparator.nullsLast(Integer::compareTo));
            default -> null;
        };
    }
}
