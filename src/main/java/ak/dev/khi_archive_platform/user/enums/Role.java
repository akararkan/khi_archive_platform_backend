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

    /**
     * Maqam-team specialist. Sees the List-of-Maqam records prepared by
     * employees/admins, streams the audio, and submits a maqam-type vote +
     * note on each record they are assigned to. Cannot edit the upstream
     * song metadata (song name / producer / audio file / archive note) and
     * cannot modify another teacher's vote. Seeded with
     * {@link #TEACHER_DEFAULT_PERMISSIONS} into extraPermissions on first
     * promotion so admins can curate further.
     */
    TEACHER(Set.of()),

    /** Full power: every Permission (resource + user CRUD) and the ROLE_ADMIN tag. */
    ADMIN(EnumSet.allOf(Permission.class));

    /**
     * READ + CREATE + UPDATE for every resource (no REMOVE, no DELETE).
     * Seeded into a user's extraPermissions the first time they become an
     * EMPLOYEE — once seeded these are normal per-user grants and the admin
     * can grant/revoke any of them through the standard endpoints.
     *
     * <p>Note: {@code MAQAM_CREATE}/{@code MAQAM_UPDATE} are included so
     * employees can prepare List-of-Maqam song records (id, song name,
     * producer, audio file, archive note) the same way they prepare audio.
     * {@code MAQAM_TEACHER_MANAGE} is also seeded so employees can pick the
     * 1–3 teachers on a record they've prepared (admins keep the same power
     * via the ADMIN role).
     *
     * <p>{@code PHYSICAL_MEDIA_READ/CREATE/UPDATE/IMPORT} are seeded so
     * employees can fill and edit the physical-media inventory (the
     * {@code physical_media} table populated from {@code All Final Archive
     * Lists.xlsx}) and run the bulk Excel import themselves. REMOVE/DELETE
     * stay admin-only — soft-trash + purge of an inventory row is a
     * destructive action that touches records other teammates may own.
     */
    public static final Set<String> EMPLOYEE_DEFAULT_PERMISSIONS = Set.of(
            Permission.AUDIO_READ.getPermission(),    Permission.AUDIO_CREATE.getPermission(),    Permission.AUDIO_UPDATE.getPermission(),
            Permission.VIDEO_READ.getPermission(),    Permission.VIDEO_CREATE.getPermission(),    Permission.VIDEO_UPDATE.getPermission(),
            Permission.IMAGE_READ.getPermission(),    Permission.IMAGE_CREATE.getPermission(),    Permission.IMAGE_UPDATE.getPermission(),
            Permission.TEXT_READ.getPermission(),     Permission.TEXT_CREATE.getPermission(),     Permission.TEXT_UPDATE.getPermission(),
            Permission.CATEGORY_READ.getPermission(), Permission.CATEGORY_CREATE.getPermission(), Permission.CATEGORY_UPDATE.getPermission(),
            Permission.PERSON_READ.getPermission(),   Permission.PERSON_CREATE.getPermission(),   Permission.PERSON_UPDATE.getPermission(),
            Permission.PROJECT_READ.getPermission(),  Permission.PROJECT_CREATE.getPermission(),  Permission.PROJECT_UPDATE.getPermission(),
            Permission.MAQAM_READ.getPermission(),    Permission.MAQAM_CREATE.getPermission(),    Permission.MAQAM_UPDATE.getPermission(),
            Permission.MAQAM_TEACHER_MANAGE.getPermission(),
            Permission.PHYSICAL_MEDIA_READ.getPermission(),
            Permission.PHYSICAL_MEDIA_CREATE.getPermission(),
            Permission.PHYSICAL_MEDIA_UPDATE.getPermission(),
            Permission.PHYSICAL_MEDIA_IMPORT.getPermission()
    );

    /**
     * Read List-of-Maqam records + cast votes. Seeded into a fresh TEACHER
     * the first time they're promoted to the role. Admins may revoke or
     * extend per-user via the standard grants endpoint. Intentionally
     * narrow — teachers see only what they need to vote on.
     */
    public static final Set<String> TEACHER_DEFAULT_PERMISSIONS = Set.of(
            Permission.MAQAM_READ.getPermission(),
            Permission.MAQAM_VOTE.getPermission()
    );

    private final Set<Permission> permissions;

    /**
     * The seed set of per-user permissions for a given role. ADMIN gets its
     * authorities through the role itself, so this returns empty for ADMIN.
     */
    public static Set<String> defaultExtraPermissions(Role role) {
        if (role == EMPLOYEE) return EMPLOYEE_DEFAULT_PERMISSIONS;
        if (role == TEACHER)  return TEACHER_DEFAULT_PERMISSIONS;
        return Set.of();
    }

    public List<SimpleGrantedAuthority> getAuthorities() {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        permissions.forEach(p -> authorities.add(new SimpleGrantedAuthority(p.getPermission())));
        authorities.add(new SimpleGrantedAuthority("ROLE_" + this.name()));
        return authorities;
    }
}
