package ak.dev.khi_archive_platform.platform.repo.physicalmedia;

import ak.dev.khi_archive_platform.platform.model.physicalmedia.PhysicalMediaAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PhysicalMediaAuditLogRepository extends JpaRepository<PhysicalMediaAuditLog, Long> {
}
