package ak.dev.khi_archive_platform.platform.repo.vocabulary;

import ak.dev.khi_archive_platform.platform.dto.vocabulary.VocabularyItemDTO;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Set-based, cross-table operations over the tag/keyword
 * {@code @ElementCollection} tables. Every operation is a single native
 * statement per table executed straight through the {@link EntityManager} — no
 * entities are loaded, so a global rename/delete touches every occurrence in
 * one DB round-trip per table rather than N per row.
 *
 * <p>The table set is supplied by the caller (5 tag tables or 6 keyword tables)
 * from a trusted {@link CollectionTableRef} catalog, so identifiers are safe to
 * inline; the tag/keyword <em>value</em> is always a bound parameter.
 *
 * <p>Because these bypass Hibernate (no L1/L2, no {@code @Version} bump), the
 * calling service must evict the affected read-caches afterwards.
 */
@Repository
@RequiredArgsConstructor
public class VocabularyBulkRepository {

    @PersistenceContext
    private final EntityManager entityManager;

    /** Rows renamed vs. rows merged away as duplicates during a rename. */
    public record RenameCounts(long renamed, long merged) {}

    /**
     * Distinct values with their usage counts across the given tables, counting
     * only occurrences on <b>non-trashed</b> parents (so the listing matches the
     * live {@code /suggest} vocabulary). Ordered by usage desc then value asc.
     *
     * @param canonicalQ optional canonical substring filter; {@code null}/blank = all
     */
    public List<VocabularyItemDTO> listUsage(List<CollectionTableRef> tables,
                                             String canonicalQ, int limit, int offset) {
        String union = tables.stream()
                .map(t -> "SELECT LOWER(c." + t.valueColumn() + ") AS value"
                        + "  FROM " + t.table() + " c"
                        + "  JOIN " + t.parentTable() + " p ON p.id = c." + t.fkColumn()
                        + " WHERE p.removed_at IS NULL"
                        + "   AND c." + t.valueColumn() + " IS NOT NULL AND c." + t.valueColumn() + " <> ''")
                .collect(Collectors.joining("\n    UNION ALL\n"));

        boolean hasQ = canonicalQ != null && !canonicalQ.isBlank();
        String sql = "WITH v AS (\n    " + union + "\n)\n"
                + "SELECT value, COUNT(*) AS usage_count\n"
                + "  FROM v\n"
                + (hasQ ? " WHERE value LIKE '%' || :q || '%' ESCAPE '\\'\n" : "")
                + " GROUP BY value\n"
                + " ORDER BY usage_count DESC, value ASC\n"
                + " LIMIT :lim OFFSET :off";

        Query query = entityManager.createNativeQuery(sql);
        if (hasQ) query.setParameter("q", canonicalQ);
        query.setParameter("lim", limit);
        query.setParameter("off", offset);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return rows.stream()
                .map(r -> new VocabularyItemDTO((String) r[0], ((Number) r[1]).longValue()))
                .toList();
    }

    /**
     * Rename {@code canonicalFrom} → {@code canonicalTo} everywhere (active and
     * trashed rows, so the value is gone for good). Two statements per table:
     * <ol>
     *   <li>{@code UPDATE … SET value=:to WHERE LOWER(value)=:from} — rename.</li>
     *   <li>A {@code ctid} self-join delete that collapses any parent now
     *       holding {@code :to} twice (it already had the target) back to one
     *       row — keeps the collection a clean set without needing a unique
     *       constraint.</li>
     * </ol>
     */
    public RenameCounts rename(List<CollectionTableRef> tables, String canonicalFrom, String canonicalTo) {
        long renamed = 0;
        long merged = 0;
        for (CollectionTableRef t : tables) {
            renamed += entityManager.createNativeQuery(
                            "UPDATE " + t.table() + " SET " + t.valueColumn() + " = :to"
                                    + " WHERE LOWER(" + t.valueColumn() + ") = :from")
                    .setParameter("to", canonicalTo)
                    .setParameter("from", canonicalFrom)
                    .executeUpdate();

            merged += entityManager.createNativeQuery(
                            "DELETE FROM " + t.table() + " a"
                                    + " USING " + t.table() + " b"
                                    + " WHERE a.ctid < b.ctid"
                                    + "   AND a." + t.fkColumn() + " = b." + t.fkColumn()
                                    + "   AND a." + t.valueColumn() + " = b." + t.valueColumn()
                                    + "   AND a." + t.valueColumn() + " = :to")
                    .setParameter("to", canonicalTo)
                    .executeUpdate();
        }
        return new RenameCounts(renamed, merged);
    }

    /** Remove {@code canonicalValue} from every table (active and trashed rows). */
    public long delete(List<CollectionTableRef> tables, String canonicalValue) {
        long deleted = 0;
        for (CollectionTableRef t : tables) {
            deleted += entityManager.createNativeQuery(
                            "DELETE FROM " + t.table()
                                    + " WHERE LOWER(" + t.valueColumn() + ") = :value")
                    .setParameter("value", canonicalValue)
                    .executeUpdate();
        }
        return deleted;
    }
}
