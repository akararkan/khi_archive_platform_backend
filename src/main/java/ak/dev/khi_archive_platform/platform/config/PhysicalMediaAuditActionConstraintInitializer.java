package ak.dev.khi_archive_platform.platform.config;

import ak.dev.khi_archive_platform.platform.enums.PhysicalMediaAuditAction;
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
 * Keeps the CHECK constraint on {@code physical_media_audit_logs.action} in
 * sync with {@link PhysicalMediaAuditAction}. Same boot-time resync trick we
 * use for the maqam audit-action and the user role: Hibernate writes the
 * CHECK once on column create and never refreshes it under
 * {@code ddl-auto=update}, so newly added enum values would otherwise fail.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PhysicalMediaAuditActionConstraintInitializer {

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
                    "WHERE c.relname = 'physical_media_audit_logs' " +
                    "  AND con.contype = 'c' " +
                    "  AND a.attname = 'action'",
                    String.class);
            for (String name : existing) {
                jdbcTemplate.execute(
                        "ALTER TABLE physical_media_audit_logs DROP CONSTRAINT IF EXISTS \"" + name + "\"");
            }
            String values = Stream.of(PhysicalMediaAuditAction.values())
                    .map(a -> "'" + a.name() + "'")
                    .collect(Collectors.joining(","));
            jdbcTemplate.execute(
                    "ALTER TABLE physical_media_audit_logs ADD CONSTRAINT physical_media_audit_logs_action_check " +
                    "CHECK (action IN (" + values + "))");
            log.info("physical_media_audit_logs_action_check re-synced with PhysicalMediaAuditAction enum: {}", values);
        } catch (Exception e) {
            log.warn("Could not re-sync physical_media_audit_logs_action_check: {}", e.getMessage());
        }
    }
}
