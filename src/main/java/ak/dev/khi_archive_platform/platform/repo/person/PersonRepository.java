package ak.dev.khi_archive_platform.platform.repo.person;

import ak.dev.khi_archive_platform.platform.model.person.Person;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PersonRepository extends JpaRepository<Person, Long> {

    Optional<Person> findByPersonCodeAndRemovedAtIsNull(String personCode);

    List<Person> findAllByRemovedAtIsNull();

    boolean existsByPersonCodeAndRemovedAtIsNull(String personCode);
}
