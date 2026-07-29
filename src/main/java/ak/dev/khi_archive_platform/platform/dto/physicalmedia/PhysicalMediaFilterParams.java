package ak.dev.khi_archive_platform.platform.dto.physicalmedia;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Filter + sort parameters for the active physical-media listing
 * ({@code GET /api/physical-media}). Bound straight from the query string via
 * {@code @ModelAttribute}, exactly like {@code AudioFilterParams} /
 * {@code ImageFilterParams} — the two entities that pioneered this style — so
 * the admin table gets the same filter contract every other entity already has.
 *
 * <p>Operator taxonomy (identical to the media entities):
 * <ul>
 *   <li><b>Sort</b>: {@code sortBy} + {@code sortDirection} (asc|desc, default asc).</li>
 *   <li><b>Categorical equals</b> (case-insensitive exact): {@code physicalMediaType},
 *       {@code mediaCategory}, {@code physicalSize}, {@code extension},
 *       {@code formatCodec}, {@code source} (MANUAL|IMPORT).</li>
 *   <li><b>Enum / boolean</b>: {@code digitization} (NOT_DIGITIZED|DIGITIZED|DUPLICATED,
 *       case-insensitive) or {@code digitizationCode} (0|1|2); {@code needToClear}
 *       (true|false) or {@code needToClearCode} (0|1).</li>
 *   <li><b>Long-text contains</b> (case-insensitive substring): {@code pmCode},
 *       {@code title}, {@code physicalLabel}, {@code content}, {@code archiveDepNote},
 *       {@code owner}, {@code tags}, {@code trackName}, {@code captureDepNote},
 *       {@code sizeGB}, {@code playbackModel}, {@code captureInterface},
 *       {@code signalInterface}, {@code ingestSoftware}, {@code bitOrColorDepth},
 *       {@code sampleOrFrameRate}, {@code channelsOrResolution},
 *       {@code createdBy}, {@code updatedBy}.</li>
 *   <li><b>Numeric ranges</b> (inclusive): {@code yearMin/Max},
 *       {@code durationMinutesMin/Max} (over {@code durationMin}),
 *       {@code trackNumbersMin/Max}, {@code inventoryNumberMin/Max},
 *       {@code rowNumberMin/Max}.</li>
 *   <li><b>Date ranges</b> (inclusive): {@code digitizeDateFrom/To} (ISO date),
 *       {@code createdFrom/To} and {@code updatedFrom/To} (ISO instants).</li>
 * </ul>
 *
 * <p>Note {@code tags} is a single free-text column on this entity (not a list),
 * so it is a contains-filter rather than the collection any/all filter the
 * media entities use for their tag arrays.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PhysicalMediaFilterParams {

    private String sortBy;
    private String sortDirection;

    // ─── Categorical equals (case-insensitive exact) ─────────────────────────────
    private String physicalMediaType;
    private String mediaCategory;
    private String physicalSize;
    private String extension;
    private String formatCodec;
    private String source;

    // ─── Enum / boolean ──────────────────────────────────────────────────────────
    /** Enum name, case-insensitive: NOT_DIGITIZED | DIGITIZED | DUPLICATED. */
    private String digitization;
    /** Numeric encoding of the same field: 0 | 1 | 2. */
    private Integer digitizationCode;
    private Boolean needToClear;
    /** Numeric encoding of needToClear: 0 | 1. */
    private Integer needToClearCode;

    // ─── Long-text contains (case-insensitive substring) ──────────────────────────
    private String pmCode;
    private String title;
    private String physicalLabel;
    private String content;
    private String archiveDepNote;
    private String owner;
    private String tags;
    private String trackName;
    private String captureDepNote;
    private String sizeGB;
    private String playbackModel;
    private String captureInterface;
    private String signalInterface;
    private String ingestSoftware;
    private String bitOrColorDepth;
    private String sampleOrFrameRate;
    private String channelsOrResolution;
    private String createdBy;
    private String updatedBy;

    // ─── Numeric ranges (inclusive) ───────────────────────────────────────────────
    private Integer yearMin;
    private Integer yearMax;
    private Integer durationMinutesMin;
    private Integer durationMinutesMax;
    private Integer trackNumbersMin;
    private Integer trackNumbersMax;
    private Integer inventoryNumberMin;
    private Integer inventoryNumberMax;
    private Integer rowNumberMin;
    private Integer rowNumberMax;

    // ─── Date ranges (inclusive) ──────────────────────────────────────────────────
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) private LocalDate digitizeDateFrom;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) private LocalDate digitizeDateTo;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) private Instant createdFrom;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) private Instant createdTo;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) private Instant updatedFrom;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) private Instant updatedTo;

    /**
     * True only when no filter/sort param is set at all — the fully unfiltered,
     * unsorted request that takes the fast DB-paged path with the default order.
     */
    public boolean isEmpty() {
        return blank(sortBy) && blank(sortDirection) && !hasActiveFilters();
    }

    /**
     * True when at least one <em>non-sort</em> filter is set. A sort-only
     * request (just {@code sortBy}/{@code sortDirection}) returns {@code false}
     * here, which lets the service keep the fast DB-paged path and push the
     * ordering into the database instead of loading the whole active set to
     * sort it in memory.
     */
    public boolean hasActiveFilters() {
        return !blank(physicalMediaType) || !blank(mediaCategory) || !blank(physicalSize)
                || !blank(extension) || !blank(formatCodec) || !blank(source)
                || !blank(digitization) || digitizationCode != null
                || needToClear != null || needToClearCode != null
                || !blank(pmCode) || !blank(title) || !blank(physicalLabel)
                || !blank(content) || !blank(archiveDepNote) || !blank(owner)
                || !blank(tags) || !blank(trackName) || !blank(captureDepNote)
                || !blank(sizeGB) || !blank(playbackModel) || !blank(captureInterface)
                || !blank(signalInterface) || !blank(ingestSoftware) || !blank(bitOrColorDepth)
                || !blank(sampleOrFrameRate) || !blank(channelsOrResolution)
                || !blank(createdBy) || !blank(updatedBy)
                || yearMin != null || yearMax != null
                || durationMinutesMin != null || durationMinutesMax != null
                || trackNumbersMin != null || trackNumbersMax != null
                || inventoryNumberMin != null || inventoryNumberMax != null
                || rowNumberMin != null || rowNumberMax != null
                || digitizeDateFrom != null || digitizeDateTo != null
                || createdFrom != null || createdTo != null
                || updatedFrom != null || updatedTo != null;
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
}
