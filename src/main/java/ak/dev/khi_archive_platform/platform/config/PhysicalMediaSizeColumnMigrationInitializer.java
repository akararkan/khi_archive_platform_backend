package ak.dev.khi_archive_platform.platform.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * One-shot data migration for the physical-media "size" rename/repurpose.
 *
 * <p>Two columns changed meaning on the entity:
 * <ul>
 *   <li>The legacy {@code size} column (material size — big / medium / normal /
 *       small) was renamed to {@code physical_size}. Under
 *       {@code ddl-auto=update} Hibernate creates the new {@code physical_size}
 *       column but never copies data across, so existing rows would appear to
 *       lose their size. This initializer backfills {@code physical_size} from
 *       the legacy {@code size} column once.</li>
 *   <li>The legacy {@code sub_type} column was retired and its role re-used as
 *       {@code size_gb} (digital file size in gigabytes). Old sub-type text is
 *       intentionally <b>not</b> carried over — {@code size_gb} starts empty.
 *       The orphaned {@code sub_type} column is left in place (harmless); drop
 *       it by hand once you're happy:
 *       {@code ALTER TABLE physical_media DROP COLUMN sub_type;}</li>
 * </ul>
 *
 * <p>Runs on {@link ApplicationReadyEvent} — after Hibernate has already added
 * the new {@code physical_size} column — and is idempotent: the backfill only
 * touches rows whose {@code physical_size} is still null, so re-running on every
 * startup is a cheap no-op after the first pass. Guarded by information_schema
 * checks so it does nothing once the legacy {@code size} column is dropped.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PhysicalMediaSizeColumnMigrationInitializer {

    private final JdbcTemplate jdbcTemplate;

    @EventListener(ApplicationReadyEvent.class)
    public void migrate() {
        try {
            if (!columnExists("size") || !columnExists("physical_size")) {
                return; // fresh DB, or the legacy column has already been cleaned up
            }
            int updated = jdbcTemplate.update(
                    "UPDATE physical_media SET physical_size = size " +
                    "WHERE physical_size IS NULL AND size IS NOT NULL");
            if (updated > 0) {
                log.info("Backfilled physical_media.physical_size from legacy 'size' column for {} row(s)", updated);
            }
        } catch (Exception e) {
            log.warn("Could not backfill physical_media.physical_size from legacy 'size' column: {}", e.getMessage());
        }
    }

    private boolean columnExists(String column) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns " +
                "WHERE table_name = 'physical_media' AND column_name = ?",
                Integer.class, column);
        return count != null && count > 0;
    }
}
