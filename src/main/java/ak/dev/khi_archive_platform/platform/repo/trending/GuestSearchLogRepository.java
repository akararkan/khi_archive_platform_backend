package ak.dev.khi_archive_platform.platform.repo.trending;

import ak.dev.khi_archive_platform.platform.model.trending.GuestSearchLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Repository
public interface GuestSearchLogRepository extends JpaRepository<GuestSearchLog, Long> {

    /**
     * Top search queries by frequency within the given time window.
     * Returns Object[]: [query, count]
     */
    @Query(value = """
            SELECT query, COUNT(*) AS cnt
            FROM   guest_search_logs
            WHERE  searched_at >= :since
            GROUP  BY query
            ORDER  BY cnt DESC
            LIMIT  :lim
            """, nativeQuery = true)
    List<Object[]> findTopSearches(
            @Param("since") Instant since,
            @Param("lim")   int     lim);

    @Modifying
    @Transactional
    @Query("DELETE FROM GuestSearchLog g WHERE g.searchedAt < :cutoff")
    void deleteOlderThan(@Param("cutoff") Instant cutoff);
}
