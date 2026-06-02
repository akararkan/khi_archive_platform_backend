package ak.dev.khi_archive_platform.platform.api.correction;

import ak.dev.khi_archive_platform.platform.dto.correction.GuestCorrectionResponseDTO;
import ak.dev.khi_archive_platform.platform.dto.correction.GuestCorrectionSubmitRequestDTO;
import ak.dev.khi_archive_platform.platform.service.correction.GuestCorrectionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * Authenticated-user endpoints for correction suggestions.
 *
 * <p>Any logged-in user (GUEST, EMPLOYEE, ADMIN) may submit a correction or
 * view their own submissions. No specific permission is required beyond
 * {@code isAuthenticated()} — the "Help Us" button on the public media pages
 * is available to all signed-in users.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/corrections")
@PreAuthorize("isAuthenticated()")
public class GuestCorrectionAPI {

    private final GuestCorrectionService correctionService;

    /**
     * Submit a correction suggestion for a media record.
     * Body: mediaType, mediaCode, targetField, suggestedValue, (optional) currentValue, note.
     */
    @PostMapping
    public ResponseEntity<GuestCorrectionResponseDTO> submit(
            @Valid @RequestBody GuestCorrectionSubmitRequestDTO dto,
            Authentication auth,
            HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(correctionService.submit(dto, auth, request));
    }

    /** List the authenticated user's own correction submissions (newest first). */
    @GetMapping("/me")
    public ResponseEntity<Page<GuestCorrectionResponseDTO>> myCorrections(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            Authentication auth
    ) {
        return ResponseEntity.ok(correctionService.getMyCorrections(auth, page, size));
    }

    /** Get one of the authenticated user's own correction submissions by id. */
    @GetMapping("/me/{id}")
    public ResponseEntity<GuestCorrectionResponseDTO> myCorrection(
            @PathVariable Long id,
            Authentication auth,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(correctionService.getMyCorrection(id, auth, request));
    }

    /** Catalog of valid media types for the "Help Us" form. */
    @GetMapping("/catalog/media-types")
    public ResponseEntity<java.util.List<String>> mediaTypeCatalog() {
        return ResponseEntity.ok(correctionService.mediaTypeCatalog());
    }
}
