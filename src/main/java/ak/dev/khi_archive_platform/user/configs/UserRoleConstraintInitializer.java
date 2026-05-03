package ak.dev.khi_archive_platform.user.configs;

import ak.dev.khi_archive_platform.user.enums.Role;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Keeps the CHECK constraint on {@code users_tbl.role} in sync with the
 * {@link Role} enum.
 *
 * Hibernate emits a {@code CHECK (role IN ('A','B',...))} when it first
 * creates the column. With {@code ddl-auto=update} Hibernate never refreshes
 * that constraint when enum values are added or removed — so an admin trying
 * to set a newer value (e.g. {@code GUEST}) fails with
 * {@code violates check constraint "users_tbl_role_check"} even though the
 * Java side accepts it. On every boot we drop any CHECK on the role column
 * and recreate it from the live enum values.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserRoleConstraintInitializer {

    private final JdbcTemplate jdbcTemplate;

    @EventListener(ApplicationReadyEvent.class)
    public void resync() {
        try {
            List<String> existing = jdbcTemplate.queryForList(
                    "SELECT con.conname " +
                    "FROM pg_constraint con " +
                    "JOIN pg_class c ON c.oid = con.conrelid " +
                    "JOIN pg_attribute a " +
                    "  ON a.attrelid = c.oid AND a.attnum = ANY(con.conkey) " +
                    "WHERE c.relname = 'users_tbl' " +
                    "  AND con.contype = 'c' " +
                    "  AND a.attname = 'role'",
                    String.class);

            for (String name : existing) {
                jdbcTemplate.execute(
                        "ALTER TABLE users_tbl DROP CONSTRAINT IF EXISTS \"" + name + "\"");
            }

            String values = Stream.of(Role.values())
                    .map(r -> "'" + r.name() + "'")
                    .collect(Collectors.joining(","));
            jdbcTemplate.execute(
                    "ALTER TABLE users_tbl ADD CONSTRAINT users_tbl_role_check " +
                    "CHECK (role IN (" + values + "))");

            log.info("users_tbl_role_check re-synced with Role enum: {}", values);
        } catch (Exception e) {
            log.warn("Could not re-sync users_tbl_role_check: {}", e.getMessage());
        }
    }
}
