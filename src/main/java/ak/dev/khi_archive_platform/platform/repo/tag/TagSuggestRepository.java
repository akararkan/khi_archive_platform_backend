package ak.dev.khi_archive_platform.platform.repo.tag;

import ak.dev.khi_archive_platform.platform.dto.tag.TagSuggestionDTO;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Native, read-only access to the union of all tag collection tables —
 * {@code audio_tags}, {@code video_tags}, {@code image_tags},
 * {@code text_tags}, {@code project_tags}.
 *
 * <p>Lives in its own repository (not on any single entity) because the data
 * is intentionally cross-entity: the same tag {@code "sulaimaniyah"} may live
 * on an audio, a video and a project, and the suggest endpoint must return
 * one ranked row regardless of which table holds it.</p>
 *
 * <p>The {@link #suggest(String, int)} query is built once and parameterised
 * — no string concatenation of user input. The {@code ?} placeholders for the
 * trash-aware join conditions are constant; the {@code :q} bind is escaped
 * with {@code LIKE '%' || :q || '%' ESCAPE '\\'}.</p>
 */
@Repository
@RequiredArgsConstructor
public class TagSuggestRepository {

    @PersistenceContext
    private final EntityManager entityManager;

    /**
     * Returns ranked tag suggestions whose canonical form contains {@code q}.
     *
     * Ranking (lowest matchRank = best):
     * <ol start="0">
     *   <li>exact match — {@code value = q}</li>
     *   <li>prefix match — {@code value LIKE q || '%'}</li>
     *   <li>substring match — {@code value LIKE '%' || q || '%'}</li>
     * </ol>
     * Within each rank we order by descending usage count, then alphabetical.
     *
     * <p>Excludes records that are soft-trashed ({@code removed_at IS NOT NULL}) —
     * a trashed media's tags should not pollute the autocomplete.</p>
     *
     * @param q     canonical (lower-cased, trimmed) query. Must not be blank.
     * @param limit how many rows to return. Caller is expected to clamp.
     */
    public List<TagSuggestionDTO> suggest(String q, int limit) {
        // Build the union from each child table, joining its parent to skip trashed rows.
        // Each leg already lower-cases the column value because tags are persisted canonical;
        // we still LOWER() defensively in case legacy rows pre-date the canonicaliser.
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                WITH all_tags AS (
                    SELECT LOWER(t.tag) AS value
                      FROM audio_tags t
                      JOIN audios a ON a.id = t.audio_id
                     WHERE a.removed_at IS NULL
                       AND t.tag IS NOT NULL AND t.tag <> ''
                    UNION ALL
                    SELECT LOWER(t.tag) AS value
                      FROM video_tags t
                      JOIN videos v ON v.id = t.video_id
                     WHERE v.removed_at IS NULL
                       AND t.tag IS NOT NULL AND t.tag <> ''
                    UNION ALL
                    SELECT LOWER(t.tag) AS value
                      FROM image_tags t
                      JOIN images i ON i.id = t.image_id
                     WHERE i.removed_at IS NULL
                       AND t.tag IS NOT NULL AND t.tag <> ''
                    UNION ALL
                    SELECT LOWER(t.tag) AS value
                      FROM text_tags t
                      JOIN texts x ON x.id = t.text_id
                     WHERE x.removed_at IS NULL
                       AND t.tag IS NOT NULL AND t.tag <> ''
                    UNION ALL
                    SELECT LOWER(t.tag) AS value
                      FROM project_tags t
                      JOIN projects p ON p.id = t.project_id
                     WHERE p.removed_at IS NULL
                       AND t.tag IS NOT NULL AND t.tag <> ''
                ),
                grouped AS (
                    SELECT value, COUNT(*) AS usage_count
                      FROM all_tags
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
                .map(r -> new TagSuggestionDTO(
                        (String) r[0],
                        ((Number) r[1]).longValue(),
                        ((Number) r[2]).intValue()))
                .toList();
    }
}
