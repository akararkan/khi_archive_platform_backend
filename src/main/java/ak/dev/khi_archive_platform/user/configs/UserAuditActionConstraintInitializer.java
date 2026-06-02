package ak.dev.khi_archive_platform.user.configs;

import ak.dev.khi_archive_platform.user.enums.UserAuditAction;
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
 * Keeps the CHECK constraint on {@code user_audit_logs.action} in sync with
 * the {@link UserAuditAction} enum. Same story as
 * {@link UserRoleConstraintInitializer}: Hibernate emits the CHECK once on
 * column creation and never refreshes it under {@code ddl-auto=update}, so
 * adding values like {@code WARNING_SENT} breaks inserts with
 * {@code violates check constraint "user_audit_logs_action_check"}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserAuditActionConstraintInitializer {

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
                    "WHERE c.relname = 'user_audit_logs' " +
                    "  AND con.contype = 'c' " +
                    "  AND a.attname = 'action'",
                    String.class);

            for (String name : existing) {
                jdbcTemplate.execute(
                        "ALTER TABLE user_audit_logs DROP CONSTRAINT IF EXISTS \"" + name + "\"");
            }

            String values = Stream.of(UserAuditAction.values())
                    .map(a -> "'" + a.name() + "'")
                    .collect(Collectors.joining(","));
            jdbcTemplate.execute(
                    "ALTER TABLE user_audit_logs ADD CONSTRAINT user_audit_logs_action_check " +
                    "CHECK (action IN (" + values + "))");

            log.info("user_audit_logs_action_check re-synced with UserAuditAction enum: {}", values);
        } catch (Exception e) {
            log.warn("Could not re-sync user_audit_logs_action_check: {}", e.getMessage());
        }
    }
}
