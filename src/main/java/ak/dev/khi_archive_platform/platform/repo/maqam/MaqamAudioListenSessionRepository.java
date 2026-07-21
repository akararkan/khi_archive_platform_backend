package ak.dev.khi_archive_platform.platform.repo.maqam;

import ak.dev.khi_archive_platform.platform.model.maqam.MaqamAudioListenSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MaqamAudioListenSessionRepository extends JpaRepository<MaqamAudioListenSession, Long> {

    Optional<MaqamAudioListenSession> findBySessionKeyAndTeacherUserId(String sessionKey, Long teacherUserId);

    Page<MaqamAudioListenSession> findAllByListOfMaqamIdOrderByStartedAtDesc(Long listOfMaqamId, Pageable pageable);

    Page<MaqamAudioListenSession> findAllByTeacherUserIdOrderByStartedAtDesc(Long teacherUserId, Pageable pageable);

    Page<MaqamAudioListenSession> findAllByListOfMaqamIdAndTeacherUserIdOrderByStartedAtDesc(Long listOfMaqamId, Long teacherUserId, Pageable pageable);

    @Query("SELECT COALESCE(SUM(s.secondsListened), 0) FROM MaqamAudioListenSession s " +
            "WHERE s.listOfMaqamId = :maqamId AND s.teacherUserId = :teacherUserId")
    long sumSecondsListened(@Param("maqamId") Long maqamId, @Param("teacherUserId") Long teacherUserId);

    /**
     * Per-teacher listen-session rollup over ACTIVE records only (parent
     * {@code list_of_maqam.removed_at IS NULL}). Each row is
     * {@code [teacherUserId, sessionCount, totalSecondsListened,
     * distinctRecordsListened]}. Feeds the maqam teacher leaderboard's
     * engagement columns and the overview's listen totals; scoping to active
     * records keeps it reconcilable with the (active-only) vote leaderboard.
     *
     * <p>{@code listOfMaqamId} is a scalar column (no mapped association), so
     * the {@link ak.dev.khi_archive_platform.platform.model.maqam.ListOfMaqam}
     * parent is joined explicitly on id. Uses the idx_mals_teacher index.
     */
    @Query("""
            SELECT s.teacherUserId,
                   COUNT(s.id),
                   COALESCE(SUM(s.secondsListened), 0),
                   COUNT(DISTINCT s.listOfMaqamId)
              FROM MaqamAudioListenSession s, ListOfMaqam m
             WHERE m.id = s.listOfMaqamId
               AND m.removedAt IS NULL
             GROUP BY s.teacherUserId
            """)
    List<Object[]> listenStatsByTeacher();
}
