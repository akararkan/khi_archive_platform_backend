package ak.dev.khi_archive_platform.platform.dto.physicalmedia;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Returned by {@code POST /api/physical-media/import}. Summarises what
 * happened to every data row of the uploaded {@code .xlsx} so the user can
 * tell at a glance how many rows landed and which ones the importer
 * skipped (with the reason).
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
    private int inserted;
    private int updated;
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
