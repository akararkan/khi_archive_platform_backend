package ak.dev.khi_archive_platform.platform.repo.audio;

import ak.dev.khi_archive_platform.platform.model.audio.Audio;
import ak.dev.khi_archive_platform.platform.model.project.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

@SuppressWarnings("unused")
public interface AudioRepository extends JpaRepository<Audio, Long> {

    Optional<Audio> findByAudioCodeAndRemovedAtIsNull(String audioCode);

    boolean existsByAudioCode(String audioCode);

    List<Audio> findAllByRemovedAtIsNull();

    long countByProject(Project project);

    long countByProjectAndAudioVersionAndVersionNumber(Project project, String audioVersion, Integer versionNumber);

    long countByProjectAndAudioVersionAndVersionNumberAndCopyNumber(Project project, String audioVersion, Integer versionNumber, Integer copyNumber);

    /**
     * Comprehensive two-phase search: prefix + substring + fuzzy across every
     * Audio field and child collection. See {@code ImageRepository.searchByText}
     * for the full algorithm contract.
     */
    @Query(value = """
            WITH cands AS (
                SELECT a.id
                  FROM audios a
                 WHERE a.removed_at IS NULL
                   AND (
                        LOWER(COALESCE(a.audio_code, ''))            LIKE LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(a.audio_code, ''))            LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(a.fullname, ''))              LIKE LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(a.fullname, ''))              LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(a.fullname, '')) % LOWER(:qRaw)
                     OR LOWER(COALESCE(a.volume_name, ''))           LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(a.directory_name, ''))        LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(a.path_in_external, ''))      LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(a.auto_path, ''))             LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(a.origin_title, ''))          LIKE LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(a.origin_title, ''))          LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(a.origin_title, '')) % LOWER(:qRaw)
                     OR LOWER(COALESCE(a.alter_title, ''))           LIKE LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(a.alter_title, ''))           LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(a.alter_title, '')) % LOWER(:qRaw)
                     OR LOWER(COALESCE(a.central_kurdish_title, '')) LIKE LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(a.central_kurdish_title, '')) LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(a.central_kurdish_title, '')) % LOWER(:qRaw)
                     OR LOWER(COALESCE(a.romanized_title, ''))       LIKE LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(a.romanized_title, ''))       LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(a.romanized_title, '')) % LOWER(:qRaw)
                     OR LOWER(COALESCE(a.form, ''))                  LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(a.type_of_basta, ''))         LIKE LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(a.type_of_basta, ''))         LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(a.type_of_maqam, ''))         LIKE LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(a.type_of_maqam, ''))         LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(a.abstract_text, ''))         LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(a.abstract_text, '')) % LOWER(:qRaw)
                     OR LOWER(COALESCE(a.description, ''))           LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(a.description, '')) % LOWER(:qRaw)
                     OR LOWER(COALESCE(a.speaker, ''))               LIKE LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(a.speaker, ''))               LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(a.speaker, '')) % LOWER(:qRaw)
                     OR LOWER(COALESCE(a.producer, ''))              LIKE LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(a.producer, ''))              LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(a.producer, '')) % LOWER(:qRaw)
                     OR LOWER(COALESCE(a.composer, ''))              LIKE LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(a.composer, ''))              LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(a.composer, '')) % LOWER(:qRaw)
                     OR LOWER(COALESCE(a.language, ''))              LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(a.dialect, ''))               LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(a.type_of_composition, ''))   LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(a.type_of_performance, ''))   LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(a.lyrics, ''))                LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(a.lyrics, '')) % LOWER(:qRaw)
                     OR LOWER(COALESCE(a.poet, ''))                  LIKE LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(a.poet, ''))                  LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(a.poet, '')) % LOWER(:qRaw)
                     OR LOWER(COALESCE(a.recording_venue, ''))       LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(a.recording_venue, '')) % LOWER(:qRaw)
                     OR LOWER(COALESCE(a.city, ''))                  LIKE LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(a.city, ''))                  LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(a.city, '')) % LOWER(:qRaw)
                     OR LOWER(COALESCE(a.region, ''))                LIKE LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(a.region, ''))                LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(a.region, '')) % LOWER(:qRaw)
                     OR LOWER(COALESCE(a.audience, ''))              LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(a.physical_label, ''))        LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(a.location_archive, ''))      LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(a.degitized_by, ''))          LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(a.degitization_equipment, '')) LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(a.audio_file_note, ''))       LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(a.audio_channel, ''))         LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(a.audio_version, ''))         LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(a.lcc_classification, ''))    LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(a.accrual_method, ''))        LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(a.provenance, ''))            LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(a.copyright, ''))             LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(a.right_owner, ''))           LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(a.availability, ''))          LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(a.license_type, ''))          LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(a.usage_rights, ''))          LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(a.owner, ''))                 LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(a.publisher, ''))             LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(a.archive_local_note, ''))    LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                   )
                UNION
                SELECT g.audio_id  FROM audio_genres       g  WHERE LOWER(g.genre)       LIKE LOWER(:q) || '%' ESCAPE '\\' OR LOWER(g.genre)       LIKE '%' || LOWER(:q) || '%' ESCAPE '\\' OR LOWER(g.genre)       % LOWER(:qRaw)
                UNION
                SELECT c.audio_id  FROM audio_contributors c  WHERE LOWER(c.contributor) LIKE LOWER(:q) || '%' ESCAPE '\\' OR LOWER(c.contributor) LIKE '%' || LOWER(:q) || '%' ESCAPE '\\' OR LOWER(c.contributor) % LOWER(:qRaw)
                UNION
                SELECT t.audio_id  FROM audio_tags         t  WHERE LOWER(t.tag)         LIKE LOWER(:q) || '%' ESCAPE '\\' OR LOWER(t.tag)         LIKE '%' || LOWER(:q) || '%' ESCAPE '\\' OR LOWER(t.tag)         % LOWER(:qRaw)
                UNION
                SELECT k.audio_id  FROM audio_keywords     k  WHERE LOWER(k.keyword)     LIKE LOWER(:q) || '%' ESCAPE '\\' OR LOWER(k.keyword)     LIKE '%' || LOWER(:q) || '%' ESCAPE '\\' OR LOWER(k.keyword)     % LOWER(:qRaw)
                LIMIT :prefilter
            )
            SELECT a.* FROM audios a
              JOIN cands ON cands.id = a.id
             WHERE a.removed_at IS NULL
             ORDER BY
               (CASE
                   WHEN LOWER(COALESCE(a.origin_title, ''))          LIKE LOWER(:q) || '%' ESCAPE '\\' THEN 1
                   WHEN LOWER(COALESCE(a.alter_title, ''))           LIKE LOWER(:q) || '%' ESCAPE '\\' THEN 1
                   WHEN LOWER(COALESCE(a.central_kurdish_title, '')) LIKE LOWER(:q) || '%' ESCAPE '\\' THEN 1
                   WHEN LOWER(COALESCE(a.romanized_title, ''))       LIKE LOWER(:q) || '%' ESCAPE '\\' THEN 1
                   WHEN LOWER(COALESCE(a.audio_code, ''))            LIKE LOWER(:q) || '%' ESCAPE '\\' THEN 1
                   WHEN LOWER(COALESCE(a.speaker, ''))               LIKE LOWER(:q) || '%' ESCAPE '\\' THEN 1
                   WHEN LOWER(COALESCE(a.composer, ''))              LIKE LOWER(:q) || '%' ESCAPE '\\' THEN 1
                   WHEN LOWER(COALESCE(a.poet, ''))                  LIKE LOWER(:q) || '%' ESCAPE '\\' THEN 1
                   WHEN LOWER(COALESCE(a.producer, ''))              LIKE LOWER(:q) || '%' ESCAPE '\\' THEN 1
                   WHEN LOWER(COALESCE(a.fullname, ''))              LIKE LOWER(:q) || '%' ESCAPE '\\' THEN 1
                   WHEN LOWER(COALESCE(a.city, ''))                  LIKE LOWER(:q) || '%' ESCAPE '\\' THEN 1
                   WHEN LOWER(COALESCE(a.region, ''))                LIKE LOWER(:q) || '%' ESCAPE '\\' THEN 1
                   ELSE 0
                END) DESC,
               (CASE
                   WHEN LOWER(COALESCE(a.origin_title, ''))          LIKE '%' || LOWER(:q) || '%' ESCAPE '\\' THEN 1
                   WHEN LOWER(COALESCE(a.alter_title, ''))           LIKE '%' || LOWER(:q) || '%' ESCAPE '\\' THEN 1
                   WHEN LOWER(COALESCE(a.central_kurdish_title, '')) LIKE '%' || LOWER(:q) || '%' ESCAPE '\\' THEN 1
                   WHEN LOWER(COALESCE(a.romanized_title, ''))       LIKE '%' || LOWER(:q) || '%' ESCAPE '\\' THEN 1
                   WHEN LOWER(COALESCE(a.speaker, ''))               LIKE '%' || LOWER(:q) || '%' ESCAPE '\\' THEN 1
                   WHEN LOWER(COALESCE(a.composer, ''))              LIKE '%' || LOWER(:q) || '%' ESCAPE '\\' THEN 1
                   WHEN LOWER(COALESCE(a.poet, ''))                  LIKE '%' || LOWER(:q) || '%' ESCAPE '\\' THEN 1
                   WHEN LOWER(COALESCE(a.producer, ''))              LIKE '%' || LOWER(:q) || '%' ESCAPE '\\' THEN 1
                   ELSE 0
                END) DESC,
               GREATEST(
                   similarity(LOWER(COALESCE(a.fullname, '')),               LOWER(:qRaw)),
                   similarity(LOWER(COALESCE(a.origin_title, '')),           LOWER(:qRaw)),
                   similarity(LOWER(COALESCE(a.alter_title, '')),            LOWER(:qRaw)),
                   similarity(LOWER(COALESCE(a.central_kurdish_title, '')),  LOWER(:qRaw)),
                   similarity(LOWER(COALESCE(a.romanized_title, '')),        LOWER(:qRaw)),
                   similarity(LOWER(COALESCE(a.speaker, '')),                LOWER(:qRaw)),
                   similarity(LOWER(COALESCE(a.composer, '')),               LOWER(:qRaw)),
                   similarity(LOWER(COALESCE(a.poet, '')),                   LOWER(:qRaw)),
                   similarity(LOWER(COALESCE(a.producer, '')),               LOWER(:qRaw)),
                   similarity(LOWER(COALESCE(a.lyrics, '')),                 LOWER(:qRaw)),
                   similarity(LOWER(COALESCE(a.description, '')),            LOWER(:qRaw)),
                   similarity(LOWER(COALESCE(a.abstract_text, '')),          LOWER(:qRaw)),
                   similarity(LOWER(COALESCE(a.city, '')),                   LOWER(:qRaw)),
                   similarity(LOWER(COALESCE(a.region, '')),                 LOWER(:qRaw)),
                   COALESCE((SELECT MAX(similarity(LOWER(t.tag),         LOWER(:qRaw))) FROM audio_tags         t WHERE t.audio_id = a.id), 0),
                   COALESCE((SELECT MAX(similarity(LOWER(k.keyword),     LOWER(:qRaw))) FROM audio_keywords     k WHERE k.audio_id = a.id), 0),
                   COALESCE((SELECT MAX(similarity(LOWER(g.genre),       LOWER(:qRaw))) FROM audio_genres       g WHERE g.audio_id = a.id), 0),
                   COALESCE((SELECT MAX(similarity(LOWER(c.contributor), LOWER(:qRaw))) FROM audio_contributors c WHERE c.audio_id = a.id), 0)
               ) DESC,
               a.origin_title ASC NULLS LAST,
               a.id ASC
             LIMIT :limit
            """, nativeQuery = true)
    List<Audio> searchByText(@Param("q") String escapedQuery,
                             @Param("qRaw") String rawQuery,
                             @Param("prefilter") int prefilterLimit,
                             @Param("limit") int limit);
}
