package ak.dev.khi_archive_platform.user.repo;


import ak.dev.khi_archive_platform.user.model.Session;
import ak.dev.khi_archive_platform.user.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface SessionRepository extends JpaRepository<Session, Long> {

    Optional<Session> findBySessionId(String sessionId);

    List<Session> findByUserAndIsActive(User user, Boolean isActive);

    List<Session> findByUser(User user);

    void deleteBySessionId(String sessionId);



}
