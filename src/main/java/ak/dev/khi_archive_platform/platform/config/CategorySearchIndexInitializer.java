package ak.dev.khi_archive_platform.platform.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CategorySearchIndexInitializer {

    private final JdbcTemplate jdbcTemplate;

    @EventListener(ApplicationReadyEvent.class)
    public void ensureSearchIndexes() {
        try {
            jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");
            jdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS idx_categories_name_lower_trgm " +
                            "ON categories USING GIN (LOWER(name) gin_trgm_ops)"
            );
            jdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS idx_categories_description_lower_trgm " +
                            "ON categories USING GIN (LOWER(description) gin_trgm_ops)"
            );
            jdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS idx_category_keywords_lower_trgm " +
                            "ON category_keywords USING GIN (LOWER(keyword) gin_trgm_ops)"
            );
            log.info("Category search indexes ensured (pg_trgm GIN on name, description, keywords)");
        } catch (Exception e) {
            log.warn("Failed to ensure category search indexes: {}", e.getMessage());
        }

        // Hibernate auto-generates a CHECK constraint from @Enumerated(STRING) on
        // create, but never updates it under ddl-auto=update. Dropping it lets new
        // enum values (e.g. SEARCH) be inserted; Java enum binding still enforces
        // valid values at the application layer.
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE category_audit_logs DROP CONSTRAINT IF EXISTS category_audit_logs_action_check"
            );
            log.info("Dropped stale category_audit_logs_action_check constraint (Java enum still enforces values)");
        } catch (Exception e) {
            log.warn("Failed to drop category_audit_logs_action_check: {}", e.getMessage());
        }
    }
}
