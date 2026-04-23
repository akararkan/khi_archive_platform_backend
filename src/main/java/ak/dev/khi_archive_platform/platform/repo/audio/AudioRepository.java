package ak.dev.khi_archive_platform.platform.repo.audio;

import ak.dev.khi_archive_platform.platform.model.audio.Audio;
import ak.dev.khi_archive_platform.platform.model.object.ObjectAttribute;
import ak.dev.khi_archive_platform.platform.model.person.Person;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

@SuppressWarnings("unused")
public interface AudioRepository extends JpaRepository<Audio, Long> {
    Optional<Audio> findByAudioCodeAndDeletedAtIsNull(String audioCode);

    boolean existsByAudioCode(String audioCode);

    List<Audio> findAllByDeletedAtIsNull();

    long countByPerson(Person person);

    long countByArchiveObject(ObjectAttribute archiveObject);

    long countByPersonAndAudioVersionAndVersionNumber(Person person, String audioVersion, Integer versionNumber);

    long countByArchiveObjectAndAudioVersionAndVersionNumber(ObjectAttribute archiveObject, String audioVersion, Integer versionNumber);

    long countByPersonAndAudioVersionAndVersionNumberAndCopyNumber(Person person, String audioVersion, Integer versionNumber, Integer copyNumber);

    long countByArchiveObjectAndAudioVersionAndVersionNumberAndCopyNumber(ObjectAttribute archiveObject, String audioVersion, Integer versionNumber, Integer copyNumber);
}

