package ak.dev.khi_archive_platform.platform.repo.video;

import ak.dev.khi_archive_platform.platform.model.project.Project;
import ak.dev.khi_archive_platform.platform.model.video.Video;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

@SuppressWarnings("unused")
public interface VideoRepository extends JpaRepository<Video, Long> {

    Optional<Video> findByVideoCodeAndRemovedAtIsNull(String videoCode);

    boolean existsByVideoCode(String videoCode);

    List<Video> findAllByRemovedAtIsNull();

    long countByProject(Project project);

    long countByProjectAndVideoVersionAndVersionNumber(Project project, String videoVersion, Integer versionNumber);

    long countByProjectAndVideoVersionAndVersionNumberAndCopyNumber(Project project, String videoVersion, Integer versionNumber, Integer copyNumber);

    /**
     * Comprehensive two-phase search: prefix + substring + fuzzy across every
     * Video field and child collection. See {@code ImageRepository.searchByText}
     * for the full algorithm contract.
     */
    @Query(value = """
            WITH cands AS (
                SELECT v.id
                  FROM videos v
                 WHERE v.removed_at IS NULL
                   AND (
                        LOWER(COALESCE(v.video_code, ''))                  LIKE LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(v.video_code, ''))                  LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(v.file_name, ''))                   LIKE LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(v.file_name, ''))                   LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(v.volume_name, ''))                 LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(v.directory, ''))                   LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(v.path_in_external_volume, ''))     LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(v.auto_path, ''))                   LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(v.original_title, ''))              LIKE LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(v.original_title, ''))              LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(v.original_title, '')) % LOWER(:qRaw)
                     OR LOWER(COALESCE(v.alternative_title, ''))           LIKE LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(v.alternative_title, ''))           LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(v.alternative_title, '')) % LOWER(:qRaw)
                     OR LOWER(COALESCE(v.title_in_central_kurdish, ''))    LIKE LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(v.title_in_central_kurdish, ''))    LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(v.title_in_central_kurdish, '')) % LOWER(:qRaw)
                     OR LOWER(COALESCE(v.romanized_title, ''))             LIKE LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(v.romanized_title, ''))             LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(v.romanized_title, '')) % LOWER(:qRaw)
                     OR LOWER(COALESCE(v.event, ''))                       LIKE LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(v.event, ''))                       LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(v.event, '')) % LOWER(:qRaw)
                     OR LOWER(COALESCE(v.location, ''))                    LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(v.location, '')) % LOWER(:qRaw)
                     OR LOWER(COALESCE(v.description, ''))                 LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(v.description, '')) % LOWER(:qRaw)
                     OR LOWER(COALESCE(v.person_shown_in_video, ''))       LIKE LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(v.person_shown_in_video, ''))       LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(v.person_shown_in_video, '')) % LOWER(:qRaw)
                     OR LOWER(COALESCE(v.video_version, ''))               LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(v.resolution, ''))                  LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(v.video_codec, ''))                 LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(v.audio_codec, ''))                 LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(v.audio_channels, ''))              LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(v.language, ''))                    LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(v.dialect, ''))                     LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(v.subtitle, ''))                    LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(v.creator_artist_director, ''))     LIKE LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(v.creator_artist_director, ''))     LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(v.creator_artist_director, '')) % LOWER(:qRaw)
                     OR LOWER(COALESCE(v.producer, ''))                    LIKE LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(v.producer, ''))                    LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(v.producer, '')) % LOWER(:qRaw)
                     OR LOWER(COALESCE(v.contributor, ''))                 LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(v.contributor, '')) % LOWER(:qRaw)
                     OR LOWER(COALESCE(v.audience, ''))                    LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(v.accrual_method, ''))              LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(v.provenance, ''))                  LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(v.video_status, ''))                LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(v.archive_cataloging, ''))          LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(v.physical_label, ''))              LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(v.location_in_archive_room, ''))    LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(v.lcc_classification, ''))          LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(v.note, ''))                        LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(v.copyright, ''))                   LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(v.right_owner, ''))                 LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(v.license_type, ''))                LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(v.usage_rights, ''))                LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(v.availability, ''))                LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(v.owner, ''))                       LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(v.publisher, ''))                   LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                   )
                UNION
                SELECT s.video_id FROM video_subjects s WHERE LOWER(s.subject)       LIKE LOWER(:q) || '%' ESCAPE '\\' OR LOWER(s.subject)       LIKE '%' || LOWER(:q) || '%' ESCAPE '\\' OR LOWER(s.subject)       % LOWER(:qRaw)
                UNION
                SELECT g.video_id FROM video_genres   g WHERE LOWER(g.genre)         LIKE LOWER(:q) || '%' ESCAPE '\\' OR LOWER(g.genre)         LIKE '%' || LOWER(:q) || '%' ESCAPE '\\' OR LOWER(g.genre)         % LOWER(:qRaw)
                UNION
                SELECT c.video_id FROM video_colors   c WHERE LOWER(c.color)         LIKE LOWER(:q) || '%' ESCAPE '\\' OR LOWER(c.color)         LIKE '%' || LOWER(:q) || '%' ESCAPE '\\' OR LOWER(c.color)         % LOWER(:qRaw)
                UNION
                SELECT u.video_id FROM video_usages   u WHERE LOWER(u.usage_context) LIKE LOWER(:q) || '%' ESCAPE '\\' OR LOWER(u.usage_context) LIKE '%' || LOWER(:q) || '%' ESCAPE '\\' OR LOWER(u.usage_context) % LOWER(:qRaw)
                UNION
                SELECT t.video_id FROM video_tags     t WHERE LOWER(t.tag)           LIKE LOWER(:q) || '%' ESCAPE '\\' OR LOWER(t.tag)           LIKE '%' || LOWER(:q) || '%' ESCAPE '\\' OR LOWER(t.tag)           % LOWER(:qRaw)
                UNION
                SELECT k.video_id FROM video_keywords k WHERE LOWER(k.keyword)       LIKE LOWER(:q) || '%' ESCAPE '\\' OR LOWER(k.keyword)       LIKE '%' || LOWER(:q) || '%' ESCAPE '\\' OR LOWER(k.keyword)       % LOWER(:qRaw)
                LIMIT :prefilter
            )
            SELECT v.* FROM videos v
              JOIN cands ON cands.id = v.id
             WHERE v.removed_at IS NULL
             ORDER BY
               (CASE
                   WHEN LOWER(COALESCE(v.original_title, ''))            LIKE LOWER(:q) || '%' ESCAPE '\\' THEN 1
                   WHEN LOWER(COALESCE(v.alternative_title, ''))         LIKE LOWER(:q) || '%' ESCAPE '\\' THEN 1
                   WHEN LOWER(COALESCE(v.title_in_central_kurdish, ''))  LIKE LOWER(:q) || '%' ESCAPE '\\' THEN 1
                   WHEN LOWER(COALESCE(v.romanized_title, ''))           LIKE LOWER(:q) || '%' ESCAPE '\\' THEN 1
                   WHEN LOWER(COALESCE(v.video_code, ''))                LIKE LOWER(:q) || '%' ESCAPE '\\' THEN 1
                   WHEN LOWER(COALESCE(v.creator_artist_director, ''))   LIKE LOWER(:q) || '%' ESCAPE '\\' THEN 1
                   WHEN LOWER(COALESCE(v.producer, ''))                  LIKE LOWER(:q) || '%' ESCAPE '\\' THEN 1
                   WHEN LOWER(COALESCE(v.person_shown_in_video, ''))     LIKE LOWER(:q) || '%' ESCAPE '\\' THEN 1
                   WHEN LOWER(COALESCE(v.event, ''))                     LIKE LOWER(:q) || '%' ESCAPE '\\' THEN 1
                   WHEN LOWER(COALESCE(v.file_name, ''))                 LIKE LOWER(:q) || '%' ESCAPE '\\' THEN 1
                   ELSE 0
                END) DESC,
               (CASE
                   WHEN LOWER(COALESCE(v.original_title, ''))            LIKE '%' || LOWER(:q) || '%' ESCAPE '\\' THEN 1
                   WHEN LOWER(COALESCE(v.alternative_title, ''))         LIKE '%' || LOWER(:q) || '%' ESCAPE '\\' THEN 1
                   WHEN LOWER(COALESCE(v.title_in_central_kurdish, ''))  LIKE '%' || LOWER(:q) || '%' ESCAPE '\\' THEN 1
                   WHEN LOWER(COALESCE(v.romanized_title, ''))           LIKE '%' || LOWER(:q) || '%' ESCAPE '\\' THEN 1
                   WHEN LOWER(COALESCE(v.creator_artist_director, ''))   LIKE '%' || LOWER(:q) || '%' ESCAPE '\\' THEN 1
                   WHEN LOWER(COALESCE(v.producer, ''))                  LIKE '%' || LOWER(:q) || '%' ESCAPE '\\' THEN 1
                   WHEN LOWER(COALESCE(v.event, ''))                     LIKE '%' || LOWER(:q) || '%' ESCAPE '\\' THEN 1
                   ELSE 0
                END) DESC,
               GREATEST(
                   similarity(LOWER(COALESCE(v.original_title, '')),            LOWER(:qRaw)),
                   similarity(LOWER(COALESCE(v.alternative_title, '')),         LOWER(:qRaw)),
                   similarity(LOWER(COALESCE(v.title_in_central_kurdish, '')),  LOWER(:qRaw)),
                   similarity(LOWER(COALESCE(v.romanized_title, '')),           LOWER(:qRaw)),
                   similarity(LOWER(COALESCE(v.creator_artist_director, '')),   LOWER(:qRaw)),
                   similarity(LOWER(COALESCE(v.producer, '')),                  LOWER(:qRaw)),
                   similarity(LOWER(COALESCE(v.event, '')),                     LOWER(:qRaw)),
                   similarity(LOWER(COALESCE(v.location, '')),                  LOWER(:qRaw)),
                   similarity(LOWER(COALESCE(v.person_shown_in_video, '')),     LOWER(:qRaw)),
                   similarity(LOWER(COALESCE(v.description, '')),               LOWER(:qRaw)),
                   COALESCE((SELECT MAX(similarity(LOWER(t.tag),     LOWER(:qRaw))) FROM video_tags     t WHERE t.video_id = v.id), 0),
                   COALESCE((SELECT MAX(similarity(LOWER(k.keyword), LOWER(:qRaw))) FROM video_keywords k WHERE k.video_id = v.id), 0),
                   COALESCE((SELECT MAX(similarity(LOWER(s.subject), LOWER(:qRaw))) FROM video_subjects s WHERE s.video_id = v.id), 0),
                   COALESCE((SELECT MAX(similarity(LOWER(g.genre),   LOWER(:qRaw))) FROM video_genres   g WHERE g.video_id = v.id), 0)
               ) DESC,
               v.original_title ASC NULLS LAST,
               v.id ASC
             LIMIT :limit
            """, nativeQuery = true)
    List<Video> searchByText(@Param("q") String escapedQuery,
                             @Param("qRaw") String rawQuery,
                             @Param("prefilter") int prefilterLimit,
                             @Param("limit") int limit);
}
