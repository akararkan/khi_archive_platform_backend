package ak.dev.khi_archive_platform.platform.repo.keyword;

import ak.dev.khi_archive_platform.platform.dto.keyword.KeywordSuggestionDTO;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Native, read-only access to the union of all keyword collection tables —
 * {@code audio_keywords}, {@code video_keywords}, {@code image_keywords},
 * {@code text_keywords}, {@code project_keywords}, {@code category_keywords}.
 *
 * <p>Mirror of
 * {@link ak.dev.khi_archive_platform.platform.repo.tag.TagSuggestRepository}
 * — same algorithm, six tables instead of five (Category has keywords but no
 * tags), and trash-aware via each parent's {@code removed_at}.</p>
 */
@Repository
@RequiredArgsConstructor
public class KeywordSuggestRepository {

    @PersistenceContext
    private final EntityManager entityManager;

    /**
     * Returns ranked keyword suggestions whose canonical form contains {@code q}.
     *
     * Ranking (lowest matchRank = best):
     * <ol start="0">
     *   <li>exact match — {@code value = q}</li>
     *   <li>prefix match — {@code value LIKE q || '%'}</li>
     *   <li>substring match — {@code value LIKE '%' || q || '%'}</li>
     * </ol>
     * Within each rank we order by descending usage count, then alphabetical.
     *
     * @param q     canonical (lower-cased, trimmed, whitespace-collapsed) query.
     *              Must not be blank.
     * @param limit how many rows to return. Caller is expected to clamp.
     */
    public List<KeywordSuggestionDTO> suggest(String q, int limit) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                WITH all_keywords AS (
                    SELECT LOWER(k.keyword) AS value
                      FROM audio_keywords k
                      JOIN audios a ON a.id = k.audio_id
                     WHERE a.removed_at IS NULL
                       AND k.keyword IS NOT NULL AND k.keyword <> ''
                    UNION ALL
                    SELECT LOWER(k.keyword) AS value
                      FROM video_keywords k
                      JOIN videos v ON v.id = k.video_id
                     WHERE v.removed_at IS NULL
                       AND k.keyword IS NOT NULL AND k.keyword <> ''
                    UNION ALL
                    SELECT LOWER(k.keyword) AS value
                      FROM image_keywords k
                      JOIN images i ON i.id = k.image_id
                     WHERE i.removed_at IS NULL
                       AND k.keyword IS NOT NULL AND k.keyword <> ''
                    UNION ALL
                    SELECT LOWER(k.keyword) AS value
                      FROM text_keywords k
                      JOIN texts x ON x.id = k.text_id
                     WHERE x.removed_at IS NULL
                       AND k.keyword IS NOT NULL AND k.keyword <> ''
                    UNION ALL
                    SELECT LOWER(k.keyword) AS value
                      FROM project_keywords k
                      JOIN projects p ON p.id = k.project_id
                     WHERE p.removed_at IS NULL
                       AND k.keyword IS NOT NULL AND k.keyword <> ''
                    UNION ALL
                    SELECT LOWER(k.keyword) AS value
                      FROM category_keywords k
                      JOIN categories c ON c.id = k.category_id
                     WHERE c.removed_at IS NULL
                       AND k.keyword IS NOT NULL AND k.keyword <> ''
                ),
                grouped AS (
                    SELECT value, COUNT(*) AS usage_count
                      FROM all_keywords
                     GROUP BY value
                ),
                ranked AS (
                    SELECT value,
                           usage_count,
                           CASE
                             WHEN value = :q                           THEN 0
                             WHEN value LIKE :q || '%' ESCAPE '\\'     THEN 1
                             WHEN value LIKE '%' || :q || '%' ESCAPE '\\' THEN 2
                             ELSE 3
                           END AS match_rank
                      FROM grouped
                     WHERE value LIKE '%' || :q || '%' ESCAPE '\\'
                )
                SELECT value, usage_count, match_rank
                  FROM ranked
                 WHERE match_rank < 3
                 ORDER BY match_rank ASC, usage_count DESC, value ASC
                 LIMIT :lim
                """)
                .setParameter("q", q)
                .setParameter("lim", limit)
                .getResultList();

        return rows.stream()
                .map(r -> new KeywordSuggestionDTO(
                        (String) r[0],
                        ((Number) r[1]).longValue(),
                        ((Number) r[2]).intValue()))
                .toList();
    }
}
