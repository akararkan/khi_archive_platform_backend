package ak.dev.khi_archive_platform.user.service;

import ak.dev.khi_archive_platform.user.dto.UserCreateRequestDTO;
import ak.dev.khi_archive_platform.user.dto.UserUpdateRequestDTO;
import ak.dev.khi_archive_platform.user.dto.admin.PermissionsChangeRequestDTO;
import ak.dev.khi_archive_platform.user.dto.admin.RoleCatalogDTO;
import ak.dev.khi_archive_platform.user.dto.admin.RoleChangeRequestDTO;
import ak.dev.khi_archive_platform.user.dto.admin.UserAdminDTO;
import ak.dev.khi_archive_platform.user.enums.Permission;
import ak.dev.khi_archive_platform.user.enums.Role;
import ak.dev.khi_archive_platform.user.enums.UserAuditAction;
import ak.dev.khi_archive_platform.user.exceptions.IllegalAdminOperationException;
import ak.dev.khi_archive_platform.user.exceptions.UnknownPermissionException;
import ak.dev.khi_archive_platform.user.exceptions.UserAlreadyExistsException;
import ak.dev.khi_archive_platform.user.exceptions.UserNotFoundException;
import ak.dev.khi_archive_platform.user.model.Session;
import ak.dev.khi_archive_platform.user.model.User;
import ak.dev.khi_archive_platform.user.repo.SessionRepository;
import ak.dev.khi_archive_platform.user.repo.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    /** Default password expiry window for admin-created or admin-reset accounts.
     *  Mirrors the value used in {@code UserService} for the self-registration flow. */
    private static final Duration PASSWORD_EXPIRY = Duration.ofDays(90);

    private final UserRepository userRepository;
    private final SessionRepository sessionRepository;
    private final UserAuditService auditService;
    private final PasswordEncoder passwordEncoder;
    private final UserValidator userValidator;

    // ─── Read ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<UserAdminDTO> listAll(Authentication auth, HttpServletRequest request) {
        // Listing is intentionally NOT audited — every admin opens this on
        // page load and the noise drowns out genuine state changes. Only
        // record-level reads (getById) and mutations get an audit row.
        return userRepository.findAll().stream().map(this::toDto).toList();
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
        // Seed role defaults for first-time EMPLOYEE promotions; no-op if the
        // user already has any extras (admin's curated set is preserved).
        user.applyRoleDefaults();
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

        // ADMINs already hold every permission via the role; granting extras is
        // meaningless and would mask a future ADMIN -> EMPLOYEE demotion (the
        // extra grants would survive). Block it explicitly.
        if (user.getRole() == Role.ADMIN) {
            throw new IllegalAdminOperationException(
                    "ADMIN_PERMISSIONS_LOCKED",
                    "Cannot grant extra permissions to an ADMIN — admins already hold every permission. "
                            + "Demote the user to EMPLOYEE first if you want a smaller permission set.");
        }

        Set<String> requested = sanitiseAndValidate(dto.getPermissions());
        Set<String> existing = user.getExtraPermissions() == null
                ? new HashSet<>() : new HashSet<>(user.getExtraPermissions());
        Set<String> added = new TreeSet<>(requested);
        added.removeAll(existing);
        if (added.isEmpty()) return toDto(user);

        existing.addAll(added);
        user.setExtraPermissions(existing);

        // Auto-promote GUEST -> EMPLOYEE so the granted permissions actually
        // resolve on the user's authority set: a GUEST otherwise has no
        // baseline authorities, and a single granted permission would be the
        // user's only privilege. Bumping to EMPLOYEE matches the intent of
        // "this user is now a working account, not a placeholder".
        Role oldRole = user.getRole();
        boolean autoPromoted = false;
        if (oldRole == Role.GUEST) {
            user.setRole(Role.EMPLOYEE);
            autoPromoted = true;
        }

        user.setUpdatedAt(Instant.now());
        User saved = userRepository.save(user);

        auditService.record(saved, UserAuditAction.GRANT_PERMISSIONS,
                null, null, added, auth, request,
                "Granted permissions: " + added
                        + (autoPromoted ? " (auto-promoted GUEST -> EMPLOYEE)" : ""));
        if (autoPromoted) {
            auditService.record(saved, UserAuditAction.ROLE_CHANGE,
                    oldRole.name(), Role.EMPLOYEE.name(), null, auth, request,
                    "Auto-promoted from GUEST to EMPLOYEE on permission grant");
        }
        return toDto(saved);
    }

    public UserAdminDTO revokePermissions(Long userId, PermissionsChangeRequestDTO dto,
                                          Authentication auth, HttpServletRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found: id=" + userId));

        // ADMIN authorities flow from the role, not from the per-user extras
        // table. Revoking from extras would silently succeed without removing
        // anything — confusing. Block it and steer the admin to the role
        // change endpoint instead.
        if (user.getRole() == Role.ADMIN) {
            throw new IllegalAdminOperationException(
                    "ADMIN_PERMISSIONS_LOCKED",
                    "Cannot revoke permissions from an ADMIN — every permission comes from the role, "
                            + "not from the per-user grants table. Change the role to EMPLOYEE first.");
        }

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

        if (!activate) guardNotSelf(user, auth, "SELF_DEACTIVATE",
                "You cannot deactivate your own account. Ask another admin to do it.");

        user.setIsActivated(activate);
        user.setUpdatedAt(Instant.now());
        User saved = userRepository.save(user);

        auditService.record(saved,
                activate ? UserAuditAction.ACTIVATE : UserAuditAction.DEACTIVATE,
                null, null, null, auth, request,
                (activate ? "Activated" : "Deactivated") + " user '" + saved.getUsername()
                        + "' (id=" + saved.getUserId() + ")"
                        + " isActivated: " + (!activate) + " -> " + activate);
        return toDto(saved);
    }

    // ─── Lock / unlock ──────────────────────────────────────────────────────

    public UserAdminDTO lock(Long userId, Authentication auth, HttpServletRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found: id=" + userId));
        guardNotSelf(user, auth, "SELF_LOCK",
                "You cannot lock your own account. Ask another admin to do it.");
        if (Boolean.TRUE.equals(user.getIsLocked())) return toDto(user);

        user.setIsLocked(true);
        user.setLockTime(Instant.now());
        user.setUpdatedAt(Instant.now());
        User saved = userRepository.save(user);

        auditService.record(saved, UserAuditAction.UPDATE, null, null, null,
                auth, request,
                "Locked user '" + saved.getUsername() + "' (id=" + saved.getUserId() + ")"
                        + " isLocked: false -> true; lockTime=" + saved.getLockTime());
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
                auth, request,
                "Unlocked user '" + saved.getUsername() + "' (id=" + saved.getUserId() + ")"
                        + " isLocked: " + wasLocked + " -> false; failedAttempts cleared (was hadFailures=" + hadFailures + ")");
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
        guardNotSelf(user, auth, "SELF_FORCE_LOGOUT",
                "You cannot force-logout your own account here. Use POST /api/auth/logout-all instead.");

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

    // ─── Create / Update ────────────────────────────────────────────────────

    /**
     * Admin-driven user creation. Mirrors the validation that the self-register
     * flow performs (email normalisation + uniqueness, password rules, username
     * uniqueness). Seeds role-default permissions via {@link User#applyRoleDefaults()}
     * so a freshly-created EMPLOYEE starts with the standard editable set.
     *
     * <p>Audits one {@link UserAuditAction#CREATE} row whose {@code details}
     * column captures: target username + id, role assigned, activated flag, and
     * the seeded permission set.
     */
    public UserAdminDTO createUserAsAdmin(UserCreateRequestDTO dto,
                                          Authentication auth,
                                          HttpServletRequest request) {
        // MX check only when admin is provisioning a GUEST; corporate EMPLOYEE/ADMIN
        // emails bypass the DNS gate.
        Role targetRole = dto.getRole() != null ? dto.getRole() : Role.GUEST;
        String email = userValidator.validateAndNormalizeEmail(dto.getEmail(), targetRole);
        userValidator.validatePassword(dto.getPassword(), dto.getUsername(), email, dto.getName());

        if (userRepository.findByUsername(dto.getUsername()).isPresent()) {
            throw new UserAlreadyExistsException("Username is already taken.");
        }
        if (userRepository.findByEmail(email).isPresent()) {
            throw new UserAlreadyExistsException("Email is already registered.");
        }

        Instant now = Instant.now();
        Role role = dto.getRole() != null ? dto.getRole() : Role.GUEST;
        boolean activated = dto.getIsActivated() == null || dto.getIsActivated();

        User toCreate = User.builder()
                .name(dto.getName())
                .username(dto.getUsername())
                .email(email)
                .password(passwordEncoder.encode(dto.getPassword()))
                .role(role)
                .provider("local")
                .isActivated(activated)
                .createdAt(now)
                .updatedAt(now)
                .failedAttempts(0)
                .isLocked(false)
                .passwordExpiryDate(now.plus(PASSWORD_EXPIRY))
                .build();
        toCreate.applyRoleDefaults();
        User saved = userRepository.save(toCreate);

        Set<String> seeded = saved.getExtraPermissions() == null
                ? Set.of() : new TreeSet<>(saved.getExtraPermissions());
        StringBuilder details = new StringBuilder()
                .append("Created user '").append(saved.getUsername())
                .append("' (id=").append(saved.getUserId()).append(")")
                .append(" name='").append(saved.getName()).append('\'')
                .append(" email=").append(saved.getEmail())
                .append(" role=").append(role.name())
                .append(" activated=").append(activated);
        if (!seeded.isEmpty()) details.append(" seededPermissions=").append(seeded);

        auditService.record(saved, UserAuditAction.CREATE,
                null, role.name(), seeded, auth, request, details.toString());
        return toDto(saved);
    }

    /**
     * Admin-driven update of arbitrary user fields (name, username, email,
     * password, role, activation). For role transitions the same self-demotion
     * guard as {@link #changeRole} applies; for deactivation the same
     * self-protection as {@link #setActivated} applies.
     *
     * <p>Audits one row per call (skipping the no-op case). The {@code details}
     * column is a structured per-field diff so an admin or auditor can read
     * exactly what changed: {@code "Updated user fields. name='Old' -> 'New';
     * email=old@a -> new@b; role=GUEST -> EMPLOYEE; password=(reset)"}.
     * Password values are never recorded — only the fact of a reset.
     */
    public UserAdminDTO updateUserAsAdmin(Long userId, UserUpdateRequestDTO dto,
                                          Authentication auth, HttpServletRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found: id=" + userId));

        Map<String, String> diff = new LinkedHashMap<>();
        Role oldRole = user.getRole();
        Role newRole = oldRole;

        if (dto.getName() != null && !dto.getName().equals(user.getName())) {
            diff.put("name", "'" + nullSafe(user.getName()) + "' -> '" + dto.getName() + "'");
            user.setName(dto.getName());
        }

        if (dto.getUsername() != null && !dto.getUsername().equals(user.getUsername())) {
            if (userRepository.findByUsername(dto.getUsername()).isPresent()) {
                throw new UserAlreadyExistsException("Username is already taken.");
            }
            diff.put("username", user.getUsername() + " -> " + dto.getUsername());
            user.setUsername(dto.getUsername());
        }

        if (dto.getEmail() != null) {
            // MX check only when the target user is (or is becoming) a GUEST.
            Role targetRole = dto.getRole() != null ? dto.getRole() : user.getRole();
            String normalised = userValidator.validateAndNormalizeEmail(dto.getEmail(), targetRole);
            if (!normalised.equals(user.getEmail())) {
                if (userRepository.findByEmail(normalised).isPresent()) {
                    throw new UserAlreadyExistsException("Email is already registered.");
                }
                diff.put("email", user.getEmail() + " -> " + normalised);
                user.setEmail(normalised);
            }
        }

        if (dto.getPassword() != null && !dto.getPassword().isBlank()) {
            userValidator.validatePassword(dto.getPassword(),
                    user.getUsername(), user.getEmail(), user.getName());
            user.setPassword(passwordEncoder.encode(dto.getPassword()));
            user.setPasswordExpiryDate(Instant.now().plus(PASSWORD_EXPIRY));
            diff.put("password", "(reset by admin)");
        }

        if (dto.getIsActivated() != null && !dto.getIsActivated().equals(user.getIsActivated())) {
            if (Boolean.FALSE.equals(dto.getIsActivated())) {
                guardNotSelf(user, auth, "SELF_DEACTIVATE",
                        "You cannot deactivate your own account. Ask another admin to do it.");
            }
            diff.put("isActivated", user.getIsActivated() + " -> " + dto.getIsActivated());
            user.setIsActivated(dto.getIsActivated());
        }

        if (dto.getRole() != null && dto.getRole() != oldRole) {
            guardNotSelfDemotion(user, auth, oldRole, dto.getRole());
            newRole = dto.getRole();
            diff.put("role", oldRole + " -> " + newRole);
            user.setRole(newRole);
            user.applyRoleDefaults();
            Set<String> seeded = user.getExtraPermissions() == null
                    ? Set.of() : new TreeSet<>(user.getExtraPermissions());
            // Seed visibility: when promotion to EMPLOYEE seeded the defaults,
            // surface that in the diff so the audit row is self-describing.
            if (!seeded.isEmpty()
                    && newRole == Role.EMPLOYEE
                    && oldRole != Role.EMPLOYEE) {
                diff.put("seededPermissions", seeded.toString());
            }
        }

        if (diff.isEmpty()) return toDto(user);

        user.setUpdatedAt(Instant.now());
        User saved = userRepository.save(user);

        String details = "Updated user fields. " + diff.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("; "));

        auditService.record(saved, UserAuditAction.UPDATE,
                oldRole == newRole ? null : oldRole.name(),
                oldRole == newRole ? null : newRole.name(),
                null, auth, request, details);
        return toDto(saved);
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    // ─── Delete ─────────────────────────────────────────────────────────────

    /**
     * Hard-delete a user. Drops the user's active sessions first so the FK on
     * {@code sessions.user_id} doesn't block the delete, then writes a single
     * {@link UserAuditAction#DELETE} row capturing identity, then removes the
     * user. The audit row uses {@code REQUIRES_NEW} so it survives even if
     * the delete itself rolls back.
     *
     * <p>Self-protection:
     * <ul>
     *   <li>An admin cannot delete their own account.</li>
     *   <li>The last remaining ADMIN cannot be deleted — would lock the
     *       system out of every {@code /api/admin} endpoint.</li>
     * </ul>
     */
    public void deleteUser(Long userId, Authentication auth, HttpServletRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found: id=" + userId));

        guardNotSelf(user, auth, "SELF_DELETE",
                "You cannot delete your own account. Ask another admin to do it.");

        if (user.getRole() == Role.ADMIN) {
            long admins = userRepository.countByRole(Role.ADMIN);
            if (admins <= 1) {
                throw new IllegalAdminOperationException(
                        "LAST_ADMIN",
                        "Cannot delete the only remaining ADMIN. Promote another user to ADMIN first.");
            }
        }

        java.util.List<Session> sessions = sessionRepository.findByUser(user);
        if (sessions != null && !sessions.isEmpty()) {
            sessionRepository.deleteAll(sessions);
        }

        auditService.record(user, UserAuditAction.DELETE,
                user.getRole() == null ? null : user.getRole().name(), null,
                user.getExtraPermissions(), auth, request,
                "Deleted user '" + user.getUsername() + "' (id=" + user.getUserId() + ")");

        userRepository.delete(user);
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
        if (!unknown.isEmpty()) throw new UnknownPermissionException(unknown);
        return out;
    }

    private void guardNotSelf(User target, Authentication auth, String code, String message) {
        if (auth == null) return;
        Object principal = auth.getPrincipal();
        if (principal instanceof User actor && actor.getUserId() != null
                && actor.getUserId().equals(target.getUserId())) {
            throw new IllegalAdminOperationException(code, message);
        }
    }

    private void guardNotSelfDemotion(User target, Authentication auth, Role oldRole, Role newRole) {
        if (oldRole == Role.ADMIN && newRole != Role.ADMIN) {
            guardNotSelf(target, auth, "SELF_DEMOTION",
                    "You cannot demote your own ADMIN account. Ask another admin to do it.");
        }
    }

    private void guardNotSelfRevokingUserMgmt(User target, Authentication auth, Set<String> removed) {
        boolean removingUserMgmt = removed.stream().anyMatch(p -> p.startsWith("user:"));
        if (removingUserMgmt) {
            guardNotSelf(target, auth, "SELF_USER_MGMT_REVOKE",
                    "You cannot revoke your own user-management permissions. "
                            + "Ask another admin to do it.");
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
