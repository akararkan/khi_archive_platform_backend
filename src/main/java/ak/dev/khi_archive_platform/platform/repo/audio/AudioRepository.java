package ak.dev.khi_archive_platform.platform.repo.audio;

import ak.dev.khi_archive_platform.platform.model.audio.Audio;
import ak.dev.khi_archive_platform.platform.model.project.Project;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

@SuppressWarnings("unused")
public interface AudioRepository extends JpaRepository<Audio, Long> {

    Optional<Audio> findByAudioCodeAndRemovedAtIsNull(String audioCode);

    boolean existsByAudioCode(String audioCode);

    List<Audio> findAllByRemovedAtIsNull();

    long countByProject(Project project);

    long countByProjectAndAudioVersionAndVersionNumber(Project project, String audioVersion, Integer versionNumber);

    long countByProjectAndAudioVersionAndVersionNumberAndCopyNumber(Project project, String audioVersion, Integer versionNumber, Integer copyNumber);
}
