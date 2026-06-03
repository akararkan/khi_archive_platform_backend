package ak.dev.khi_archive_platform.platform.service.physicalmedia;

import ak.dev.khi_archive_platform.platform.dto.physicalmedia.PhysicalMediaCreateRequestDTO;
import ak.dev.khi_archive_platform.platform.dto.physicalmedia.PhysicalMediaImportReportDTO;
import ak.dev.khi_archive_platform.platform.enums.DigitizationStatus;
import ak.dev.khi_archive_platform.platform.exceptions.PhysicalMediaValidationException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Parses an {@code .xlsx} sheet (Apache POI) into
 * {@link PhysicalMediaCreateRequestDTO} rows and hands each to
 * {@link PhysicalMediaService#upsertFromImport} for persistence.
 *
 * <p><b>Header matching</b>: the importer reads row 1 of the chosen sheet
 * (default: first sheet) and matches each cell against the canonical Excel
 * header text — Kurdish + English — declared in {@link #HEADER_BINDINGS}.
 * Whitespace and the embedded zero-width characters that creep in via
 * copy-paste are normalised away. Unknown headers are reported back in the
 * import report so the user can rename the column or tell the team the
 * field doesn't exist yet.
 *
 * <p><b>Dedupe</b>: rows are upserted by {@code (physicalMediaType,
 * physicalLabel)}. Rows missing both are always inserted.
 *
 * <p>The whole import runs in one transaction so a single bad row that
 * trips an entity validation does not poison the partial set already
 * persisted; the service catches per-row exceptions, records them in the
 * report, and continues.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PhysicalMediaExcelImportService {

    /** Bindings between the canonical Excel column header (whitespace-
     *  normalised) and the field name on
     *  {@link PhysicalMediaCreateRequestDTO}. Multiple labels per field are
     *  supported so the same column can be matched whether the sheet was
     *  exported with English-only, Kurdish-only, or both. */
    private static final Map<String, String> HEADER_BINDINGS = new HashMap<>();
    static {
        bind("No.", "rowNumber");
        bind("Number", "inventoryNumber");
        bind("Physical Media Type", "physicalMediaType");
        bind("جۆری بابەت(Media Category)", "mediaCategory");
        bind("Media Category", "mediaCategory");
        bind("ناوی بابەت (Title)", "title");
        bind("Title", "title");
        bind("جۆر(Type)", "subType");
        bind("Type", "subType");
        bind("کۆد (Physical Label)", "physicalLabel");
        bind("Physical Label", "physicalLabel");
        bind("قەبارە (Size)", "size");
        bind("Size", "size");
        bind("ناوەڕۆک (Content)", "content");
        bind("Content", "content");
        bind("تێبینی بەشی ئارشیڤ Archive Dep Note", "archiveDepNote");
        bind("Archive Dep Note", "archiveDepNote");
        bind("دیجیتایز Digitization", "digitization");
        bind("Digitization", "digitization");
        bind("بەرهەمهێن(خاوەن) Owner", "owner");
        bind("Owner", "owner");
        bind("ساڵ Year", "year");
        bind("Year", "year");
        bind("درێژایی خولەک Duration Min", "durationMin");
        bind("Duration Min", "durationMin");
        bind("ژمارەی تراک Track Numbers", "trackNumbers");
        bind("Track Numbers", "trackNumbers");
        bind("ناوی تراکەکان Track Name", "trackName");
        bind("Track Name", "trackName");
        bind("Extension", "extension");
        bind("Bit Depth / Color Depth", "bitOrColorDepth");
        bind("Sample Rate kHz/Frame Rate fps", "sampleOrFrameRate");
        bind("Channels/Resolution", "channelsOrResolution");
        bind("Playback Model", "playbackModel");
        bind("Capture Interface", "captureInterface");
        bind("Signal Interface(Cable Type)", "signalInterface");
        bind("Signal Interface", "signalInterface");
        bind("Ingest Software", "ingestSoftware");
        bind("Format/Codec", "formatCodec");
        bind("ڕێکەوتی دیجیتایز/ گواستنەوە Digitize Date", "digitizeDate");
        bind("Digitize Date", "digitizeDate");
        bind("تاگ Tags", "tags");
        bind("Tags", "tags");
        bind("خاوێنکردن Need to Clear", "needToClear");
        bind("Need to Clear", "needToClear");
        bind("تێبینی بەشی تیجیتایز Capture Dep Note", "captureDepNote");
        bind("Capture Dep Note", "captureDepNote");
    }
    private static void bind(String header, String field) {
        HEADER_BINDINGS.put(normalize(header), field);
    }

    private final PhysicalMediaService physicalMediaService;
    private final DataFormatter formatter = new DataFormatter(Locale.US);

    /**
     * Parses the uploaded workbook (first sheet by default), upserts every
     * data row, and returns a structured report.
     *
     * @param file       the {@code .xlsx} upload
     * @param sheetName  optional explicit sheet name; {@code null} picks the
     *                   first sheet in the workbook
     */
    /**
     * Intentionally <b>not</b> {@code @Transactional}: each row's persistence
     * goes through {@link PhysicalMediaService#upsertFromImport} which opens
     * its own {@code REQUIRES_NEW} transaction. Wrapping the outer call in a
     * transaction here would re-introduce the rollback-only failure mode
     * since per-row exceptions inside the loop would mark the wrapping
     * transaction rollback-only despite our catch.
     */
    public PhysicalMediaImportReportDTO importExcel(MultipartFile file,
                                                    String sheetName,
                                                    Authentication auth,
                                                    HttpServletRequest request) {
        if (file == null || file.isEmpty()) {
            throw new PhysicalMediaValidationException("Excel file is required");
        }
        String original = file.getOriginalFilename();
        if (original != null && !original.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw new PhysicalMediaValidationException("Only .xlsx workbooks are supported");
        }

        String actor = auth == null ? "system" : auth.getName();
        List<PhysicalMediaImportReportDTO.RowError> errors = new ArrayList<>();
        Set<String> matchedHeaders = new HashSet<>();
        Set<String> unknownHeaders = new HashSet<>();
        int inserted = 0;
        int updated = 0;
        int skipped = 0;
        int totalDataRows = 0;
        String resolvedSheetName;

        try (InputStream in = file.getInputStream(); Workbook wb = new XSSFWorkbook(in)) {
            Sheet sheet = (sheetName == null || sheetName.isBlank())
                    ? wb.getSheetAt(0)
                    : findSheetTolerant(wb, sheetName);
            if (sheet == null) {
                throw new PhysicalMediaValidationException(
                        "Sheet not found: '" + sheetName + "'. Available sheets: "
                                + listSheetNames(wb));
            }
            resolvedSheetName = sheet.getSheetName();

            Row header = sheet.getRow(sheet.getFirstRowNum());
            if (header == null) {
                throw new PhysicalMediaValidationException("Header row is empty");
            }
            Map<Integer, String> colToField = resolveHeader(header, matchedHeaders, unknownHeaders);
            if (colToField.isEmpty()) {
                throw new PhysicalMediaValidationException(
                        "No recognisable columns found in header row");
            }

            int last = sheet.getLastRowNum();
            for (int r = header.getRowNum() + 1; r <= last; r++) {
                Row row = sheet.getRow(r);
                if (row == null || isRowEmpty(row, colToField.keySet())) continue;
                totalDataRows++;

                try {
                    PhysicalMediaCreateRequestDTO dto = rowToDto(row, colToField);
                    if (dto.getPhysicalMediaType() == null && dto.getTitle() == null
                            && dto.getPhysicalLabel() == null) {
                        skipped++;
                        errors.add(rowError(r + 1, "row has neither media type, title, nor physical label"));
                        continue;
                    }
                    var result = physicalMediaService.upsertFromImport(dto, actor);
                    if (result.inserted()) inserted++; else updated++;
                } catch (Exception ex) {
                    skipped++;
                    errors.add(rowError(r + 1, ex.getMessage()));
                    log.warn("Excel import row {} skipped: {}", r + 1, ex.getMessage());
                }
            }
        } catch (PhysicalMediaValidationException e) {
            throw e;
        } catch (IOException e) {
            throw new PhysicalMediaValidationException("Failed to read workbook: " + e.getMessage());
        }

        physicalMediaService.recordImport(inserted, updated, skipped, auth, request);

        return PhysicalMediaImportReportDTO.builder()
                .sheetName(resolvedSheetName)
                .matchedHeaders(new ArrayList<>(matchedHeaders))
                .unknownHeaders(new ArrayList<>(unknownHeaders))
                .totalDataRows(totalDataRows)
                .inserted(inserted)
                .updated(updated)
                .skipped(skipped)
                .errors(errors)
                .finishedAt(Instant.now())
                .build();
    }

    /**
     * Peeks at the workbook's sheet names without persisting anything.
     * Drives the frontend's "pick a sheet" dropdown so the user doesn't
     * have to guess the tab name. Cheap: opens the workbook, reads the
     * names, closes it — no row iteration.
     */
    public List<String> listSheets(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new PhysicalMediaValidationException("Excel file is required");
        }
        String original = file.getOriginalFilename();
        if (original != null && !original.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw new PhysicalMediaValidationException("Only .xlsx workbooks are supported");
        }
        try (InputStream in = file.getInputStream(); Workbook wb = new XSSFWorkbook(in)) {
            List<String> names = new ArrayList<>(wb.getNumberOfSheets());
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                names.add(wb.getSheetName(i));
            }
            return names;
        } catch (IOException e) {
            throw new PhysicalMediaValidationException("Failed to read workbook: " + e.getMessage());
        }
    }

    // ─── Header / row helpers ────────────────────────────────────────────────

    private Map<Integer, String> resolveHeader(Row header,
                                               Set<String> matched,
                                               Set<String> unknown) {
        Map<Integer, String> out = new HashMap<>();
        short last = header.getLastCellNum();
        for (int c = header.getFirstCellNum(); c < last; c++) {
            Cell cell = header.getCell(c);
            if (cell == null) continue;
            String label = cellAsText(cell);
            if (label == null || label.isBlank()) continue;
            String key = normalize(label);
            String field = HEADER_BINDINGS.get(key);
            if (field != null) {
                out.put(c, field);
                matched.add(label.trim());
            } else {
                unknown.add(label.trim());
            }
        }
        return out;
    }

    private boolean isRowEmpty(Row row, Set<Integer> dataCols) {
        for (Integer c : dataCols) {
            Cell cell = row.getCell(c);
            if (cell == null) continue;
            String v = cellAsText(cell);
            if (v != null && !v.isBlank()) return false;
        }
        return true;
    }

    private PhysicalMediaCreateRequestDTO rowToDto(Row row, Map<Integer, String> colToField) {
        PhysicalMediaCreateRequestDTO dto = new PhysicalMediaCreateRequestDTO();
        for (var entry : colToField.entrySet()) {
            Cell cell = row.getCell(entry.getKey());
            if (cell == null) continue;
            applyCell(dto, entry.getValue(), cell);
        }
        return dto;
    }

    private void applyCell(PhysicalMediaCreateRequestDTO dto, String field, Cell cell) {
        switch (field) {
            case "rowNumber" -> dto.setRowNumber(intOf(cell));
            case "inventoryNumber" -> dto.setInventoryNumber(intOf(cell));
            case "physicalMediaType" -> dto.setPhysicalMediaType(textOf(cell));
            case "mediaCategory" -> dto.setMediaCategory(textOf(cell));
            case "title" -> dto.setTitle(textOf(cell));
            case "subType" -> dto.setSubType(textOf(cell));
            case "physicalLabel" -> dto.setPhysicalLabel(textOf(cell));
            case "size" -> dto.setSize(textOf(cell));
            case "content" -> dto.setContent(textOf(cell));
            case "archiveDepNote" -> dto.setArchiveDepNote(textOf(cell));
            case "digitization" -> {
                Integer code = intOf(cell);
                if (code != null) dto.setDigitization(DigitizationStatus.fromCode(code));
            }
            case "owner" -> dto.setOwner(textOf(cell));
            case "year" -> dto.setYear(intOf(cell));
            case "durationMin" -> dto.setDurationMin(intOf(cell));
            case "trackNumbers" -> dto.setTrackNumbers(intOf(cell));
            case "trackName" -> dto.setTrackName(textOf(cell));
            case "extension" -> dto.setExtension(textOf(cell));
            case "bitOrColorDepth" -> dto.setBitOrColorDepth(textOf(cell));
            case "sampleOrFrameRate" -> dto.setSampleOrFrameRate(textOf(cell));
            case "channelsOrResolution" -> dto.setChannelsOrResolution(textOf(cell));
            case "playbackModel" -> dto.setPlaybackModel(textOf(cell));
            case "captureInterface" -> dto.setCaptureInterface(textOf(cell));
            case "signalInterface" -> dto.setSignalInterface(textOf(cell));
            case "ingestSoftware" -> dto.setIngestSoftware(textOf(cell));
            case "formatCodec" -> dto.setFormatCodec(textOf(cell));
            case "digitizeDate" -> dto.setDigitizeDate(localDateOf(cell));
            case "tags" -> dto.setTags(textOf(cell));
            case "needToClear" -> {
                Integer code = intOf(cell);
                if (code != null) {
                    if (code == 0) dto.setNeedToClear(Boolean.FALSE);
                    else if (code == 1) dto.setNeedToClear(Boolean.TRUE);
                    // anything else: silently ignore — same forgiveness as other importers
                }
            }
            case "captureDepNote" -> dto.setCaptureDepNote(textOf(cell));
            default -> { /* no-op */ }
        }
    }

    // ─── Cell-type coercion ─────────────────────────────────────────────────

    private String cellAsText(Cell cell) {
        if (cell == null) return null;
        if (cell.getCellType() == CellType.NUMERIC) {
            // Honour "this is a date" hint from the workbook.
            if (DateUtil.isCellDateFormatted(cell)) {
                return cell.getLocalDateTimeCellValue().toLocalDate().toString();
            }
            double d = cell.getNumericCellValue();
            // Drop trailing ".0" on integral values so "60.0" doesn't bleed
            // through into a tag column that's clearly an int.
            if (d == Math.floor(d) && !Double.isInfinite(d)) {
                return Long.toString((long) d);
            }
            return Double.toString(d);
        }
        return formatter.formatCellValue(cell);
    }

    private String textOf(Cell cell) {
        String s = cellAsText(cell);
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private Integer intOf(Cell cell) {
        if (cell == null) return null;
        if (cell.getCellType() == CellType.NUMERIC) {
            double d = cell.getNumericCellValue();
            return (int) d;
        }
        String s = cellAsText(cell);
        if (s == null) return null;
        s = s.trim();
        if (s.isEmpty()) return null;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException ignored) {
            // Some sheets carry "12.0" as the number's text form; try double.
            try {
                return (int) Double.parseDouble(s);
            } catch (NumberFormatException ignored2) {
                return null;
            }
        }
    }

    private LocalDate localDateOf(Cell cell) {
        if (cell == null) return null;
        if (cell.getCellType() == CellType.NUMERIC) {
            if (DateUtil.isCellDateFormatted(cell)) {
                return cell.getLocalDateTimeCellValue().toLocalDate();
            }
            // Excel epoch number — best-effort interpretation.
            return DateUtil.getJavaDate(cell.getNumericCellValue())
                    .toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        }
        String s = textOf(cell);
        if (s == null) return null;
        try {
            return LocalDate.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Tolerant sheet lookup: tries an exact match first (POI is case- and
     * whitespace-sensitive), then falls back to a normalised match. The
     * normalisation collapses runs of whitespace and lower-cases — so the
     * user's {@code sheet=archive physical list} resolves to a workbook tab
     * literally titled {@code "Archive Physical List "} or
     * {@code "archive  physical list"}.
     */
    private Sheet findSheetTolerant(Workbook wb, String requested) {
        Sheet exact = wb.getSheet(requested);
        if (exact != null) return exact;
        String want = normalize(requested);
        for (int i = 0; i < wb.getNumberOfSheets(); i++) {
            Sheet candidate = wb.getSheetAt(i);
            if (normalize(candidate.getSheetName()).equals(want)) return candidate;
        }
        return null;
    }

    /** Comma-joined sheet names so a "not found" error tells the user what's
     *  actually available in their workbook. */
    private String listSheetNames(Workbook wb) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < wb.getNumberOfSheets(); i++) {
            if (i > 0) sb.append(", ");
            sb.append('\'').append(wb.getSheetName(i)).append('\'');
        }
        return sb.toString();
    }

    private static String normalize(String s) {
        if (s == null) return "";
        // strip zero-width spaces and friends, collapse whitespace, lower-case
        return s.replace("​", "")
                .replace("‌", "")
                .replace("‍", "")
                .replace("﻿", "")
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private static PhysicalMediaImportReportDTO.RowError rowError(int rowNum, String msg) {
        return PhysicalMediaImportReportDTO.RowError.builder()
                .rowNumber(rowNum)
                .message(msg == null ? "unknown error" : msg)
                .build();
    }
}
