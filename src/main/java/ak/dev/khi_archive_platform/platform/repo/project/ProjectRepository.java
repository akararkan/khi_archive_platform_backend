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

    /**
     * Loads every active project; relies on Hibernate's
     * {@code default_batch_fetch_size} (set in application.yaml) to load
     * categories/tags/keywords/person in batched secondary queries — NOT N+1.
     * Used by the read-cache to populate Redis once per 10 minutes.
     */
    @Query("SELECT p FROM Project p WHERE p.removedAt IS NULL ORDER BY p.id ASC")
    List<Project> findAllActive();

    boolean existsByProjectCodeAndRemovedAtIsNull(String projectCode);

    boolean existsByPersonAndRemovedAtIsNull(Person person);

    @Query("SELECT CASE WHEN COUNT(p) > 0 THEN true ELSE false END FROM Project p JOIN p.categories c WHERE c = :category AND p.removedAt IS NULL")
    boolean existsByCategoryAndRemovedAtIsNull(@Param("category") Category category);

    long countByPerson(Person person);

    long countByPersonIsNull();
}
