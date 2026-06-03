package ak.dev.khi_archive_platform.user.configs;

import ak.dev.khi_archive_platform.user.enums.Permission;
import ak.dev.khi_archive_platform.user.enums.Role;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * One-shot backfill so existing EMPLOYEE rows pick up the four physical-media
 * authorities now seeded into {@link Role#EMPLOYEE_DEFAULT_PERMISSIONS}.
 * Role-default seeding only runs on creation / role-transition, so already-
 * provisioned employees would otherwise need an admin to grant each
 * permission by hand. {@code ON CONFLICT DO NOTHING} keeps the initializer
 * idempotent — re-runs touch zero rows.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmployeePhysicalMediaPermissionBackfillInitializer {

    private static final List<Permission> NEW_PERMS = List.of(
            Permission.PHYSICAL_MEDIA_READ,
            Permission.PHYSICAL_MEDIA_CREATE,
            Permission.PHYSICAL_MEDIA_UPDATE,
            Permission.PHYSICAL_MEDIA_IMPORT
    );

    private final JdbcTemplate jdbcTemplate;

    @EventListener(ApplicationReadyEvent.class)
    public void backfill() {
        int total = 0;
        for (Permission perm : NEW_PERMS) {
            try {
                int inserted = jdbcTemplate.update(
                        "INSERT INTO user_permissions (user_id, permission) " +
                        "SELECT u.user_id, ? FROM users_tbl u " +
                        "WHERE u.role = ? " +
                        "ON CONFLICT ON CONSTRAINT uk_user_permissions_user_perm DO NOTHING",
                        perm.getPermission(),
                        Role.EMPLOYEE.name());
                if (inserted > 0) {
                    log.info("Backfilled {} to {} EMPLOYEE user(s)", perm.getPermission(), inserted);
                    total += inserted;
                }
            } catch (Exception e) {
                log.warn("Could not backfill {} for EMPLOYEEs: {}", perm.getPermission(), e.getMessage());
            }
        }
        if (total == 0) {
            log.debug("EMPLOYEE physical-media permission backfill: nothing to insert");
        }
    }
}
