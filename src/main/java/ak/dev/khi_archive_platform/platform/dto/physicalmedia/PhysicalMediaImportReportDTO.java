package ak.dev.khi_archive_platform.platform.dto.physicalmedia;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Returned by {@code POST /api/physical-media/import}. Summarises what
 * happened to every data row of the uploaded {@code .xlsx} so the user
 * can tell at a glance how many rows landed.
 *
 * <p>The importer is intentionally <b>maximally lenient</b>: every row
 * with any data is saved, even when fields are missing or contain
 * garbage. There is no dedupe — every sheet row becomes a new
 * {@code physical_media} record with its own {@code pmCode}. Two
 * artefacts that happen to share {@code (physicalMediaType, physicalLabel)}
 * remain two artefacts.
 *
 * <p>{@code errors[]} therefore plays two roles:
 * <ul>
 *   <li>{@code skipped > 0}: the row could not be persisted at all.</li>
 *   <li>{@code skipped == 0} but {@code errors[]} non-empty: the row
 *       was saved with stripped fields after the original DTO tripped
 *       on persistence — the entry tells staff which artefacts to
 *       review later.</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PhysicalMediaImportReportDTO {

    /** Sheet name parsed from the workbook. */
    private String sheetName;
    /** Excel column → entity field map detected from the header row. */
    private List<String> matchedHeaders;
    /** Headers in the sheet we couldn't map (logged so the user can rename or extend the schema). */
    private List<String> unknownHeaders;

    private int totalDataRows;
    /** Every successfully-persisted row counts here, including the
     *  stripped-fallback re-tries — there is no "updated" bucket. */
    private int inserted;
    private int skipped;

    /** Per-row error reports for skipped rows. Order matches the sheet. */
    private List<RowError> errors;

    private Instant finishedAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RowError {
        private int rowNumber;
        private String message;
    }
}
