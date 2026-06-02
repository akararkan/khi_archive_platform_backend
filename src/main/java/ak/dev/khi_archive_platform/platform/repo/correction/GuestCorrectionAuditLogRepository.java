package ak.dev.khi_archive_platform.platform.repo.correction;

import ak.dev.khi_archive_platform.platform.model.correction.GuestCorrectionAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GuestCorrectionAuditLogRepository extends JpaRepository<GuestCorrectionAuditLog, Long> {
}
