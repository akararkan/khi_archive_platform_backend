package ak.dev.khi_archive_platform.platform.repo.physicalmedia;

import ak.dev.khi_archive_platform.platform.model.physicalmedia.PhysicalMedia;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PhysicalMediaRepository extends JpaRepository<PhysicalMedia, Long> {

    Optional<PhysicalMedia> findByPmCode(String pmCode);

    Optional<PhysicalMedia> findByPmCodeAndRemovedAtIsNull(String pmCode);

    boolean existsByPmCode(String pmCode);

    Page<PhysicalMedia> findAllByRemovedAtIsNull(Pageable pageable);

    Page<PhysicalMedia> findAllByRemovedAtIsNotNull(Pageable pageable);

    /** Full active set (id-ascending) for the in-memory filter path. Used only
     *  when the caller supplied filter/sort params; the unfiltered list stays
     *  on the paged {@link #findAllByRemovedAtIsNull(Pageable)} query. */
    List<PhysicalMedia> findAllByRemovedAtIsNullOrderByIdAsc();

    /** Full trashed set (id-ascending) — the trash-listing analogue of
     *  {@link #findAllByRemovedAtIsNullOrderByIdAsc()}. Used only on the
     *  filtered path; the unfiltered trash list stays on the paged
     *  {@link #findAllByRemovedAtIsNotNull(Pageable)} query. */
    List<PhysicalMedia> findAllByRemovedAtIsNotNullOrderByIdAsc();

    long countByRemovedAtIsNull();

    long countByRemovedAtIsNotNull();

    /**
     * High-water mark on {@code Number} for one media type, used to mint
     * the next per-type sequence value when an employee adds a new row
     * without supplying one. Counts every row — active <em>and</em>
     * trashed — so the sequence is monotonic across the trash lifecycle.
     * Returns {@code null} when no row of that type exists yet.
     */
    @Query("SELECT MAX(p.inventoryNumber) FROM PhysicalMedia p " +
            "WHERE p.physicalMediaType = :type")
    Integer findMaxInventoryNumberByPhysicalMediaType(@Param("type") String type);

    /**
     * Cheap full-text-ish search over the columns end-users actually care
     * about. Kept native so it can use the indexes on physical_label /
     * media_type and stay readable; no pg_trgm dependency for this entity.
     */
    @Query(value = """
            SELECT * FROM physical_media p
             WHERE p.removed_at IS NULL
               AND (
                    LOWER(COALESCE(p.pm_code, ''))             LIKE '%' || LOWER(:q) || '%'
                 OR LOWER(COALESCE(p.physical_label, ''))      LIKE '%' || LOWER(:q) || '%'
                 OR LOWER(COALESCE(p.physical_media_type, '')) LIKE '%' || LOWER(:q) || '%'
                 OR LOWER(COALESCE(p.media_category, ''))      LIKE '%' || LOWER(:q) || '%'
                 OR LOWER(COALESCE(p.title, ''))               LIKE '%' || LOWER(:q) || '%'
                 OR LOWER(COALESCE(p.physical_size, ''))       LIKE '%' || LOWER(:q) || '%'
                 OR LOWER(COALESCE(p.content, ''))             LIKE '%' || LOWER(:q) || '%'
                 OR LOWER(COALESCE(p.owner, ''))               LIKE '%' || LOWER(:q) || '%'
                 OR LOWER(COALESCE(p.tags, ''))                LIKE '%' || LOWER(:q) || '%'
                 OR LOWER(COALESCE(p.track_name, ''))          LIKE '%' || LOWER(:q) || '%'
               )
             ORDER BY p.id ASC
             LIMIT :limit
            """, nativeQuery = true)
    List<PhysicalMedia> searchByText(@Param("q") String q, @Param("limit") int limit);
}
