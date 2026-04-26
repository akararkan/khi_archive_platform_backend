package ak.dev.khi_archive_platform.platform.repo.project;

import ak.dev.khi_archive_platform.platform.model.project.ProjectAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectAuditLogRepository extends JpaRepository<ProjectAuditLog, Long> {
}
