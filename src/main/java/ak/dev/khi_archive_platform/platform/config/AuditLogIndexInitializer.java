package ak.dev.khi_archive_platform.platform.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Ensures the indexes that the analytics queries rely on are present.
 *
 * <p>The analytics service runs a UNION ALL across all seven {@code
 * *_audit_logs} tables and aggregates by user / day / entity. Without these
 * indexes Postgres falls back to seq-scanning each table, which is fine at
 * a few hundred rows but turns into hundred-millisecond responses once the
 * audit log grows. With them, even a year-of-activity report stays in the
 * single-digit-millisecond range.
 *
 * <p>Indexes per table:
 * <ul>
 *   <li>{@code (actor_username, occurred_at DESC)} — per-user windowed
 *       scans (the {@code /api/analytics/me} and {@code /users/{name}} paths).</li>
 *   <li>{@code (occurred_at DESC)} — team-wide windowed scans
 *       (the {@code /overview} and {@code /users} paths).</li>
 *   <li>{@code (action, occurred_at DESC)} — accelerates the {@code FILTER
 *       (WHERE action = …)} aggregations.</li>
 * </ul>
 *
 * <p>All statements use {@code IF NOT EXISTS} so this runs idempotently on
 * every boot.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditLogIndexInitializer {

    private static final List<String> TABLES = List.of(
            "audio_audit_logs",
            "video_audit_logs",
            "image_audit_logs",
            "text_audit_logs",
            "project_audit_logs",
            "category_audit_logs",
            "person_audit_logs",
            "analytics_audit_logs"
    );

    private final JdbcTemplate jdbcTemplate;

    @EventListener(ApplicationReadyEvent.class)
    public void ensureIndexes() {
        for (String table : TABLES) {
            createIndex(
                    "idx_" + table + "_actor_occurred",
                    "ON " + table + " (actor_username, occurred_at DESC)");
            createIndex(
                    "idx_" + table + "_occurred",
                    "ON " + table + " (occurred_at DESC)");
            createIndex(
                    "idx_" + table + "_action_occurred",
                    "ON " + table + " (action, occurred_at DESC)");
        }
        log.info("Audit-log analytics indexes ensured on {} tables", TABLES.size());
    }

    private void createIndex(String name, String tail) {
        try {
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS " + name + " " + tail);
        } catch (Exception e) {
            // Table may not exist yet on first boot before Hibernate creates it.
            log.warn("Skipped index {}: {}", name, e.getMessage());
        }
    }
}
