package ak.dev.khi_archive_platform.platform.repo.video;

import ak.dev.khi_archive_platform.platform.model.project.Project;
import ak.dev.khi_archive_platform.platform.model.video.Video;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

@SuppressWarnings("unused")
public interface VideoRepository extends JpaRepository<Video, Long> {

    Optional<Video> findByVideoCodeAndRemovedAtIsNull(String videoCode);

    boolean existsByVideoCode(String videoCode);

    List<Video> findAllByRemovedAtIsNull();

    long countByProject(Project project);

    long countByProjectAndVideoVersionAndVersionNumber(Project project, String videoVersion, Integer versionNumber);

    long countByProjectAndVideoVersionAndVersionNumberAndCopyNumber(Project project, String videoVersion, Integer versionNumber, Integer copyNumber);
}
