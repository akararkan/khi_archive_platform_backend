package ak.dev.khi_archive_platform.platform.repo.text;

import ak.dev.khi_archive_platform.platform.model.project.Project;
import ak.dev.khi_archive_platform.platform.model.text.Text;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@SuppressWarnings("unused")
public interface TextRepository extends JpaRepository<Text, Long> {

    Optional<Text> findByTextCodeAndRemovedAtIsNull(String textCode);

    boolean existsByTextCode(String textCode);

    List<Text> findAllByRemovedAtIsNull();

    List<Text> findAllByRemovedAtIsNotNull();

    Optional<Text> findByTextCode(String textCode);

    /**
     * Bulk soft-trash every active text belonging to the given project.
     * Used when a project is moved to trash so its media follow it.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Text t SET t.removedAt = :removedAt, t.removedBy = :removedBy, " +
            "t.version = COALESCE(t.version, 0) + 1 " +
            "WHERE t.project = :project AND t.removedAt IS NULL")
    int softTrashByProject(@Param("project") Project project,
                           @Param("removedAt") Instant removedAt,
                           @Param("removedBy") String removedBy);

    /**
     * Bulk-restore every trashed text belonging to the given project.
     * Used when a project is restored so its media come back with it.
     * Bumps {@code version} so concurrent edits surface a stale-version error.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Text t SET t.removedAt = NULL, t.removedBy = NULL, " +
            "t.version = COALESCE(t.version, 0) + 1 " +
            "WHERE t.project = :project AND t.removedAt IS NOT NULL")
    int restoreByProject(@Param("project") Project project);

    long countByProject(Project project);

    /** Loads every text for a project regardless of trash state — used during purge to collect S3 URLs. */
    List<Text> findAllByProject(Project project);

    long countByProjectAndTextVersionAndVersionNumber(Project project, String textVersion, Integer versionNumber);

    long countByProjectAndTextVersionAndVersionNumberAndCopyNumber(Project project, String textVersion, Integer versionNumber, Integer copyNumber);

    /**
     * Comprehensive two-phase search: prefix + substring + fuzzy across every
     * Text field and child collection. See {@code ImageRepository.searchByText}
     * for the full algorithm contract.
     */
    @Query(value = """
            WITH cands AS (
                SELECT t.id
                  FROM texts t
                 WHERE t.removed_at IS NULL
                   AND (
                        LOWER(COALESCE(t.text_code, ''))                  LIKE LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(t.text_code, ''))                  LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(t.file_name, ''))                  LIKE LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(t.file_name, ''))                  LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(t.volume_name, ''))                LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(t.directory, ''))                  LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(t.path_in_external_volume, ''))    LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(t.auto_path, ''))                  LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(t.original_title, ''))             LIKE LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(t.original_title, ''))             LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(t.original_title, '')) % LOWER(:qRaw)
                     OR LOWER(COALESCE(t.alternative_title, ''))          LIKE LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(t.alternative_title, ''))          LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(t.alternative_title, '')) % LOWER(:qRaw)
                     OR LOWER(COALESCE(t.title_in_central_kurdish, ''))   LIKE LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(t.title_in_central_kurdish, ''))   LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(t.title_in_central_kurdish, '')) % LOWER(:qRaw)
                     OR LOWER(COALESCE(t.romanized_title, ''))            LIKE LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(t.romanized_title, ''))            LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(t.romanized_title, '')) % LOWER(:qRaw)
                     OR LOWER(COALESCE(t.document_type, ''))              LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(t.description, ''))                LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(t.description, '')) % LOWER(:qRaw)
                     OR LOWER(COALESCE(t.script, ''))                     LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(t.transcription, ''))              LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(t.transcription, '')) % LOWER(:qRaw)
                     OR LOWER(COALESCE(t.isbn, ''))                       LIKE LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(t.isbn, ''))                       LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(t.assignment_number, ''))          LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(t.edition, ''))                    LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(t.volume, ''))                     LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(t.series, ''))                     LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(t.text_version, ''))               LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(t.language, ''))                   LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(t.dialect, ''))                    LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(t.author, ''))                     LIKE LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(t.author, ''))                     LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(t.author, '')) % LOWER(:qRaw)
                     OR LOWER(COALESCE(t.contributors, ''))               LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(t.contributors, '')) % LOWER(:qRaw)
                     OR LOWER(COALESCE(t.printing_house, ''))             LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(t.audience, ''))                   LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(t.accrual_method, ''))             LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(t.provenance, ''))                 LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(t.text_status, ''))                LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(t.archive_cataloging, ''))         LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(t.physical_label, ''))             LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(t.location_in_archive_room, ''))   LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(t.lcc_classification, ''))         LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(t.note, ''))                       LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(t.copyright, ''))                  LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(t.right_owner, ''))                LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(t.license_type, ''))               LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(t.usage_rights, ''))               LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(t.availability, ''))               LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(t.owner, ''))                      LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(t.publisher, ''))                  LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                   )
                UNION
                SELECT s.text_id  FROM text_subjects s WHERE LOWER(s.subject) LIKE LOWER(:q) || '%' ESCAPE '\\' OR LOWER(s.subject) LIKE '%' || LOWER(:q) || '%' ESCAPE '\\' OR LOWER(s.subject) % LOWER(:qRaw)
                UNION
                SELECT g.text_id  FROM text_genres   g WHERE LOWER(g.genre)   LIKE LOWER(:q) || '%' ESCAPE '\\' OR LOWER(g.genre)   LIKE '%' || LOWER(:q) || '%' ESCAPE '\\' OR LOWER(g.genre)   % LOWER(:qRaw)
                UNION
                SELECT tg.text_id FROM text_tags    tg WHERE LOWER(tg.tag)    LIKE LOWER(:q) || '%' ESCAPE '\\' OR LOWER(tg.tag)    LIKE '%' || LOWER(:q) || '%' ESCAPE '\\' OR LOWER(tg.tag)    % LOWER(:qRaw)
                UNION
                SELECT k.text_id  FROM text_keywords k WHERE LOWER(k.keyword) LIKE LOWER(:q) || '%' ESCAPE '\\' OR LOWER(k.keyword) LIKE '%' || LOWER(:q) || '%' ESCAPE '\\' OR LOWER(k.keyword) % LOWER(:qRaw)
                LIMIT :prefilter
            )
            SELECT t.* FROM texts t
              JOIN cands ON cands.id = t.id
             WHERE t.removed_at IS NULL
             ORDER BY
               (CASE
                   WHEN LOWER(COALESCE(t.original_title, ''))           LIKE LOWER(:q) || '%' ESCAPE '\\' THEN 1
                   WHEN LOWER(COALESCE(t.alternative_title, ''))        LIKE LOWER(:q) || '%' ESCAPE '\\' THEN 1
                   WHEN LOWER(COALESCE(t.title_in_central_kurdish, '')) LIKE LOWER(:q) || '%' ESCAPE '\\' THEN 1
                   WHEN LOWER(COALESCE(t.romanized_title, ''))          LIKE LOWER(:q) || '%' ESCAPE '\\' THEN 1
                   WHEN LOWER(COALESCE(t.text_code, ''))                LIKE LOWER(:q) || '%' ESCAPE '\\' THEN 1
                   WHEN LOWER(COALESCE(t.author, ''))                   LIKE LOWER(:q) || '%' ESCAPE '\\' THEN 1
                   WHEN LOWER(COALESCE(t.isbn, ''))                     LIKE LOWER(:q) || '%' ESCAPE '\\' THEN 1
                   WHEN LOWER(COALESCE(t.file_name, ''))                LIKE LOWER(:q) || '%' ESCAPE '\\' THEN 1
                   ELSE 0
                END) DESC,
               (CASE
                   WHEN LOWER(COALESCE(t.original_title, ''))           LIKE '%' || LOWER(:q) || '%' ESCAPE '\\' THEN 1
                   WHEN LOWER(COALESCE(t.alternative_title, ''))        LIKE '%' || LOWER(:q) || '%' ESCAPE '\\' THEN 1
                   WHEN LOWER(COALESCE(t.title_in_central_kurdish, '')) LIKE '%' || LOWER(:q) || '%' ESCAPE '\\' THEN 1
                   WHEN LOWER(COALESCE(t.romanized_title, ''))          LIKE '%' || LOWER(:q) || '%' ESCAPE '\\' THEN 1
                   WHEN LOWER(COALESCE(t.author, ''))                   LIKE '%' || LOWER(:q) || '%' ESCAPE '\\' THEN 1
                   ELSE 0
                END) DESC,
               GREATEST(
                   similarity(LOWER(COALESCE(t.original_title, '')),           LOWER(:qRaw)),
                   similarity(LOWER(COALESCE(t.alternative_title, '')),        LOWER(:qRaw)),
                   similarity(LOWER(COALESCE(t.title_in_central_kurdish, '')), LOWER(:qRaw)),
                   similarity(LOWER(COALESCE(t.romanized_title, '')),          LOWER(:qRaw)),
                   similarity(LOWER(COALESCE(t.author, '')),                   LOWER(:qRaw)),
                   similarity(LOWER(COALESCE(t.description, '')),              LOWER(:qRaw)),
                   similarity(LOWER(COALESCE(t.transcription, '')),            LOWER(:qRaw)),
                   COALESCE((SELECT MAX(similarity(LOWER(tg.tag),   LOWER(:qRaw))) FROM text_tags     tg WHERE tg.text_id = t.id), 0),
                   COALESCE((SELECT MAX(similarity(LOWER(k.keyword), LOWER(:qRaw))) FROM text_keywords k WHERE k.text_id = t.id), 0),
                   COALESCE((SELECT MAX(similarity(LOWER(s.subject), LOWER(:qRaw))) FROM text_subjects s WHERE s.text_id = t.id), 0),
                   COALESCE((SELECT MAX(similarity(LOWER(g.genre),   LOWER(:qRaw))) FROM text_genres   g WHERE g.text_id = t.id), 0)
               ) DESC,
               t.original_title ASC NULLS LAST,
               t.id ASC
             LIMIT :limit
            """, nativeQuery = true)
    List<Text> searchByText(@Param("q") String escapedQuery,
                            @Param("qRaw") String rawQuery,
                            @Param("prefilter") int prefilterLimit,
                            @Param("limit") int limit);
}
