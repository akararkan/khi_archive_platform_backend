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
public class PersonSearchIndexInitializer {

    private final JdbcTemplate jdbcTemplate;

    @EventListener(ApplicationReadyEvent.class)
    public void ensureSearchIndexes() {
        // pg_trgm is created by CategorySearchIndexInitializer too — CREATE EXTENSION
        // IF NOT EXISTS is idempotent, so calling it again is harmless.
        try {
            jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");

            // Primary name fields — used for similarity ranking, so must be indexed.
            jdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS idx_person_full_name_lower_trgm " +
                            "ON person USING GIN (LOWER(full_name) gin_trgm_ops)"
            );
            jdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS idx_person_nickname_lower_trgm " +
                            "ON person USING GIN (LOWER(nickname) gin_trgm_ops)"
            );
            jdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS idx_person_romanized_name_lower_trgm " +
                            "ON person USING GIN (LOWER(romanized_name) gin_trgm_ops)"
            );

            // Secondary substring fields — index speeds up the LIKE legs of the query.
            jdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS idx_person_description_lower_trgm " +
                            "ON person USING GIN (LOWER(description) gin_trgm_ops)"
            );
            jdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS idx_person_tag_lower_trgm " +
                            "ON person USING GIN (LOWER(tag) gin_trgm_ops)"
            );
            jdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS idx_person_keywords_lower_trgm " +
                            "ON person USING GIN (LOWER(keywords) gin_trgm_ops)"
            );
            jdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS idx_person_region_lower_trgm " +
                            "ON person USING GIN (LOWER(region) gin_trgm_ops)"
            );
            jdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS idx_person_place_of_birth_lower_trgm " +
                            "ON person USING GIN (LOWER(place_of_birth) gin_trgm_ops)"
            );
            jdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS idx_person_place_of_death_lower_trgm " +
                            "ON person USING GIN (LOWER(place_of_death) gin_trgm_ops)"
            );

            // person_type ElementCollection lives in a side table.
            jdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS idx_person_person_type_lower_trgm " +
                            "ON person_person_type USING GIN (LOWER(person_type) gin_trgm_ops)"
            );

            log.info("Person search indexes ensured (pg_trgm GIN on names, places, tags, keywords, types)");
        } catch (Exception e) {
            log.warn("Failed to ensure person search indexes: {}", e.getMessage());
        }

        // Same trap as the category audit log: Hibernate-generated CHECK constraint
        // on the action column doesn't get updated when a new enum value is added.
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE person_audit_logs DROP CONSTRAINT IF EXISTS person_audit_logs_action_check"
            );
            log.info("Dropped stale person_audit_logs_action_check constraint (Java enum still enforces values)");
        } catch (Exception e) {
            log.warn("Failed to drop person_audit_logs_action_check: {}", e.getMessage());
        }
    }
}
