package ak.dev.khi_archive_platform.platform.repo.person;

import ak.dev.khi_archive_platform.platform.model.person.Person;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PersonRepository extends JpaRepository<Person, Long> {

    Optional<Person> findByPersonCodeAndRemovedAtIsNull(String personCode);

    List<Person> findAllByRemovedAtIsNull();

    List<Person> findAllByRemovedAtIsNotNull();

    Optional<Person> findByPersonCode(String personCode);

    /**
     * Eager-loads personType in a single query to avoid N+1 selects when listing
     * all active persons. Sorted by full_name for stable order.
     */
    @Query("""
            SELECT DISTINCT p FROM Person p
            LEFT JOIN FETCH p.personType
            WHERE p.removedAt IS NULL
            ORDER BY p.fullName ASC
            """)
    List<Person> findAllActiveWithPersonType();

    boolean existsByPersonCodeAndRemovedAtIsNull(String personCode);

    /**
     * Typo-tolerant, multilingual search across the searchable Person fields.
     * Substring (LOWER LIKE) matches and trigram-similarity (pg_trgm) matches are
     * unioned, then ranked by the best similarity score among the primary name
     * fields (full_name, nickname, romanized_name).
     */
    @Query(value = """
            SELECT p.* FROM person p
            WHERE p.removed_at IS NULL
              AND (
                    LOWER(p.full_name) LIKE LOWER(CONCAT('%', :q, '%'))
                 OR LOWER(COALESCE(p.nickname, '')) LIKE LOWER(CONCAT('%', :q, '%'))
                 OR LOWER(COALESCE(p.romanized_name, '')) LIKE LOWER(CONCAT('%', :q, '%'))
                 OR LOWER(COALESCE(p.description, '')) LIKE LOWER(CONCAT('%', :q, '%'))
                 OR LOWER(COALESCE(p.tag, '')) LIKE LOWER(CONCAT('%', :q, '%'))
                 OR LOWER(COALESCE(p.keywords, '')) LIKE LOWER(CONCAT('%', :q, '%'))
                 OR LOWER(COALESCE(p.region, '')) LIKE LOWER(CONCAT('%', :q, '%'))
                 OR LOWER(COALESCE(p.place_of_birth, '')) LIKE LOWER(CONCAT('%', :q, '%'))
                 OR LOWER(COALESCE(p.place_of_death, '')) LIKE LOWER(CONCAT('%', :q, '%'))
                 OR LOWER(p.person_code) LIKE LOWER(CONCAT('%', :q, '%'))
                 OR EXISTS (
                        SELECT 1 FROM person_person_type pt
                         WHERE pt.person_id = p.id
                           AND LOWER(pt.person_type) LIKE LOWER(CONCAT('%', :q, '%'))
                    )
                 OR similarity(LOWER(p.full_name), LOWER(:q)) > :threshold
                 OR similarity(LOWER(COALESCE(p.nickname, '')), LOWER(:q)) > :threshold
                 OR similarity(LOWER(COALESCE(p.romanized_name, '')), LOWER(:q)) > :threshold
              )
            ORDER BY
                GREATEST(
                    similarity(LOWER(p.full_name), LOWER(:q)),
                    similarity(LOWER(COALESCE(p.nickname, '')), LOWER(:q)),
                    similarity(LOWER(COALESCE(p.romanized_name, '')), LOWER(:q))
                ) DESC,
                p.full_name ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<Person> searchByText(@Param("q") String query,
                              @Param("threshold") double threshold,
                              @Param("limit") int limit);
}
