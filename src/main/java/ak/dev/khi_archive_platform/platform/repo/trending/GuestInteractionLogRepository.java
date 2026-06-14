package ak.dev.khi_archive_platform.platform.repo.trending;

import ak.dev.khi_archive_platform.platform.model.trending.GuestInteractionLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Repository
public interface GuestInteractionLogRepository extends JpaRepository<GuestInteractionLog, Long> {

    /**
     * Time-decay trending score per entity.
     *
     * Scoring weights:
     *   Viewed in the last  1 hour  → 3 points
     *   Viewed in the last 24 hours → 2 points
     *   Viewed in the last  7 days  → 1 point
     *
     * Returns Object[]: [entityType, entityCode, score]
     */
    @Query(value = """
            SELECT entity_type  AS entityType,
                   entity_code  AS entityCode,
                   SUM(CASE WHEN interacted_at >= :oneHourAgo THEN 3
                            WHEN interacted_at >= :oneDayAgo  THEN 2
                            ELSE                                   1 END) AS score
            FROM   guest_interaction_logs
            WHERE  interacted_at >= :sevenDaysAgo
            GROUP  BY entity_type, entity_code
            ORDER  BY score DESC
            LIMIT  :lim
            """, nativeQuery = true)
    List<Object[]> findTrendingRaw(
            @Param("sevenDaysAgo") Instant sevenDaysAgo,
            @Param("oneDayAgo")    Instant oneDayAgo,
            @Param("oneHourAgo")   Instant oneHourAgo,
            @Param("lim")          int     lim);

    @Modifying
    @Transactional
    @Query("DELETE FROM GuestInteractionLog g WHERE g.interactedAt < :cutoff")
    void deleteOlderThan(@Param("cutoff") Instant cutoff);
}
