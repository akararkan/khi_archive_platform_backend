package ak.dev.khi_archive_platform.user.service;

import ak.dev.khi_archive_platform.user.dto.admin.PermissionsChangeRequestDTO;
import ak.dev.khi_archive_platform.user.dto.admin.RoleCatalogDTO;
import ak.dev.khi_archive_platform.user.dto.admin.RoleChangeRequestDTO;
import ak.dev.khi_archive_platform.user.dto.admin.UserAdminDTO;
import ak.dev.khi_archive_platform.user.enums.Permission;
import ak.dev.khi_archive_platform.user.enums.Role;
import ak.dev.khi_archive_platform.user.enums.UserAuditAction;
import ak.dev.khi_archive_platform.user.exceptions.UserNotFoundException;
import ak.dev.khi_archive_platform.user.model.Session;
import ak.dev.khi_archive_platform.user.model.User;
import ak.dev.khi_archive_platform.user.repo.SessionRepository;
import ak.dev.khi_archive_platform.user.repo.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Admin-only user-management service.
 *
 * <p>Mutating methods record one row in {@code user_audit_logs} via
 * {@link UserAuditService} so every role/permission change is traceable.
 *
 * <p>Self-protection: the calling admin cannot demote themselves below
 * ADMIN, and cannot revoke their own ADMIN authorities. Otherwise a single
 * mistake could lock all admins out of the system.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class AdminUserService {

    /** Catalog of every permission string the system understands.
     *  Permission grants are validated against this set so admins can't
     *  introduce typos or unknown strings. Computed once at class load. */
    public static final Set<String> KNOWN_PERMISSIONS;
    static {
        Set<String> all = new TreeSet<>();
        for (Permission p : Permission.values()) all.add(p.getPermission());
        KNOWN_PERMISSIONS = Set.copyOf(all);
    }

    private final UserRepository userRepository;
    private final SessionRepository sessionRepository;
    private final UserAuditService auditService;

    // ─── Read ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<UserAdminDTO> listAll(Authentication auth, HttpServletRequest request) {
        List<UserAdminDTO> out = userRepository.findAll().stream()
                .map(this::toDto)
                .toList();
        auditService.record(null, UserAuditAction.LIST, null, null, null,
                auth, request, "Listed " + out.size() + " users");
        return out;
    }

    @Transactional(readOnly = true)
    public UserAdminDTO getById(Long userId, Authentication auth, HttpServletRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found: id=" + userId));
        auditService.record(user, UserAuditAction.READ, null, null, null,
                auth, request, "Read user record");
        return toDto(user);
    }

    // ─── Role ───────────────────────────────────────────────────────────────

    public UserAdminDTO changeRole(Long userId, RoleChangeRequestDTO dto,
                                   Authentication auth, HttpServletRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found: id=" + userId));

        Role newRole = parseRole(dto.getRole());
        Role oldRole = user.getRole();
        if (newRole == oldRole) return toDto(user);

        // Self-protection: prevent the calling admin from demoting themselves.
        guardNotSelfDemotion(user, auth, oldRole, newRole);

        user.setRole(newRole);
        user.setUpdatedAt(Instant.now());
        User saved = userRepository.save(user);

        auditService.record(saved, UserAuditAction.ROLE_CHANGE,
                oldRole.name(), newRole.name(), null,
                auth, request,
                "Role changed from " + oldRole + " to " + newRole);
        return toDto(saved);
    }

    // ─── Permissions ────────────────────────────────────────────────────────

    public UserAdminDTO grantPermissions(Long userId, PermissionsChangeRequestDTO dto,
                                         Authentication auth, HttpServletRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found: id=" + userId));

        Set<String> requested = sanitiseAndValidate(dto.getPermissions());
        Set<String> existing = user.getExtraPermissions() == null
                ? new HashSet<>() : new HashSet<>(user.getExtraPermissions());
        Set<String> added = new TreeSet<>(requested);
        added.removeAll(existing);
        if (added.isEmpty()) return toDto(user);

        existing.addAll(added);
        user.setExtraPermissions(existing);
        user.setUpdatedAt(Instant.now());
        User saved = userRepository.save(user);

        auditService.record(saved, UserAuditAction.GRANT_PERMISSIONS,
                null, null, added, auth, request,
                "Granted permissions: " + added);
        return toDto(saved);
    }

    public UserAdminDTO revokePermissions(Long userId, PermissionsChangeRequestDTO dto,
                                          Authentication auth, HttpServletRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found: id=" + userId));

        Set<String> requested = sanitiseAndValidate(dto.getPermissions());
        Set<String> existing = user.getExtraPermissions() == null
                ? new HashSet<>() : new HashSet<>(user.getExtraPermissions());
        Set<String> removed = new TreeSet<>(requested);
        removed.retainAll(existing);
        if (removed.isEmpty()) return toDto(user);

        // Self-protection: don't let the calling admin strip their own
        // user:* permissions if they're acting on themselves.
        guardNotSelfRevokingUserMgmt(user, auth, removed);

        existing.removeAll(removed);
        user.setExtraPermissions(existing);
        user.setUpdatedAt(Instant.now());
        User saved = userRepository.save(user);

        auditService.record(saved, UserAuditAction.REVOKE_PERMISSIONS,
                null, null, removed, auth, request,
                "Revoked permissions: " + removed);
        return toDto(saved);
    }

    // ─── Activate / deactivate ──────────────────────────────────────────────

    public UserAdminDTO setActivated(Long userId, boolean activate,
                                     Authentication auth, HttpServletRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found: id=" + userId));

        if (Boolean.valueOf(activate).equals(user.getIsActivated())) return toDto(user);

        if (!activate) guardNotSelf(user, auth, "Admin cannot deactivate their own account");

        user.setIsActivated(activate);
        user.setUpdatedAt(Instant.now());
        User saved = userRepository.save(user);

        auditService.record(saved,
                activate ? UserAuditAction.ACTIVATE : UserAuditAction.DEACTIVATE,
                null, null, null, auth, request,
                activate ? "Activated user account" : "Deactivated user account");
        return toDto(saved);
    }

    // ─── Lock / unlock ──────────────────────────────────────────────────────

    public UserAdminDTO lock(Long userId, Authentication auth, HttpServletRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found: id=" + userId));
        guardNotSelf(user, auth, "Admin cannot lock their own account");
        if (Boolean.TRUE.equals(user.getIsLocked())) return toDto(user);

        user.setIsLocked(true);
        user.setLockTime(Instant.now());
        user.setUpdatedAt(Instant.now());
        User saved = userRepository.save(user);

        auditService.record(saved, UserAuditAction.UPDATE, null, null, null,
                auth, request, "Locked user account");
        return toDto(saved);
    }

    public UserAdminDTO unlock(Long userId, Authentication auth, HttpServletRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found: id=" + userId));
        boolean wasLocked = Boolean.TRUE.equals(user.getIsLocked());
        boolean hadFailures = user.getFailedAttempts() > 0;
        if (!wasLocked && !hadFailures) return toDto(user);

        user.setIsLocked(false);
        user.setLockTime(null);
        user.setFailedAttempts(0);
        user.setUpdatedAt(Instant.now());
        User saved = userRepository.save(user);

        auditService.record(saved, UserAuditAction.UPDATE, null, null, null,
                auth, request, "Unlocked user account (cleared lock + failed attempts)");
        return toDto(saved);
    }

    /** Clear failed-login counter without changing lock state. */
    public UserAdminDTO resetFailedAttempts(Long userId, Authentication auth, HttpServletRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found: id=" + userId));
        if (user.getFailedAttempts() == 0) return toDto(user);

        int previous = user.getFailedAttempts();
        user.setFailedAttempts(0);
        user.setUpdatedAt(Instant.now());
        User saved = userRepository.save(user);

        auditService.record(saved, UserAuditAction.UPDATE, null, null, null,
                auth, request, "Reset failed-login counter (was=" + previous + ")");
        return toDto(saved);
    }

    /** Sign the user out of every device by deactivating each Session row.
     *  Existing JWTs become useless because the session-id check fails. */
    public UserAdminDTO forceLogoutAll(Long userId, Authentication auth, HttpServletRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found: id=" + userId));
        guardNotSelf(user, auth, "Admin cannot force-logout their own account from this endpoint; use POST /api/auth/logout-all");

        java.util.List<Session> sessions = sessionRepository.findByUser(user);
        int revoked = 0;
        if (sessions != null) {
            Instant now = Instant.now();
            for (Session s : sessions) {
                if (Boolean.TRUE.equals(s.getIsActive())) {
                    s.setIsActive(false);
                    s.setLogoutTimestamp(now);
                    revoked++;
                }
            }
            if (revoked > 0) sessionRepository.saveAll(sessions);
        }

        auditService.record(user, UserAuditAction.UPDATE, null, null, null,
                auth, request, "Force-logout: revoked " + revoked + " active session(s)");
        return toDto(user);
    }

    // ─── Catalog ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<RoleCatalogDTO> listRoles() {
        return Arrays.stream(Role.values())
                .map(r -> RoleCatalogDTO.builder()
                        .name(r.name())
                        .authorities(r.getAuthorities().stream()
                                .map(GrantedAuthority::getAuthority)
                                .collect(Collectors.toCollection(TreeSet::new)))
                        .build())
                .toList();
    }

    @Transactional(readOnly = true)
    public Set<String> listPermissions() {
        return KNOWN_PERMISSIONS;
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    private Role parseRole(String roleName) {
        if (roleName == null || roleName.isBlank()) {
            throw new IllegalArgumentException("Role name is required");
        }
        try {
            return Role.valueOf(roleName.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown role: '" + roleName
                    + "'. Allowed: " + Stream.of(Role.values()).map(Enum::name).toList());
        }
    }

    private Set<String> sanitiseAndValidate(Set<String> permissions) {
        if (permissions == null || permissions.isEmpty()) {
            throw new IllegalArgumentException("permissions list cannot be empty");
        }
        Set<String> out = new TreeSet<>();
        Set<String> unknown = new TreeSet<>();
        for (String p : permissions) {
            if (p == null) continue;
            String norm = p.trim().toLowerCase(Locale.ROOT);
            if (norm.isEmpty()) continue;
            if (KNOWN_PERMISSIONS.contains(norm)) out.add(norm);
            else unknown.add(p);
        }
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(
                    "Unknown permission(s): " + unknown
                            + ". Use GET /api/admin/permissions for the catalog.");
        }
        return out;
    }

    private void guardNotSelf(User target, Authentication auth, String message) {
        if (auth == null) return;
        Object principal = auth.getPrincipal();
        if (principal instanceof User actor && actor.getUserId() != null
                && actor.getUserId().equals(target.getUserId())) {
            throw new IllegalArgumentException(message);
        }
    }

    private void guardNotSelfDemotion(User target, Authentication auth, Role oldRole, Role newRole) {
        if (oldRole == Role.ADMIN && newRole != Role.ADMIN) {
            guardNotSelf(target, auth, "Admin cannot demote their own account");
        }
    }

    private void guardNotSelfRevokingUserMgmt(User target, Authentication auth, Set<String> removed) {
        boolean removingUserMgmt = removed.stream().anyMatch(p -> p.startsWith("user:"));
        if (removingUserMgmt) {
            guardNotSelf(target, auth, "Admin cannot revoke their own user-management permissions");
        }
    }

    private UserAdminDTO toDto(User user) {
        Set<String> effective = user.getAuthorities() == null ? Set.of()
                : user.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .collect(Collectors.toCollection(TreeSet::new));
        Set<String> extra = user.getExtraPermissions() == null
                ? Set.of() : new TreeSet<>(user.getExtraPermissions());
        return UserAdminDTO.builder()
                .userId(user.getUserId())
                .username(user.getUsername())
                .name(user.getName())
                .email(user.getEmail())
                .profileImage(user.getProfileImage())
                .role(user.getRole() == null ? null : user.getRole().name())
                .isActivated(user.getIsActivated())
                .isLocked(user.getIsLocked())
                .lockTime(user.getLockTime())
                .failedAttempts(user.getFailedAttempts())
                .extraPermissions(extra)
                .effectiveAuthorities(effective)
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
    }
}
