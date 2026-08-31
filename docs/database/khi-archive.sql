-- ============================================================================================
--  KHI ARCHIVE PLATFORM — SQL
-- ============================================================================================
--
--  Every SQL statement the documentation refers to, in one file. PostgreSQL only: several
--  sections use pg_trgm, information_schema and pg_catalog features with no portable equivalent.
--
--  Nothing here is illustrative pseudo-SQL. Each statement was either lifted from a JdbcTemplate
--  call in the Java source or written against the real column names in the @Entity classes. Each
--  section names the initializer bean or repository class it came from.
--
--  This file is the CODE. The prose that explains it lives in docs/internal/database/ — start
--  there if you want to understand a table before you query it.
--
--  --------------------------------------------------------------------------------------------
--  HOW TO USE THIS FILE
--  --------------------------------------------------------------------------------------------
--
--  Do NOT run it top to bottom, and do NOT \i the whole thing. It is a reference, not a migration
--  script: sections 1-4 change the database, sections 5-12 are read-only queries that would just
--  print a dozen result sets at you. Open the file, find the section you want, read the comment
--  above the block, and run that block.
--
--  The two index sections are the exception — they are safe to run whole, and that is the normal
--  way to use them:
--
--      sed -n '/^-- 1\. SEARCH INDEXES/,/^-- 3\. ENUM/p' docs/database/khi-archive.sql \
--        | psql "$DATABASE_URL"
--
--  --------------------------------------------------------------------------------------------
--  BEFORE YOU RUN ANYTHING
--  --------------------------------------------------------------------------------------------
--
--  * Sections 1-3 duplicate work the app already does at boot. Every statement is IF NOT EXISTS
--    or DROP ... IF EXISTS, so running them by hand is idempotent and converges on the same state
--    a restart would. Reach for them when a restart is not available, or when you want the
--    indexes built before the first request rather than during it.
--
--  * CREATE INDEX takes an ACCESS EXCLUSIVE lock. On a populated table use CREATE INDEX
--    CONCURRENTLY instead — it cannot run inside a transaction block, and it can leave an INVALID
--    index behind if it fails. See docs/internal/database/migrations.md Recipe 5, and the
--    invalid-index query in section 5.
--
--  * Literals stand in for bind parameters. The repository classes use :q, :lim, :sevenDaysAgo
--    and friends; those are written out here as literals and NOW() - INTERVAL ... so the
--    statements run as-is. Substitute your own values.
--
--  * removed_at IS NULL means active, everywhere. DELETE never removes a row in this schema — it
--    stamps that column. Leave the predicate out and you are counting the trash.
--
--  --------------------------------------------------------------------------------------------
--  CONTENTS
--  --------------------------------------------------------------------------------------------
--
--   WRITES
--    1. Search indexes — pg_trgm GIN + btree text_pattern_ops
--    2. Audit-log analytics indexes
--    3. Enum CHECK constraint re-sync
--    4. Idempotent backfills
--
--   READ-ONLY
--    5. Schema diagnostics — did that migration actually run?
--    6. Data integrity diagnostics
--    7. Queries — media, projects, visibility
--    8. Queries — audit logs and analytics
--    9. Queries — maqam vote panel and listen accountability
--   10. Queries — physical media inventory
--   11. Queries — guest corrections
--   12. Queries — tags and keywords
--
-- =================================================================================================


-- =================================================================================================
-- 1. SEARCH INDEXES — PG_TRGM GIN + BTREE TEXT_PATTERN_OPS
-- =================================================================================================

-- Generated at boot by MediaSearchIndexInitializer, CategorySearchIndexInitializer and
-- PersonSearchIndexInitializer (all @EventListener(ApplicationReadyEvent)). Every statement
-- is IF NOT EXISTS, so running this file by hand is idempotent and matches what the app
-- would create on its next boot. Run it when you want the indexes in place *before* the
-- first request, or on a replica the app does not boot against.
--
-- On a populated table prefer CREATE INDEX CONCURRENTLY (see migrations.md, Recipe 5) —
-- the plain form below takes an ACCESS EXCLUSIVE lock for the duration of the build.

CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ------------------------------------------------------------------------
-- images + image child collection tables
-- ------------------------------------------------------------------------

-- GIN trigram on every searchable column. Used by `LIKE '%q%'` and `%`
-- (similarity) operators when q.length() >= 3.
CREATE INDEX IF NOT EXISTS idx_images_image_code_trgm
    ON images USING GIN (LOWER(image_code) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_images_file_name_trgm
    ON images USING GIN (LOWER(file_name) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_images_volume_name_trgm
    ON images USING GIN (LOWER(volume_name) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_images_directory_trgm
    ON images USING GIN (LOWER(directory) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_images_path_external_trgm
    ON images USING GIN (LOWER(path_in_external_volume) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_images_auto_path_trgm
    ON images USING GIN (LOWER(auto_path) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_images_original_title_trgm
    ON images USING GIN (LOWER(original_title) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_images_alternative_title_trgm
    ON images USING GIN (LOWER(alternative_title) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_images_central_kurdish_title_trgm
    ON images USING GIN (LOWER(title_in_central_kurdish) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_images_romanized_title_trgm
    ON images USING GIN (LOWER(romanized_title) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_images_form_trgm
    ON images USING GIN (LOWER(form) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_images_event_trgm
    ON images USING GIN (LOWER(event) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_images_location_trgm
    ON images USING GIN (LOWER(location) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_images_description_trgm
    ON images USING GIN (LOWER(description) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_images_person_shown_trgm
    ON images USING GIN (LOWER(person_shown_in_image) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_images_creator_trgm
    ON images USING GIN (LOWER(creator_artist_photographer) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_images_contributor_trgm
    ON images USING GIN (LOWER(contributor) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_images_provenance_trgm
    ON images USING GIN (LOWER(provenance) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_images_photostory_trgm
    ON images USING GIN (LOWER(photostory) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_images_archive_cataloging_trgm
    ON images USING GIN (LOWER(archive_cataloging) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_images_physical_label_trgm
    ON images USING GIN (LOWER(physical_label) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_images_location_in_archive_trgm
    ON images USING GIN (LOWER(location_in_archive_room) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_images_note_trgm
    ON images USING GIN (LOWER(note) gin_trgm_ops);

-- Btree text_pattern_ops on primary fields. Lets `LIKE 'q%'` use the
-- index for *any* query length — including 1-2 char prefixes (where
-- the GIN trigram index can't help).
CREATE INDEX IF NOT EXISTS idx_images_image_code_pat
    ON images (LOWER(image_code) text_pattern_ops);
CREATE INDEX IF NOT EXISTS idx_images_file_name_pat
    ON images (LOWER(file_name) text_pattern_ops);
CREATE INDEX IF NOT EXISTS idx_images_original_title_pat
    ON images (LOWER(original_title) text_pattern_ops);
CREATE INDEX IF NOT EXISTS idx_images_alternative_title_pat
    ON images (LOWER(alternative_title) text_pattern_ops);
CREATE INDEX IF NOT EXISTS idx_images_central_kurdish_title_pat
    ON images (LOWER(title_in_central_kurdish) text_pattern_ops);
CREATE INDEX IF NOT EXISTS idx_images_romanized_title_pat
    ON images (LOWER(romanized_title) text_pattern_ops);
CREATE INDEX IF NOT EXISTS idx_images_creator_pat
    ON images (LOWER(creator_artist_photographer) text_pattern_ops);
CREATE INDEX IF NOT EXISTS idx_images_person_shown_pat
    ON images (LOWER(person_shown_in_image) text_pattern_ops);
CREATE INDEX IF NOT EXISTS idx_images_event_pat
    ON images (LOWER(event) text_pattern_ops);

-- Child collections — trigram for substring/fuzzy + pattern for prefix.
CREATE INDEX IF NOT EXISTS idx_image_subjects_subject_trgm
    ON image_subjects USING GIN (LOWER(subject) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_image_genres_genre_trgm
    ON image_genres USING GIN (LOWER(genre) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_image_colors_color_trgm
    ON image_colors USING GIN (LOWER(color) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_image_usages_usage_trgm
    ON image_usages USING GIN (LOWER(usage_context) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_image_tags_tag_trgm
    ON image_tags USING GIN (LOWER(tag) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_image_keywords_keyword_trgm
    ON image_keywords USING GIN (LOWER(keyword) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_image_subjects_subject_pat
    ON image_subjects (LOWER(subject) text_pattern_ops);
CREATE INDEX IF NOT EXISTS idx_image_genres_genre_pat
    ON image_genres (LOWER(genre) text_pattern_ops);
CREATE INDEX IF NOT EXISTS idx_image_tags_tag_pat
    ON image_tags (LOWER(tag) text_pattern_ops);
CREATE INDEX IF NOT EXISTS idx_image_keywords_keyword_pat
    ON image_keywords (LOWER(keyword) text_pattern_ops);

-- Btree on FK columns — speeds up phase-2 per-row similarity subqueries.
CREATE INDEX IF NOT EXISTS idx_image_subjects_image_id
    ON image_subjects (image_id);
CREATE INDEX IF NOT EXISTS idx_image_genres_image_id
    ON image_genres (image_id);
CREATE INDEX IF NOT EXISTS idx_image_colors_image_id
    ON image_colors (image_id);
CREATE INDEX IF NOT EXISTS idx_image_usages_image_id
    ON image_usages (image_id);
CREATE INDEX IF NOT EXISTS idx_image_tags_image_id
    ON image_tags (image_id);
CREATE INDEX IF NOT EXISTS idx_image_keywords_image_id
    ON image_keywords (image_id);


-- ------------------------------------------------------------------------
-- texts + text child collection tables
-- ------------------------------------------------------------------------

CREATE INDEX IF NOT EXISTS idx_texts_text_code_trgm
    ON texts USING GIN (LOWER(text_code) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_texts_file_name_trgm
    ON texts USING GIN (LOWER(file_name) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_texts_volume_name_trgm
    ON texts USING GIN (LOWER(volume_name) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_texts_directory_trgm
    ON texts USING GIN (LOWER(directory) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_texts_path_external_trgm
    ON texts USING GIN (LOWER(path_in_external_volume) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_texts_auto_path_trgm
    ON texts USING GIN (LOWER(auto_path) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_texts_original_title_trgm
    ON texts USING GIN (LOWER(original_title) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_texts_alternative_title_trgm
    ON texts USING GIN (LOWER(alternative_title) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_texts_central_kurdish_title_trgm
    ON texts USING GIN (LOWER(title_in_central_kurdish) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_texts_romanized_title_trgm
    ON texts USING GIN (LOWER(romanized_title) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_texts_document_type_trgm
    ON texts USING GIN (LOWER(document_type) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_texts_description_trgm
    ON texts USING GIN (LOWER(description) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_texts_script_trgm
    ON texts USING GIN (LOWER(script) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_texts_transcription_trgm
    ON texts USING GIN (LOWER(transcription) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_texts_isbn_trgm
    ON texts USING GIN (LOWER(isbn) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_texts_language_trgm
    ON texts USING GIN (LOWER(language) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_texts_dialect_trgm
    ON texts USING GIN (LOWER(dialect) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_texts_author_trgm
    ON texts USING GIN (LOWER(author) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_texts_contributors_trgm
    ON texts USING GIN (LOWER(contributors) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_texts_printing_house_trgm
    ON texts USING GIN (LOWER(printing_house) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_texts_provenance_trgm
    ON texts USING GIN (LOWER(provenance) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_texts_note_trgm
    ON texts USING GIN (LOWER(note) gin_trgm_ops);

-- Prefix-anchored fast paths
CREATE INDEX IF NOT EXISTS idx_texts_text_code_pat
    ON texts (LOWER(text_code) text_pattern_ops);
CREATE INDEX IF NOT EXISTS idx_texts_file_name_pat
    ON texts (LOWER(file_name) text_pattern_ops);
CREATE INDEX IF NOT EXISTS idx_texts_original_title_pat
    ON texts (LOWER(original_title) text_pattern_ops);
CREATE INDEX IF NOT EXISTS idx_texts_alternative_title_pat
    ON texts (LOWER(alternative_title) text_pattern_ops);
CREATE INDEX IF NOT EXISTS idx_texts_central_kurdish_title_pat
    ON texts (LOWER(title_in_central_kurdish) text_pattern_ops);
CREATE INDEX IF NOT EXISTS idx_texts_romanized_title_pat
    ON texts (LOWER(romanized_title) text_pattern_ops);
CREATE INDEX IF NOT EXISTS idx_texts_author_pat
    ON texts (LOWER(author) text_pattern_ops);
CREATE INDEX IF NOT EXISTS idx_texts_isbn_pat
    ON texts (LOWER(isbn) text_pattern_ops);

CREATE INDEX IF NOT EXISTS idx_text_subjects_subject_trgm
    ON text_subjects USING GIN (LOWER(subject) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_text_genres_genre_trgm
    ON text_genres USING GIN (LOWER(genre) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_text_tags_tag_trgm
    ON text_tags USING GIN (LOWER(tag) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_text_keywords_keyword_trgm
    ON text_keywords USING GIN (LOWER(keyword) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_text_subjects_subject_pat
    ON text_subjects (LOWER(subject) text_pattern_ops);
CREATE INDEX IF NOT EXISTS idx_text_genres_genre_pat
    ON text_genres (LOWER(genre) text_pattern_ops);
CREATE INDEX IF NOT EXISTS idx_text_tags_tag_pat
    ON text_tags (LOWER(tag) text_pattern_ops);
CREATE INDEX IF NOT EXISTS idx_text_keywords_keyword_pat
    ON text_keywords (LOWER(keyword) text_pattern_ops);

CREATE INDEX IF NOT EXISTS idx_text_subjects_text_id
    ON text_subjects (text_id);
CREATE INDEX IF NOT EXISTS idx_text_genres_text_id
    ON text_genres (text_id);
CREATE INDEX IF NOT EXISTS idx_text_tags_text_id
    ON text_tags (text_id);
CREATE INDEX IF NOT EXISTS idx_text_keywords_text_id
    ON text_keywords (text_id);


-- ------------------------------------------------------------------------
-- videos + video child collection tables
-- ------------------------------------------------------------------------

CREATE INDEX IF NOT EXISTS idx_videos_video_code_trgm
    ON videos USING GIN (LOWER(video_code) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_videos_file_name_trgm
    ON videos USING GIN (LOWER(file_name) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_videos_volume_name_trgm
    ON videos USING GIN (LOWER(volume_name) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_videos_directory_trgm
    ON videos USING GIN (LOWER(directory) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_videos_path_external_trgm
    ON videos USING GIN (LOWER(path_in_external_volume) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_videos_auto_path_trgm
    ON videos USING GIN (LOWER(auto_path) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_videos_original_title_trgm
    ON videos USING GIN (LOWER(original_title) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_videos_alternative_title_trgm
    ON videos USING GIN (LOWER(alternative_title) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_videos_central_kurdish_title_trgm
    ON videos USING GIN (LOWER(title_in_central_kurdish) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_videos_romanized_title_trgm
    ON videos USING GIN (LOWER(romanized_title) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_videos_event_trgm
    ON videos USING GIN (LOWER(event) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_videos_location_trgm
    ON videos USING GIN (LOWER(location) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_videos_description_trgm
    ON videos USING GIN (LOWER(description) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_videos_person_shown_trgm
    ON videos USING GIN (LOWER(person_shown_in_video) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_videos_resolution_trgm
    ON videos USING GIN (LOWER(resolution) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_videos_codec_trgm
    ON videos USING GIN (LOWER(video_codec) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_videos_subtitle_trgm
    ON videos USING GIN (LOWER(subtitle) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_videos_creator_trgm
    ON videos USING GIN (LOWER(creator_artist_director) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_videos_producer_trgm
    ON videos USING GIN (LOWER(producer) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_videos_contributor_trgm
    ON videos USING GIN (LOWER(contributor) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_videos_provenance_trgm
    ON videos USING GIN (LOWER(provenance) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_videos_note_trgm
    ON videos USING GIN (LOWER(note) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_videos_video_code_pat
    ON videos (LOWER(video_code) text_pattern_ops);
CREATE INDEX IF NOT EXISTS idx_videos_file_name_pat
    ON videos (LOWER(file_name) text_pattern_ops);
CREATE INDEX IF NOT EXISTS idx_videos_original_title_pat
    ON videos (LOWER(original_title) text_pattern_ops);
CREATE INDEX IF NOT EXISTS idx_videos_alternative_title_pat
    ON videos (LOWER(alternative_title) text_pattern_ops);
CREATE INDEX IF NOT EXISTS idx_videos_central_kurdish_title_pat
    ON videos (LOWER(title_in_central_kurdish) text_pattern_ops);
CREATE INDEX IF NOT EXISTS idx_videos_romanized_title_pat
    ON videos (LOWER(romanized_title) text_pattern_ops);
CREATE INDEX IF NOT EXISTS idx_videos_creator_pat
    ON videos (LOWER(creator_artist_director) text_pattern_ops);
CREATE INDEX IF NOT EXISTS idx_videos_producer_pat
    ON videos (LOWER(producer) text_pattern_ops);
CREATE INDEX IF NOT EXISTS idx_videos_event_pat
    ON videos (LOWER(event) text_pattern_ops);
CREATE INDEX IF NOT EXISTS idx_videos_person_shown_pat
    ON videos (LOWER(person_shown_in_video) text_pattern_ops);

CREATE INDEX IF NOT EXISTS idx_video_subjects_subject_trgm
    ON video_subjects USING GIN (LOWER(subject) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_video_genres_genre_trgm
    ON video_genres USING GIN (LOWER(genre) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_video_colors_color_trgm
    ON video_colors USING GIN (LOWER(color) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_video_usages_usage_trgm
    ON video_usages USING GIN (LOWER(usage_context) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_video_tags_tag_trgm
    ON video_tags USING GIN (LOWER(tag) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_video_keywords_keyword_trgm
    ON video_keywords USING GIN (LOWER(keyword) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_video_subjects_subject_pat
    ON video_subjects (LOWER(subject) text_pattern_ops);
CREATE INDEX IF NOT EXISTS idx_video_genres_genre_pat
    ON video_genres (LOWER(genre) text_pattern_ops);
CREATE INDEX IF NOT EXISTS idx_video_tags_tag_pat
    ON video_tags (LOWER(tag) text_pattern_ops);
CREATE INDEX IF NOT EXISTS idx_video_keywords_keyword_pat
    ON video_keywords (LOWER(keyword) text_pattern_ops);

CREATE INDEX IF NOT EXISTS idx_video_subjects_video_id
    ON video_subjects (video_id);
CREATE INDEX IF NOT EXISTS idx_video_genres_video_id
    ON video_genres (video_id);
CREATE INDEX IF NOT EXISTS idx_video_colors_video_id
    ON video_colors (video_id);
CREATE INDEX IF NOT EXISTS idx_video_usages_video_id
    ON video_usages (video_id);
CREATE INDEX IF NOT EXISTS idx_video_tags_video_id
    ON video_tags (video_id);
CREATE INDEX IF NOT EXISTS idx_video_keywords_video_id
    ON video_keywords (video_id);


-- ------------------------------------------------------------------------
-- audios + audio child collection tables
-- ------------------------------------------------------------------------

CREATE INDEX IF NOT EXISTS idx_audios_audio_code_trgm
    ON audios USING GIN (LOWER(audio_code) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_audios_file_name_trgm
    ON audios USING GIN (LOWER(file_name) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_audios_volume_name_trgm
    ON audios USING GIN (LOWER(volume_name) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_audios_directory_name_trgm
    ON audios USING GIN (LOWER(directory_name) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_audios_path_external_trgm
    ON audios USING GIN (LOWER(path_in_external) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_audios_auto_path_trgm
    ON audios USING GIN (LOWER(auto_path) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_audios_origin_title_trgm
    ON audios USING GIN (LOWER(origin_title) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_audios_alter_title_trgm
    ON audios USING GIN (LOWER(alter_title) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_audios_central_kurdish_title_trgm
    ON audios USING GIN (LOWER(central_kurdish_title) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_audios_romanized_title_trgm
    ON audios USING GIN (LOWER(romanized_title) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_audios_form_trgm
    ON audios USING GIN (LOWER(form) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_audios_type_of_basta_trgm
    ON audios USING GIN (LOWER(type_of_basta) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_audios_type_of_maqam_trgm
    ON audios USING GIN (LOWER(type_of_maqam) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_audios_abstract_trgm
    ON audios USING GIN (LOWER(abstract_text) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_audios_description_trgm
    ON audios USING GIN (LOWER(description) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_audios_speaker_trgm
    ON audios USING GIN (LOWER(speaker) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_audios_producer_trgm
    ON audios USING GIN (LOWER(producer) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_audios_composer_trgm
    ON audios USING GIN (LOWER(composer) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_audios_language_trgm
    ON audios USING GIN (LOWER(language) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_audios_dialect_trgm
    ON audios USING GIN (LOWER(dialect) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_audios_lyrics_trgm
    ON audios USING GIN (LOWER(lyrics) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_audios_poet_trgm
    ON audios USING GIN (LOWER(poet) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_audios_recording_venue_trgm
    ON audios USING GIN (LOWER(recording_venue) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_audios_city_trgm
    ON audios USING GIN (LOWER(city) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_audios_region_trgm
    ON audios USING GIN (LOWER(region) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_audios_provenance_trgm
    ON audios USING GIN (LOWER(provenance) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_audios_audio_file_note_trgm
    ON audios USING GIN (LOWER(audio_file_note) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_audios_audio_code_pat
    ON audios (LOWER(audio_code) text_pattern_ops);
CREATE INDEX IF NOT EXISTS idx_audios_file_name_pat
    ON audios (LOWER(file_name) text_pattern_ops);
CREATE INDEX IF NOT EXISTS idx_audios_origin_title_pat
    ON audios (LOWER(origin_title) text_pattern_ops);
CREATE INDEX IF NOT EXISTS idx_audios_alter_title_pat
    ON audios (LOWER(alter_title) text_pattern_ops);
CREATE INDEX IF NOT EXISTS idx_audios_central_kurdish_title_pat
    ON audios (LOWER(central_kurdish_title) text_pattern_ops);
CREATE INDEX IF NOT EXISTS idx_audios_romanized_title_pat
    ON audios (LOWER(romanized_title) text_pattern_ops);
CREATE INDEX IF NOT EXISTS idx_audios_speaker_pat
    ON audios (LOWER(speaker) text_pattern_ops);
CREATE INDEX IF NOT EXISTS idx_audios_composer_pat
    ON audios (LOWER(composer) text_pattern_ops);
CREATE INDEX IF NOT EXISTS idx_audios_poet_pat
    ON audios (LOWER(poet) text_pattern_ops);
CREATE INDEX IF NOT EXISTS idx_audios_producer_pat
    ON audios (LOWER(producer) text_pattern_ops);
CREATE INDEX IF NOT EXISTS idx_audios_city_pat
    ON audios (LOWER(city) text_pattern_ops);
CREATE INDEX IF NOT EXISTS idx_audios_region_pat
    ON audios (LOWER(region) text_pattern_ops);
CREATE INDEX IF NOT EXISTS idx_audios_type_of_basta_pat
    ON audios (LOWER(type_of_basta) text_pattern_ops);
CREATE INDEX IF NOT EXISTS idx_audios_type_of_maqam_pat
    ON audios (LOWER(type_of_maqam) text_pattern_ops);

CREATE INDEX IF NOT EXISTS idx_audio_genres_genre_trgm
    ON audio_genres USING GIN (LOWER(genre) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_audio_contributors_contributor_trgm
    ON audio_contributors USING GIN (LOWER(contributor) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_audio_tags_tag_trgm
    ON audio_tags USING GIN (LOWER(tag) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_audio_keywords_keyword_trgm
    ON audio_keywords USING GIN (LOWER(keyword) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_audio_genres_genre_pat
    ON audio_genres (LOWER(genre) text_pattern_ops);
CREATE INDEX IF NOT EXISTS idx_audio_contributors_contributor_pat
    ON audio_contributors (LOWER(contributor) text_pattern_ops);
CREATE INDEX IF NOT EXISTS idx_audio_tags_tag_pat
    ON audio_tags (LOWER(tag) text_pattern_ops);
CREATE INDEX IF NOT EXISTS idx_audio_keywords_keyword_pat
    ON audio_keywords (LOWER(keyword) text_pattern_ops);

CREATE INDEX IF NOT EXISTS idx_audio_genres_audio_id
    ON audio_genres (audio_id);
CREATE INDEX IF NOT EXISTS idx_audio_contributors_audio_id
    ON audio_contributors (audio_id);
CREATE INDEX IF NOT EXISTS idx_audio_tags_audio_id
    ON audio_tags (audio_id);
CREATE INDEX IF NOT EXISTS idx_audio_keywords_audio_id
    ON audio_keywords (audio_id);


-- ------------------------------------------------------------------------
-- categories + category_keywords
-- ------------------------------------------------------------------------

CREATE INDEX IF NOT EXISTS idx_categories_name_lower_trgm
    ON categories USING GIN (LOWER(name) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_categories_description_lower_trgm
    ON categories USING GIN (LOWER(description) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_category_keywords_lower_trgm
    ON category_keywords USING GIN (LOWER(keyword) gin_trgm_ops);

-- ------------------------------------------------------------------------
-- person + person_person_type
-- ------------------------------------------------------------------------

CREATE INDEX IF NOT EXISTS idx_person_full_name_lower_trgm
    ON person USING GIN (LOWER(full_name) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_person_nickname_lower_trgm
    ON person USING GIN (LOWER(nickname) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_person_romanized_name_lower_trgm
    ON person USING GIN (LOWER(romanized_name) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_person_description_lower_trgm
    ON person USING GIN (LOWER(description) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_person_tag_lower_trgm
    ON person USING GIN (LOWER(tag) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_person_keywords_lower_trgm
    ON person USING GIN (LOWER(keywords) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_person_region_lower_trgm
    ON person USING GIN (LOWER(region) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_person_place_of_birth_lower_trgm
    ON person USING GIN (LOWER(place_of_birth) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_person_place_of_death_lower_trgm
    ON person USING GIN (LOWER(place_of_death) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_person_person_type_lower_trgm
    ON person_person_type USING GIN (LOWER(person_type) gin_trgm_ops);


-- =================================================================================================
-- 2. AUDIT-LOG ANALYTICS INDEXES
-- =================================================================================================

-- Generated at boot by AuditLogIndexInitializer (@EventListener(ApplicationReadyEvent)).
-- Every statement is IF NOT EXISTS, so running this file by hand is idempotent and matches
-- what the app would create on its next boot.
--
-- AnalyticsService runs one UNION ALL across all eleven *_audit_logs tables and aggregates
-- by user / day / entity. Without these indexes Postgres seq-scans each table — fine at a
-- few hundred rows, hundreds of milliseconds once the log grows. Three indexes per table:
--
--   (actor_username, occurred_at DESC)  per-user windowed scans  — /api/analytics/me, /users/{name}
--   (occurred_at DESC)                  team-wide windowed scans — /overview, /users
--   (action, occurred_at DESC)          the FILTER (WHERE action = …) aggregations
--
-- On a populated table prefer CREATE INDEX CONCURRENTLY (see migrations.md, Recipe 5).

CREATE INDEX IF NOT EXISTS idx_audio_audit_logs_actor_occurred  ON audio_audit_logs (actor_username, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_audio_audit_logs_occurred        ON audio_audit_logs (occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_audio_audit_logs_action_occurred ON audio_audit_logs (action, occurred_at DESC);

CREATE INDEX IF NOT EXISTS idx_video_audit_logs_actor_occurred  ON video_audit_logs (actor_username, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_video_audit_logs_occurred        ON video_audit_logs (occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_video_audit_logs_action_occurred ON video_audit_logs (action, occurred_at DESC);

CREATE INDEX IF NOT EXISTS idx_image_audit_logs_actor_occurred  ON image_audit_logs (actor_username, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_image_audit_logs_occurred        ON image_audit_logs (occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_image_audit_logs_action_occurred ON image_audit_logs (action, occurred_at DESC);

CREATE INDEX IF NOT EXISTS idx_text_audit_logs_actor_occurred  ON text_audit_logs (actor_username, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_text_audit_logs_occurred        ON text_audit_logs (occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_text_audit_logs_action_occurred ON text_audit_logs (action, occurred_at DESC);

CREATE INDEX IF NOT EXISTS idx_project_audit_logs_actor_occurred  ON project_audit_logs (actor_username, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_project_audit_logs_occurred        ON project_audit_logs (occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_project_audit_logs_action_occurred ON project_audit_logs (action, occurred_at DESC);

CREATE INDEX IF NOT EXISTS idx_category_audit_logs_actor_occurred  ON category_audit_logs (actor_username, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_category_audit_logs_occurred        ON category_audit_logs (occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_category_audit_logs_action_occurred ON category_audit_logs (action, occurred_at DESC);

CREATE INDEX IF NOT EXISTS idx_person_audit_logs_actor_occurred  ON person_audit_logs (actor_username, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_person_audit_logs_occurred        ON person_audit_logs (occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_person_audit_logs_action_occurred ON person_audit_logs (action, occurred_at DESC);

CREATE INDEX IF NOT EXISTS idx_maqam_audit_logs_actor_occurred  ON maqam_audit_logs (actor_username, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_maqam_audit_logs_occurred        ON maqam_audit_logs (occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_maqam_audit_logs_action_occurred ON maqam_audit_logs (action, occurred_at DESC);

CREATE INDEX IF NOT EXISTS idx_physical_media_audit_logs_actor_occurred  ON physical_media_audit_logs (actor_username, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_physical_media_audit_logs_occurred        ON physical_media_audit_logs (occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_physical_media_audit_logs_action_occurred ON physical_media_audit_logs (action, occurred_at DESC);

CREATE INDEX IF NOT EXISTS idx_analytics_audit_logs_actor_occurred  ON analytics_audit_logs (actor_username, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_analytics_audit_logs_occurred        ON analytics_audit_logs (occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_analytics_audit_logs_action_occurred ON analytics_audit_logs (action, occurred_at DESC);

CREATE INDEX IF NOT EXISTS idx_user_audit_logs_actor_occurred  ON user_audit_logs (actor_username, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_user_audit_logs_occurred        ON user_audit_logs (occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_user_audit_logs_action_occurred ON user_audit_logs (action, occurred_at DESC);


-- =================================================================================================
-- 3. ENUM CHECK CONSTRAINT RE-SYNC
-- =================================================================================================

-- Hibernate writes a `CHECK (col IN (…))` constraint from @Enumerated(STRING) the FIRST time it
-- creates a table, and never touches it again under ddl-auto=update. Add a value to the Java enum
-- and every INSERT carrying the new value fails with a constraint violation against a constraint
-- nobody edited. The app works around this at boot with a family of @EventListener initializers
-- that drop and rebuild the constraint from the live enum. This file is the same work, by hand,
-- for when you cannot restart the app.
--
--   Rebuilt from the enum : users_tbl.role, physical_media.digitization,
--                           maqam_audit_logs.action, physical_media_audit_logs.action,
--                           analytics_audit_logs.action, guest_correction_audit_logs.action
--   Dropped, not rebuilt  : the media and catalog *_audit_logs action columns — the Java enum
--                           binding is the only check they need
--
-- The value lists below are transcribed from the enum classes named in each section. If you add
-- an enum constant, add it here too, or just let the initializer rebuild it on the next boot.
--
-- IMPORTANT: each DROP below names the constraint the APP creates. On a database that has
-- never run these initializers the constraint still carries Hibernate's generated name, which
-- differs by Hibernate version. Run the discovery query in section 0 first and drop whatever
-- it returns — that is exactly what the initializers do.


-- ---------------------------------------------------------------------------
-- 3.0 Discovery — what CHECK constraints exist on a column right now
-- ---------------------------------------------------------------------------
-- Hibernate's generated name is not stable across versions, so always look it up rather than
-- guessing. This is the exact query the initializers run.

SELECT con.conname, pg_get_constraintdef(con.oid)
FROM   pg_constraint con
JOIN   pg_class     c ON c.oid = con.conrelid
JOIN   pg_attribute a ON a.attrelid = c.oid AND a.attnum = ANY(con.conkey)
WHERE  c.relname   = 'users_tbl'      -- <- table
  AND  con.contype = 'c'
  AND  a.attname   = 'role';          -- <- column

-- Every CHECK constraint in the schema, if you want the whole picture:
-- SELECT c.relname AS table_name, con.conname, pg_get_constraintdef(con.oid)
-- FROM   pg_constraint con
-- JOIN   pg_class     c ON c.oid = con.conrelid
-- JOIN   pg_namespace n ON n.oid = c.relnamespace
-- WHERE  n.nspname = 'public' AND con.contype = 'c'
-- ORDER  BY c.relname, con.conname;


-- ---------------------------------------------------------------------------
-- 3.1 users_tbl.role  —  user/enums/Role.java  (UserRoleConstraintInitializer)
-- ---------------------------------------------------------------------------
-- Drop whatever the discovery query returned, then rebuild under a name we control.
ALTER TABLE users_tbl DROP CONSTRAINT IF EXISTS users_tbl_role_check;

ALTER TABLE users_tbl ADD CONSTRAINT users_tbl_role_check
  CHECK (role IN ('GUEST','EMPLOYEE','TEACHER','ADMIN'));


-- ---------------------------------------------------------------------------
-- 3.2 physical_media.digitization  —  platform/enums/DigitizationStatus.java
--    (PhysicalMediaDigitizationConstraintInitializer)
-- ---------------------------------------------------------------------------
-- Nullable column, so the constraint has to allow NULL explicitly.
ALTER TABLE physical_media DROP CONSTRAINT IF EXISTS physical_media_digitization_check;

ALTER TABLE physical_media ADD CONSTRAINT physical_media_digitization_check
  CHECK (digitization IS NULL OR digitization IN ('NOT_DIGITIZED','DIGITIZED','DUPLICATED'));


-- ---------------------------------------------------------------------------
-- 3.3 maqam_audit_logs.action  —  platform/enums/MaqamAuditAction.java
--    (MaqamAuditActionConstraintInitializer)
-- ---------------------------------------------------------------------------
ALTER TABLE maqam_audit_logs DROP CONSTRAINT IF EXISTS maqam_audit_logs_action_check;

ALTER TABLE maqam_audit_logs ADD CONSTRAINT maqam_audit_logs_action_check
  CHECK (action IN (
    'CREATE','READ','LIST','SEARCH','UPDATE','REMOVE','DELETE','RESTORE','PURGE',
    'TEACHER_ASSIGNED','TEACHER_REMOVED','VOTE_CAST','VOTE_UPDATED','VOTE_DELETED',
    'STREAM','LISTEN_STARTED','LISTEN_PROGRESS','LISTEN_ENDED'
  ));


-- ---------------------------------------------------------------------------
-- 3.4 physical_media_audit_logs.action  —  platform/enums/PhysicalMediaAuditAction.java
--    (PhysicalMediaAuditActionConstraintInitializer)
-- ---------------------------------------------------------------------------
ALTER TABLE physical_media_audit_logs DROP CONSTRAINT IF EXISTS physical_media_audit_logs_action_check;

ALTER TABLE physical_media_audit_logs ADD CONSTRAINT physical_media_audit_logs_action_check
  CHECK (action IN (
    'CREATE','READ','LIST','SEARCH','UPDATE','REMOVE','DELETE','RESTORE','PURGE',
    'IMPORT','TYPE_CREATE','TYPE_UPDATE','TYPE_DELETE'
  ));


-- ---------------------------------------------------------------------------
-- 3.5 analytics_audit_logs.action  —  platform/enums/AnalyticsAuditAction.java
--    (AnalyticsAuditActionConstraintInitializer)
-- ---------------------------------------------------------------------------
ALTER TABLE analytics_audit_logs DROP CONSTRAINT IF EXISTS analytics_audit_logs_action_check;

ALTER TABLE analytics_audit_logs ADD CONSTRAINT analytics_audit_logs_action_check
  CHECK (action IN (
    'VIEW_OVERVIEW','VIEW_USER','VIEW_USERS','VIEW_FEED','VIEW_ACTIONS',
    'VIEW_DAILY','VIEW_WEEKLY','VIEW_MONTHLY','VIEW_YEARLY',
    'VIEW_ENTITY_STATS','VIEW_ACTION_CATALOG','VIEW_INVENTORY','VIEW_VISIBILITY',
    'VIEW_MAQAM_OVERVIEW','VIEW_MAQAM_TEACHERS','VIEW_MAQAM_TEACHER'
  ));


-- ---------------------------------------------------------------------------
-- 3.6 guest_correction_audit_logs.action  —  platform/enums/GuestCorrectionAuditAction.java
--    (GuestCorrectionAuditActionConstraintInitializer)
-- ---------------------------------------------------------------------------
ALTER TABLE guest_correction_audit_logs DROP CONSTRAINT IF EXISTS guest_correction_audit_logs_action_check;

ALTER TABLE guest_correction_audit_logs ADD CONSTRAINT guest_correction_audit_logs_action_check
  CHECK (action IN ('SUBMIT','VIEW','LIST','FORWARD','RESOLVE','REJECT','REMOVE'));


-- ---------------------------------------------------------------------------
-- 3.7 user_audit_logs.action  —  user/enums/UserAuditAction.java
--    (UserAuditActionConstraintInitializer)
-- ---------------------------------------------------------------------------
ALTER TABLE user_audit_logs DROP CONSTRAINT IF EXISTS user_audit_logs_action_check;

ALTER TABLE user_audit_logs ADD CONSTRAINT user_audit_logs_action_check
  CHECK (action IN (
    'CREATE','UPDATE','DELETE','ROLE_CHANGE','GRANT_PERMISSIONS','REVOKE_PERMISSIONS',
    'ACTIVATE','DEACTIVATE','READ','LIST',
    'WARNING_SENT','WARNING_REVOKED','WARNING_ACKNOWLEDGED'
  ));


-- ---------------------------------------------------------------------------
-- 3.8 Dropped and NOT rebuilt — the media and catalog audit-log action columns
-- ---------------------------------------------------------------------------
-- MediaSearchIndexInitializer drops the first five, CategorySearchIndexInitializer and
-- PersonSearchIndexInitializer drop the last two. No constraint is put back: the enum binding on
-- the Java side already rejects anything invalid, and leaving the column unconstrained means a
-- new action value (e.g. SEARCH) never needs a migration.

ALTER TABLE image_audit_logs    DROP CONSTRAINT IF EXISTS image_audit_logs_action_check;
ALTER TABLE text_audit_logs     DROP CONSTRAINT IF EXISTS text_audit_logs_action_check;
ALTER TABLE video_audit_logs    DROP CONSTRAINT IF EXISTS video_audit_logs_action_check;
ALTER TABLE audio_audit_logs    DROP CONSTRAINT IF EXISTS audio_audit_logs_action_check;
ALTER TABLE project_audit_logs  DROP CONSTRAINT IF EXISTS project_audit_logs_action_check;
ALTER TABLE category_audit_logs DROP CONSTRAINT IF EXISTS category_audit_logs_action_check;
ALTER TABLE person_audit_logs   DROP CONSTRAINT IF EXISTS person_audit_logs_action_check;


-- =================================================================================================
-- 4. IDEMPOTENT BACKFILLS
-- =================================================================================================

-- Every statement here is safe to run repeatedly: each one is scoped by a WHERE clause that stops
-- matching once the work is done. All of them also run at boot from an @EventListener initializer,
-- so this file is only needed when you want the effect without a restart — or want to see exactly
-- what the app did to your rows.


-- ---------------------------------------------------------------------------
-- 4.1 version = 0 on rows that predate the @Version column
--    MediaSearchIndexInitializer.backfillNullVersions()
-- ---------------------------------------------------------------------------
-- A @Version column added under ddl-auto=update arrives NULL on existing rows. Hibernate treats a
-- NULL version as fresh-insert semantics, which silently defeats optimistic locking — two
-- concurrent edits can both "win". Backfill once and every row has a real version.

UPDATE audios     SET version = 0 WHERE version IS NULL;
UPDATE videos     SET version = 0 WHERE version IS NULL;
UPDATE images     SET version = 0 WHERE version IS NULL;
UPDATE texts      SET version = 0 WHERE version IS NULL;
UPDATE projects   SET version = 0 WHERE version IS NULL;
UPDATE person     SET version = 0 WHERE version IS NULL;
UPDATE categories SET version = 0 WHERE version IS NULL;

-- Expect 0 from each of these afterwards:
-- SELECT count(*) FROM audios WHERE version IS NULL;


-- ---------------------------------------------------------------------------
-- 4.2 physical_media.size -> physical_media.physical_size
--    PhysicalMediaSizeColumnMigrationInitializer
-- ---------------------------------------------------------------------------
-- The column was renamed on the entity. Hibernate under ddl-auto=update ADDS the new column and
-- leaves the old one populated, so the data has to be carried across by hand. The initializer
-- first checks that both columns still exist:
--
--   SELECT COUNT(*) FROM information_schema.columns
--   WHERE table_name = 'physical_media' AND column_name = ?

UPDATE physical_media
   SET physical_size = size
 WHERE physical_size IS NULL
   AND size IS NOT NULL;

-- Expect 0 afterwards:
-- SELECT count(*) FROM physical_media WHERE physical_size IS NULL AND size IS NOT NULL;
--
-- Only drop the old column once the app has been running on physical_size for a release and
-- nothing reads `size` any more — see migrations.md, Recipe 6.
-- ALTER TABLE physical_media DROP COLUMN size;


-- ---------------------------------------------------------------------------
-- 4.3 Per-user permission grants added after EMPLOYEE accounts already existed
--    EmployeePhysicalMediaPermissionBackfillInitializer,
--    EmployeeMaqamTeacherManageBackfillInitializer
-- ---------------------------------------------------------------------------
-- Role-implied authorities come from the Role enum at login, but a grant that has to survive a
-- role change is stored per user in user_permissions. When a new permission is introduced, the
-- accounts that already exist do not get it retroactively — these inserts do that.
--
-- ON CONFLICT makes them idempotent: uk_user_permissions_user_perm is the unique constraint on
-- (user_id, permission).

INSERT INTO user_permissions (user_id, permission)
SELECT u.user_id, 'physical_media:read'   FROM users_tbl u WHERE u.role = 'EMPLOYEE'
ON CONFLICT ON CONSTRAINT uk_user_permissions_user_perm DO NOTHING;

INSERT INTO user_permissions (user_id, permission)
SELECT u.user_id, 'physical_media:create' FROM users_tbl u WHERE u.role = 'EMPLOYEE'
ON CONFLICT ON CONSTRAINT uk_user_permissions_user_perm DO NOTHING;

INSERT INTO user_permissions (user_id, permission)
SELECT u.user_id, 'physical_media:update' FROM users_tbl u WHERE u.role = 'EMPLOYEE'
ON CONFLICT ON CONSTRAINT uk_user_permissions_user_perm DO NOTHING;

INSERT INTO user_permissions (user_id, permission)
SELECT u.user_id, 'physical_media:import' FROM users_tbl u WHERE u.role = 'EMPLOYEE'
ON CONFLICT ON CONSTRAINT uk_user_permissions_user_perm DO NOTHING;

INSERT INTO user_permissions (user_id, permission)
SELECT u.user_id, 'maqam:teacher_manage'  FROM users_tbl u WHERE u.role = 'EMPLOYEE'
ON CONFLICT ON CONSTRAINT uk_user_permissions_user_perm DO NOTHING;

-- What every EMPLOYEE now holds explicitly:
-- SELECT up.permission, count(*)
-- FROM   user_permissions up
-- JOIN   users_tbl u ON u.user_id = up.user_id
-- WHERE  u.role = 'EMPLOYEE'
-- GROUP  BY up.permission
-- ORDER  BY up.permission;


-- =================================================================================================
-- 5. SCHEMA DIAGNOSTICS — DID THAT MIGRATION ACTUALLY RUN?
-- =================================================================================================

-- Read-only. Nothing here modifies data or schema. Run any block on its own against the live
-- database when you need to confirm what shape it is really in, rather than what the entity
-- classes say it should be.


-- ---------------------------------------------------------------------------
-- Columns of one table, as Postgres actually has them
-- ---------------------------------------------------------------------------
SELECT column_name, data_type, character_maximum_length, is_nullable, column_default
FROM   information_schema.columns
WHERE  table_schema = 'public'
  AND  table_name   = 'physical_media'
ORDER  BY ordinal_position;

-- Does one specific column exist? (1 = yes, 0 = no — the check the size-migration
-- initializer runs before touching anything)
SELECT count(*)
FROM   information_schema.columns
WHERE  table_name = 'physical_media' AND column_name = 'physical_size';


-- ---------------------------------------------------------------------------
-- Indexes
-- ---------------------------------------------------------------------------
SELECT indexname, indexdef
FROM   pg_indexes
WHERE  schemaname = 'public' AND tablename = 'audios'
ORDER  BY indexname;

-- Does one named index exist? NULL = no.
SELECT to_regclass('public.idx_audios_speaker_trgm');

-- Indexes left INVALID by a failed CREATE INDEX CONCURRENTLY. These are not used by the planner
-- and must be dropped and rebuilt.
SELECT c.relname AS invalid_index
FROM   pg_index i
JOIN   pg_class c ON c.oid = i.indexrelid
WHERE  NOT i.indisvalid;


-- ---------------------------------------------------------------------------
-- Extensions
-- ---------------------------------------------------------------------------
-- pg_trgm is what makes the `%` similarity operator and the GIN indexes work. Without it every
-- search index creation is skipped at boot with a warning, and search silently falls back to
-- sequential scans.
SELECT extname, extversion FROM pg_extension WHERE extname = 'pg_trgm';


-- ---------------------------------------------------------------------------
-- CHECK constraints
-- ---------------------------------------------------------------------------
-- One column (this is the discovery query the constraint initializers run):
SELECT con.conname, pg_get_constraintdef(con.oid)
FROM   pg_constraint con
JOIN   pg_class     c ON c.oid = con.conrelid
JOIN   pg_attribute a ON a.attrelid = c.oid AND a.attnum = ANY(con.conkey)
WHERE  c.relname   = 'users_tbl'
  AND  con.contype = 'c'
  AND  a.attname   = 'role';

-- Every CHECK constraint in the schema — the fastest way to spot one Hibernate generated years
-- ago and never refreshed. See section 3 for the fix.
SELECT c.relname AS table_name, con.conname, pg_get_constraintdef(con.oid)
FROM   pg_constraint con
JOIN   pg_class     c ON c.oid = con.conrelid
JOIN   pg_namespace n ON n.oid = c.relnamespace
WHERE  n.nspname = 'public' AND con.contype = 'c'
ORDER  BY c.relname, con.conname;


-- ---------------------------------------------------------------------------
-- Is the planner using the index you think it is?
-- ---------------------------------------------------------------------------
-- Phase 1 of the two-phase search, with a literal in place of the bind parameters.
EXPLAIN (ANALYZE, BUFFERS)
WITH cands AS (
    SELECT i.id
      FROM images i
     WHERE i.removed_at IS NULL
       AND ( LOWER(COALESCE(i.original_title, '')) LIKE '%hasan%' ESCAPE '\' )
     LIMIT 2000
)
SELECT i.* FROM images i JOIN cands ON cands.id = i.id LIMIT 50;

-- If it seq-scans, force the issue to find out whether an index *could* have served the query.
-- A plan that is still a seq-scan with enable_seqscan off means the index does not match the
-- expression — usually a LOWER()/COALESCE() mismatch between the query and the index definition.
-- SET   enable_seqscan = off;
-- EXPLAIN ANALYZE <statement>;
-- RESET enable_seqscan;


-- =================================================================================================
-- 6. DATA INTEGRITY DIAGNOSTICS
-- =================================================================================================

-- Read-only. Each block answers one "is the data actually consistent?" question that the API
-- itself will not tell you, because the API only ever shows you rows that pass its filters.


-- ---------------------------------------------------------------------------
-- Row counts after a seed or import
-- ---------------------------------------------------------------------------
SELECT 'categories' AS tbl, count(*) FROM categories
UNION ALL SELECT 'person',   count(*) FROM person
UNION ALL SELECT 'projects', count(*) FROM projects
UNION ALL SELECT 'audios',   count(*) FROM audios
UNION ALL SELECT 'videos',   count(*) FROM videos
UNION ALL SELECT 'texts',    count(*) FROM texts
UNION ALL SELECT 'images',   count(*) FROM images;


-- ---------------------------------------------------------------------------
-- Active records with no file behind them
-- ---------------------------------------------------------------------------
-- An active row whose *_file_url is NULL or empty will 404 or 500 the moment anyone streams it.
-- Normally this means an upload failed after the row was written.
SELECT audio_code FROM audios WHERE removed_at IS NULL AND (audio_file_url IS NULL OR audio_file_url = '');
SELECT video_code FROM videos WHERE removed_at IS NULL AND (video_file_url IS NULL OR video_file_url = '');
SELECT image_code FROM images WHERE removed_at IS NULL AND (image_file_url IS NULL OR image_file_url = '');
SELECT text_code  FROM texts  WHERE removed_at IS NULL AND (text_file_url  IS NULL OR text_file_url  = '');

-- URLs that do not look like this deployment's bucket — usually rows carried over from another
-- environment. Adjust the bucket fragment to match your configuration.
SELECT audio_code, audio_file_url
FROM   audios
WHERE  audio_file_url IS NOT NULL
  AND  audio_file_url <> ''
  AND  (audio_file_url NOT LIKE '%khi-archive-platform%' OR audio_file_url NOT LIKE '%.s3.%');

-- One record, whole story — is it active, and where does its object live?
SELECT audio_code, audio_file_url, removed_at
FROM   audios
WHERE  audio_code = 'HASAZIRA_AUD_RAW_V1_Copy(1)_000001';


-- ---------------------------------------------------------------------------
-- Visibility drift
-- ---------------------------------------------------------------------------
-- Media that still says is_public under a project that is not visible to the public. Not a bug —
-- toggling a project's visibility only cascades to its media when the caller asks for the cascade
-- — but it is the usual reason a record "should be public" and is not. The read gate below is the
-- authority: BOTH the media row and its project must pass.
SELECT p.project_code, a.audio_code, a.is_public, p.is_visible_to_public
FROM   audios a
JOIN   projects p ON p.id = a.project_id
WHERE  a.removed_at IS NULL
  AND  p.removed_at IS NULL
  AND  p.is_visible_to_public = FALSE
  AND  (a.is_public IS NULL OR a.is_public = TRUE)
ORDER  BY p.project_code, a.audio_code;

-- The exact predicate an anonymous read applies, for reference:
--   WHERE a.removed_at IS NULL
--     AND (a.is_public          IS NULL OR a.is_public          = TRUE)
--     AND p.removed_at IS NULL
--     AND (p.is_visible_to_public IS NULL OR p.is_visible_to_public = TRUE)
-- NULL counts as public in both columns. That is deliberate: rows created before the flag existed
-- stay visible.


-- ---------------------------------------------------------------------------
-- Backfills that should have nothing left to do
-- ---------------------------------------------------------------------------
SELECT count(*) FROM audios WHERE version IS NULL;                    -- expect 0
SELECT count(*) FROM physical_media
 WHERE physical_size IS NULL AND size IS NOT NULL;                    -- expect 0


-- ---------------------------------------------------------------------------
-- What is in the trash
-- ---------------------------------------------------------------------------
-- `removed_at IS NULL` means active and `removed_at IS NOT NULL` means trashed, everywhere in
-- this schema — DELETE never removes a row, it stamps this column.
SELECT 'audio' AS kind, audio_code AS code, origin_title   AS title, removed_at, removed_by
  FROM audios WHERE removed_at IS NOT NULL
UNION ALL
SELECT 'video', video_code, original_title, removed_at, removed_by
  FROM videos WHERE removed_at IS NOT NULL
UNION ALL
SELECT 'image', image_code, original_title, removed_at, removed_by
  FROM images WHERE removed_at IS NOT NULL
UNION ALL
SELECT 'text',  text_code,  original_title, removed_at, removed_by
  FROM texts  WHERE removed_at IS NOT NULL
ORDER  BY removed_at DESC
LIMIT  100;


-- =================================================================================================
-- 7. QUERIES — MEDIA, PROJECTS, VISIBILITY
-- =================================================================================================

-- Read-only. Audio is used as the worked example throughout; videos / images / texts have the
-- identical shape with their own code column (video_code, image_code, text_code) and title column.


-- ---------------------------------------------------------------------------
-- Active, publicly visible audio — what an anonymous caller can see
-- ---------------------------------------------------------------------------
SELECT a.audio_code,
       a.origin_title,
       a.central_kurdish_title,
       p.project_code,
       pe.person_code,
       a.created_at
FROM   audios a
JOIN   projects p   ON p.id  = a.project_id
LEFT JOIN person pe ON pe.id = p.person_id
WHERE  a.removed_at IS NULL
  AND  (a.is_public IS NULL OR a.is_public = TRUE)
  AND  p.removed_at IS NULL
  AND  (p.is_visible_to_public IS NULL OR p.is_visible_to_public = TRUE)
ORDER  BY a.created_at DESC
LIMIT  50;


-- ---------------------------------------------------------------------------
-- Resolve one record by its business code
-- ---------------------------------------------------------------------------
-- The code — not the id — is what appears in every URL and every audit row.
SELECT a.id, a.audio_code, a.origin_title, a.is_public,
       a.audio_file_url,          -- internal S3 URL; the API never returns this
       a.created_by, a.updated_by, a.version
FROM   audios a
WHERE  a.audio_code = 'HZI_AUD_RAW_V1_Copy(1)_000001'
  AND  a.removed_at IS NULL;


-- ---------------------------------------------------------------------------
-- Visibility split per project
-- ---------------------------------------------------------------------------
-- How many of each project's active audios are public, and whether the project itself is.
SELECT p.project_code,
       p.project_name,
       p.is_visible_to_public,
       COUNT(*) FILTER (WHERE a.id IS NOT NULL)                   AS active_audios,
       COUNT(*) FILTER (WHERE a.is_public = FALSE)                AS hidden_audios,
       COUNT(*) FILTER (WHERE a.is_public IS NULL OR a.is_public) AS public_audios
FROM   projects p
LEFT JOIN audios a ON a.project_id = p.id AND a.removed_at IS NULL
WHERE  p.removed_at IS NULL
GROUP  BY p.project_code, p.project_name, p.is_visible_to_public
ORDER  BY p.is_visible_to_public ASC, active_audios DESC;


-- ---------------------------------------------------------------------------
-- Business-code generation, for reference
-- ---------------------------------------------------------------------------
-- Codes are allocated under a Postgres advisory lock held for the transaction, so two concurrent
-- creates cannot mint the same sequence number:
--
--   SELECT pg_advisory_xact_lock(?)
--
-- The lock key is derived per entity type in CodeGenLock. Nothing to run here — it is listed so
-- the lock shows up when you are reading pg_locks and wondering where it came from.


-- =================================================================================================
-- 8. QUERIES — AUDIT LOGS AND ANALYTICS
-- =================================================================================================

-- Read-only. The analytics module has no table of its own: every report is a UNION ALL across the
-- eleven *_audit_logs tables, projected into one common shape and then aggregated. These are the
-- hand-runnable versions of what AnalyticsService builds.
--
-- The indexes these rely on are in section 2. Without them, each of these queries
-- seq-scans every audit table.


-- ---------------------------------------------------------------------------
-- Per-user activity across every audit table, last 30 days
-- ---------------------------------------------------------------------------
-- Days are bucketed in Asia/Baghdad, matching what the API reports. LIST actions are excluded —
-- they are page views, not work, and they swamp the counts (AnalyticsService.EXCLUDE_LIST_PREDICATE).
WITH all_logs AS (
    SELECT 'audio'   AS entity, action::text AS action, audio_code   AS entity_code,
           actor_username, occurred_at FROM audio_audit_logs
    UNION ALL
    SELECT 'video',   action::text, video_code,   actor_username, occurred_at FROM video_audit_logs
    UNION ALL
    SELECT 'image',   action::text, image_code,   actor_username, occurred_at FROM image_audit_logs
    UNION ALL
    SELECT 'text',    action::text, text_code,    actor_username, occurred_at FROM text_audit_logs
    UNION ALL
    SELECT 'project', action::text, project_code, actor_username, occurred_at FROM project_audit_logs
    UNION ALL
    SELECT 'person',  action::text, person_code,  actor_username, occurred_at FROM person_audit_logs
    UNION ALL
    SELECT 'category',action::text, category_code,actor_username, occurred_at FROM category_audit_logs
    UNION ALL
    SELECT 'maqam',   action::text, maqam_code,   actor_username, occurred_at FROM maqam_audit_logs
    UNION ALL
    SELECT 'physical_media', action::text, physical_media_code,
           actor_username, occurred_at FROM physical_media_audit_logs
    UNION ALL
    SELECT 'user',    action::text, target_username, actor_username, occurred_at FROM user_audit_logs
)
SELECT actor_username,
       DATE_TRUNC('day', occurred_at AT TIME ZONE 'Asia/Baghdad') AS baghdad_day,
       entity,
       action,
       COUNT(*) AS events
FROM   all_logs
WHERE  occurred_at >= NOW() - INTERVAL '30 days'
  AND  action <> 'LIST'
  AND  actor_username IS NOT NULL
GROUP  BY actor_username, baghdad_day, entity, action
ORDER  BY baghdad_day DESC, events DESC;


-- ---------------------------------------------------------------------------
-- The full projected shape
-- ---------------------------------------------------------------------------
-- The query above keeps only the five columns the activity report needs. The feed endpoints
-- project every column instead — the pattern per branch is:
--
--   SELECT '<entity>'      AS entity,
--          action::text    AS action,
--          <entity>_id     AS entity_id,
--          <entity>_code   AS entity_code,
--          actor_user_id, actor_username, actor_display_name,
--          actor_authorities, actor_permissions,
--          device_info, ip_address, session_id,
--          request_method, request_path,
--          occurred_at, details
--     FROM <entity>_audit_logs
--
-- user_audit_logs is the one that does not fit the pattern: it has no *_code column, so it maps
-- target_user_id -> entity_id and target_username -> entity_code.


-- ---------------------------------------------------------------------------
-- Guest interaction score — what "trending" means
-- ---------------------------------------------------------------------------
-- Guest reads are logged separately from staff audit rows. Trending is a time-decayed count over
-- the last seven days: an interaction in the last hour counts 3, in the last day 2, older 1.
SELECT entity_type  AS "entityType",
       entity_code  AS "entityCode",
       SUM(CASE WHEN interacted_at >= NOW() - INTERVAL '1 hour' THEN 3
                WHEN interacted_at >= NOW() - INTERVAL '1 day'  THEN 2
                ELSE                                                 1 END) AS score
FROM   guest_interaction_logs
WHERE  interacted_at >= NOW() - INTERVAL '7 days'
GROUP  BY entity_type, entity_code
ORDER  BY score DESC
LIMIT  20;


-- =================================================================================================
-- 9. QUERIES — MAQAM VOTE PANEL AND LISTEN ACCOUNTABILITY
-- =================================================================================================

-- Read-only. Audio for a maqam record is range-streamed and never downloadable, and every second
-- a teacher listens is recorded in maqam_audio_listen_sessions. These queries are how you check
-- that a vote was cast by someone who actually heard the recording.


-- ---------------------------------------------------------------------------
-- The vote panel with listen accountability
-- ---------------------------------------------------------------------------
-- Two independent measures of listening sit side by side on purpose:
--   aggregate_seconds  the rolled-up counter on the vote row, updated by progress pings
--   session_seconds    the sum of the individual session rows
-- They should agree. A gap means pings were lost, or sessions were written against a vote row
-- that was later replaced.
--
-- audio_duration_seconds is frequently NULL: no application code writes it, so "% heard" cannot
-- be computed unless it was populated out of band.
SELECT m.maqam_code,
       m.song_name,
       m.producer,
       v.teacher_username,
       v.maqam_type,
       v.voted_at,
       v.total_listen_seconds               AS aggregate_seconds,
       COALESCE(SUM(s.seconds_listened), 0) AS session_seconds,
       m.audio_duration_seconds
FROM   list_of_maqam m
JOIN   maqam_teacher_votes v ON v.list_of_maqam_id = m.id
LEFT JOIN maqam_audio_listen_sessions s
       ON s.list_of_maqam_id = m.id
      AND s.teacher_user_id  = v.teacher_user_id
WHERE  m.removed_at IS NULL
GROUP  BY m.maqam_code, m.song_name, m.producer,
          v.teacher_username, v.maqam_type, v.voted_at,
          v.total_listen_seconds, m.audio_duration_seconds
ORDER  BY m.maqam_code, v.teacher_username;


-- ---------------------------------------------------------------------------
-- Total seconds listened per record
-- ---------------------------------------------------------------------------
SELECT s.list_of_maqam_id,
       COALESCE(SUM(s.seconds_listened), 0) AS total_seconds,
       COUNT(*)                             AS session_count,
       COUNT(DISTINCT s.teacher_user_id)    AS teachers_listened
FROM   maqam_audio_listen_sessions s
GROUP  BY s.list_of_maqam_id;

-- Same thing, joined back to the record so it is readable. LEFT JOIN so records nobody has
-- opened yet appear with 0 rather than dropping out.
SELECT m.maqam_code,
       m.song_name,
       COALESCE(SUM(s.seconds_listened), 0) AS total_seconds
FROM   list_of_maqam m
LEFT JOIN maqam_audio_listen_sessions s ON s.list_of_maqam_id = m.id
WHERE  m.removed_at IS NULL
GROUP  BY m.id, m.maqam_code, m.song_name
ORDER  BY total_seconds DESC;


-- ---------------------------------------------------------------------------
-- Seconds per (record, teacher)
-- ---------------------------------------------------------------------------
-- maqam_audio_listen_sessions has no FK to list_of_maqam — the join is on the raw id column, so
-- sessions survive a record being purged. Filter by m.removed_at yourself when that matters.
SELECT s.list_of_maqam_id,
       s.teacher_user_id,
       COALESCE(SUM(s.seconds_listened), 0) AS total_seconds
FROM   maqam_audio_listen_sessions s
GROUP  BY s.list_of_maqam_id, s.teacher_user_id;


-- =================================================================================================
-- 10. QUERIES — PHYSICAL MEDIA INVENTORY
-- =================================================================================================

-- Read-only. physical_media is the inventory of the physical carriers — tapes, discs, reels —
-- most of which arrive through the .xlsx import rather than the API.


-- ---------------------------------------------------------------------------
-- Inventory by type and digitization status
-- ---------------------------------------------------------------------------
-- digitization is a varchar holding the enum name, constrained by a CHECK that the app rebuilds
-- at boot (see section 3). Values: NOT_DIGITIZED, DIGITIZED, DUPLICATED, NULL.
SELECT pm.physical_media_type,
       pm.media_category,
       pm.digitization,
       COUNT(*)                                     AS items,
       COUNT(*) FILTER (WHERE pm.need_to_clear)     AS need_clearing,
       MAX(pm.inventory_number)                     AS highest_number,
       COUNT(*) FILTER (WHERE pm.source = 'IMPORT') AS from_xlsx_import
FROM   physical_media pm
WHERE  pm.removed_at IS NULL
GROUP  BY pm.physical_media_type, pm.media_category, pm.digitization
ORDER  BY pm.physical_media_type, pm.digitization;


-- ---------------------------------------------------------------------------
-- After an import
-- ---------------------------------------------------------------------------
-- The importer does NOT dedupe: the old upsert on (physical_media_type, physical_label) was
-- removed because it merged genuinely distinct tapes. Every sheet row becomes a new record, so
-- repeated labels are expected — this query finds them so a human can decide.
SELECT physical_media_type, physical_label, COUNT(*) AS rows_with_this_label
FROM   physical_media
WHERE  removed_at IS NULL
  AND  physical_label IS NOT NULL
  AND  physical_label <> ''
GROUP  BY physical_media_type, physical_label
HAVING COUNT(*) > 1
ORDER  BY rows_with_this_label DESC, physical_media_type, physical_label;


-- =================================================================================================
-- 11. QUERIES — GUEST CORRECTIONS
-- =================================================================================================

-- Read-only. A guest submits a suggested fix against one field of one record; an admin forwards
-- it to the employee who created that record, and the employee resolves it. Every step also
-- writes a guest_correction_audit_logs row.


-- ---------------------------------------------------------------------------
-- Everything awaiting action
-- ---------------------------------------------------------------------------
-- PENDING = submitted, nobody has looked. FORWARDED = an admin sent it to the record's owner and
-- it is now that employee's to resolve. RESOLVED and REJECTED are terminal.
SELECT gc.status,
       gc.media_type,
       gc.media_code,
       gc.media_title,
       gc.target_field,
       gc.current_value,
       gc.suggested_value,
       gc.guest_username,
       gc.record_created_by,
       gc.forwarded_by,
       gc.forward_note,
       DATE_TRUNC('day', gc.created_at AT TIME ZONE 'Asia/Baghdad') AS baghdad_day
FROM   guest_corrections gc
WHERE  gc.removed_at IS NULL
  AND  gc.status IN ('PENDING', 'FORWARDED')
ORDER  BY gc.created_at DESC;


-- ---------------------------------------------------------------------------
-- Backlog per employee
-- ---------------------------------------------------------------------------
-- Who has forwarded corrections sitting against records they created, and for how long.
SELECT gc.record_created_by AS employee,
       COUNT(*)             AS forwarded_open,
       MIN(gc.forwarded_at) AS oldest_forward,
       NOW() - MIN(gc.forwarded_at) AS oldest_age
FROM   guest_corrections gc
WHERE  gc.removed_at IS NULL
  AND  gc.status = 'FORWARDED'
GROUP  BY gc.record_created_by
ORDER  BY forwarded_open DESC, oldest_forward ASC;


-- ---------------------------------------------------------------------------
-- The audit trail for one correction
-- ---------------------------------------------------------------------------
-- Actions: SUBMIT, VIEW, LIST, FORWARD, RESOLVE, REJECT, REMOVE. Note there is no APPLY action —
-- POST /apply writes a RESOLVE row.
SELECT l.action, l.actor_username, l.occurred_at, l.details
FROM   guest_correction_audit_logs l
WHERE  l.correction_id = 1        -- <- the correction's id
ORDER  BY l.occurred_at ASC;


-- =================================================================================================
-- 12. QUERIES — TAGS AND KEYWORDS
-- =================================================================================================

-- Read-only. Tags and keywords are @ElementCollection side tables, one pair per entity. There is
-- no central tag table: the autocomplete endpoints build the vocabulary by UNION ALL-ing every
-- side table on each call, then cache the result.
--
--   Tags     — 5 tables: audio_tags, video_tags, image_tags, text_tags, project_tags
--   Keywords — 6 tables: the same four media tables, project_keywords, and category_keywords
--
-- Both are canonicalised on write (trimmed, collapsed whitespace, length-capped at 64 for a tag
-- and 200 for a keyword), so LOWER() here is belt and braces rather than the real normalisation.


-- ---------------------------------------------------------------------------
-- Tag frequency across all five tag tables
-- ---------------------------------------------------------------------------
-- Joining back to the owning table and filtering removed_at IS NULL is what keeps trashed records
-- out of the vocabulary — without it, deleted content keeps suggesting its tags forever.
WITH all_tags AS (
    SELECT LOWER(t.tag) AS value FROM audio_tags   t JOIN audios   a ON a.id = t.audio_id
     WHERE a.removed_at IS NULL AND t.tag IS NOT NULL AND t.tag <> ''
    UNION ALL
    SELECT LOWER(t.tag) FROM video_tags   t JOIN videos   v ON v.id = t.video_id
     WHERE v.removed_at IS NULL AND t.tag IS NOT NULL AND t.tag <> ''
    UNION ALL
    SELECT LOWER(t.tag) FROM image_tags   t JOIN images   i ON i.id = t.image_id
     WHERE i.removed_at IS NULL AND t.tag IS NOT NULL AND t.tag <> ''
    UNION ALL
    SELECT LOWER(t.tag) FROM text_tags    t JOIN texts    x ON x.id = t.text_id
     WHERE x.removed_at IS NULL AND t.tag IS NOT NULL AND t.tag <> ''
    UNION ALL
    SELECT LOWER(t.tag) FROM project_tags t JOIN projects p ON p.id = t.project_id
     WHERE p.removed_at IS NULL AND t.tag IS NOT NULL AND t.tag <> ''
)
SELECT value, COUNT(*) AS usage_count
FROM   all_tags
GROUP  BY value
ORDER  BY usage_count DESC, value ASC
LIMIT  100;


-- ---------------------------------------------------------------------------
-- The keyword autocomplete, exactly as GET /api/keywords/suggest runs it
-- ---------------------------------------------------------------------------
-- Replace 'hasan' with the query term. match_rank is the ordering trick: exact match first, then
-- prefix, then substring; anything that does not match at all is rank 3 and filtered out.
WITH all_keywords AS (
    SELECT LOWER(k.keyword) AS value FROM audio_keywords    k JOIN audios     a ON a.id = k.audio_id
     WHERE a.removed_at IS NULL AND k.keyword IS NOT NULL AND k.keyword <> ''
    UNION ALL
    SELECT LOWER(k.keyword) FROM video_keywords    k JOIN videos     v ON v.id = k.video_id
     WHERE v.removed_at IS NULL AND k.keyword IS NOT NULL AND k.keyword <> ''
    UNION ALL
    SELECT LOWER(k.keyword) FROM image_keywords    k JOIN images     i ON i.id = k.image_id
     WHERE i.removed_at IS NULL AND k.keyword IS NOT NULL AND k.keyword <> ''
    UNION ALL
    SELECT LOWER(k.keyword) FROM text_keywords     k JOIN texts      x ON x.id = k.text_id
     WHERE x.removed_at IS NULL AND k.keyword IS NOT NULL AND k.keyword <> ''
    UNION ALL
    SELECT LOWER(k.keyword) FROM project_keywords  k JOIN projects   p ON p.id = k.project_id
     WHERE p.removed_at IS NULL AND k.keyword IS NOT NULL AND k.keyword <> ''
    UNION ALL
    SELECT LOWER(k.keyword) FROM category_keywords k JOIN categories c ON c.id = k.category_id
     WHERE c.removed_at IS NULL AND k.keyword IS NOT NULL AND k.keyword <> ''
),
grouped AS (
    SELECT value, COUNT(*) AS usage_count
      FROM all_keywords
     GROUP BY value
),
ranked AS (
    SELECT value,
           usage_count,
           CASE
             WHEN value = 'hasan'                          THEN 0
             WHEN value LIKE 'hasan' || '%'     ESCAPE '\' THEN 1
             WHEN value LIKE '%' || 'hasan' || '%' ESCAPE '\' THEN 2
             ELSE 3
           END AS match_rank
      FROM grouped
     WHERE value LIKE '%' || 'hasan' || '%' ESCAPE '\'
)
SELECT value, usage_count, match_rank
FROM   ranked
WHERE  match_rank < 3
ORDER  BY match_rank ASC, usage_count DESC, value ASC
LIMIT  20;


-- ---------------------------------------------------------------------------
-- One tag's usage, broken down by entity
-- ---------------------------------------------------------------------------
SELECT 'audio' AS kind, a.audio_code AS code FROM audio_tags   t JOIN audios   a ON a.id = t.audio_id
 WHERE a.removed_at IS NULL AND LOWER(t.tag) = 'hasan'
UNION ALL
SELECT 'video', v.video_code FROM video_tags   t JOIN videos   v ON v.id = t.video_id
 WHERE v.removed_at IS NULL AND LOWER(t.tag) = 'hasan'
UNION ALL
SELECT 'image', i.image_code FROM image_tags   t JOIN images   i ON i.id = t.image_id
 WHERE i.removed_at IS NULL AND LOWER(t.tag) = 'hasan'
UNION ALL
SELECT 'text',  x.text_code  FROM text_tags    t JOIN texts    x ON x.id = t.text_id
 WHERE x.removed_at IS NULL AND LOWER(t.tag) = 'hasan'
UNION ALL
SELECT 'project', p.project_code FROM project_tags t JOIN projects p ON p.id = t.project_id
 WHERE p.removed_at IS NULL AND LOWER(t.tag) = 'hasan';
