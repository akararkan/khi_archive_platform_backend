package ak.dev.khi_archive_platform.platform.service.physicalmedia;

import ak.dev.khi_archive_platform.platform.dto.physicalmedia.PhysicalMediaFilterParams;
import ak.dev.khi_archive_platform.platform.dto.physicalmedia.PhysicalMediaResponseDTO;
import ak.dev.khi_archive_platform.platform.service.common.ArchiveTime;
import ak.dev.khi_archive_platform.platform.service.common.KurdishText;
import ak.dev.khi_archive_platform.platform.service.common.SortSupport;
import org.springframework.data.domain.Sort;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * In-memory filter + sort over a list of active {@link PhysicalMediaResponseDTO}.
 *
 * <p>Same engine shape as {@code AudioFilterSupport}: a single linear pass with
 * cheap-first short-circuiting (boolean/enum equality → numeric range → date
 * range → categorical equals → substring contains), then an optional
 * comparator-based sort chosen from a small synonym whitelist. With no params it
 * collapses to identity and returns the source list untouched.
 */
final class PhysicalMediaFilterSupport {

    private PhysicalMediaFilterSupport() {}

    static List<PhysicalMediaResponseDTO> applyFiltersAndSort(
            List<PhysicalMediaResponseDTO> source,
            PhysicalMediaFilterParams params) {

        if (source == null || source.isEmpty()) {
            return List.of();
        }
        if (params == null || params.isEmpty()) {
            return source;
        }

        // ── Pre-normalize needles once ─────────────────────────────────────────
        String q = lower(params.getQ());
        String physicalMediaType = lower(params.getPhysicalMediaType());
        String mediaCategory = lower(params.getMediaCategory());
        String physicalSize = lower(params.getPhysicalSize());
        String extension = lower(params.getExtension());
        String formatCodec = lower(params.getFormatCodec());
        String source_ = lower(params.getSource());
        String digitization = lower(params.getDigitization());

        String pmCode = lower(params.getPmCode());
        String title = lower(params.getTitle());
        String physicalLabel = lower(params.getPhysicalLabel());
        String content = lower(params.getContent());
        String archiveDepNote = lower(params.getArchiveDepNote());
        String owner = lower(params.getOwner());
        String tags = lower(params.getTags());
        String trackName = lower(params.getTrackName());
        String captureDepNote = lower(params.getCaptureDepNote());
        String sizeGB = lower(params.getSizeGB());
        String playbackModel = lower(params.getPlaybackModel());
        String captureInterface = lower(params.getCaptureInterface());
        String signalInterface = lower(params.getSignalInterface());
        String ingestSoftware = lower(params.getIngestSoftware());
        String bitOrColorDepth = lower(params.getBitOrColorDepth());
        String sampleOrFrameRate = lower(params.getSampleOrFrameRate());
        String channelsOrResolution = lower(params.getChannelsOrResolution());
        String createdBy = lower(params.getCreatedBy());
        String updatedBy = lower(params.getUpdatedBy());
        String removedBy = lower(params.getRemovedBy());

        // ── Linear scan ────────────────────────────────────────────────────────
        List<PhysicalMediaResponseDTO> filtered = new ArrayList<>(Math.min(source.size(), 256));
        for (PhysicalMediaResponseDTO p : source) {

            // Cheapest: enum / boolean equality
            if (digitization != null
                    && !(p.getDigitization() != null
                        && p.getDigitization().name().toLowerCase(Locale.ROOT).equals(digitization))) continue;
            if (params.getDigitizationCode() != null
                    && !params.getDigitizationCode().equals(p.getDigitizationCode())) continue;
            if (params.getNeedToClear() != null
                    && !params.getNeedToClear().equals(p.getNeedToClear())) continue;
            if (params.getNeedToClearCode() != null
                    && !params.getNeedToClearCode().equals(p.getNeedToClearCode())) continue;

            // Numeric ranges
            if (!withinIntRange(p.getYear(), params.getYearMin(), params.getYearMax())) continue;
            if (!withinIntRange(p.getDurationMin(), params.getDurationMinutesMin(), params.getDurationMinutesMax())) continue;
            if (!withinIntRange(p.getTrackNumbers(), params.getTrackNumbersMin(), params.getTrackNumbersMax())) continue;
            if (!withinIntRange(p.getInventoryNumber(), params.getInventoryNumberMin(), params.getInventoryNumberMax())) continue;
            if (!withinIntRange(p.getRowNumber(), params.getRowNumberMin(), params.getRowNumberMax())) continue;

            // Date ranges — digitizeDate is a date column (compared as-is); the
            // audit ranges are YYYY-MM-DD resolved to instants in the archive zone.
            if (!withinDateRange(p.getDigitizeDate(), params.getDigitizeDateFrom(), params.getDigitizeDateTo())) continue;
            if (!withinInstantRange(p.getCreatedAt(),
                    ArchiveTime.startOfDay(params.getCreatedFrom()),
                    ArchiveTime.endOfDay(params.getCreatedTo()))) continue;
            if (!withinInstantRange(p.getUpdatedAt(),
                    ArchiveTime.startOfDay(params.getUpdatedFrom()),
                    ArchiveTime.endOfDay(params.getUpdatedTo()))) continue;
            if (!withinInstantRange(p.getRemovedAt(),
                    ArchiveTime.startOfDay(params.getRemovedFrom()),
                    ArchiveTime.endOfDay(params.getRemovedTo()))) continue;

            // Categorical equals (case-insensitive)
            if (physicalMediaType != null && !equalsLower(p.getPhysicalMediaType(), physicalMediaType)) continue;
            if (mediaCategory != null && !equalsLower(p.getMediaCategory(), mediaCategory)) continue;
            if (physicalSize != null && !equalsLower(p.getPhysicalSize(), physicalSize)) continue;
            if (extension != null && !equalsLower(p.getExtension(), extension)) continue;
            if (formatCodec != null && !equalsLower(p.getFormatCodec(), formatCodec)) continue;
            if (source_ != null && !equalsLower(p.getSource(), source_)) continue;

            // Long-text contains (more expensive; runs after equality filters most rows out)
            if (pmCode != null && !containsLower(p.getPmCode(), pmCode)) continue;
            if (title != null && !containsLower(p.getTitle(), title)) continue;
            if (physicalLabel != null && !containsLower(p.getPhysicalLabel(), physicalLabel)) continue;
            if (content != null && !containsLower(p.getContent(), content)) continue;
            if (archiveDepNote != null && !containsLower(p.getArchiveDepNote(), archiveDepNote)) continue;
            if (owner != null && !containsLower(p.getOwner(), owner)) continue;
            if (tags != null && !containsLower(p.getTags(), tags)) continue;
            if (trackName != null && !containsLower(p.getTrackName(), trackName)) continue;
            if (captureDepNote != null && !containsLower(p.getCaptureDepNote(), captureDepNote)) continue;
            if (sizeGB != null && !containsLower(p.getSizeGB(), sizeGB)) continue;
            if (playbackModel != null && !containsLower(p.getPlaybackModel(), playbackModel)) continue;
            if (captureInterface != null && !containsLower(p.getCaptureInterface(), captureInterface)) continue;
            if (signalInterface != null && !containsLower(p.getSignalInterface(), signalInterface)) continue;
            if (ingestSoftware != null && !containsLower(p.getIngestSoftware(), ingestSoftware)) continue;
            if (bitOrColorDepth != null && !containsLower(p.getBitOrColorDepth(), bitOrColorDepth)) continue;
            if (sampleOrFrameRate != null && !containsLower(p.getSampleOrFrameRate(), sampleOrFrameRate)) continue;
            if (channelsOrResolution != null && !containsLower(p.getChannelsOrResolution(), channelsOrResolution)) continue;
            if (createdBy != null && !containsLower(p.getCreatedBy(), createdBy)) continue;
            if (updatedBy != null && !containsLower(p.getUpdatedBy(), updatedBy)) continue;
            if (removedBy != null && !containsLower(p.getRemovedBy(), removedBy)) continue;

            // Free-text q — broad OR across searchable fields; last.
            if (q != null && !matchesQuery(p, q)) continue;

            filtered.add(p);
        }

        // Always finish on a deterministic total order (chosen key, then id ASC
        // as the tiebreaker) so paging is stable across requests — mirrors the
        // ", id ASC" that SortSupport appends on the DB fast path.
        Comparator<PhysicalMediaResponseDTO> comparator = comparatorFor(params.getSortBy());
        if (comparator != null) {
            if ("desc".equalsIgnoreCase(params.getSortDirection())) {
                comparator = comparator.reversed();
            }
            comparator = comparator.thenComparing(
                    PhysicalMediaResponseDTO::getId, Comparator.nullsLast(Long::compareTo));
        } else {
            comparator = Comparator.comparing(
                    PhysicalMediaResponseDTO::getId, Comparator.nullsLast(Long::compareTo));
        }
        filtered.sort(comparator);

        return filtered;
    }

    // ─── Predicates ───────────────────────────────────────────────────────────────

    /** Case-insensitive substring across the row's searchable fields — powers
     *  the {@code q} free-text filter (mirrors the {@code /search} field set). */
    private static boolean matchesQuery(PhysicalMediaResponseDTO p, String qLower) {
        return containsLower(p.getPmCode(), qLower)
                || containsLower(p.getPhysicalLabel(), qLower)
                || containsLower(p.getPhysicalMediaType(), qLower)
                || containsLower(p.getMediaCategory(), qLower)
                || containsLower(p.getTitle(), qLower)
                || containsLower(p.getPhysicalSize(), qLower)
                || containsLower(p.getContent(), qLower)
                || containsLower(p.getOwner(), qLower)
                || containsLower(p.getTags(), qLower)
                || containsLower(p.getTrackName(), qLower);
    }

    static boolean withinInstantRange(Instant value, Instant from, Instant to) {
        if (from == null && to == null) return true;
        if (value == null) return false;
        if (from != null && value.isBefore(from)) return false;
        if (to != null && value.isAfter(to)) return false;
        return true;
    }

    static boolean withinDateRange(LocalDate value, LocalDate from, LocalDate to) {
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
        return value != null && KurdishText.normalize(value).equals(needleLower);
    }

    static boolean containsLower(String value, String needleLower) {
        return value != null && KurdishText.normalize(value).contains(needleLower);
    }

    static String lower(String s) {
        if (s == null) return null;
        String t = KurdishText.normalize(s);
        return t.isEmpty() ? null : t;
    }

    // ─── Sort ─────────────────────────────────────────────────────────────────────

    private static Comparator<PhysicalMediaResponseDTO> comparatorFor(String sortBy) {
        if (sortBy == null || sortBy.isBlank()) return null;
        return switch (sortBy.toLowerCase(Locale.ROOT)) {
            case "pmcode", "code" -> Comparator.comparing(
                    PhysicalMediaResponseDTO::getPmCode,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "inventorynumber", "number", "inventory" -> Comparator.comparing(
                    PhysicalMediaResponseDTO::getInventoryNumber,
                    Comparator.nullsLast(Integer::compareTo));
            case "rownumber", "row", "no" -> Comparator.comparing(
                    PhysicalMediaResponseDTO::getRowNumber,
                    Comparator.nullsLast(Integer::compareTo));
            case "physicalmediatype", "type", "mediatype" -> Comparator.comparing(
                    PhysicalMediaResponseDTO::getPhysicalMediaType,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "mediacategory", "category" -> Comparator.comparing(
                    PhysicalMediaResponseDTO::getMediaCategory,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "title", "name", "alpha", "alphabet", "alphabetical" -> Comparator.comparing(
                    PhysicalMediaResponseDTO::getTitle,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "physicallabel", "label" -> Comparator.comparing(
                    PhysicalMediaResponseDTO::getPhysicalLabel,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "owner" -> Comparator.comparing(
                    PhysicalMediaResponseDTO::getOwner,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "year" -> Comparator.comparing(
                    PhysicalMediaResponseDTO::getYear,
                    Comparator.nullsLast(Integer::compareTo));
            case "duration", "durationmin", "durationminutes" -> Comparator.comparing(
                    PhysicalMediaResponseDTO::getDurationMin,
                    Comparator.nullsLast(Integer::compareTo));
            case "tracknumbers", "tracks" -> Comparator.comparing(
                    PhysicalMediaResponseDTO::getTrackNumbers,
                    Comparator.nullsLast(Integer::compareTo));
            case "digitization", "digitizationcode" -> Comparator.comparing(
                    PhysicalMediaResponseDTO::getDigitizationCode,
                    Comparator.nullsLast(Integer::compareTo));
            case "digitizedate", "digitized" -> Comparator.comparing(
                    PhysicalMediaResponseDTO::getDigitizeDate,
                    Comparator.nullsLast(LocalDate::compareTo));
            case "createdat", "created", "added", "dateadded", "date_added" -> Comparator.comparing(
                    PhysicalMediaResponseDTO::getCreatedAt,
                    Comparator.nullsLast(Instant::compareTo));
            case "updatedat", "updated", "modified", "datemodified", "date_modified" -> Comparator.comparing(
                    PhysicalMediaResponseDTO::getUpdatedAt,
                    Comparator.nullsLast(Instant::compareTo));
            default -> null;
        };
    }

    // ─── DB-side sort (fast path) ───────────────────────────────────────────────

    /**
     * Translate {@code sortBy}/{@code sortDirection} into a Spring Data
     * {@link Sort} for the DB-paged fast path (sort-only requests — no
     * full-set in-memory load). Every key here maps to a real
     * {@code physical_media} column and mirrors {@link #comparatorFor(String)}
     * (case-insensitive for text via {@code LOWER()}; NULL placement left to
     * the DB native default, which matches {@code Comparator.nullsLast}).
     *
     * <p>Returns {@link Sort#unsorted()} for a blank/unknown key, and
     * deliberately for {@code digitization} — that key sorts by a derived
     * numeric code with no backing column, so it is handled in memory instead
     * (see {@link #requiresInMemorySort}).
     */
    static Sort resolveDbSort(String sortBy, String sortDirection) {
        if (sortBy == null || sortBy.isBlank()) return Sort.unsorted();
        return switch (sortBy.toLowerCase(Locale.ROOT)) {
            case "pmcode", "code" -> SortSupport.ci("pmCode", sortDirection);
            case "inventorynumber", "number", "inventory" -> SortSupport.plain("inventoryNumber", sortDirection);
            case "rownumber", "row", "no" -> SortSupport.plain("rowNumber", sortDirection);
            case "physicalmediatype", "type", "mediatype" -> SortSupport.ci("physicalMediaType", sortDirection);
            case "mediacategory", "category" -> SortSupport.ci("mediaCategory", sortDirection);
            case "title", "name", "alpha", "alphabet", "alphabetical" -> SortSupport.ci("title", sortDirection);
            case "physicallabel", "label" -> SortSupport.ci("physicalLabel", sortDirection);
            case "owner" -> SortSupport.ci("owner", sortDirection);
            case "year" -> SortSupport.plain("year", sortDirection);
            case "duration", "durationmin", "durationminutes" -> SortSupport.plain("durationMin", sortDirection);
            case "tracknumbers", "tracks" -> SortSupport.plain("trackNumbers", sortDirection);
            case "digitizedate", "digitized" -> SortSupport.plain("digitizeDate", sortDirection);
            case "createdat", "created", "added", "dateadded", "date_added" -> SortSupport.plain("createdAt", sortDirection);
            case "updatedat", "updated", "modified", "datemodified", "date_modified" -> SortSupport.plain("updatedAt", sortDirection);
            default -> Sort.unsorted(); // includes digitization / digitizationCode → in-memory only
        };
    }

    /**
     * True when {@code sortBy} names a key we can sort in memory but cannot
     * push to the DB — the request must take the in-memory path even with no
     * filters. For physical media this is only {@code digitization} /
     * {@code digitizationCode} (ordered by the derived 0/1/2 code).
     */
    static boolean requiresInMemorySort(String sortBy, String sortDirection) {
        return comparatorFor(sortBy) != null && resolveDbSort(sortBy, sortDirection).isUnsorted();
    }
}
