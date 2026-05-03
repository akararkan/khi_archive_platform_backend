package ak.dev.khi_archive_platform.user.api;

import ak.dev.khi_archive_platform.user.dto.admin.PermissionsChangeRequestDTO;
import ak.dev.khi_archive_platform.user.dto.admin.RoleCatalogDTO;
import ak.dev.khi_archive_platform.user.dto.admin.RoleChangeRequestDTO;
import ak.dev.khi_archive_platform.user.dto.admin.UserAdminDTO;
import ak.dev.khi_archive_platform.user.service.AdminUserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
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

    /** All users with their role + extra permissions + effective authority set. */
    @GetMapping
    @PreAuthorize("hasAuthority('user:read')")
    public ResponseEntity<List<UserAdminDTO>> list(Authentication auth, HttpServletRequest request) {
        return ResponseEntity.ok(adminUserService.listAll(auth, request));
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
