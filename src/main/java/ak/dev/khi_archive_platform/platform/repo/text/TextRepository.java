package ak.dev.khi_archive_platform.platform.repo.text;

import ak.dev.khi_archive_platform.platform.model.project.Project;
import ak.dev.khi_archive_platform.platform.model.text.Text;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

@SuppressWarnings("unused")
public interface TextRepository extends JpaRepository<Text, Long> {

    Optional<Text> findByTextCodeAndRemovedAtIsNull(String textCode);

    boolean existsByTextCode(String textCode);

    List<Text> findAllByRemovedAtIsNull();

    long countByProject(Project project);

    long countByProjectAndTextVersionAndVersionNumber(Project project, String textVersion, Integer versionNumber);

    long countByProjectAndTextVersionAndVersionNumberAndCopyNumber(Project project, String textVersion, Integer versionNumber, Integer copyNumber);
}
