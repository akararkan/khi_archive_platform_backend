package ak.dev.khi_archive_platform.user.repo;

import ak.dev.khi_archive_platform.user.model.UserAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAuditLogRepository extends JpaRepository<UserAuditLog, Long> {
}
