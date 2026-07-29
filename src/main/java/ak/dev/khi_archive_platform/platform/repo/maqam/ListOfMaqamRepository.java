package ak.dev.khi_archive_platform.platform.repo.maqam;

import ak.dev.khi_archive_platform.platform.model.maqam.ListOfMaqam;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ListOfMaqamRepository extends JpaRepository<ListOfMaqam, Long> {

    Optional<ListOfMaqam> findByMaqamCodeAndRemovedAtIsNull(String maqamCode);

    Optional<ListOfMaqam> findByMaqamCode(String maqamCode);

    boolean existsByMaqamCode(String maqamCode);

    Page<ListOfMaqam> findAllByRemovedAtIsNull(Pageable pageable);

    Page<ListOfMaqam> findAllByRemovedAtIsNotNull(Pageable pageable);

    /** Full active set for the in-memory filter path (non-teacher callers).
     *  Used only when the caller supplied filter/sort params; the unfiltered
     *  list stays on the paged {@link #findAllByRemovedAtIsNull(Pageable)}. */
    List<ListOfMaqam> findAllByRemovedAtIsNull();

    /** Full trashed set for the in-memory filter path on the admin trash
     *  listing. Used only when filter/sort params are supplied; the unfiltered
     *  trash list stays on the paged {@link #findAllByRemovedAtIsNotNull(Pageable)}. */
    List<ListOfMaqam> findAllByRemovedAtIsNotNull();

    /** Active maqam records across the archive (removed_at IS NULL). */
    long countByRemovedAtIsNull();

    /** Soft-trashed maqam records across the archive (removed_at IS NOT NULL). */
    long countByRemovedAtIsNotNull();

    /**
     * Per-record classification stats over ACTIVE records, for the maqam
     * teacher overview. Each row is {@code [recordId, assignedCount,
     * votedCount, distinctMaqamTypeCount]}:
     * <ul>
     *   <li>assignedCount — teacher vote rows on the record (0..MAX_TEACHERS)</li>
     *   <li>votedCount — of those, how many have a non-null voted_at</li>
     *   <li>distinctMaqamTypeCount — distinct non-null maqam_type values voted</li>
     * </ul>
     * From these the service derives unclassified / partial / full and
     * consensus / disagreement buckets. Uses a LEFT JOIN so records with zero
     * assignments still return a row.
     */
    @Query("""
            SELECT m.id,
                   COUNT(v.id),
                   SUM(CASE WHEN v.votedAt IS NOT NULL THEN 1 ELSE 0 END),
                   COUNT(DISTINCT v.maqamType)
              FROM ListOfMaqam m
              LEFT JOIN m.teacherVotes v
             WHERE m.removedAt IS NULL
             GROUP BY m.id
            """)
    List<Object[]> perRecordVoteStats();

    /**
     * Distribution of voted maqam_type values across ACTIVE records — each row
     * is {@code [maqamType, count]}, most-common types first. Only counts votes
     * that were actually cast (voted_at set) with a non-null type.
     */
    @Query("""
            SELECT v.maqamType, COUNT(v.id)
              FROM ListOfMaqam m
              JOIN m.teacherVotes v
             WHERE m.removedAt IS NULL
               AND v.votedAt IS NOT NULL
               AND v.maqamType IS NOT NULL
             GROUP BY v.maqamType
             ORDER BY COUNT(v.id) DESC
            """)
    List<Object[]> maqamTypeDistribution();

    /** Active records the given teacher is assigned to — used by the
     *  TEACHER landing page. */
    @Query("SELECT DISTINCT m FROM ListOfMaqam m " +
            "JOIN m.teacherVotes v " +
            "WHERE m.removedAt IS NULL AND v.teacherUserId = :teacherUserId")
    Page<ListOfMaqam> findAssignedToTeacher(@Param("teacherUserId") Long teacherUserId, Pageable pageable);

    /** Active records by free-text match on song name or producer. */
    @Query("SELECT m FROM ListOfMaqam m " +
            "WHERE m.removedAt IS NULL AND (" +
            "  LOWER(m.songName) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
            "  LOWER(m.producer) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
            "  LOWER(m.maqamCode) LIKE LOWER(CONCAT('%', :q, '%'))" +
            ") ORDER BY m.createdAt DESC")
    List<ListOfMaqam> searchByText(@Param("q") String q, Pageable pageable);
}
