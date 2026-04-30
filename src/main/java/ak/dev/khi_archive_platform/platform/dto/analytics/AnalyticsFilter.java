package ak.dev.khi_archive_platform.platform.dto.analytics;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.FieldDefaults;

import java.io.Serializable;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Universal filter accepted by every analytics endpoint. Keeps the controller
 * signatures small and gives the service a single place to validate / sanitise
 * the request before it is folded into a SQL where-clause.
 *
 * <p>Resolution order for the time window:
 * <ol>
 *   <li>If both {@code from} and {@code to} are provided, they are used as-is.</li>
 *   <li>Else {@code days} (default 30, capped at 365) defines a window ending
 *       at {@code now}.</li>
 * </ol>
 *
 * <p>{@code entities} and {@code actions} are whitelisted by the service —
 * unknown values are silently dropped, so a malformed query degrades to "all".
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AnalyticsFilter implements Serializable {

    /** Inclusive lower bound. Null → derived from {@link #days}. */
    Instant from;
    /** Inclusive upper bound. Null → {@code now}. */
    Instant to;
    /** Window length in days when {@link #from}/{@link #to} are absent. */
    Integer days;

    /** Lower-cased entity names ("audio","video",…). Null/empty → all. */
    Set<String> entities;
    /** Upper-cased action names ("CREATE","DELETE",…). Null/empty → all. */
    Set<String> actions;

    /** Exact match on {@code actor_username}. */
    String actor;
    /** ILIKE substring on actor username/display name. */
    String actorPattern;

    /** Exact match on entity_code. */
    String entityCode;
    /** ILIKE substring on details / entity_code / actor fields. */
    String q;

    /** True iff the filter is "default" (only {@link #days} populated). Used
     *  to gate Redis caching: only the simple, popular requests are cached. */
    public boolean isCacheable() {
        return from == null
                && to == null
                && (entities == null || entities.isEmpty())
                && (actions == null || actions.isEmpty())
                && isBlank(actor)
                && isBlank(actorPattern)
                && isBlank(entityCode)
                && isBlank(q);
    }

    /**
     * Stable, deterministic cache key. Excludes {@link #from}/{@link #to} so
     * that a "last-30-days" request hits the same key regardless of the exact
     * second it was made — TTL handles staleness.
     */
    public String toCacheKey() {
        StringBuilder sb = new StringBuilder();
        sb.append("d=").append(days == null ? "" : days);
        sb.append("|e=").append(joined(entities));
        sb.append("|a=").append(joined(actions));
        sb.append("|u=").append(nullToEmpty(actor));
        sb.append("|up=").append(nullToEmpty(actorPattern));
        sb.append("|ec=").append(nullToEmpty(entityCode));
        sb.append("|q=").append(nullToEmpty(q));
        return sb.toString();
    }

    private static String joined(Set<String> values) {
        if (values == null || values.isEmpty()) return "";
        return String.join(",", new TreeSet<>(values));
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
