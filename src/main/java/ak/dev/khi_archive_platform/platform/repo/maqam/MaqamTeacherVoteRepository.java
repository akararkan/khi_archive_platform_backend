package ak.dev.khi_archive_platform.platform.repo.maqam;

import ak.dev.khi_archive_platform.platform.model.maqam.ListOfMaqam;
import ak.dev.khi_archive_platform.platform.model.maqam.MaqamTeacherVote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MaqamTeacherVoteRepository extends JpaRepository<MaqamTeacherVote, Long> {

    Optional<MaqamTeacherVote> findByListOfMaqamAndTeacherUserId(ListOfMaqam listOfMaqam, Long teacherUserId);

    boolean existsByListOfMaqamAndTeacherUserId(ListOfMaqam listOfMaqam, Long teacherUserId);

    long countByListOfMaqam(ListOfMaqam listOfMaqam);

    List<MaqamTeacherVote> findAllByListOfMaqamOrderByVotedAtAsc(ListOfMaqam listOfMaqam);

    List<MaqamTeacherVote> findAllByTeacherUserId(Long teacherUserId);

    /**
     * Per-teacher vote/assignment rollup over ACTIVE records only (parent
     * {@code list_of_maqam.removed_at IS NULL}). Each row is
     * {@code [teacherUserId, teacherUsername, teacherDisplayName,
     * assignedRecords, votesCast, distinctMaqamTypes, totalListenSeconds,
     * maxPositionSeconds, firstVotedAt, lastListenAt]}. Drives the teacher
     * leaderboard in the maqam analytics overview. Uses the idx_mtv_teacher
     * index.
     */
    @Query("""
            SELECT v.teacherUserId,
                   MAX(v.teacherUsername),
                   MAX(v.teacherDisplayName),
                   COUNT(v.id),
                   SUM(CASE WHEN v.votedAt IS NOT NULL THEN 1 ELSE 0 END),
                   COUNT(DISTINCT v.maqamType),
                   COALESCE(SUM(v.totalListenSeconds), 0),
                   COALESCE(MAX(v.maxPositionSeconds), 0),
                   MIN(v.votedAt),
                   MAX(v.lastListenAt)
              FROM MaqamTeacherVote v
              JOIN v.listOfMaqam m
             WHERE m.removedAt IS NULL
             GROUP BY v.teacherUserId
            """)
    List<Object[]> teacherVoteStats();

    /**
     * Loads every active-record vote belonging to the given teacher, eagerly
     * fetching the parent {@link ListOfMaqam}. Used by the teacher's "where
     * was I?" recent-activity feed.
     *
     * <p>Returns a list (not a Page) because every teacher is on at most a
     * few dozen records; sorting by the composite "last activity" timestamp
     * (max of lastListenAt / updatedAt / votedAt / assignedAt) happens in the
     * service so the JPQL stays portable — Postgres' {@code GREATEST} on
     * nullable timestamps is awkward to express in JPQL. Pagination is then
     * applied in-memory via {@code PaginationSupport}.
     */
    @Query("SELECT v FROM MaqamTeacherVote v " +
            "JOIN FETCH v.listOfMaqam m " +
            "WHERE v.teacherUserId = :teacherUserId " +
            "  AND m.removedAt IS NULL")
    List<MaqamTeacherVote> findAllAssignedActiveByTeacher(@Param("teacherUserId") Long teacherUserId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE MaqamTeacherVote v SET v.totalListenSeconds = COALESCE(v.totalListenSeconds, 0) + :delta, " +
            "v.maxPositionSeconds = GREATEST(COALESCE(v.maxPositionSeconds, 0), :position), " +
            "v.lastListenAt = :now, " +
            "v.version = COALESCE(v.version, 0) + 1 " +
            "WHERE v.id = :voteId")
    int bumpListen(@Param("voteId") Long voteId,
                   @Param("delta") long deltaSeconds,
                   @Param("position") long positionSeconds,
                   @Param("now") java.time.Instant now);
}
