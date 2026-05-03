package ak.dev.khi_archive_platform.user.api;

import ak.dev.khi_archive_platform.user.dto.admin.UserAuditLogDTO;
import ak.dev.khi_archive_platform.user.enums.UserAuditAction;
import ak.dev.khi_archive_platform.user.service.UserAuditLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Read-side endpoints for the {@code user_audit_logs} table.
 *
 * <p>Every endpoint is gated on {@code ROLE_ADMIN} + {@code user:read}.
 * The audit-log reads are intentionally <b>not</b> themselves audited —
 * otherwise opening the page would generate a row on every refresh and the
 * useful CREATE/UPDATE/DELETE/etc. signal would be drowned out.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/users/audit-logs")
@PreAuthorize("hasRole('ADMIN')")
public class UserAuditLogAPI {

    private final UserAuditLogService auditLogService;

    /**
     * Paged list with optional filters. All filters are AND-ed together.
     *
     * <p>Query parameters:
     * <ul>
     *   <li>{@code targetUserId} — exact match on the action target</li>
     *   <li>{@code targetUsername} — exact match (case-insensitive)</li>
     *   <li>{@code actor} — exact admin username (case-insensitive)</li>
     *   <li>{@code action} — one of {@link UserAuditAction} values</li>
     *   <li>{@code from} / {@code to} — ISO-8601 instants bracketing {@code occurred_at}</li>
     *   <li>{@code q} — free-text substring searched across details, target/actor
     *       usernames, target email, and permissions_changed</li>
     *   <li>{@code page} (default 0), {@code size} (default 50, max 200)</li>
     *   <li>{@code sort} — {@code asc} or {@code desc}; default {@code desc}</li>
     * </ul>
     */
    @GetMapping
    @PreAuthorize("hasAuthority('user:read')")
    public ResponseEntity<Page<UserAuditLogDTO>> list(
            @RequestParam(required = false) Long targetUserId,
            @RequestParam(required = false) String targetUsername,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort
    ) {
        UserAuditLogService.Filter filter = new UserAuditLogService.Filter(
                targetUserId, targetUsername, actor, parseAction(action), from, to, q
        );
        return ResponseEntity.ok(auditLogService.search(filter, page, size, sort));
    }

    /** Single row by id. */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('user:read')")
    public ResponseEntity<UserAuditLogDTO> getOne(@PathVariable Long id) {
        return ResponseEntity.ok(auditLogService.getById(id));
    }

    /** Catalog of recognised action names — drives the action-filter dropdown. */
    @GetMapping("/actions")
    @PreAuthorize("hasAuthority('user:read')")
    public ResponseEntity<List<String>> actions() {
        return ResponseEntity.ok(auditLogService.listActions());
    }

    /** Distinct admin usernames seen in the audit log — drives the actor-filter dropdown. */
    @GetMapping("/actors")
    @PreAuthorize("hasAuthority('user:read')")
    public ResponseEntity<List<String>> actors() {
        return ResponseEntity.ok(auditLogService.listActors());
    }

    private UserAuditAction parseAction(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return UserAuditAction.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "Unknown action '" + raw + "'. Use GET /api/admin/users/audit-logs/actions for the list.");
        }
    }
}
