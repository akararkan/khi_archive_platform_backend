package ak.dev.khi_archive_platform.user.api;

import ak.dev.khi_archive_platform.user.dto.admin.UnacknowledgedWarningCountDTO;
import ak.dev.khi_archive_platform.user.dto.admin.UserWarningResponseDTO;
import ak.dev.khi_archive_platform.user.service.UserWarningService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Recipient-facing warning endpoints. Any authenticated user can list their
 * own warnings and acknowledge them — no {@code warning:*} authority is
 * required here. Admin-issued warnings flow into this list automatically.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/warnings")
@PreAuthorize("isAuthenticated()")
public class UserWarningAPI {

    private final UserWarningService warningService;

    /**
     * Paged list of the caller's own warnings, unacknowledged first then
     * newest first. Revoked warnings are filtered out.
     */
    @GetMapping("/me")
    public ResponseEntity<Page<UserWarningResponseDTO>> myWarnings(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            Authentication auth
    ) {
        return ResponseEntity.ok(warningService.getMyWarnings(auth, page, size));
    }

    /** Unacknowledged-warning count for the caller. Drives the top-bar badge. */
    @GetMapping("/me/count")
    public ResponseEntity<UnacknowledgedWarningCountDTO> myCount(Authentication auth) {
        return ResponseEntity.ok(warningService.myUnacknowledgedCount(auth));
    }

    /**
     * Mark one warning as read. Only the warning's target can call this;
     * anyone else gets {@code 409 WARNING_NOT_FOR_YOU}.
     */
    @PostMapping("/{warningId}/acknowledge")
    public ResponseEntity<UserWarningResponseDTO> acknowledge(
            @PathVariable Long warningId,
            Authentication auth,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(warningService.acknowledge(warningId, auth, request));
    }
}
