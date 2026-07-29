package ak.dev.khi_archive_platform.platform.service.common;

import org.springframework.data.domain.Sort;

/**
 * Builds Spring Data {@link Sort} objects from the shared
 * {@code sortBy}/{@code sortDirection} filter params, so the DB-paged "fast
 * path" of an entity listing can order rows <em>in the database</em> — loading
 * a single page — instead of pulling the whole active set into memory just to
 * sort it.
 *
 * <p>The generated order is built to <b>match the in-memory comparators</b> in
 * the various {@code *FilterSupport} engines, so a row's position is identical
 * whether a request took the DB fast path (sort only) or the in-memory path
 * (sort + filters):
 * <ul>
 *   <li>{@link #ci(String, String)} emits {@code ORDER BY LOWER(col)} —
 *       mirrors {@code String.CASE_INSENSITIVE_ORDER} used for text keys.</li>
 *   <li>{@link #plain(String, String)} leaves NULL handling to the database
 *       default ({@code NullHandling.NATIVE}). On PostgreSQL that is
 *       {@code NULLS LAST} for ASC and {@code NULLS FIRST} for DESC — exactly
 *       what {@code Comparator.nullsLast(...)} (and its {@code .reversed()})
 *       produce in memory.</li>
 * </ul>
 *
 * <p>An entity's {@code *FilterSupport.resolveDbSort(...)} returns
 * {@link Sort#unsorted()} for any key it cannot express as a real column
 * (e.g. Physical Media's {@code digitization}, which sorts by a derived numeric
 * code). The service then routes that request through the in-memory engine
 * instead, so correctness never depends on the DB being able to sort the key.
 */
public final class SortSupport {

    private SortSupport() {}

    /**
     * Deterministic tiebreaker appended to <em>every</em> generated order.
     * {@code ORDER BY <key>} alone is not a total order — PostgreSQL may return
     * equal-keyed rows in a different sequence per query, so paging a sorted
     * list could show a row twice and skip another. Appending {@code , id ASC}
     * makes the order total and paging stable. Every entity that uses this
     * helper has a {@code Long id} primary key, and the in-memory comparators
     * mirror this with {@code .thenComparing(getId())}.
     */
    private static final String TIEBREAKER = "id";

    /** {@code desc} → DESC (case-insensitive); anything else → ASC. */
    public static Sort.Direction direction(String sortDirection) {
        return "desc".equalsIgnoreCase(sortDirection) ? Sort.Direction.DESC : Sort.Direction.ASC;
    }

    /** Case-insensitive order for text columns: {@code ORDER BY LOWER(property), id ASC}. */
    public static Sort ci(String property, String sortDirection) {
        return Sort.by(new Sort.Order(direction(sortDirection), property).ignoreCase())
                .and(Sort.by(Sort.Order.asc(TIEBREAKER)));
    }

    /** Plain order for numeric / date / instant columns (NULL handling = DB
     *  native), with the {@code id ASC} tiebreaker appended. */
    public static Sort plain(String property, String sortDirection) {
        return Sort.by(new Sort.Order(direction(sortDirection), property))
                .and(Sort.by(Sort.Order.asc(TIEBREAKER)));
    }
}
