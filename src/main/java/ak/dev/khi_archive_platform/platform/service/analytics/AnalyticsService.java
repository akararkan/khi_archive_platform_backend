package ak.dev.khi_archive_platform.platform.service.analytics;

import ak.dev.khi_archive_platform.platform.dto.analytics.ActionStatsDTO;
import ak.dev.khi_archive_platform.platform.dto.analytics.AnalyticsFilter;
import ak.dev.khi_archive_platform.platform.dto.analytics.DailyBucketDTO;
import ak.dev.khi_archive_platform.platform.dto.analytics.EntityStatsDTO;
import ak.dev.khi_archive_platform.platform.dto.analytics.FeedPageDTO;
import ak.dev.khi_archive_platform.platform.dto.analytics.RecentActivityItemDTO;
import ak.dev.khi_archive_platform.platform.dto.analytics.TeamOverviewDTO;
import ak.dev.khi_archive_platform.platform.dto.analytics.UserActivityDTO;
import ak.dev.khi_archive_platform.platform.dto.analytics.UserSummaryDTO;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Aggregates the seven {@code *_audit_logs} tables into user-activity and
 * team-overview reports. The expensive part — scanning seven tables — is
 * pushed into Postgres via a single UNION ALL CTE so we issue one query per
 * report instead of fourteen. Hot reads then come from Redis (TTL configured
 * by {@code spring.cache.redis.time-to-live} in application.yaml; default
 * 10 min), making typical responses sub-millisecond.
 *
 * <p>Every public method takes an {@link AnalyticsFilter} so callers can
 * narrow by date range, entity, action, actor, entity-code or free text.
 * The filter is also the cache key — when the filter is "default" the
 * Redis lookup is hit; when it carries any non-default option the read
 * goes straight to the indexed CTE (still single-digit ms typical).
 *
 * <p>Indexes the queries rely on (created at startup by
 * {@code AuditLogIndexInitializer}):
 * <ul>
 *   <li>{@code (actor_username, occurred_at DESC)} — per-user windowed scans</li>
 *   <li>{@code (occurred_at DESC)} — team-wide windowed scans</li>
 *   <li>{@code (action, occurred_at DESC)} — accelerates per-action filters</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnalyticsService {

    public static final List<String> ENTITY_KEYS = List.of(
            "audio", "video", "image", "text", "project", "category", "person"
    );

    /** Whitelist of action names accepted by the {@code actions=} filter. The
     *  CTE casts the per-table enum to text and compares as strings, so we must
     *  guard the input. Project's enum lacks SEARCH; the others define it.
     *  Any unknown value is silently dropped (filter degrades to "all"). */
    private static final Set<String> ACTION_KEYS = Set.of(
            "CREATE", "READ", "LIST", "SEARCH", "UPDATE", "DELETE",
            "REMOVE", "RESTORE", "PURGE"
    );

    /**
     * UNION ALL fragment with one branch per audit-log table. Every branch
     * exposes the same column shape so the surrounding query can group/filter
     * uniformly. Composed once and reused for every aggregation/feed query.
     */
    private static final String ALL_LOGS_CTE = """
            WITH all_logs AS (
                SELECT 'audio'    AS entity, action::text AS action,
                       audio_id    AS entity_id, audio_code    AS entity_code,
                       actor_user_id, actor_username, actor_display_name,
                       actor_authorities, actor_permissions,
                       device_info, ip_address, session_id,
                       request_method, request_path,
                       occurred_at, details
                  FROM audio_audit_logs
                UNION ALL
                SELECT 'video'    , action::text, video_id   , video_code   ,
                       actor_user_id, actor_username, actor_display_name,
                       actor_authorities, actor_permissions,
                       device_info, ip_address, session_id,
                       request_method, request_path,
                       occurred_at, details
                  FROM video_audit_logs
                UNION ALL
                SELECT 'image'    , action::text, image_id   , image_code   ,
                       actor_user_id, actor_username, actor_display_name,
                       actor_authorities, actor_permissions,
                       device_info, ip_address, session_id,
                       request_method, request_path,
                       occurred_at, details
                  FROM image_audit_logs
                UNION ALL
                SELECT 'text'     , action::text, text_id    , text_code    ,
                       actor_user_id, actor_username, actor_display_name,
                       actor_authorities, actor_permissions,
                       device_info, ip_address, session_id,
                       request_method, request_path,
                       occurred_at, details
                  FROM text_audit_logs
                UNION ALL
                SELECT 'project'  , action::text, project_id , project_code ,
                       actor_user_id, actor_username, actor_display_name,
                       actor_authorities, actor_permissions,
                       device_info, ip_address, session_id,
                       request_method, request_path,
                       occurred_at, details
                  FROM project_audit_logs
                UNION ALL
                SELECT 'category' , action::text, category_id, category_code,
                       actor_user_id, actor_username, actor_display_name,
                       actor_authorities, actor_permissions,
                       device_info, ip_address, session_id,
                       request_method, request_path,
                       occurred_at, details
                  FROM category_audit_logs
                UNION ALL
                SELECT 'person'   , action::text, person_id  , person_code  ,
                       actor_user_id, actor_username, actor_display_name,
                       actor_authorities, actor_permissions,
                       device_info, ip_address, session_id,
                       request_method, request_path,
                       occurred_at, details
                  FROM person_audit_logs
            )
            """;

    private static final int DEFAULT_DAYS = 30;
    private static final int MAX_DAYS = 365;

    @PersistenceContext
    private EntityManager em;

    // ─── Public API ─────────────────────────────────────────────────────────

    /**
     * Fast path: served from Redis on cache hit when {@code filter} is the
     * default (no extra constraints). Filtered requests bypass cache and run
     * the indexed CTE directly.
     */
    @Cacheable(value = "analytics:user",
            key = "#username + ':' + #filter.toCacheKey() + ':' + #recentLimit",
            condition = "#filter.isCacheable()")
    public UserActivityDTO getUserActivity(String username, AnalyticsFilter filter, int recentLimit) {
        AnalyticsFilter f = withActor(filter, username);
        Window w = window(f);

        Map<String, EntityStatsDTO> byEntity = loadEntityStats(f, w);
        List<DailyBucketDTO> daily = loadDailyBuckets(f, w);
        List<RecentActivityItemDTO> recent = loadRecentFeed(f, w, recentLimit, 0).getItems();
        long total = byEntity.values().stream().mapToLong(EntityStatsDTO::getTotal).sum();

        Object[] firstLast = loadFirstLastSeen(f, w);

        return UserActivityDTO.builder()
                .actorUserId(firstLast[3] == null ? null : ((Number) firstLast[3]).longValue())
                .username(username)
                .displayName((String) firstLast[2])
                .authorities((String) firstLast[4])
                .permissions((String) firstLast[5])
                .from(w.from)
                .to(w.to)
                .firstSeen(instantOf(firstLast[0]))
                .lastSeen(instantOf(firstLast[1]))
                .totalActions(total)
                .byEntity(byEntity)
                .daily(daily)
                .recent(recent)
                .build();
    }

    @Cacheable(value = "analytics:overview",
            key = "#filter.toCacheKey() + ':' + #topN",
            condition = "#filter.isCacheable()")
    public TeamOverviewDTO getOverview(AnalyticsFilter filter, int topN) {
        Window w = window(filter);

        Map<String, EntityStatsDTO> byEntity = loadEntityStats(filter, w);
        List<DailyBucketDTO> daily = loadDailyBuckets(filter, w);
        List<UserSummaryDTO> users = loadUserSummaries(filter, w);
        long total = byEntity.values().stream().mapToLong(EntityStatsDTO::getTotal).sum();

        List<UserSummaryDTO> top = users.stream()
                .sorted(Comparator.comparingLong(UserSummaryDTO::getTotalActions).reversed())
                .limit(topN)
                .toList();

        return TeamOverviewDTO.builder()
                .from(w.from)
                .to(w.to)
                .totalActions(total)
                .activeUsers(users.size())
                .byEntity(byEntity)
                .topUsers(top)
                .daily(daily)
                .build();
    }

    @Cacheable(value = "analytics:users",
            key = "#filter.toCacheKey()",
            condition = "#filter.isCacheable()")
    public List<UserSummaryDTO> getUsers(AnalyticsFilter filter) {
        Window w = window(filter);
        return loadUserSummaries(filter, w).stream()
                .sorted(Comparator.comparingLong(UserSummaryDTO::getTotalActions).reversed())
                .toList();
    }

    /** Per-action breakdown across the (filtered) window. Always live (no cache). */
    public List<ActionStatsDTO> getActionStats(AnalyticsFilter filter) {
        return loadActionStats(filter, window(filter));
    }

    /** Daily buckets, exposed as a standalone endpoint. Always live. */
    public List<DailyBucketDTO> getDaily(AnalyticsFilter filter) {
        return loadDailyBuckets(filter, window(filter));
    }

    /** Per-entity stats, exposed as a standalone endpoint. Always live. */
    public Map<String, EntityStatsDTO> getEntityStats(AnalyticsFilter filter) {
        return loadEntityStats(filter, window(filter));
    }

    /** Paginated cross-entity feed. Always live. */
    public FeedPageDTO getFeed(AnalyticsFilter filter, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 500));
        return loadRecentFeed(filter, window(filter), safeSize, safePage);
    }

    // ─── Cache invalidation ─────────────────────────────────────────────────

    /** No-op: TTL-driven. Hook is here so callers don't depend on cache impl. */
    public void evictAll() { }

    // ─── Internal queries ───────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, EntityStatsDTO> loadEntityStats(AnalyticsFilter filter, Window w) {
        Set<String> entities = sanitisedEntities(filter);
        WhereClause where = buildWhere(filter, w, entities);

        String sql = ALL_LOGS_CTE + """
                SELECT entity,
                       COUNT(*)                                                     AS total,
                       COUNT(*) FILTER (WHERE action = 'CREATE')                    AS created,
                       COUNT(*) FILTER (WHERE action = 'UPDATE')                    AS updated,
                       COUNT(*) FILTER (WHERE action IN ('DELETE','REMOVE'))        AS deleted,
                       COUNT(*) FILTER (WHERE action = 'RESTORE')                   AS restored,
                       COUNT(*) FILTER (WHERE action = 'PURGE')                     AS purged,
                       COUNT(*) FILTER (WHERE action = 'READ')                      AS viewed,
                       COUNT(*) FILTER (WHERE action = 'SEARCH')                    AS searched,
                       COUNT(*) FILTER (WHERE action = 'LIST')                      AS listed,
                       COUNT(DISTINCT entity_id) FILTER (WHERE entity_id IS NOT NULL) AS distinct_entities
                  FROM all_logs
                """ + where.sql + " GROUP BY entity";

        Query q = em.createNativeQuery(sql);
        where.bind(q);

        Map<String, EntityStatsDTO> out = new LinkedHashMap<>();
        for (String entity : entities) {
            out.put(entity, EntityStatsDTO.builder().build());
        }
        for (Object row : q.getResultList()) {
            Object[] r = (Object[]) row;
            EntityStatsDTO stats = EntityStatsDTO.builder()
                    .total(longOf(r[1]))
                    .created(longOf(r[2]))
                    .updated(longOf(r[3]))
                    .deleted(longOf(r[4]))
                    .restored(longOf(r[5]))
                    .purged(longOf(r[6]))
                    .viewed(longOf(r[7]))
                    .searched(longOf(r[8]))
                    .listed(longOf(r[9]))
                    .distinctEntities(longOf(r[10]))
                    .build();
            out.put((String) r[0], stats);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<DailyBucketDTO> loadDailyBuckets(AnalyticsFilter filter, Window w) {
        WhereClause where = buildWhere(filter, w, sanitisedEntities(filter));

        String sql = ALL_LOGS_CTE + """
                SELECT DATE_TRUNC('day', occurred_at)                                AS day,
                       COUNT(*)                                                      AS total,
                       COUNT(*) FILTER (WHERE action = 'CREATE')                     AS created,
                       COUNT(*) FILTER (WHERE action = 'UPDATE')                     AS updated,
                       COUNT(*) FILTER (WHERE action IN ('DELETE','REMOVE'))         AS deleted,
                       COUNT(*) FILTER (WHERE action = 'RESTORE')                    AS restored,
                       COUNT(*) FILTER (WHERE action = 'PURGE')                      AS purged
                  FROM all_logs
                """ + where.sql + " GROUP BY day ORDER BY day DESC";

        Query q = em.createNativeQuery(sql);
        where.bind(q);

        List<DailyBucketDTO> out = new ArrayList<>();
        for (Object row : q.getResultList()) {
            Object[] r = (Object[]) row;
            Instant day = instantOf(r[0]);
            out.add(DailyBucketDTO.builder()
                    .date(day == null ? null : day.atZone(ZoneOffset.UTC).toLocalDate())
                    .total(longOf(r[1]))
                    .created(longOf(r[2]))
                    .updated(longOf(r[3]))
                    .deleted(longOf(r[4]))
                    .restored(longOf(r[5]))
                    .purged(longOf(r[6]))
                    .build());
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<ActionStatsDTO> loadActionStats(AnalyticsFilter filter, Window w) {
        WhereClause where = buildWhere(filter, w, sanitisedEntities(filter));

        String sql = ALL_LOGS_CTE + """
                SELECT action,
                       COUNT(*)                                                      AS total,
                       COUNT(DISTINCT actor_username) FILTER
                           (WHERE actor_username IS NOT NULL)                        AS distinct_actors,
                       COUNT(DISTINCT entity_id) FILTER
                           (WHERE entity_id IS NOT NULL)                             AS distinct_entities
                  FROM all_logs
                """ + where.sql + " GROUP BY action ORDER BY total DESC";

        Query q = em.createNativeQuery(sql);
        where.bind(q);

        List<ActionStatsDTO> out = new ArrayList<>();
        for (Object row : q.getResultList()) {
            Object[] r = (Object[]) row;
            out.add(ActionStatsDTO.builder()
                    .action((String) r[0])
                    .total(longOf(r[1]))
                    .distinctActors(longOf(r[2]))
                    .distinctEntities(longOf(r[3]))
                    .build());
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private FeedPageDTO loadRecentFeed(AnalyticsFilter filter, Window w, int size, int page) {
        WhereClause where = buildWhere(filter, w, sanitisedEntities(filter));

        // Two queries: one for the page slice, one for total count. The count
        // is needed so the UI can render pagination controls; both share the
        // same indexed (occurred_at DESC) traversal.
        String pageSql = ALL_LOGS_CTE + """
                SELECT entity, action,
                       entity_id, entity_code,
                       actor_user_id, actor_username, actor_display_name,
                       actor_authorities, actor_permissions,
                       request_method, request_path,
                       ip_address, device_info, session_id,
                       occurred_at, details
                  FROM all_logs
                """ + where.sql + " ORDER BY occurred_at DESC LIMIT :limit OFFSET :offset";

        Query q = em.createNativeQuery(pageSql);
        where.bind(q);
        q.setParameter("limit", size);
        q.setParameter("offset", (long) page * size);

        List<RecentActivityItemDTO> items = new ArrayList<>(size);
        for (Object row : q.getResultList()) {
            Object[] r = (Object[]) row;
            items.add(RecentActivityItemDTO.builder()
                    .entity((String) r[0])
                    .action((String) r[1])
                    .entityId(r[2] == null ? null : ((Number) r[2]).longValue())
                    .entityCode((String) r[3])
                    .actorUserId(r[4] == null ? null : ((Number) r[4]).longValue())
                    .actorUsername((String) r[5])
                    .actorDisplayName((String) r[6])
                    .actorAuthorities((String) r[7])
                    .actorPermissions((String) r[8])
                    .requestMethod((String) r[9])
                    .requestPath((String) r[10])
                    .ipAddress((String) r[11])
                    .deviceInfo((String) r[12])
                    .sessionId((String) r[13])
                    .occurredAt(instantOf(r[14]))
                    .details((String) r[15])
                    .build());
        }

        String countSql = ALL_LOGS_CTE
                + " SELECT COUNT(*) FROM all_logs " + where.sql;
        Query c = em.createNativeQuery(countSql);
        where.bind(c);
        long total = longOf(c.getSingleResult());

        int totalPages = (int) Math.max(1, (total + size - 1) / size);
        return FeedPageDTO.builder()
                .items(items)
                .page(page)
                .size(size)
                .totalElements(total)
                .totalPages(totalPages)
                .hasNext((page + 1L) * size < total)
                .hasPrevious(page > 0)
                .build();
    }

    @SuppressWarnings("unchecked")
    private List<UserSummaryDTO> loadUserSummaries(AnalyticsFilter filter, Window w) {
        WhereClause where = buildWhere(filter, w, sanitisedEntities(filter));

        // Two helper CTEs:
        //   agg    — per-user counts and first/last seen.
        //   latest — most-recent (user_id, authorities, permissions) per user via
        //            DISTINCT ON (actor_username) ORDER BY occurred_at DESC.
        // Joining them only in the final SELECT keeps the WHERE clause inside each
        // CTE unambiguous (no column name conflicts).
        String sql = ALL_LOGS_CTE + """
                ,
                agg AS (
                    SELECT actor_username,
                           MAX(actor_display_name)                                AS display_name,
                           COUNT(*)                                               AS total,
                           COUNT(*) FILTER (WHERE action = 'CREATE')              AS created,
                           COUNT(*) FILTER (WHERE action = 'UPDATE')              AS updated,
                           COUNT(*) FILTER (WHERE action IN ('DELETE','REMOVE'))  AS deleted,
                           COUNT(*) FILTER (WHERE action = 'RESTORE')             AS restored,
                           COUNT(*) FILTER (WHERE action = 'PURGE')               AS purged,
                           COUNT(*) FILTER (WHERE action = 'READ')                AS viewed,
                           COUNT(*) FILTER (WHERE action = 'LIST')                AS listed,
                           COUNT(*) FILTER (WHERE action = 'SEARCH')              AS searched,
                           MIN(occurred_at)                                       AS first_seen,
                           MAX(occurred_at)                                       AS last_seen
                      FROM all_logs
                """ + where.sql + """
                       AND actor_username IS NOT NULL
                     GROUP BY actor_username
                ),
                latest AS (
                    SELECT DISTINCT ON (actor_username)
                           actor_username, actor_user_id,
                           actor_authorities, actor_permissions
                      FROM all_logs
                """ + where.sql + """
                       AND actor_username IS NOT NULL
                     ORDER BY actor_username, occurred_at DESC
                )
                SELECT g.actor_username, g.display_name,
                       l.actor_user_id, l.actor_authorities, l.actor_permissions,
                       g.total, g.created, g.updated, g.deleted,
                       g.restored, g.purged, g.viewed, g.listed, g.searched,
                       g.first_seen, g.last_seen
                  FROM agg g
             LEFT JOIN latest l ON l.actor_username = g.actor_username
                """;

        Query q = em.createNativeQuery(sql);
        where.bind(q);

        List<UserSummaryDTO> out = new ArrayList<>();
        for (Object row : q.getResultList()) {
            Object[] r = (Object[]) row;
            out.add(UserSummaryDTO.builder()
                    .username((String) r[0])
                    .displayName((String) r[1])
                    .actorUserId(r[2] == null ? null : ((Number) r[2]).longValue())
                    .authorities((String) r[3])
                    .permissions((String) r[4])
                    .totalActions(longOf(r[5]))
                    .createCount(longOf(r[6]))
                    .updateCount(longOf(r[7]))
                    .deleteCount(longOf(r[8]))
                    .restoreCount(longOf(r[9]))
                    .purgeCount(longOf(r[10]))
                    .readCount(longOf(r[11]))
                    .listCount(longOf(r[12]))
                    .searchCount(longOf(r[13]))
                    .firstSeen(instantOf(r[14]))
                    .lastSeen(instantOf(r[15]))
                    .build());
        }
        return out;
    }

    /** Returns {first_seen, last_seen, display_name, actor_user_id, authorities, permissions}. */
    @SuppressWarnings("unchecked")
    private Object[] loadFirstLastSeen(AnalyticsFilter filter, Window w) {
        WhereClause where = buildWhere(filter, w, sanitisedEntities(filter));

        String sql = ALL_LOGS_CTE + """
                ,
                latest AS (
                    SELECT actor_user_id, actor_display_name,
                           actor_authorities, actor_permissions
                      FROM all_logs
                """ + where.sql + """
                     ORDER BY occurred_at DESC
                     LIMIT 1
                )
                SELECT MIN(a.occurred_at)              AS first_seen,
                       MAX(a.occurred_at)              AS last_seen,
                       (SELECT actor_display_name FROM latest)  AS display_name,
                       (SELECT actor_user_id      FROM latest)  AS actor_user_id,
                       (SELECT actor_authorities  FROM latest)  AS authorities,
                       (SELECT actor_permissions  FROM latest)  AS permissions
                  FROM all_logs a
                """ + where.sql;

        Query q = em.createNativeQuery(sql);
        where.bind(q);
        List<Object[]> rows = q.getResultList();
        return rows.isEmpty() ? new Object[]{null, null, null, null, null, null} : rows.get(0);
    }

    // ─── Filter helpers ─────────────────────────────────────────────────────

    /** Resolved [from, to] window. Inclusive on both sides. */
    private record Window(Instant from, Instant to) {}

    private Window window(AnalyticsFilter filter) {
        Instant to = filter.getTo() != null ? filter.getTo() : Instant.now();
        Instant from = filter.getFrom();
        if (from == null) {
            int days = filter.getDays() == null
                    ? DEFAULT_DAYS
                    : Math.max(1, Math.min(filter.getDays(), MAX_DAYS));
            from = to.minusSeconds((long) days * 86_400);
        }
        if (from.isAfter(to)) {
            // swap silently — easier UX than 400ing on a transposed range
            Instant tmp = from; from = to; to = tmp;
        }
        return new Window(from, to);
    }

    private Set<String> sanitisedEntities(AnalyticsFilter filter) {
        Set<String> requested = filter.getEntities();
        if (requested == null || requested.isEmpty()) {
            return new TreeSet<>(ENTITY_KEYS);
        }
        Set<String> out = new TreeSet<>();
        for (String e : requested) {
            if (e == null) continue;
            String norm = e.trim().toLowerCase(Locale.ROOT);
            if (ENTITY_KEYS.contains(norm)) out.add(norm);
        }
        return out.isEmpty() ? new TreeSet<>(ENTITY_KEYS) : out;
    }

    private Set<String> sanitisedActions(AnalyticsFilter filter) {
        Set<String> requested = filter.getActions();
        if (requested == null || requested.isEmpty()) return Set.of();
        Set<String> out = new TreeSet<>();
        for (String a : requested) {
            if (a == null) continue;
            String norm = a.trim().toUpperCase(Locale.ROOT);
            if (ACTION_KEYS.contains(norm)) out.add(norm);
        }
        return out;
    }

    /** Returns a copy of {@code filter} with {@code actor} forced to {@code username}. */
    private AnalyticsFilter withActor(AnalyticsFilter filter, String username) {
        AnalyticsFilter copy = AnalyticsFilter.builder()
                .from(filter.getFrom())
                .to(filter.getTo())
                .days(filter.getDays())
                .entities(filter.getEntities())
                .actions(filter.getActions())
                .actor(username)
                .actorPattern(filter.getActorPattern())
                .entityCode(filter.getEntityCode())
                .q(filter.getQ())
                .build();
        return copy;
    }

    /** Holds a SQL fragment plus the parameter binder so we can attach it to
     *  several queries that share the same predicate. */
    private static final class WhereClause {
        final String sql;
        private final java.util.function.Consumer<Query> binder;
        WhereClause(String sql, java.util.function.Consumer<Query> binder) {
            this.sql = sql;
            this.binder = binder;
        }
        void bind(Query q) { binder.accept(q); }
    }

    private WhereClause buildWhere(AnalyticsFilter filter, Window w, Set<String> entities) {
        Set<String> actions = sanitisedActions(filter);
        StringBuilder sb = new StringBuilder(" WHERE occurred_at >= :from AND occurred_at <= :to ");

        if (!entities.isEmpty() && entities.size() < ENTITY_KEYS.size()) {
            sb.append(" AND entity IN (:entities) ");
        }
        if (!actions.isEmpty()) {
            sb.append(" AND action IN (:actions) ");
        }
        if (filter.getActor() != null && !filter.getActor().isBlank()) {
            sb.append(" AND actor_username = :actor ");
        }
        if (filter.getActorPattern() != null && !filter.getActorPattern().isBlank()) {
            sb.append(" AND (LOWER(actor_username)     LIKE :actorPat ")
              .append("      OR LOWER(actor_display_name) LIKE :actorPat) ");
        }
        if (filter.getEntityCode() != null && !filter.getEntityCode().isBlank()) {
            sb.append(" AND entity_code = :entityCode ");
        }
        if (filter.getQ() != null && !filter.getQ().isBlank()) {
            sb.append(" AND (LOWER(COALESCE(details,''))     LIKE :qPat ")
              .append("      OR LOWER(COALESCE(entity_code,''))  LIKE :qPat ")
              .append("      OR LOWER(COALESCE(actor_username,'')) LIKE :qPat ")
              .append("      OR LOWER(COALESCE(actor_display_name,'')) LIKE :qPat) ");
        }

        java.util.function.Consumer<Query> binder = q -> {
            q.setParameter("from", Timestamp.from(w.from));
            q.setParameter("to",   Timestamp.from(w.to));
            if (!entities.isEmpty() && entities.size() < ENTITY_KEYS.size()) {
                q.setParameter("entities", entities);
            }
            if (!actions.isEmpty()) {
                q.setParameter("actions", actions);
            }
            if (filter.getActor() != null && !filter.getActor().isBlank()) {
                q.setParameter("actor", filter.getActor().trim());
            }
            if (filter.getActorPattern() != null && !filter.getActorPattern().isBlank()) {
                q.setParameter("actorPat",
                        "%" + filter.getActorPattern().trim().toLowerCase(Locale.ROOT) + "%");
            }
            if (filter.getEntityCode() != null && !filter.getEntityCode().isBlank()) {
                q.setParameter("entityCode", filter.getEntityCode().trim());
            }
            if (filter.getQ() != null && !filter.getQ().isBlank()) {
                q.setParameter("qPat",
                        "%" + filter.getQ().trim().toLowerCase(Locale.ROOT) + "%");
            }
        };

        return new WhereClause(sb.toString(), binder);
    }

    // ─── Result-mapper helpers ──────────────────────────────────────────────

    private static long longOf(Object value) {
        if (value == null) return 0L;
        if (value instanceof Number n) return n.longValue();
        if (value instanceof BigInteger bi) return bi.longValue();
        return Long.parseLong(value.toString());
    }

    // Hibernate 6 maps PostgreSQL `timestamp` to Instant; older drivers and the JDBC
    // ResultSet path can return Timestamp/LocalDateTime/OffsetDateTime instead.
    // Accept all four so the same query works regardless of the path that ran it.
    private static Instant instantOf(Object value) {
        if (value == null) return null;
        if (value instanceof Instant i) return i;
        if (value instanceof Timestamp ts) return ts.toInstant();
        if (value instanceof OffsetDateTime odt) return odt.toInstant();
        if (value instanceof LocalDateTime ldt) return ldt.toInstant(ZoneOffset.UTC);
        throw new IllegalStateException("Unexpected timestamp result type: " + value.getClass());
    }
}
