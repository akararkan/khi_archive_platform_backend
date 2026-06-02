package ak.dev.khi_archive_platform.platform.repo.maqam;

import ak.dev.khi_archive_platform.platform.model.maqam.MaqamAudioListenSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface MaqamAudioListenSessionRepository extends JpaRepository<MaqamAudioListenSession, Long> {

    Optional<MaqamAudioListenSession> findBySessionKeyAndTeacherUserId(String sessionKey, Long teacherUserId);

    Page<MaqamAudioListenSession> findAllByListOfMaqamIdOrderByStartedAtDesc(Long listOfMaqamId, Pageable pageable);

    Page<MaqamAudioListenSession> findAllByTeacherUserIdOrderByStartedAtDesc(Long teacherUserId, Pageable pageable);

    Page<MaqamAudioListenSession> findAllByListOfMaqamIdAndTeacherUserIdOrderByStartedAtDesc(Long listOfMaqamId, Long teacherUserId, Pageable pageable);

    @Query("SELECT COALESCE(SUM(s.secondsListened), 0) FROM MaqamAudioListenSession s " +
            "WHERE s.listOfMaqamId = :maqamId AND s.teacherUserId = :teacherUserId")
    long sumSecondsListened(@Param("maqamId") Long maqamId, @Param("teacherUserId") Long teacherUserId);
}
