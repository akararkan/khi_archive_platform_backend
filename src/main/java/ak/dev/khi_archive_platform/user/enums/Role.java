package ak.dev.khi_archive_platform.user.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static ak.dev.khi_archive_platform.user.enums.Permission.*;
import static ak.dev.khi_archive_platform.user.enums.UserPermission.*;

/**
 * Application roles.
 *
 * GUEST    — placeholder. No authorities yet. Reserved for future
 *            read-only public-facing endpoints.
 * EMPLOYEE — day-to-day archivist. Can read / create / update / soft-remove
 *            every application resource. Cannot hard-delete and cannot
 *            manage user accounts.
 * ADMIN    — full control: every resource permission AND every user-account
 *            permission, including hard delete.
 */
@Getter
@RequiredArgsConstructor
public enum Role {

    GUEST(
            Set.of(),
            Set.of()
    ),

    EMPLOYEE(
            EnumSet.of(
                    AUDIO_READ,    AUDIO_CREATE,    AUDIO_UPDATE,    AUDIO_REMOVE,
                    VIDEO_READ,    VIDEO_CREATE,    VIDEO_UPDATE,    VIDEO_REMOVE,
                    IMAGE_READ,    IMAGE_CREATE,    IMAGE_UPDATE,    IMAGE_REMOVE,
                    TEXT_READ,     TEXT_CREATE,     TEXT_UPDATE,     TEXT_REMOVE,
                    CATEGORY_READ, CATEGORY_CREATE, CATEGORY_UPDATE, CATEGORY_REMOVE,
                    PERSON_READ,   PERSON_CREATE,   PERSON_UPDATE,   PERSON_REMOVE,
                    PROJECT_READ,  PROJECT_CREATE,  PROJECT_UPDATE,  PROJECT_REMOVE
            ),
            Set.of()
    ),

    ADMIN(
            EnumSet.allOf(Permission.class),
            EnumSet.allOf(UserPermission.class)
    );

    private final Set<Permission> permissions;
    private final Set<UserPermission> userPermissions;

    public List<SimpleGrantedAuthority> getAuthorities() {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        permissions.forEach(p -> authorities.add(new SimpleGrantedAuthority(p.getPermission())));
        userPermissions.forEach(p -> authorities.add(new SimpleGrantedAuthority(p.getPermission())));
        authorities.add(new SimpleGrantedAuthority("ROLE_" + this.name()));
        return authorities;
    }
}
