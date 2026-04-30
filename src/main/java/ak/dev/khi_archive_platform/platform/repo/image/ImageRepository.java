package ak.dev.khi_archive_platform.platform.repo.image;

import ak.dev.khi_archive_platform.platform.model.image.Image;
import ak.dev.khi_archive_platform.platform.model.project.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@SuppressWarnings("unused")
public interface ImageRepository extends JpaRepository<Image, Long> {

    Optional<Image> findByImageCodeAndRemovedAtIsNull(String imageCode);

    boolean existsByImageCode(String imageCode);

    List<Image> findAllByRemovedAtIsNull();

    List<Image> findAllByRemovedAtIsNotNull();

    Optional<Image> findByImageCode(String imageCode);

    /**
     * Bulk soft-trash every active image belonging to the given project.
     * Used when a project is moved to trash so its media follow it.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Image i SET i.removedAt = :removedAt, i.removedBy = :removedBy, " +
            "i.version = COALESCE(i.version, 0) + 1 " +
            "WHERE i.project = :project AND i.removedAt IS NULL")
    int softTrashByProject(@Param("project") Project project,
                           @Param("removedAt") Instant removedAt,
                           @Param("removedBy") String removedBy);

    /**
     * Bulk-restore every trashed image belonging to the given project.
     * Used when a project is restored so its media come back with it.
     * Bumps {@code version} so concurrent edits surface a stale-version error.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Image i SET i.removedAt = NULL, i.removedBy = NULL, " +
            "i.version = COALESCE(i.version, 0) + 1 " +
            "WHERE i.project = :project AND i.removedAt IS NOT NULL")
    int restoreByProject(@Param("project") Project project);

    long countByProject(Project project);

    /** Loads every image for a project regardless of trash state — used during purge to collect S3 URLs. */
    List<Image> findAllByProject(Project project);

    long countByProjectAndImageVersionAndVersionNumber(Project project, String imageVersion, Integer versionNumber);

    long countByProjectAndImageVersionAndVersionNumberAndCopyNumber(Project project, String imageVersion, Integer versionNumber, Integer copyNumber);

    /**
     * Comprehensive two-phase search across every searchable Image field and
     * child collection. Three operator legs per column:
     *   1. {@code LIKE 'q%'}  – prefix-anchored (works from 1 letter, fast on
     *                            btree text_pattern_ops indexes)
     *   2. {@code LIKE '%q%'} – substring (GIN trigram for q.length >= 3)
     *   3. {@code col % q}    – fuzzy (GIN trigram, typo-tolerant for q.length >= 3)
     *
     * Phase 1 (CTE 'cands') produces a bounded candidate set; phase 2 ranks in
     * three tiers: prefix-on-primary > substring-on-primary > fuzzy similarity.
     * The {@code q} parameter must already have LIKE wildcards escaped (`%`,
     * `_`, `\`) – the caller (service) does this.
     */
    @Query(value = """
            WITH cands AS (
                SELECT i.id
                  FROM images i
                 WHERE i.removed_at IS NULL
                   AND (
                        LOWER(COALESCE(i.image_code, ''))                  LIKE LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(i.image_code, ''))                  LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(i.file_name, ''))                   LIKE LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(i.file_name, ''))                   LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(i.volume_name, ''))                 LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(i.directory, ''))                   LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(i.path_in_external_volume, ''))     LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(i.auto_path, ''))                   LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(i.original_title, ''))              LIKE LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(i.original_title, ''))              LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(i.original_title, '')) % LOWER(:qRaw)
                     OR LOWER(COALESCE(i.alternative_title, ''))           LIKE LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(i.alternative_title, ''))           LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(i.alternative_title, '')) % LOWER(:qRaw)
                     OR LOWER(COALESCE(i.title_in_central_kurdish, ''))    LIKE LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(i.title_in_central_kurdish, ''))    LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(i.title_in_central_kurdish, '')) % LOWER(:qRaw)
                     OR LOWER(COALESCE(i.romanized_title, ''))             LIKE LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(i.romanized_title, ''))             LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(i.romanized_title, '')) % LOWER(:qRaw)
                     OR LOWER(COALESCE(i.form, ''))                        LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(i.event, ''))                       LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(i.event, '')) % LOWER(:qRaw)
                     OR LOWER(COALESCE(i.location, ''))                    LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(i.location, '')) % LOWER(:qRaw)
                     OR LOWER(COALESCE(i.description, ''))                 LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(i.description, '')) % LOWER(:qRaw)
                     OR LOWER(COALESCE(i.person_shown_in_image, ''))       LIKE LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(i.person_shown_in_image, ''))       LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(i.person_shown_in_image, '')) % LOWER(:qRaw)
                     OR LOWER(COALESCE(i.image_version, ''))               LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(i.manufacturer, ''))                LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(i.model, ''))                       LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(i.lens, ''))                        LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(i.creator_artist_photographer, '')) LIKE LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(i.creator_artist_photographer, '')) LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(i.creator_artist_photographer, '')) % LOWER(:qRaw)
                     OR LOWER(COALESCE(i.contributor, ''))                 LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(i.contributor, '')) % LOWER(:qRaw)
                     OR LOWER(COALESCE(i.audience, ''))                    LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(i.accrual_method, ''))              LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(i.provenance, ''))                  LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(i.photostory, ''))                  LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(i.image_status, ''))                LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(i.archive_cataloging, ''))          LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(i.physical_label, ''))              LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(i.location_in_archive_room, ''))    LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(i.lcc_classification, ''))          LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(i.note, ''))                        LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(i.copyright, ''))                   LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(i.right_owner, ''))                 LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(i.license_type, ''))                LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(i.usage_rights, ''))                LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(i.availability, ''))                LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(i.owner, ''))                       LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                     OR LOWER(COALESCE(i.publisher, ''))                   LIKE '%' || LOWER(:q) || '%' ESCAPE '\\'
                   )
                UNION
                SELECT s.image_id FROM image_subjects s WHERE LOWER(s.subject)       LIKE LOWER(:q) || '%' ESCAPE '\\' OR LOWER(s.subject)       LIKE '%' || LOWER(:q) || '%' ESCAPE '\\' OR LOWER(s.subject)       % LOWER(:qRaw)
                UNION
                SELECT g.image_id FROM image_genres   g WHERE LOWER(g.genre)         LIKE LOWER(:q) || '%' ESCAPE '\\' OR LOWER(g.genre)         LIKE '%' || LOWER(:q) || '%' ESCAPE '\\' OR LOWER(g.genre)         % LOWER(:qRaw)
                UNION
                SELECT c.image_id FROM image_colors   c WHERE LOWER(c.color)         LIKE LOWER(:q) || '%' ESCAPE '\\' OR LOWER(c.color)         LIKE '%' || LOWER(:q) || '%' ESCAPE '\\' OR LOWER(c.color)         % LOWER(:qRaw)
                UNION
                SELECT u.image_id FROM image_usages   u WHERE LOWER(u.usage_context) LIKE LOWER(:q) || '%' ESCAPE '\\' OR LOWER(u.usage_context) LIKE '%' || LOWER(:q) || '%' ESCAPE '\\' OR LOWER(u.usage_context) % LOWER(:qRaw)
                UNION
                SELECT t.image_id FROM image_tags     t WHERE LOWER(t.tag)           LIKE LOWER(:q) || '%' ESCAPE '\\' OR LOWER(t.tag)           LIKE '%' || LOWER(:q) || '%' ESCAPE '\\' OR LOWER(t.tag)           % LOWER(:qRaw)
                UNION
                SELECT k.image_id FROM image_keywords k WHERE LOWER(k.keyword)       LIKE LOWER(:q) || '%' ESCAPE '\\' OR LOWER(k.keyword)       LIKE '%' || LOWER(:q) || '%' ESCAPE '\\' OR LOWER(k.keyword)       % LOWER(:qRaw)
                LIMIT :prefilter
            )
            SELECT i.* FROM images i
              JOIN cands ON cands.id = i.id
             WHERE i.removed_at IS NULL
             ORDER BY
               -- Tier 1: prefix-anchored hit on a primary field
               (CASE
                   WHEN LOWER(COALESCE(i.original_title, ''))              LIKE LOWER(:q) || '%' ESCAPE '\\' THEN 1
                   WHEN LOWER(COALESCE(i.alternative_title, ''))           LIKE LOWER(:q) || '%' ESCAPE '\\' THEN 1
                   WHEN LOWER(COALESCE(i.title_in_central_kurdish, ''))    LIKE LOWER(:q) || '%' ESCAPE '\\' THEN 1
                   WHEN LOWER(COALESCE(i.romanized_title, ''))             LIKE LOWER(:q) || '%' ESCAPE '\\' THEN 1
                   WHEN LOWER(COALESCE(i.image_code, ''))                  LIKE LOWER(:q) || '%' ESCAPE '\\' THEN 1
                   WHEN LOWER(COALESCE(i.creator_artist_photographer, '')) LIKE LOWER(:q) || '%' ESCAPE '\\' THEN 1
                   WHEN LOWER(COALESCE(i.person_shown_in_image, ''))       LIKE LOWER(:q) || '%' ESCAPE '\\' THEN 1
                   WHEN LOWER(COALESCE(i.file_name, ''))                   LIKE LOWER(:q) || '%' ESCAPE '\\' THEN 1
                   ELSE 0
                END) DESC,
               -- Tier 2: substring hit on a primary field
               (CASE
                   WHEN LOWER(COALESCE(i.original_title, ''))              LIKE '%' || LOWER(:q) || '%' ESCAPE '\\' THEN 1
                   WHEN LOWER(COALESCE(i.alternative_title, ''))           LIKE '%' || LOWER(:q) || '%' ESCAPE '\\' THEN 1
                   WHEN LOWER(COALESCE(i.title_in_central_kurdish, ''))    LIKE '%' || LOWER(:q) || '%' ESCAPE '\\' THEN 1
                   WHEN LOWER(COALESCE(i.romanized_title, ''))             LIKE '%' || LOWER(:q) || '%' ESCAPE '\\' THEN 1
                   WHEN LOWER(COALESCE(i.creator_artist_photographer, '')) LIKE '%' || LOWER(:q) || '%' ESCAPE '\\' THEN 1
                   WHEN LOWER(COALESCE(i.person_shown_in_image, ''))       LIKE '%' || LOWER(:q) || '%' ESCAPE '\\' THEN 1
                   ELSE 0
                END) DESC,
               -- Tier 3: trigram similarity on primary fields + child tables
               GREATEST(
                   similarity(LOWER(COALESCE(i.original_title, '')),              LOWER(:qRaw)),
                   similarity(LOWER(COALESCE(i.alternative_title, '')),           LOWER(:qRaw)),
                   similarity(LOWER(COALESCE(i.title_in_central_kurdish, '')),    LOWER(:qRaw)),
                   similarity(LOWER(COALESCE(i.romanized_title, '')),             LOWER(:qRaw)),
                   similarity(LOWER(COALESCE(i.creator_artist_photographer, '')), LOWER(:qRaw)),
                   similarity(LOWER(COALESCE(i.person_shown_in_image, '')),       LOWER(:qRaw)),
                   similarity(LOWER(COALESCE(i.description, '')),                 LOWER(:qRaw)),
                   similarity(LOWER(COALESCE(i.event, '')),                       LOWER(:qRaw)),
                   similarity(LOWER(COALESCE(i.location, '')),                    LOWER(:qRaw)),
                   COALESCE((SELECT MAX(similarity(LOWER(t.tag),     LOWER(:qRaw))) FROM image_tags     t WHERE t.image_id = i.id), 0),
                   COALESCE((SELECT MAX(similarity(LOWER(k.keyword), LOWER(:qRaw))) FROM image_keywords k WHERE k.image_id = i.id), 0),
                   COALESCE((SELECT MAX(similarity(LOWER(s.subject), LOWER(:qRaw))) FROM image_subjects s WHERE s.image_id = i.id), 0),
                   COALESCE((SELECT MAX(similarity(LOWER(g.genre),   LOWER(:qRaw))) FROM image_genres   g WHERE g.image_id = i.id), 0)
               ) DESC,
               i.original_title ASC NULLS LAST,
               i.id ASC
             LIMIT :limit
            """, nativeQuery = true)
    List<Image> searchByText(@Param("q") String escapedQuery,
                             @Param("qRaw") String rawQuery,
                             @Param("prefilter") int prefilterLimit,
                             @Param("limit") int limit);
}
