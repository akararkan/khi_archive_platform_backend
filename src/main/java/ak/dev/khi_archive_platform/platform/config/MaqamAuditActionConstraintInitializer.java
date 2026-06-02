package ak.dev.khi_archive_platform.platform.config;

import ak.dev.khi_archive_platform.platform.enums.MaqamAuditAction;
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
 * Keeps the CHECK constraint on {@code maqam_audit_logs.action} aligned with
 * the live {@link MaqamAuditAction} enum across every boot. Same story as
 * {@code AnalyticsAuditActionConstraintInitializer}: Hibernate writes the
 * CHECK once on column create and never refreshes it under
 * {@code ddl-auto=update}, so adding values (e.g. {@code LISTEN_PROGRESS})
 * would otherwise break inserts.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MaqamAuditActionConstraintInitializer {

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
                    "WHERE c.relname = 'maqam_audit_logs' " +
                    "  AND con.contype = 'c' " +
                    "  AND a.attname = 'action'",
                    String.class);

            for (String name : existing) {
                jdbcTemplate.execute(
                        "ALTER TABLE maqam_audit_logs DROP CONSTRAINT IF EXISTS \"" + name + "\"");
            }

            String values = Stream.of(MaqamAuditAction.values())
                    .map(a -> "'" + a.name() + "'")
                    .collect(Collectors.joining(","));
            jdbcTemplate.execute(
                    "ALTER TABLE maqam_audit_logs ADD CONSTRAINT maqam_audit_logs_action_check " +
                    "CHECK (action IN (" + values + "))");

            log.info("maqam_audit_logs_action_check re-synced with MaqamAuditAction enum: {}", values);
        } catch (Exception e) {
            log.warn("Could not re-sync maqam_audit_logs_action_check: {}", e.getMessage());
        }
    }
}
