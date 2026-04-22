package ak.dev.khi_archive_platform.platform.repo.audio;

import ak.dev.khi_archive_platform.platform.model.audio.AudioAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AudioAuditLogRepository extends JpaRepository<AudioAuditLog, Long> {
}

