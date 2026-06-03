package ak.dev.khi_archive_platform.platform.config;

import ak.dev.khi_archive_platform.platform.enums.DigitizationStatus;
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
 * Keeps the CHECK constraint on {@code physical_media.digitization} aligned
 * with {@link DigitizationStatus}. Same pattern as the audit-action
 * initializers — Hibernate writes the CHECK once when it first creates the
 * column and never updates it under {@code ddl-auto=update}, so adding a
 * new status would otherwise break inserts.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PhysicalMediaDigitizationConstraintInitializer {

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
                    "WHERE c.relname = 'physical_media' " +
                    "  AND con.contype = 'c' " +
                    "  AND a.attname = 'digitization'",
                    String.class);
            for (String name : existing) {
                jdbcTemplate.execute(
                        "ALTER TABLE physical_media DROP CONSTRAINT IF EXISTS \"" + name + "\"");
            }
            String values = Stream.of(DigitizationStatus.values())
                    .map(s -> "'" + s.name() + "'")
                    .collect(Collectors.joining(","));
            jdbcTemplate.execute(
                    "ALTER TABLE physical_media ADD CONSTRAINT physical_media_digitization_check " +
                    "CHECK (digitization IS NULL OR digitization IN (" + values + "))");
            log.info("physical_media_digitization_check re-synced with DigitizationStatus enum: {}", values);
        } catch (Exception e) {
            log.warn("Could not re-sync physical_media_digitization_check: {}", e.getMessage());
        }
    }
}
