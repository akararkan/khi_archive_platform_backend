package ak.dev.khi_archive_platform.platform.repo.person;

import ak.dev.khi_archive_platform.platform.model.person.PersonAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PersonAuditLogRepository extends JpaRepository<PersonAuditLog, Long> {
}

