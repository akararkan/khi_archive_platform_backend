package ak.dev.khi_archive_platform.platform.repo.category;

import ak.dev.khi_archive_platform.platform.model.category.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    Optional<Category> findByCategoryCodeAndRemovedAtIsNull(String categoryCode);

    List<Category> findAllByRemovedAtIsNull();

    /**
     * Eager-loads keywords in a single query to avoid N+1 selects when listing
     * all active categories. Sorted by name for stable order.
     */
    @Query("""
            SELECT DISTINCT c FROM Category c
            LEFT JOIN FETCH c.keywords
            WHERE c.removedAt IS NULL
            ORDER BY c.name ASC
            """)
    List<Category> findAllActiveWithKeywords();

    boolean existsByCategoryCodeAndRemovedAtIsNull(String categoryCode);

    /**
     * Typo-tolerant, multilingual search across name, description, and keywords.
     * Uses pg_trgm trigram similarity (GIN-indexed). Substring (ILIKE) matches and
     * fuzzy (similarity > threshold) matches are unioned, then ranked by best score.
     */
    @Query(value = """
            SELECT c.* FROM categories c
            WHERE c.removed_at IS NULL
              AND (
                    LOWER(c.name) LIKE LOWER(CONCAT('%', :q, '%'))
                 OR LOWER(COALESCE(c.description, '')) LIKE LOWER(CONCAT('%', :q, '%'))
                 OR LOWER(c.category_code) LIKE LOWER(CONCAT('%', :q, '%'))
                 OR EXISTS (
                        SELECT 1 FROM category_keywords k
                         WHERE k.category_id = c.id
                           AND LOWER(k.keyword) LIKE LOWER(CONCAT('%', :q, '%'))
                    )
                 OR similarity(LOWER(c.name), LOWER(:q)) > :threshold
                 OR EXISTS (
                        SELECT 1 FROM category_keywords k
                         WHERE k.category_id = c.id
                           AND similarity(LOWER(k.keyword), LOWER(:q)) > :threshold
                    )
              )
            ORDER BY
                GREATEST(
                    similarity(LOWER(c.name), LOWER(:q)),
                    COALESCE((
                        SELECT MAX(similarity(LOWER(k.keyword), LOWER(:q)))
                          FROM category_keywords k
                         WHERE k.category_id = c.id
                    ), 0)
                ) DESC,
                c.name ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<Category> searchByText(@Param("q") String query,
                                @Param("threshold") double threshold,
                                @Param("limit") int limit);
}
