package ak.dev.khi_archive_platform.platform.repo.analytics;

import ak.dev.khi_archive_platform.platform.model.analytics.AnalyticsAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnalyticsAuditLogRepository extends JpaRepository<AnalyticsAuditLog, Long> {
}
