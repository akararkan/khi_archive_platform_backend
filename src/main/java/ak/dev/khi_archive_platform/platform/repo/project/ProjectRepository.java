package ak.dev.khi_archive_platform.platform.repo.project;

import ak.dev.khi_archive_platform.platform.model.category.Category;
import ak.dev.khi_archive_platform.platform.model.person.Person;
import ak.dev.khi_archive_platform.platform.model.project.Project;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
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

    /** Active projects across a batch of persons — drives guest search scope expansion. */
    @EntityGraph(attributePaths = {"categories", "person"})
    List<Project> findAllByPersonInAndRemovedAtIsNull(Collection<Person> persons);

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

    /** Counts active projects for the given person — used by guest person detail. */
    long countByPersonAndRemovedAtIsNull(Person person);

    /** Counts public active projects for the given person — used by guest/public pages. */
    @Query("""
            SELECT COUNT(p)
              FROM Project p
             WHERE p.person = :person
               AND p.removedAt IS NULL
               AND (p.isVisibleToPublic IS NULL OR p.isVisibleToPublic = true)
            """)
    long countPublicByPerson(@Param("person") Person person);

    /** Counts active projects in the given category — used by guest category detail. */
    @Query("SELECT COUNT(DISTINCT p) FROM Project p JOIN p.categories c WHERE c = :category AND p.removedAt IS NULL")
    long countActiveByCategory(@Param("category") Category category);

    /** Counts public active projects in the given category — used by guest/public pages. */
    @Query("""
            SELECT COUNT(DISTINCT p)
              FROM Project p
              JOIN p.categories c
             WHERE c = :category
               AND p.removedAt IS NULL
               AND (p.isVisibleToPublic IS NULL OR p.isVisibleToPublic = true)
            """)
    long countPublicByCategory(@Param("category") Category category);

    /**
     * Typo-tolerant search across project name, code, description, tags, and
     * keywords. Substring (LOWER LIKE) plus pg_trgm similarity, ranked by best
     * score. Mirrors {@code CategoryRepository.searchByText}.
     */
    @Query(value = """
            SELECT p.* FROM projects p
            WHERE p.removed_at IS NULL
              AND (
                    LOWER(p.project_name) LIKE LOWER(CONCAT('%', :q, '%'))
                 OR LOWER(p.project_code) LIKE LOWER(CONCAT('%', :q, '%'))
                 OR LOWER(COALESCE(p.description, '')) LIKE LOWER(CONCAT('%', :q, '%'))
                 OR EXISTS (
                        SELECT 1 FROM project_tags t
                         WHERE t.project_id = p.id
                           AND LOWER(t.tag) LIKE LOWER(CONCAT('%', :q, '%'))
                    )
                 OR EXISTS (
                        SELECT 1 FROM project_keywords k
                         WHERE k.project_id = p.id
                           AND LOWER(k.keyword) LIKE LOWER(CONCAT('%', :q, '%'))
                    )
                 OR similarity(LOWER(p.project_name), LOWER(:q)) > :threshold
                 OR EXISTS (
                        SELECT 1 FROM project_tags t
                         WHERE t.project_id = p.id
                           AND similarity(LOWER(t.tag), LOWER(:q)) > :threshold
                    )
                 OR EXISTS (
                        SELECT 1 FROM project_keywords k
                         WHERE k.project_id = p.id
                           AND similarity(LOWER(k.keyword), LOWER(:q)) > :threshold
                    )
              )
            ORDER BY
                GREATEST(
                    similarity(LOWER(p.project_name), LOWER(:q)),
                    COALESCE((SELECT MAX(similarity(LOWER(t.tag),     LOWER(:q))) FROM project_tags     t WHERE t.project_id = p.id), 0),
                    COALESCE((SELECT MAX(similarity(LOWER(k.keyword), LOWER(:q))) FROM project_keywords k WHERE k.project_id = p.id), 0)
                ) DESC,
                p.project_name ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<Project> searchByText(@Param("q") String query,
                               @Param("threshold") double threshold,
                               @Param("limit") int limit);
}
