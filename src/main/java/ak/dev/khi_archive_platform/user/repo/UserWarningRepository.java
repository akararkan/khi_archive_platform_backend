package ak.dev.khi_archive_platform.user.repo;

import ak.dev.khi_archive_platform.user.model.UserWarning;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Spec-driven for the admin search ({@link JpaSpecificationExecutor}) so that
 * untyped nullable parameters never reach Postgres — predicates only appear
 * in the generated SQL when their filter value is supplied. The plain JPQL
 * methods below have no nullable comparisons, so they're safe to keep.
 */
public interface UserWarningRepository
        extends JpaRepository<UserWarning, Long>,
                JpaSpecificationExecutor<UserWarning> {

    /** Returns one warning by id only if it has not been revoked. */
    Optional<UserWarning> findByIdAndRemovedAtIsNull(Long id);

    /** Recipient-facing list: every non-revoked warning the user has received,
     *  unacknowledged first then newest. Used by {@code GET /api/warnings/me}. */
    @Query("""
            SELECT w FROM UserWarning w
             WHERE w.targetUserId = :userId
               AND w.removedAt IS NULL
             ORDER BY w.acknowledged ASC, w.createdAt DESC
            """)
    Page<UserWarning> findActiveForUser(@Param("userId") Long userId, Pageable pageable);

    /** Recipient-facing count of unacknowledged warnings — drives the
     *  red-badge counter in the top bar. */
    long countByTargetUserIdAndAcknowledgedFalseAndRemovedAtIsNull(Long targetUserId);
}
