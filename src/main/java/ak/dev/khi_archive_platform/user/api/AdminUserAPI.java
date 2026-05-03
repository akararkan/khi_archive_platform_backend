package ak.dev.khi_archive_platform.user.api;

import ak.dev.khi_archive_platform.user.dto.UserCreateRequestDTO;
import ak.dev.khi_archive_platform.user.dto.UserUpdateRequestDTO;
import ak.dev.khi_archive_platform.user.dto.admin.PermissionsChangeRequestDTO;
import ak.dev.khi_archive_platform.user.dto.admin.RoleCatalogDTO;
import ak.dev.khi_archive_platform.user.dto.admin.RoleChangeRequestDTO;
import ak.dev.khi_archive_platform.user.dto.admin.UserAdminDTO;
import ak.dev.khi_archive_platform.user.dto.admin.UserAuditLogDTO;
import ak.dev.khi_archive_platform.user.enums.UserAuditAction;
import ak.dev.khi_archive_platform.user.service.AdminUserService;
import ak.dev.khi_archive_platform.user.service.UserAuditLogService;
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
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Admin-only user-management API. Every endpoint is double-guarded:
 * <ul>
 *   <li>Class-level {@code hasRole('ADMIN')} ensures only admins reach
 *       these handlers (defence-in-depth even if a permission is misgranted).</li>
 *   <li>Per-method {@code hasAuthority('user:...')} aligns with the existing
 *       {@code Permission} catalog so granular permission grants work
 *       (e.g. give one trusted user {@code user:read} without making them
 *       full ADMIN).</li>
 * </ul>
 *
 * Every mutating call writes a row to {@code user_audit_logs} via
 * {@code UserAuditService} so role/permission changes are fully traceable.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/users")
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserAPI {

    private final AdminUserService adminUserService;
    private final UserAuditLogService auditLogService;

    /** All users with their role + extra permissions + effective authority set.
     *  Listing is intentionally not audited — it would drown out genuine
     *  state-change rows. */
    @GetMapping
    @PreAuthorize("hasAuthority('user:read')")
    public ResponseEntity<List<UserAdminDTO>> list(Authentication auth, HttpServletRequest request) {
        return ResponseEntity.ok(adminUserService.listAll(auth, request));
    }

    /**
     * Admin-driven user creation. Body shape mirrors {@code UserCreateRequestDTO}
     * (name, username, email, password, optional role + isActivated). Audited
     * as a {@code CREATE} row with the assigned role and seeded permission set
     * captured in the {@code details} column.
     */
    @PostMapping
    @PreAuthorize("hasAuthority('user:create')")
    public ResponseEntity<UserAdminDTO> create(@Valid @RequestBody UserCreateRequestDTO dto,
                                               Authentication auth,
                                               HttpServletRequest request) {
        return ResponseEntity.ok(adminUserService.createUserAsAdmin(dto, auth, request));
    }

    /**
     * Admin-driven update of arbitrary user fields (name, username, email,
     * password, role, isActivated). Audited as an {@code UPDATE} row whose
     * {@code details} column carries a per-field diff. Role transitions trigger
     * {@code applyRoleDefaults()} (seeding EMPLOYEE perms) and audit
     * {@code previousRole}/{@code newRole} columns alongside the diff.
     */
    @PutMapping("/{userId}")
    @PreAuthorize("hasAuthority('user:update')")
    public ResponseEntity<UserAdminDTO> update(@PathVariable Long userId,
                                               @Valid @RequestBody UserUpdateRequestDTO dto,
                                               Authentication auth,
                                               HttpServletRequest request) {
        return ResponseEntity.ok(adminUserService.updateUserAsAdmin(userId, dto, auth, request));
    }

    /** Single user. */
    @GetMapping("/{userId}")
    @PreAuthorize("hasAuthority('user:read')")
    public ResponseEntity<UserAdminDTO> get(@PathVariable Long userId,
                                            Authentication auth,
                                            HttpServletRequest request) {
        return ResponseEntity.ok(adminUserService.getById(userId, auth, request));
    }

    /** Change a user's role. Body: {@code { "role": "ADMIN" }}. */
    @PutMapping("/{userId}/role")
    @PreAuthorize("hasAuthority('user:update')")
    public ResponseEntity<UserAdminDTO> changeRole(@PathVariable Long userId,
                                                   @Valid @RequestBody RoleChangeRequestDTO dto,
                                                   Authentication auth,
                                                   HttpServletRequest request) {
        return ResponseEntity.ok(adminUserService.changeRole(userId, dto, auth, request));
    }

    /** Grant additional permissions on top of the user's role.
     *  Body: {@code { "permissions": ["audio:delete","user:update"] }}. */
    @PostMapping("/{userId}/permissions")
    @PreAuthorize("hasAuthority('user:update')")
    public ResponseEntity<UserAdminDTO> grantPermissions(@PathVariable Long userId,
                                                         @Valid @RequestBody PermissionsChangeRequestDTO dto,
                                                         Authentication auth,
                                                         HttpServletRequest request) {
        return ResponseEntity.ok(adminUserService.grantPermissions(userId, dto, auth, request));
    }

    /** Revoke previously-granted extra permissions. Same body shape as grant. */
    @DeleteMapping("/{userId}/permissions")
    @PreAuthorize("hasAuthority('user:update')")
    public ResponseEntity<UserAdminDTO> revokePermissions(@PathVariable Long userId,
                                                          @Valid @RequestBody PermissionsChangeRequestDTO dto,
                                                          Authentication auth,
                                                          HttpServletRequest request) {
        return ResponseEntity.ok(adminUserService.revokePermissions(userId, dto, auth, request));
    }

    /** Activate a user. */
    @PostMapping("/{userId}/activate")
    @PreAuthorize("hasAuthority('user:update')")
    public ResponseEntity<UserAdminDTO> activate(@PathVariable Long userId,
                                                 Authentication auth,
                                                 HttpServletRequest request) {
        return ResponseEntity.ok(adminUserService.setActivated(userId, true, auth, request));
    }

    /** Deactivate a user (cannot deactivate self). */
    @PostMapping("/{userId}/deactivate")
    @PreAuthorize("hasAuthority('user:update')")
    public ResponseEntity<UserAdminDTO> deactivate(@PathVariable Long userId,
                                                   Authentication auth,
                                                   HttpServletRequest request) {
        return ResponseEntity.ok(adminUserService.setActivated(userId, false, auth, request));
    }

    /** Lock the account — auth filter will start refusing logins until unlocked. */
    @PostMapping("/{userId}/lock")
    @PreAuthorize("hasAuthority('user:update')")
    public ResponseEntity<UserAdminDTO> lock(@PathVariable Long userId,
                                             Authentication auth,
                                             HttpServletRequest request) {
        return ResponseEntity.ok(adminUserService.lock(userId, auth, request));
    }

    /** Unlock the account and reset the failed-login counter. */
    @PostMapping("/{userId}/unlock")
    @PreAuthorize("hasAuthority('user:update')")
    public ResponseEntity<UserAdminDTO> unlock(@PathVariable Long userId,
                                               Authentication auth,
                                               HttpServletRequest request) {
        return ResponseEntity.ok(adminUserService.unlock(userId, auth, request));
    }

    /** Clear the failed-login counter without changing lock state. */
    @PostMapping("/{userId}/reset-failed-attempts")
    @PreAuthorize("hasAuthority('user:update')")
    public ResponseEntity<UserAdminDTO> resetFailedAttempts(@PathVariable Long userId,
                                                            Authentication auth,
                                                            HttpServletRequest request) {
        return ResponseEntity.ok(adminUserService.resetFailedAttempts(userId, auth, request));
    }

    /** Sign the user out of every device by deactivating their session rows. */
    @PostMapping("/{userId}/force-logout")
    @PreAuthorize("hasAuthority('user:update')")
    public ResponseEntity<UserAdminDTO> forceLogout(@PathVariable Long userId,
                                                    Authentication auth,
                                                    HttpServletRequest request) {
        return ResponseEntity.ok(adminUserService.forceLogoutAll(userId, auth, request));
    }

    /**
     * Hard-delete a user. Removes their sessions and the user row. Blocked if
     * the caller is the same user, or if the target is the only remaining
     * ADMIN. Audit row is written before the delete.
     */
    @DeleteMapping("/{userId}")
    @PreAuthorize("hasAuthority('user:delete')")
    public ResponseEntity<Void> delete(@PathVariable Long userId,
                                       Authentication auth,
                                       HttpServletRequest request) {
        adminUserService.deleteUser(userId, auth, request);
        return ResponseEntity.noContent().build();
    }

    /**
     * Convenience: paged audit-log history scoped to one user. Same filter
     * shape as {@code /api/admin/users/audit-logs} except {@code targetUserId}
     * is taken from the path.
     */
    @GetMapping("/{userId}/audit-logs")
    @PreAuthorize("hasAuthority('user:read')")
    public ResponseEntity<Page<UserAuditLogDTO>> userAuditLogs(
            @PathVariable Long userId,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort
    ) {
        UserAuditAction parsedAction = null;
        if (action != null && !action.isBlank()) {
            try {
                parsedAction = UserAuditAction.valueOf(action.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException(
                        "Unknown action '" + action
                                + "'. Use GET /api/admin/users/audit-logs/actions for the list.");
            }
        }
        UserAuditLogService.Filter filter = new UserAuditLogService.Filter(
                userId, null, actor, parsedAction, from, to, q
        );
        return ResponseEntity.ok(auditLogService.search(filter, page, size, sort));
    }

    // ─── Catalog ────────────────────────────────────────────────────────────

    /** All roles + the authorities each role grants. Used to populate UI dropdowns. */
    @GetMapping("/catalog/roles")
    public ResponseEntity<List<RoleCatalogDTO>> roleCatalog() {
        return ResponseEntity.ok(adminUserService.listRoles());
    }

    /** Every permission string the system understands. Use this to build
     *  the grant/revoke UI — anything outside this set is rejected. */
    @GetMapping("/catalog/permissions")
    public ResponseEntity<Set<String>> permissionCatalog() {
        return ResponseEntity.ok(adminUserService.listPermissions());
    }
}
