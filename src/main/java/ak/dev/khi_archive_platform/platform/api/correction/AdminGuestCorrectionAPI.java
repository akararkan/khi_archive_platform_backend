package ak.dev.khi_archive_platform.platform.api.correction;

import ak.dev.khi_archive_platform.platform.dto.correction.*;
import ak.dev.khi_archive_platform.platform.dto.analytics.CorrectionStatsDTO;
import ak.dev.khi_archive_platform.platform.enums.CorrectionMediaType;
import ak.dev.khi_archive_platform.platform.enums.CorrectionStatus;
import ak.dev.khi_archive_platform.platform.service.correction.GuestCorrectionService;
import ak.dev.khi_archive_platform.platform.service.analytics.AnalyticsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Admin-only endpoints to view, forward, resolve, and reject guest correction
 * suggestions. Class-level ADMIN check plus per-method correction:* authority
 * mirror the AdminUserWarningAPI pattern.
 *
 * <p>Every mutation is audited in {@code guest_correction_audit_logs}.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/corrections")
@PreAuthorize("hasRole('ADMIN')")
public class AdminGuestCorrectionAPI {

    private final GuestCorrectionService correctionService;
    private final AnalyticsService analyticsService;

    /**
     * Paged search across all corrections. All filters are optional.
     * By default excludes removed rows; pass {@code includeRemoved=true} to see them.
     */
    @GetMapping
    @PreAuthorize("hasAuthority('correction:read')")
    public ResponseEntity<Page<GuestCorrectionResponseDTO>> search(
            @RequestParam(required = false) String mediaType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String mediaCode,
            @RequestParam(required = false) String recordCreatedBy,
            @RequestParam(required = false) Long guestUserId,
            @RequestParam(required = false, defaultValue = "false") boolean includeRemoved,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            Authentication auth,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(correctionService.adminSearch(
                parseMediaType(mediaType), parseStatus(status),
                mediaCode, recordCreatedBy, guestUserId,
                includeRemoved, from, to, page, size, auth, request));
    }

    /** Get a single correction by id (includes removed rows for audit). */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('correction:read')")
    public ResponseEntity<GuestCorrectionResponseDTO> get(
            @PathVariable Long id,
            Authentication auth,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(correctionService.adminGetById(id, auth, request));
    }

    /**
     * Forward correction to the employee who created the media record.
     * Sends an INFO-severity UserWarning to that employee with full correction details.
     */
    @PostMapping("/{id}/forward")
    @PreAuthorize("hasAuthority('correction:update')")
    public ResponseEntity<GuestCorrectionResponseDTO> forward(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) AdminCorrectionForwardRequestDTO dto,
            Authentication auth,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(correctionService.adminForward(id, dto, auth, request));
    }

    /** Mark correction as resolved (employee applied the fix). */
    @PostMapping("/{id}/resolve")
    @PreAuthorize("hasAuthority('correction:update')")
    public ResponseEntity<GuestCorrectionResponseDTO> resolve(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) AdminCorrectionResolveRequestDTO dto,
            Authentication auth,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(correctionService.adminResolve(id, dto, auth, request));
    }

    /**
     * Admin directly applies the suggested value to the media record field,
     * then marks correction as RESOLVED. Supports all public string/text fields.
     * List-type fields (tags, keywords, genres, contributors) require manual
     * update via the media update endpoints.
     */
    @PostMapping("/{id}/apply")
    @PreAuthorize("hasAuthority('correction:update')")
    public ResponseEntity<GuestCorrectionResponseDTO> apply(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) AdminCorrectionApplyRequestDTO dto,
            Authentication auth,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(correctionService.adminApply(id, dto, auth, request));
    }

    /** Reject a correction suggestion. */
    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('correction:update')")
    public ResponseEntity<GuestCorrectionResponseDTO> reject(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) AdminCorrectionRejectRequestDTO dto,
            Authentication auth,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(correctionService.adminReject(id, dto, auth, request));
    }

    /** Soft-delete a correction (preserves audit trail). */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('correction:remove')")
    public ResponseEntity<Void> remove(
            @PathVariable Long id,
            Authentication auth,
            HttpServletRequest request
    ) {
        correctionService.adminRemove(id, auth, request);
        return ResponseEntity.noContent().build();
    }

    /** Live correction submission statistics (all-time totals by status and media type). */
    @GetMapping("/stats")
    @PreAuthorize("hasAuthority('correction:read')")
    public ResponseEntity<CorrectionStatsDTO> stats() {
        return ResponseEntity.ok(analyticsService.getCorrectionStats());
    }

    /** Catalog of valid status values for search/filter dropdowns. */
    @GetMapping("/catalog/statuses")
    public ResponseEntity<List<String>> statusCatalog() {
        return ResponseEntity.ok(correctionService.statusCatalog());
    }

    /** Catalog of valid media types for search/filter dropdowns. */
    @GetMapping("/catalog/media-types")
    public ResponseEntity<List<String>> mediaTypeCatalog() {
        return ResponseEntity.ok(correctionService.mediaTypeCatalog());
    }

    private static CorrectionMediaType parseMediaType(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return CorrectionMediaType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "Unknown mediaType '" + value + "'. Allowed: AUDIO, VIDEO, IMAGE, TEXT");
        }
    }

    private static CorrectionStatus parseStatus(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return CorrectionStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "Unknown status '" + value + "'. Allowed: PENDING, FORWARDED, RESOLVED, REJECTED");
        }
    }
}
