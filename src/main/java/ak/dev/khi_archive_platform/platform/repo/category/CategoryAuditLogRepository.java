package ak.dev.khi_archive_platform.platform.repo.category;

import ak.dev.khi_archive_platform.platform.model.category.CategoryAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryAuditLogRepository extends JpaRepository<CategoryAuditLog, Long> {
}

