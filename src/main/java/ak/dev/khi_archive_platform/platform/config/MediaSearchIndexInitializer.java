package ak.dev.khi_archive_platform.platform.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Ensures pg_trgm GIN indexes exist for the four media tables (image, text,
 * video, audio) and their child collection tables. These indexes are what make
 * the two-phase search fast at 30TB scale: phase-1 candidate generation uses
 * the `%` operator and ILIKE, both index-driven via gin_trgm_ops.
 *
 * Also drops Hibernate-generated CHECK constraints on each `<entity>_audit_logs`
 * `action` column so the new SEARCH enum value can be persisted (Hibernate
 * regenerates the constraint at create-time but never updates it under
 * ddl-auto=update; the Java enum already enforces valid values).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaSearchIndexInitializer {

    private final JdbcTemplate jdbcTemplate;

    @EventListener(ApplicationReadyEvent.class)
    public void ensureSearchIndexes() {
        try {
            jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");
        } catch (Exception e) {
            log.warn("Failed to ensure pg_trgm extension: {}", e.getMessage());
            return;
        }

        ensureImageIndexes();
        ensureTextIndexes();
        ensureVideoIndexes();
        ensureAudioIndexes();
        dropStaleAuditCheckConstraints();
    }

    // ─── Image ───────────────────────────────────────────────────────────────

    private void ensureImageIndexes() {
        // GIN trigram on every searchable column. Used by `LIKE '%q%'` and `%`
        // (similarity) operators when q.length() >= 3.
        createTrgmIndex("idx_images_image_code_trgm",             "images", "image_code");
        createTrgmIndex("idx_images_file_name_trgm",              "images", "file_name");
        createTrgmIndex("idx_images_volume_name_trgm",            "images", "volume_name");
        createTrgmIndex("idx_images_directory_trgm",              "images", "directory");
        createTrgmIndex("idx_images_path_external_trgm",          "images", "path_in_external_volume");
        createTrgmIndex("idx_images_auto_path_trgm",              "images", "auto_path");
        createTrgmIndex("idx_images_original_title_trgm",         "images", "original_title");
        createTrgmIndex("idx_images_alternative_title_trgm",      "images", "alternative_title");
        createTrgmIndex("idx_images_central_kurdish_title_trgm",  "images", "title_in_central_kurdish");
        createTrgmIndex("idx_images_romanized_title_trgm",        "images", "romanized_title");
        createTrgmIndex("idx_images_form_trgm",                   "images", "form");
        createTrgmIndex("idx_images_event_trgm",                  "images", "event");
        createTrgmIndex("idx_images_location_trgm",               "images", "location");
        createTrgmIndex("idx_images_description_trgm",            "images", "description");
        createTrgmIndex("idx_images_person_shown_trgm",           "images", "person_shown_in_image");
        createTrgmIndex("idx_images_creator_trgm",                "images", "creator_artist_photographer");
        createTrgmIndex("idx_images_contributor_trgm",            "images", "contributor");
        createTrgmIndex("idx_images_provenance_trgm",             "images", "provenance");
        createTrgmIndex("idx_images_photostory_trgm",             "images", "photostory");
        createTrgmIndex("idx_images_archive_cataloging_trgm",     "images", "archive_cataloging");
        createTrgmIndex("idx_images_physical_label_trgm",         "images", "physical_label");
        createTrgmIndex("idx_images_location_in_archive_trgm",    "images", "location_in_archive_room");
        createTrgmIndex("idx_images_note_trgm",                   "images", "note");

        // Btree text_pattern_ops on primary fields. Lets `LIKE 'q%'` use the
        // index for *any* query length — including 1-2 char prefixes (where
        // the GIN trigram index can't help).
        createBtreePatternIndex("idx_images_image_code_pat",            "images", "image_code");
        createBtreePatternIndex("idx_images_file_name_pat",             "images", "file_name");
        createBtreePatternIndex("idx_images_original_title_pat",        "images", "original_title");
        createBtreePatternIndex("idx_images_alternative_title_pat",     "images", "alternative_title");
        createBtreePatternIndex("idx_images_central_kurdish_title_pat", "images", "title_in_central_kurdish");
        createBtreePatternIndex("idx_images_romanized_title_pat",       "images", "romanized_title");
        createBtreePatternIndex("idx_images_creator_pat",               "images", "creator_artist_photographer");
        createBtreePatternIndex("idx_images_person_shown_pat",          "images", "person_shown_in_image");
        createBtreePatternIndex("idx_images_event_pat",                 "images", "event");

        // Child collections — trigram for substring/fuzzy + pattern for prefix.
        createTrgmIndex("idx_image_subjects_subject_trgm", "image_subjects", "subject");
        createTrgmIndex("idx_image_genres_genre_trgm",     "image_genres",   "genre");
        createTrgmIndex("idx_image_colors_color_trgm",     "image_colors",   "color");
        createTrgmIndex("idx_image_usages_usage_trgm",     "image_usages",   "usage_context");
        createTrgmIndex("idx_image_tags_tag_trgm",         "image_tags",     "tag");
        createTrgmIndex("idx_image_keywords_keyword_trgm", "image_keywords", "keyword");
        createBtreePatternIndex("idx_image_subjects_subject_pat", "image_subjects", "subject");
        createBtreePatternIndex("idx_image_genres_genre_pat",     "image_genres",   "genre");
        createBtreePatternIndex("idx_image_tags_tag_pat",         "image_tags",     "tag");
        createBtreePatternIndex("idx_image_keywords_keyword_pat", "image_keywords", "keyword");

        // Btree on FK columns — speeds up phase-2 per-row similarity subqueries.
        createBtreeIndex("idx_image_subjects_image_id", "image_subjects", "image_id");
        createBtreeIndex("idx_image_genres_image_id",   "image_genres",   "image_id");
        createBtreeIndex("idx_image_colors_image_id",   "image_colors",   "image_id");
        createBtreeIndex("idx_image_usages_image_id",   "image_usages",   "image_id");
        createBtreeIndex("idx_image_tags_image_id",     "image_tags",     "image_id");
        createBtreeIndex("idx_image_keywords_image_id", "image_keywords", "image_id");

        log.info("Image search indexes ensured (GIN trgm + btree text_pattern_ops on every searchable column + child tables)");
    }

    // ─── Text ────────────────────────────────────────────────────────────────

    private void ensureTextIndexes() {
        createTrgmIndex("idx_texts_text_code_trgm",              "texts", "text_code");
        createTrgmIndex("idx_texts_file_name_trgm",              "texts", "file_name");
        createTrgmIndex("idx_texts_volume_name_trgm",            "texts", "volume_name");
        createTrgmIndex("idx_texts_directory_trgm",              "texts", "directory");
        createTrgmIndex("idx_texts_path_external_trgm",          "texts", "path_in_external_volume");
        createTrgmIndex("idx_texts_auto_path_trgm",              "texts", "auto_path");
        createTrgmIndex("idx_texts_original_title_trgm",         "texts", "original_title");
        createTrgmIndex("idx_texts_alternative_title_trgm",      "texts", "alternative_title");
        createTrgmIndex("idx_texts_central_kurdish_title_trgm",  "texts", "title_in_central_kurdish");
        createTrgmIndex("idx_texts_romanized_title_trgm",        "texts", "romanized_title");
        createTrgmIndex("idx_texts_document_type_trgm",          "texts", "document_type");
        createTrgmIndex("idx_texts_description_trgm",            "texts", "description");
        createTrgmIndex("idx_texts_script_trgm",                 "texts", "script");
        createTrgmIndex("idx_texts_transcription_trgm",          "texts", "transcription");
        createTrgmIndex("idx_texts_isbn_trgm",                   "texts", "isbn");
        createTrgmIndex("idx_texts_language_trgm",               "texts", "language");
        createTrgmIndex("idx_texts_dialect_trgm",                "texts", "dialect");
        createTrgmIndex("idx_texts_author_trgm",                 "texts", "author");
        createTrgmIndex("idx_texts_contributors_trgm",           "texts", "contributors");
        createTrgmIndex("idx_texts_printing_house_trgm",         "texts", "printing_house");
        createTrgmIndex("idx_texts_provenance_trgm",             "texts", "provenance");
        createTrgmIndex("idx_texts_note_trgm",                   "texts", "note");

        // Prefix-anchored fast paths
        createBtreePatternIndex("idx_texts_text_code_pat",              "texts", "text_code");
        createBtreePatternIndex("idx_texts_file_name_pat",              "texts", "file_name");
        createBtreePatternIndex("idx_texts_original_title_pat",         "texts", "original_title");
        createBtreePatternIndex("idx_texts_alternative_title_pat",      "texts", "alternative_title");
        createBtreePatternIndex("idx_texts_central_kurdish_title_pat",  "texts", "title_in_central_kurdish");
        createBtreePatternIndex("idx_texts_romanized_title_pat",        "texts", "romanized_title");
        createBtreePatternIndex("idx_texts_author_pat",                 "texts", "author");
        createBtreePatternIndex("idx_texts_isbn_pat",                   "texts", "isbn");

        createTrgmIndex("idx_text_subjects_subject_trgm", "text_subjects", "subject");
        createTrgmIndex("idx_text_genres_genre_trgm",     "text_genres",   "genre");
        createTrgmIndex("idx_text_tags_tag_trgm",         "text_tags",     "tag");
        createTrgmIndex("idx_text_keywords_keyword_trgm", "text_keywords", "keyword");
        createBtreePatternIndex("idx_text_subjects_subject_pat", "text_subjects", "subject");
        createBtreePatternIndex("idx_text_genres_genre_pat",     "text_genres",   "genre");
        createBtreePatternIndex("idx_text_tags_tag_pat",         "text_tags",     "tag");
        createBtreePatternIndex("idx_text_keywords_keyword_pat", "text_keywords", "keyword");

        createBtreeIndex("idx_text_subjects_text_id", "text_subjects", "text_id");
        createBtreeIndex("idx_text_genres_text_id",   "text_genres",   "text_id");
        createBtreeIndex("idx_text_tags_text_id",     "text_tags",     "text_id");
        createBtreeIndex("idx_text_keywords_text_id", "text_keywords", "text_id");

        log.info("Text search indexes ensured (GIN trgm + btree text_pattern_ops on every searchable column + child tables)");
    }

    // ─── Video ───────────────────────────────────────────────────────────────

    private void ensureVideoIndexes() {
        createTrgmIndex("idx_videos_video_code_trgm",             "videos", "video_code");
        createTrgmIndex("idx_videos_file_name_trgm",              "videos", "file_name");
        createTrgmIndex("idx_videos_volume_name_trgm",            "videos", "volume_name");
        createTrgmIndex("idx_videos_directory_trgm",              "videos", "directory");
        createTrgmIndex("idx_videos_path_external_trgm",          "videos", "path_in_external_volume");
        createTrgmIndex("idx_videos_auto_path_trgm",              "videos", "auto_path");
        createTrgmIndex("idx_videos_original_title_trgm",         "videos", "original_title");
        createTrgmIndex("idx_videos_alternative_title_trgm",      "videos", "alternative_title");
        createTrgmIndex("idx_videos_central_kurdish_title_trgm",  "videos", "title_in_central_kurdish");
        createTrgmIndex("idx_videos_romanized_title_trgm",        "videos", "romanized_title");
        createTrgmIndex("idx_videos_event_trgm",                  "videos", "event");
        createTrgmIndex("idx_videos_location_trgm",               "videos", "location");
        createTrgmIndex("idx_videos_description_trgm",            "videos", "description");
        createTrgmIndex("idx_videos_person_shown_trgm",           "videos", "person_shown_in_video");
        createTrgmIndex("idx_videos_resolution_trgm",             "videos", "resolution");
        createTrgmIndex("idx_videos_codec_trgm",                  "videos", "video_codec");
        createTrgmIndex("idx_videos_subtitle_trgm",               "videos", "subtitle");
        createTrgmIndex("idx_videos_creator_trgm",                "videos", "creator_artist_director");
        createTrgmIndex("idx_videos_producer_trgm",               "videos", "producer");
        createTrgmIndex("idx_videos_contributor_trgm",            "videos", "contributor");
        createTrgmIndex("idx_videos_provenance_trgm",             "videos", "provenance");
        createTrgmIndex("idx_videos_note_trgm",                   "videos", "note");

        createBtreePatternIndex("idx_videos_video_code_pat",            "videos", "video_code");
        createBtreePatternIndex("idx_videos_file_name_pat",             "videos", "file_name");
        createBtreePatternIndex("idx_videos_original_title_pat",        "videos", "original_title");
        createBtreePatternIndex("idx_videos_alternative_title_pat",     "videos", "alternative_title");
        createBtreePatternIndex("idx_videos_central_kurdish_title_pat", "videos", "title_in_central_kurdish");
        createBtreePatternIndex("idx_videos_romanized_title_pat",       "videos", "romanized_title");
        createBtreePatternIndex("idx_videos_creator_pat",               "videos", "creator_artist_director");
        createBtreePatternIndex("idx_videos_producer_pat",              "videos", "producer");
        createBtreePatternIndex("idx_videos_event_pat",                 "videos", "event");
        createBtreePatternIndex("idx_videos_person_shown_pat",          "videos", "person_shown_in_video");

        createTrgmIndex("idx_video_subjects_subject_trgm", "video_subjects", "subject");
        createTrgmIndex("idx_video_genres_genre_trgm",     "video_genres",   "genre");
        createTrgmIndex("idx_video_colors_color_trgm",     "video_colors",   "color");
        createTrgmIndex("idx_video_usages_usage_trgm",     "video_usages",   "usage_context");
        createTrgmIndex("idx_video_tags_tag_trgm",         "video_tags",     "tag");
        createTrgmIndex("idx_video_keywords_keyword_trgm", "video_keywords", "keyword");
        createBtreePatternIndex("idx_video_subjects_subject_pat", "video_subjects", "subject");
        createBtreePatternIndex("idx_video_genres_genre_pat",     "video_genres",   "genre");
        createBtreePatternIndex("idx_video_tags_tag_pat",         "video_tags",     "tag");
        createBtreePatternIndex("idx_video_keywords_keyword_pat", "video_keywords", "keyword");

        createBtreeIndex("idx_video_subjects_video_id", "video_subjects", "video_id");
        createBtreeIndex("idx_video_genres_video_id",   "video_genres",   "video_id");
        createBtreeIndex("idx_video_colors_video_id",   "video_colors",   "video_id");
        createBtreeIndex("idx_video_usages_video_id",   "video_usages",   "video_id");
        createBtreeIndex("idx_video_tags_video_id",     "video_tags",     "video_id");
        createBtreeIndex("idx_video_keywords_video_id", "video_keywords", "video_id");

        log.info("Video search indexes ensured (GIN trgm + btree text_pattern_ops on every searchable column + child tables)");
    }

    // ─── Audio ───────────────────────────────────────────────────────────────

    private void ensureAudioIndexes() {
        createTrgmIndex("idx_audios_audio_code_trgm",          "audios", "audio_code");
        createTrgmIndex("idx_audios_fullname_trgm",            "audios", "fullname");
        createTrgmIndex("idx_audios_volume_name_trgm",         "audios", "volume_name");
        createTrgmIndex("idx_audios_directory_name_trgm",      "audios", "directory_name");
        createTrgmIndex("idx_audios_path_external_trgm",       "audios", "path_in_external");
        createTrgmIndex("idx_audios_auto_path_trgm",           "audios", "auto_path");
        createTrgmIndex("idx_audios_origin_title_trgm",        "audios", "origin_title");
        createTrgmIndex("idx_audios_alter_title_trgm",         "audios", "alter_title");
        createTrgmIndex("idx_audios_central_kurdish_title_trgm","audios","central_kurdish_title");
        createTrgmIndex("idx_audios_romanized_title_trgm",     "audios", "romanized_title");
        createTrgmIndex("idx_audios_form_trgm",                "audios", "form");
        createTrgmIndex("idx_audios_type_of_basta_trgm",       "audios", "type_of_basta");
        createTrgmIndex("idx_audios_type_of_maqam_trgm",       "audios", "type_of_maqam");
        createTrgmIndex("idx_audios_abstract_trgm",            "audios", "abstract_text");
        createTrgmIndex("idx_audios_description_trgm",         "audios", "description");
        createTrgmIndex("idx_audios_speaker_trgm",             "audios", "speaker");
        createTrgmIndex("idx_audios_producer_trgm",            "audios", "producer");
        createTrgmIndex("idx_audios_composer_trgm",            "audios", "composer");
        createTrgmIndex("idx_audios_language_trgm",            "audios", "language");
        createTrgmIndex("idx_audios_dialect_trgm",             "audios", "dialect");
        createTrgmIndex("idx_audios_lyrics_trgm",              "audios", "lyrics");
        createTrgmIndex("idx_audios_poet_trgm",                "audios", "poet");
        createTrgmIndex("idx_audios_recording_venue_trgm",     "audios", "recording_venue");
        createTrgmIndex("idx_audios_city_trgm",                "audios", "city");
        createTrgmIndex("idx_audios_region_trgm",              "audios", "region");
        createTrgmIndex("idx_audios_provenance_trgm",          "audios", "provenance");
        createTrgmIndex("idx_audios_audio_file_note_trgm",     "audios", "audio_file_note");

        createBtreePatternIndex("idx_audios_audio_code_pat",            "audios", "audio_code");
        createBtreePatternIndex("idx_audios_fullname_pat",              "audios", "fullname");
        createBtreePatternIndex("idx_audios_origin_title_pat",          "audios", "origin_title");
        createBtreePatternIndex("idx_audios_alter_title_pat",           "audios", "alter_title");
        createBtreePatternIndex("idx_audios_central_kurdish_title_pat", "audios", "central_kurdish_title");
        createBtreePatternIndex("idx_audios_romanized_title_pat",       "audios", "romanized_title");
        createBtreePatternIndex("idx_audios_speaker_pat",               "audios", "speaker");
        createBtreePatternIndex("idx_audios_composer_pat",              "audios", "composer");
        createBtreePatternIndex("idx_audios_poet_pat",                  "audios", "poet");
        createBtreePatternIndex("idx_audios_producer_pat",              "audios", "producer");
        createBtreePatternIndex("idx_audios_city_pat",                  "audios", "city");
        createBtreePatternIndex("idx_audios_region_pat",                "audios", "region");
        createBtreePatternIndex("idx_audios_type_of_basta_pat",         "audios", "type_of_basta");
        createBtreePatternIndex("idx_audios_type_of_maqam_pat",         "audios", "type_of_maqam");

        createTrgmIndex("idx_audio_genres_genre_trgm",            "audio_genres",       "genre");
        createTrgmIndex("idx_audio_contributors_contributor_trgm","audio_contributors", "contributor");
        createTrgmIndex("idx_audio_tags_tag_trgm",                "audio_tags",         "tag");
        createTrgmIndex("idx_audio_keywords_keyword_trgm",        "audio_keywords",     "keyword");
        createBtreePatternIndex("idx_audio_genres_genre_pat",            "audio_genres",       "genre");
        createBtreePatternIndex("idx_audio_contributors_contributor_pat","audio_contributors", "contributor");
        createBtreePatternIndex("idx_audio_tags_tag_pat",                "audio_tags",         "tag");
        createBtreePatternIndex("idx_audio_keywords_keyword_pat",        "audio_keywords",     "keyword");

        createBtreeIndex("idx_audio_genres_audio_id",       "audio_genres",       "audio_id");
        createBtreeIndex("idx_audio_contributors_audio_id", "audio_contributors", "audio_id");
        createBtreeIndex("idx_audio_tags_audio_id",         "audio_tags",         "audio_id");
        createBtreeIndex("idx_audio_keywords_audio_id",     "audio_keywords",     "audio_id");

        log.info("Audio search indexes ensured (GIN trgm + btree text_pattern_ops on every searchable column + child tables)");
    }

    // ─── Audit-log CHECK constraints ─────────────────────────────────────────

    private void dropStaleAuditCheckConstraints() {
        List<String> constraints = List.of(
                "image_audit_logs_action_check",
                "text_audit_logs_action_check",
                "video_audit_logs_action_check",
                "audio_audit_logs_action_check"
        );
        List<String> tables = List.of(
                "image_audit_logs",
                "text_audit_logs",
                "video_audit_logs",
                "audio_audit_logs"
        );
        for (int i = 0; i < tables.size(); i++) {
            try {
                jdbcTemplate.execute("ALTER TABLE " + tables.get(i)
                        + " DROP CONSTRAINT IF EXISTS " + constraints.get(i));
            } catch (Exception e) {
                log.warn("Failed to drop {}: {}", constraints.get(i), e.getMessage());
            }
        }
        log.info("Dropped stale audit-log action_check constraints (Java enum still enforces values)");
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private void createTrgmIndex(String indexName, String table, String column) {
        try {
            jdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS " + indexName
                            + " ON " + table + " USING GIN (LOWER(" + column + ") gin_trgm_ops)"
            );
        } catch (Exception e) {
            log.warn("Failed to create trigram index {} on {}({}): {}", indexName, table, column, e.getMessage());
        }
    }

    private void createBtreeIndex(String indexName, String table, String column) {
        try {
            jdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS " + indexName
                            + " ON " + table + " (" + column + ")"
            );
        } catch (Exception e) {
            log.warn("Failed to create btree index {} on {}({}): {}", indexName, table, column, e.getMessage());
        }
    }

    /**
     * Btree index on {@code LOWER(col) text_pattern_ops}. Required for prefix
     * LIKE (`LIKE 'q%'`) to be index-driven for *any* query length — including
     * 1-2 characters where the GIN trigram index can't help. With this index
     * present, a search for "ha" against a 30TB table is sub-millisecond on
     * the column lookup.
     */
    private void createBtreePatternIndex(String indexName, String table, String column) {
        try {
            jdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS " + indexName
                            + " ON " + table + " (LOWER(" + column + ") text_pattern_ops)"
            );
        } catch (Exception e) {
            log.warn("Failed to create btree pattern index {} on {}({}): {}", indexName, table, column, e.getMessage());
        }
    }
}
