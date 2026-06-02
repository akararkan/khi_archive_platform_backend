package ak.dev.khi_archive_platform.user.api;

import ak.dev.khi_archive_platform.user.dto.admin.UserWarningCreateRequestDTO;
import ak.dev.khi_archive_platform.user.dto.admin.UserWarningResponseDTO;
import ak.dev.khi_archive_platform.user.dto.admin.UserWarningUpdateRequestDTO;
import ak.dev.khi_archive_platform.user.enums.WarningSeverity;
import ak.dev.khi_archive_platform.user.service.UserWarningService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Admin-only endpoints to issue and manage warnings. Class-level
 * {@code hasRole('ADMIN')} plus per-method {@code hasAuthority('warning:*')}
 * mirror the AdminUserAPI pattern so granular delegation is possible.
 *
 * <p>Every mutation is audited via {@code user_audit_logs}.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/warnings")
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserWarningAPI {

    private final UserWarningService warningService;

    /**
     * Paged search across all warnings. Every filter is optional. Defaults
     * exclude revoked rows; pass {@code includeRevoked=true} to see them.
     */
    @GetMapping
    @PreAuthorize("hasAuthority('warning:read')")
    public ResponseEntity<Page<UserWarningResponseDTO>> search(
            @RequestParam(required = false) Long targetUserId,
            @RequestParam(required = false) Long actorUserId,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) Boolean acknowledged,
            @RequestParam(required = false, defaultValue = "false") boolean includeRevoked,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size
    ) {
        WarningSeverity sev = parseSeverity(severity);
        return ResponseEntity.ok(warningService.search(
                targetUserId, actorUserId, sev, acknowledged, includeRevoked, from, to, page, size));
    }

    /** One warning by id. Returns revoked rows too — useful for audit trails. */
    @GetMapping("/{warningId}")
    @PreAuthorize("hasAuthority('warning:read')")
    public ResponseEntity<UserWarningResponseDTO> get(@PathVariable Long warningId) {
        return ResponseEntity.ok(warningService.getByIdForAdmin(warningId));
    }

    /** Issue a new warning. */
    @PostMapping
    @PreAuthorize("hasAuthority('warning:create')")
    public ResponseEntity<UserWarningResponseDTO> send(
            @Valid @RequestBody UserWarningCreateRequestDTO dto,
            Authentication auth,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(warningService.send(dto, auth, request));
    }

    /** Edit an existing warning's severity/title/message. */
    @PutMapping("/{warningId}")
    @PreAuthorize("hasAuthority('warning:update')")
    public ResponseEntity<UserWarningResponseDTO> update(
            @PathVariable Long warningId,
            @Valid @RequestBody UserWarningUpdateRequestDTO dto,
            Authentication auth,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(warningService.update(warningId, dto, auth, request));
    }

    /** Revoke (soft-delete) a warning. Recipient stops seeing it immediately. */
    @DeleteMapping("/{warningId}")
    @PreAuthorize("hasAuthority('warning:delete')")
    public ResponseEntity<Void> revoke(
            @PathVariable Long warningId,
            Authentication auth,
            HttpServletRequest request
    ) {
        warningService.revoke(warningId, auth, request);
        return ResponseEntity.noContent().build();
    }

    /** Severity choices for the "send warning" form. */
    @GetMapping("/catalog/severities")
    public ResponseEntity<List<String>> severityCatalog() {
        return ResponseEntity.ok(Arrays.stream(WarningSeverity.values())
                .map(Enum::name).toList());
    }

    private static WarningSeverity parseSeverity(String severity) {
        if (severity == null || severity.isBlank()) return null;
        try {
            return WarningSeverity.valueOf(severity.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "Unknown severity '" + severity + "'. Allowed: "
                            + Arrays.toString(WarningSeverity.values()));
        }
    }
}
