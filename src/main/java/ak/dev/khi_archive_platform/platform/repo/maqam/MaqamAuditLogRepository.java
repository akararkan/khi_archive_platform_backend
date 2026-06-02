package ak.dev.khi_archive_platform.platform.repo.maqam;

import ak.dev.khi_archive_platform.platform.model.maqam.MaqamAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MaqamAuditLogRepository extends JpaRepository<MaqamAuditLog, Long> {
}
