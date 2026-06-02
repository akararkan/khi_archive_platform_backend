package ak.dev.khi_archive_platform.user.configs;

import ak.dev.khi_archive_platform.user.enums.Permission;
import ak.dev.khi_archive_platform.user.enums.Role;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * One-shot backfill so existing EMPLOYEE users gain {@code maqam:teacher_manage}
 * — newly seeded at the role level so employees can pick the teacher panel on
 * records they prepare. Seeding only fires on user creation / role transition,
 * so this initializer grants the perm to every EMPLOYEE row that doesn't yet
 * have it. {@code ON CONFLICT DO NOTHING} keeps it idempotent across boots.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmployeeMaqamTeacherManageBackfillInitializer {

    private final JdbcTemplate jdbcTemplate;

    @EventListener(ApplicationReadyEvent.class)
    public void backfill() {
        try {
            int inserted = jdbcTemplate.update(
                    "INSERT INTO user_permissions (user_id, permission) " +
                    "SELECT u.user_id, ? FROM users_tbl u " +
                    "WHERE u.role = ? " +
                    "ON CONFLICT ON CONSTRAINT uk_user_permissions_user_perm DO NOTHING",
                    Permission.MAQAM_TEACHER_MANAGE.getPermission(),
                    Role.EMPLOYEE.name());
            if (inserted > 0) {
                log.info("Backfilled maqam:teacher_manage to {} EMPLOYEE user(s)", inserted);
            }
        } catch (Exception e) {
            log.warn("Could not backfill maqam:teacher_manage for EMPLOYEEs: {}", e.getMessage());
        }
    }
}
