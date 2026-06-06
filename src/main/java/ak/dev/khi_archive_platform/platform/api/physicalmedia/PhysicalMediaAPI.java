package ak.dev.khi_archive_platform.platform.api.physicalmedia;

import ak.dev.khi_archive_platform.platform.dto.physicalmedia.PhysicalMediaCreateRequestDTO;
import ak.dev.khi_archive_platform.platform.dto.physicalmedia.PhysicalMediaImportReportDTO;
import ak.dev.khi_archive_platform.platform.dto.physicalmedia.PhysicalMediaNextNumberDTO;
import ak.dev.khi_archive_platform.platform.dto.physicalmedia.PhysicalMediaResponseDTO;
import ak.dev.khi_archive_platform.platform.dto.physicalmedia.PhysicalMediaUpdateRequestDTO;
import ak.dev.khi_archive_platform.platform.service.physicalmedia.PhysicalMediaExcelImportService;
import ak.dev.khi_archive_platform.platform.service.physicalmedia.PhysicalMediaService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Inventory CRUD + Excel ingest for physical media (cassettes, reels, DVDs,
 * VHS …). Permissions:
 * <ul>
 *   <li>{@code physical_media:read}    — list / get / search (seeded to ADMIN + EMPLOYEE).</li>
 *   <li>{@code physical_media:create}  — POST a new row.</li>
 *   <li>{@code physical_media:update}  — PATCH an existing row.</li>
 *   <li>{@code physical_media:remove}  — soft-trash (admin only).</li>
 *   <li>{@code physical_media:import}  — POST an .xlsx for bulk upsert.</li>
 * </ul>
 * Trash, restore, and purge live on {@link AdminPhysicalMediaAPI}.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/physical-media")
public class PhysicalMediaAPI {

    private final PhysicalMediaService service;
    private final PhysicalMediaExcelImportService importService;

    // ─── Reading ─────────────────────────────────────────────────────────────

    /** Active rows. Page size defaults to 50; max 200 enforced by JPA.
     *  Default sort is {@code id ASC} so the table reads "row 1 first" in
     *  every UI that doesn't override the {@code ?sort=} parameter. */
    @GetMapping
    @PreAuthorize("hasAuthority('physical_media:read')")
    public ResponseEntity<Page<PhysicalMediaResponseDTO>> listActive(
            @PageableDefault(size = 50, sort = "id", direction = Sort.Direction.ASC) Pageable pageable,
            Authentication auth,
            HttpServletRequest request) {
        return ResponseEntity.ok(service.listActive(pageable, auth, request));
    }

    /** Free-text search across pmCode, label, media type, title, content, owner, tags. */
    @GetMapping("/search")
    @PreAuthorize("hasAuthority('physical_media:read')")
    public ResponseEntity<List<PhysicalMediaResponseDTO>> search(
            @RequestParam("q") String q,
            @RequestParam(value = "limit", required = false, defaultValue = "20") Integer limit,
            Authentication auth,
            HttpServletRequest request) {
        return ResponseEntity.ok(service.search(q, limit == null ? 20 : limit, auth, request));
    }

    @GetMapping("/{pmCode}")
    @PreAuthorize("hasAuthority('physical_media:read')")
    public ResponseEntity<PhysicalMediaResponseDTO> getOne(
            @PathVariable String pmCode,
            Authentication auth,
            HttpServletRequest request) {
        return ResponseEntity.ok(service.getByCode(pmCode, auth, request));
    }

    /**
     * Preview the {@code Number} that the server <em>would</em> assign to
     * the next record of the given media type. Drives the create-form hint
     * "this row will be VHS Cassette #56", so the user doesn't have to
     * compute and re-type the count. Cheap read; no lock; no audit.
     */
    @GetMapping("/next-number")
    @PreAuthorize("hasAuthority('physical_media:read')")
    public ResponseEntity<PhysicalMediaNextNumberDTO> nextNumber(
            @RequestParam("type") String physicalMediaType) {
        return ResponseEntity.ok(PhysicalMediaNextNumberDTO.builder()
                .physicalMediaType(physicalMediaType)
                .nextInventoryNumber(service.peekNextInventoryNumber(physicalMediaType))
                .build());
    }

    // ─── Mutations ───────────────────────────────────────────────────────────

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('physical_media:create')")
    public ResponseEntity<PhysicalMediaResponseDTO> create(
            @Valid @RequestBody PhysicalMediaCreateRequestDTO dto,
            Authentication auth,
            HttpServletRequest request) {
        return ResponseEntity.ok(service.create(dto, auth, request));
    }

    @PatchMapping(value = "/{pmCode}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('physical_media:update')")
    public ResponseEntity<PhysicalMediaResponseDTO> update(
            @PathVariable String pmCode,
            @Valid @RequestBody PhysicalMediaUpdateRequestDTO dto,
            Authentication auth,
            HttpServletRequest request) {
        return ResponseEntity.ok(service.update(pmCode, dto, auth, request));
    }

    /** Soft-trash. Restore / purge live under {@link AdminPhysicalMediaAPI}. */
    @DeleteMapping("/{pmCode}")
    @PreAuthorize("hasAuthority('physical_media:remove')")
    public ResponseEntity<Void> softDelete(
            @PathVariable String pmCode,
            Authentication auth,
            HttpServletRequest request) {
        service.softDelete(pmCode, auth, request);
        return ResponseEntity.noContent().build();
    }

    // ─── Excel import ────────────────────────────────────────────────────────

    /**
     * Bulk upsert from {@code .xlsx}. {@code sheet} is optional — when
     * omitted the first sheet of the workbook is used. The response is a
     * structured report so the UI can show "X inserted, Y updated, Z
     * skipped" along with per-row error messages.
     *
     * <p>Dedupe key inside the importer is
     * {@code (physicalMediaType, physicalLabel)} — rows missing either
     * column are always inserted, never matched.
     */
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('physical_media:import')")
    public ResponseEntity<PhysicalMediaImportReportDTO> importExcel(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "sheet", required = false) String sheet,
            Authentication auth,
            HttpServletRequest request) {
        return ResponseEntity.ok(importService.importExcel(file, sheet, auth, request));
    }

    /**
     * Peek-only companion to {@code /import}: returns the workbook's sheet
     * names so the frontend can render a dropdown for the user to pick from
     * before kicking off the import. No DB writes; no audit entry — the
     * file isn't persisted anywhere.
     */
    @PostMapping(value = "/import/sheets", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('physical_media:import')")
    public ResponseEntity<List<String>> listSheets(@RequestPart("file") MultipartFile file) {
        return ResponseEntity.ok(importService.listSheets(file));
    }
}
