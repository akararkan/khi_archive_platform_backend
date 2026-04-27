package ak.dev.khi_archive_platform.platform.repo.image;

import ak.dev.khi_archive_platform.platform.model.image.ImageAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImageAuditLogRepository extends JpaRepository<ImageAuditLog, Long> {
}
