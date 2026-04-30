package ak.dev.khi_archive_platform.platform.repo.project;

import ak.dev.khi_archive_platform.platform.model.category.Category;
import ak.dev.khi_archive_platform.platform.model.person.Person;
import ak.dev.khi_archive_platform.platform.model.project.Project;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

@SuppressWarnings("unused")
public interface ProjectRepository extends JpaRepository<Project, Long> {

    @EntityGraph(attributePaths = {"categories", "person"})
    Optional<Project> findByProjectCodeAndRemovedAtIsNull(String projectCode);

    List<Project> findAllByRemovedAtIsNull();

    @EntityGraph(attributePaths = {"categories", "person"})
    List<Project> findAllByRemovedAtIsNotNull();

    @EntityGraph(attributePaths = {"categories", "person"})
    Optional<Project> findByProjectCode(String projectCode);

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

    /** Active projects (not in trash) for the given person — used to cascade-trash on Person delete. */
    List<Project> findAllByPersonAndRemovedAtIsNull(Person person);

    /** Trashed projects for the given person — used to cascade-restore on Person restore. */
    List<Project> findAllByPersonAndRemovedAtIsNotNull(Person person);

    /** True if any project (active OR trashed) references the given person — used to block purge. */
    boolean existsByPerson(Person person);

    @Query("SELECT CASE WHEN COUNT(p) > 0 THEN true ELSE false END FROM Project p JOIN p.categories c WHERE c = :category AND p.removedAt IS NULL")
    boolean existsByCategoryAndRemovedAtIsNull(@Param("category") Category category);

    /** True if any project (active OR trashed) joins the given category — used to block purge. */
    @Query("SELECT CASE WHEN COUNT(p) > 0 THEN true ELSE false END FROM Project p JOIN p.categories c WHERE c = :category")
    boolean existsByCategory(@Param("category") Category category);

    long countByPerson(Person person);

    long countByPersonIsNull();
}
