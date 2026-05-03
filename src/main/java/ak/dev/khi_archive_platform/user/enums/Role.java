package ak.dev.khi_archive_platform.user.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Application roles.
 * <ul>
 *   <li>{@code GUEST} — placeholder. No authorities. Reserved for future
 *       read-only public-facing endpoints.</li>
 *   <li>{@code EMPLOYEE} — day-to-day archivist. The role itself carries no
 *       baseline authorities; instead, when a user is first made an
 *       EMPLOYEE the {@link #EMPLOYEE_DEFAULT_PERMISSIONS} set is seeded
 *       into their {@code extraPermissions}. The admin can then grant or
 *       revoke any of those per user — the permission set is fully editable.
 *       Note: REMOVE is intentionally excluded (only ADMIN may soft-remove).</li>
 *   <li>{@code ADMIN} — full control: every resource permission and every
 *       user-account permission, including hard delete. The permission set
 *       is locked (cannot be edited via the per-user grants endpoint).</li>
 * </ul>
 */
@Getter
@RequiredArgsConstructor
public enum Role {

    GUEST(Set.of()),

    /** Empty role-baseline. New EMPLOYEEs are seeded with
     *  {@link #EMPLOYEE_DEFAULT_PERMISSIONS} into their extraPermissions
     *  so admins can revoke any of them later. */
    EMPLOYEE(Set.of()),

    /** Full power: every Permission (resource + user CRUD) and the ROLE_ADMIN tag. */
    ADMIN(EnumSet.allOf(Permission.class));

    /**
     * READ + CREATE + UPDATE for every resource (no REMOVE, no DELETE).
     * Seeded into a user's extraPermissions the first time they become an
     * EMPLOYEE — once seeded these are normal per-user grants and the admin
     * can grant/revoke any of them through the standard endpoints.
     */
    public static final Set<String> EMPLOYEE_DEFAULT_PERMISSIONS = Set.of(
            Permission.AUDIO_READ.getPermission(),    Permission.AUDIO_CREATE.getPermission(),    Permission.AUDIO_UPDATE.getPermission(),
            Permission.VIDEO_READ.getPermission(),    Permission.VIDEO_CREATE.getPermission(),    Permission.VIDEO_UPDATE.getPermission(),
            Permission.IMAGE_READ.getPermission(),    Permission.IMAGE_CREATE.getPermission(),    Permission.IMAGE_UPDATE.getPermission(),
            Permission.TEXT_READ.getPermission(),     Permission.TEXT_CREATE.getPermission(),     Permission.TEXT_UPDATE.getPermission(),
            Permission.CATEGORY_READ.getPermission(), Permission.CATEGORY_CREATE.getPermission(), Permission.CATEGORY_UPDATE.getPermission(),
            Permission.PERSON_READ.getPermission(),   Permission.PERSON_CREATE.getPermission(),   Permission.PERSON_UPDATE.getPermission(),
            Permission.PROJECT_READ.getPermission(),  Permission.PROJECT_CREATE.getPermission(),  Permission.PROJECT_UPDATE.getPermission()
    );

    private final Set<Permission> permissions;

    /**
     * The seed set of per-user permissions for a given role. ADMIN gets its
     * authorities through the role itself, so this returns empty for ADMIN.
     */
    public static Set<String> defaultExtraPermissions(Role role) {
        return role == EMPLOYEE ? EMPLOYEE_DEFAULT_PERMISSIONS : Set.of();
    }

    public List<SimpleGrantedAuthority> getAuthorities() {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        permissions.forEach(p -> authorities.add(new SimpleGrantedAuthority(p.getPermission())));
        authorities.add(new SimpleGrantedAuthority("ROLE_" + this.name()));
        return authorities;
    }
}
