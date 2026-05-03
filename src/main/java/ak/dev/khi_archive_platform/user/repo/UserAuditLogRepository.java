package ak.dev.khi_archive_platform.user.repo;

import ak.dev.khi_archive_platform.user.model.UserAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface UserAuditLogRepository
        extends JpaRepository<UserAuditLog, Long>, JpaSpecificationExecutor<UserAuditLog> {

    /** Distinct usernames of admins who have ever appeared as actors. Used to
     *  populate the actor filter dropdown on the audit-log UI. */
    @Query("SELECT DISTINCT u.actorUsername FROM UserAuditLog u "
            + "WHERE u.actorUsername IS NOT NULL AND u.actorUsername <> '' "
            + "ORDER BY u.actorUsername")
    List<String> findDistinctActorUsernames();
}
