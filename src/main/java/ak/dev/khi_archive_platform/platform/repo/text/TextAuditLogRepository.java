package ak.dev.khi_archive_platform.platform.repo.text;

import ak.dev.khi_archive_platform.platform.model.text.TextAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TextAuditLogRepository extends JpaRepository<TextAuditLog, Long> {
}
