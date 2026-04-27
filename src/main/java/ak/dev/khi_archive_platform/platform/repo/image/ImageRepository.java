package ak.dev.khi_archive_platform.platform.repo.image;

import ak.dev.khi_archive_platform.platform.model.image.Image;
import ak.dev.khi_archive_platform.platform.model.project.Project;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

@SuppressWarnings("unused")
public interface ImageRepository extends JpaRepository<Image, Long> {

    Optional<Image> findByImageCodeAndRemovedAtIsNull(String imageCode);

    boolean existsByImageCode(String imageCode);

    List<Image> findAllByRemovedAtIsNull();

    long countByProject(Project project);

    long countByProjectAndImageVersionAndVersionNumber(Project project, String imageVersion, Integer versionNumber);

    long countByProjectAndImageVersionAndVersionNumberAndCopyNumber(Project project, String imageVersion, Integer versionNumber, Integer copyNumber);
}
