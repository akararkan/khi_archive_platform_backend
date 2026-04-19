package ak.dev.khi_archive_platform.user.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static ak.dev.khi_archive_platform.user.enums.Permission.*;

@Getter
@RequiredArgsConstructor
public enum Role {

    // ✅ NEW: Default for self-registered users
    GUEST(Set.of(
            USER_READ
    )),

    EMPLOYEE(Set.of(
            USER_CREATE,
            USER_READ,
            USER_UPDATE
    )),

    ADMIN(Set.of(
            USER_CREATE,
            USER_READ,
            USER_UPDATE,
            USER_DELETE
    )),

    SUPER_ADMIN(Set.of(
            USER_CREATE,
            USER_READ,
            USER_UPDATE,
            USER_DELETE
    ));

    private final Set<Permission> permissions;

    public List<SimpleGrantedAuthority> getAuthorities() {
        var authorities = permissions.stream()
                .map(p -> new SimpleGrantedAuthority(p.getPermission()))
                .collect(Collectors.toList());

        authorities.add(new SimpleGrantedAuthority("ROLE_" + this.name()));
        return authorities;
    }
}
