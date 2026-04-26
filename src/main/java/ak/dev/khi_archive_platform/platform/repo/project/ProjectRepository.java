package ak.dev.khi_archive_platform.platform.repo.project;

import ak.dev.khi_archive_platform.platform.model.category.Category;
import ak.dev.khi_archive_platform.platform.model.person.Person;
import ak.dev.khi_archive_platform.platform.model.project.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

@SuppressWarnings("unused")
public interface ProjectRepository extends JpaRepository<Project, Long> {

    Optional<Project> findByProjectCodeAndRemovedAtIsNull(String projectCode);

    List<Project> findAllByRemovedAtIsNull();

    boolean existsByProjectCodeAndRemovedAtIsNull(String projectCode);

    boolean existsByPersonAndRemovedAtIsNull(Person person);

    @Query("SELECT CASE WHEN COUNT(p) > 0 THEN true ELSE false END FROM Project p JOIN p.categories c WHERE c = :category AND p.removedAt IS NULL")
    boolean existsByCategoryAndRemovedAtIsNull(@Param("category") Category category);

    long countByPerson(Person person);

    long countByPersonIsNull();
}
