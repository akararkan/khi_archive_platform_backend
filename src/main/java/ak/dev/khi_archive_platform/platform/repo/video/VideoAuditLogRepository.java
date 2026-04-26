package ak.dev.khi_archive_platform.platform.repo.video;

import ak.dev.khi_archive_platform.platform.model.video.VideoAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VideoAuditLogRepository extends JpaRepository<VideoAuditLog, Long> {
}
