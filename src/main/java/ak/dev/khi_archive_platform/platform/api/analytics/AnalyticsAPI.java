package ak.dev.khi_archive_platform.platform.api.analytics;

import ak.dev.khi_archive_platform.platform.dto.analytics.ActionStatsDTO;
import ak.dev.khi_archive_platform.platform.dto.analytics.AnalyticsFilter;
import ak.dev.khi_archive_platform.platform.dto.analytics.DailyBucketDTO;
import ak.dev.khi_archive_platform.platform.dto.analytics.EntityStatsDTO;
import ak.dev.khi_archive_platform.platform.dto.analytics.FeedPageDTO;
import ak.dev.khi_archive_platform.platform.dto.analytics.MonthlyBucketDTO;
import ak.dev.khi_archive_platform.platform.dto.analytics.TeamOverviewDTO;
import ak.dev.khi_archive_platform.platform.dto.analytics.UserActivityDTO;
import ak.dev.khi_archive_platform.platform.dto.analytics.UserSummaryDTO;
import ak.dev.khi_archive_platform.platform.enums.AnalyticsAuditAction;
import ak.dev.khi_archive_platform.platform.service.analytics.AnalyticsAuditService;
import ak.dev.khi_archive_platform.platform.service.analytics.AnalyticsService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Analytics endpoints. Backed by {@link AnalyticsService} which runs a single
 * UNION ALL across all seven {@code *_audit_logs} tables; every endpoint also
 * writes one row to {@code analytics_audit_logs} via {@link AnalyticsAuditService}.
 *
 * <p>Authorisation: the whole controller is gated on {@code ROLE_ADMIN}.
 *
 * <p>Universal query parameters (accepted on every endpoint):
 * <ul>
 *   <li>{@code days} — window length (1-365, default 30) when {@code from/to} absent</li>
 *   <li>{@code from} / {@code to} — explicit ISO-8601 instants</li>
 *   <li>{@code entities} — CSV: audio,video,image,text,project,category,person</li>
 *   <li>{@code actions} — CSV: CREATE,READ,SEARCH,UPDATE,DELETE,REMOVE,RESTORE,PURGE
 *       (LIST is intentionally never counted — page-load noise, not work).
 *       Use {@code GET /api/analytics/actions/catalog} for the UI-facing
 *       short list (CREATE/READ/UPDATE/DELETE/SEARCH).</li>
 *   <li>{@code actor} — exact username</li>
 *   <li>{@code actorPattern} — substring on username/display name (case-insensitive)</li>
 *   <li>{@code entityCode} — exact entity code</li>
 *   <li>{@code q} — free-text substring on details / entity code / actor</li>
 * </ul>
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/analytics")
@PreAuthorize("hasRole('ADMIN')")
public class AnalyticsAPI {

    private static final int MAX_RECENT = 500;
    private static final int DEFAULT_TOP = 10;
    private static final int MAX_TOP = 100;
    private static final int DEFAULT_PAGE_SIZE = 50;
    /** The /monthly endpoint defaults to a year so the UI shows ~12 buckets
     *  by default. The universal {@code days} param still wins if supplied. */
    private static final int DEFAULT_MONTHLY_WINDOW_DAYS = 365;

    private final AnalyticsService analyticsService;
    private final AnalyticsAuditService auditService;

    /** Calling admin's own activity picture, with a paginated recent feed. */
    @GetMapping("/me")
    public ResponseEntity<UserActivityDTO> me(
            @RequestParam(value = "days",        required = false) Integer days,
            @RequestParam(value = "from",        required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(value = "to",          required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(value = "entities",    required = false) String entities,
            @RequestParam(value = "actions",     required = false) String actions,
            @RequestParam(value = "actorPattern",required = false) String actorPattern,
            @RequestParam(value = "entityCode",  required = false) String entityCode,
            @RequestParam(value = "q",           required = false) String q,
            @RequestParam(value = "page",        required = false) Integer page,
            @RequestParam(value = "size",        required = false) Integer size,
            @RequestParam(value = "sort",        required = false) String sort,
            Authentication auth,
            HttpServletRequest request) {
        AnalyticsFilter filter = build(days, from, to, entities, actions, null, actorPattern, entityCode, q);
        int p = clampPage(page);
        int s = clampPageSize(size);
        AnalyticsService.SortDirection dir = parseSort(sort);
        UserActivityDTO body = analyticsService.getUserActivity(auth.getName(), filter, p, s, dir);
        auditService.record(AnalyticsAuditAction.VIEW_USER, filter.toCacheKey() + ":page=" + p + ":size=" + s + ":sort=" + dir,
                auth, request, "Self activity (page=" + p + " size=" + s + " sort=" + dir + ")");
        return ResponseEntity.ok(body);
    }

    /** Any user's activity, with a paginated recent feed. */
    @GetMapping("/users/{username}")
    public ResponseEntity<UserActivityDTO> userActivity(
            @PathVariable String username,
            @RequestParam(value = "days",        required = false) Integer days,
            @RequestParam(value = "from",        required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(value = "to",          required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(value = "entities",    required = false) String entities,
            @RequestParam(value = "actions",     required = false) String actions,
            @RequestParam(value = "entityCode",  required = false) String entityCode,
            @RequestParam(value = "q",           required = false) String q,
            @RequestParam(value = "page",        required = false) Integer page,
            @RequestParam(value = "size",        required = false) Integer size,
            @RequestParam(value = "sort",        required = false) String sort,
            Authentication auth,
            HttpServletRequest request) {
        AnalyticsFilter filter = build(days, from, to, entities, actions, null, null, entityCode, q);
        int p = clampPage(page);
        int s = clampPageSize(size);
        AnalyticsService.SortDirection dir = parseSort(sort);
        UserActivityDTO body = analyticsService.getUserActivity(username, filter, p, s, dir);
        auditService.record(AnalyticsAuditAction.VIEW_USER,
                filter.toCacheKey() + ":target=" + username + ":page=" + p + ":size=" + s + ":sort=" + dir,
                auth, request, "User activity for " + username
                        + " (page=" + p + " size=" + s + " sort=" + dir + ")");
        return ResponseEntity.ok(body);
    }

    /** Per-user totals across the team, sorted by activity. */
    @GetMapping("/users")
    public ResponseEntity<List<UserSummaryDTO>> users(
            @RequestParam(value = "days",        required = false) Integer days,
            @RequestParam(value = "from",        required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(value = "to",          required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(value = "entities",    required = false) String entities,
            @RequestParam(value = "actions",     required = false) String actions,
            @RequestParam(value = "actorPattern",required = false) String actorPattern,
            @RequestParam(value = "q",           required = false) String q,
            Authentication auth,
            HttpServletRequest request) {
        AnalyticsFilter filter = build(days, from, to, entities, actions, null, actorPattern, null, q);
        List<UserSummaryDTO> body = analyticsService.getUsers(filter);
        auditService.record(AnalyticsAuditAction.VIEW_USERS, filter.toCacheKey(), auth, request,
                "User leaderboard (returned=" + body.size() + ")");
        return ResponseEntity.ok(body);
    }

    /** Team overview (totals + per-entity + top-N users + daily breakdown). */
    @GetMapping("/overview")
    public ResponseEntity<TeamOverviewDTO> overview(
            @RequestParam(value = "days",        required = false) Integer days,
            @RequestParam(value = "from",        required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(value = "to",          required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(value = "entities",    required = false) String entities,
            @RequestParam(value = "actions",     required = false) String actions,
            @RequestParam(value = "actorPattern",required = false) String actorPattern,
            @RequestParam(value = "q",           required = false) String q,
            @RequestParam(value = "topUsers",    required = false) Integer topN,
            Authentication auth,
            HttpServletRequest request) {
        AnalyticsFilter filter = build(days, from, to, entities, actions, null, actorPattern, null, q);
        int top = clampTop(topN);
        TeamOverviewDTO body = analyticsService.getOverview(filter, top);
        auditService.record(AnalyticsAuditAction.VIEW_OVERVIEW,
                filter.toCacheKey() + ":top=" + top, auth, request,
                "Team overview (topN=" + top + ")");
        return ResponseEntity.ok(body);
    }

    /**
     * Cross-entity activity feed in chronological order, paginated. Defaults:
     * {@code page=0}, {@code size=50}, {@code sort=desc}. Caps: 500 per page.
     */
    @GetMapping("/feed")
    public ResponseEntity<FeedPageDTO> feed(
            @RequestParam(value = "days",        required = false) Integer days,
            @RequestParam(value = "from",        required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(value = "to",          required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(value = "entities",    required = false) String entities,
            @RequestParam(value = "actions",     required = false) String actions,
            @RequestParam(value = "actor",       required = false) String actor,
            @RequestParam(value = "actorPattern",required = false) String actorPattern,
            @RequestParam(value = "entityCode",  required = false) String entityCode,
            @RequestParam(value = "q",           required = false) String q,
            @RequestParam(value = "page",        required = false) Integer page,
            @RequestParam(value = "size",        required = false) Integer size,
            @RequestParam(value = "sort",        required = false) String sort,
            Authentication auth,
            HttpServletRequest request) {
        AnalyticsFilter filter = build(days, from, to, entities, actions, actor, actorPattern, entityCode, q);
        int p = clampPage(page);
        int s = clampPageSize(size);
        AnalyticsService.SortDirection dir = parseSort(sort);
        FeedPageDTO body = analyticsService.getFeed(filter, p, s, dir);
        auditService.record(AnalyticsAuditAction.VIEW_FEED,
                filter.toCacheKey() + ":page=" + p + ":size=" + s + ":sort=" + dir, auth, request,
                "Activity feed (page=" + p + " size=" + s + " sort=" + dir
                        + " total=" + body.getTotalElements() + ")");
        return ResponseEntity.ok(body);
    }

    /** Per-action breakdown: how many CREATE/UPDATE/DELETE/etc rows match the filter. */
    @GetMapping("/actions")
    public ResponseEntity<List<ActionStatsDTO>> actions(
            @RequestParam(value = "days",        required = false) Integer days,
            @RequestParam(value = "from",        required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(value = "to",          required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(value = "entities",    required = false) String entities,
            @RequestParam(value = "actor",       required = false) String actor,
            @RequestParam(value = "actorPattern",required = false) String actorPattern,
            @RequestParam(value = "entityCode",  required = false) String entityCode,
            @RequestParam(value = "q",           required = false) String q,
            Authentication auth,
            HttpServletRequest request) {
        AnalyticsFilter filter = build(days, from, to, entities, null, actor, actorPattern, entityCode, q);
        List<ActionStatsDTO> body = analyticsService.getActionStats(filter);
        auditService.record(AnalyticsAuditAction.VIEW_ACTIONS, filter.toCacheKey(), auth, request,
                "Per-action breakdown (rows=" + body.size() + ")");
        return ResponseEntity.ok(body);
    }

    /** Per-day buckets — exposes the daily time-series on its own. */
    @GetMapping("/daily")
    public ResponseEntity<List<DailyBucketDTO>> daily(
            @RequestParam(value = "days",        required = false) Integer days,
            @RequestParam(value = "from",        required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(value = "to",          required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(value = "entities",    required = false) String entities,
            @RequestParam(value = "actions",     required = false) String actions,
            @RequestParam(value = "actor",       required = false) String actor,
            @RequestParam(value = "actorPattern",required = false) String actorPattern,
            @RequestParam(value = "entityCode",  required = false) String entityCode,
            @RequestParam(value = "q",           required = false) String q,
            Authentication auth,
            HttpServletRequest request) {
        AnalyticsFilter filter = build(days, from, to, entities, actions, actor, actorPattern, entityCode, q);
        List<DailyBucketDTO> body = analyticsService.getDaily(filter);
        auditService.record(AnalyticsAuditAction.VIEW_DAILY, filter.toCacheKey(), auth, request,
                "Daily breakdown (rows=" + body.size() + ")");
        return ResponseEntity.ok(body);
    }

    /**
     * Per-month buckets — the "monthly statistics of user work" view.
     * Defaults to a 365-day window (covering ~12 months) when neither
     * {@code days} nor {@code from/to} is supplied so the chart isn't
     * empty out of the box.
     */
    @GetMapping("/monthly")
    public ResponseEntity<List<MonthlyBucketDTO>> monthly(
            @RequestParam(value = "days",        required = false) Integer days,
            @RequestParam(value = "from",        required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(value = "to",          required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(value = "entities",    required = false) String entities,
            @RequestParam(value = "actions",     required = false) String actions,
            @RequestParam(value = "actor",       required = false) String actor,
            @RequestParam(value = "actorPattern",required = false) String actorPattern,
            @RequestParam(value = "entityCode",  required = false) String entityCode,
            @RequestParam(value = "q",           required = false) String q,
            Authentication auth,
            HttpServletRequest request) {
        Integer effectiveDays = days;
        if (effectiveDays == null && from == null && to == null) {
            effectiveDays = DEFAULT_MONTHLY_WINDOW_DAYS;
        }
        AnalyticsFilter filter = build(effectiveDays, from, to, entities, actions, actor, actorPattern, entityCode, q);
        List<MonthlyBucketDTO> body = analyticsService.getMonthly(filter);
        auditService.record(AnalyticsAuditAction.VIEW_MONTHLY, filter.toCacheKey(), auth, request,
                "Monthly breakdown (rows=" + body.size() + ")");
        return ResponseEntity.ok(body);
    }

    /**
     * Catalog of the actions an admin can choose to filter analytics by.
     * Drives the "choose the actions to see" checkbox list on the UI:
     * CREATE / READ / UPDATE / DELETE / SEARCH (LIST is never offered).
     * Returned values can be sent back as the {@code actions=} CSV.
     */
    @GetMapping("/actions/catalog")
    public ResponseEntity<List<String>> actionCatalog(Authentication auth, HttpServletRequest request) {
        List<String> body = AnalyticsService.SELECTABLE_ACTIONS;
        auditService.record(AnalyticsAuditAction.VIEW_ACTION_CATALOG, "catalog",
                auth, request, "Action catalog (count=" + body.size() + ")");
        return ResponseEntity.ok(body);
    }

    /** Per-entity stats — same shape as {@code overview.byEntity} but as its own endpoint. */
    @GetMapping("/entities")
    public ResponseEntity<Map<String, EntityStatsDTO>> entities(
            @RequestParam(value = "days",        required = false) Integer days,
            @RequestParam(value = "from",        required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(value = "to",          required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(value = "entities",    required = false) String entities,
            @RequestParam(value = "actions",     required = false) String actions,
            @RequestParam(value = "actor",       required = false) String actor,
            @RequestParam(value = "actorPattern",required = false) String actorPattern,
            @RequestParam(value = "entityCode",  required = false) String entityCode,
            @RequestParam(value = "q",           required = false) String q,
            Authentication auth,
            HttpServletRequest request) {
        AnalyticsFilter filter = build(days, from, to, entities, actions, actor, actorPattern, entityCode, q);
        Map<String, EntityStatsDTO> body = analyticsService.getEntityStats(filter);
        auditService.record(AnalyticsAuditAction.VIEW_ENTITY_STATS, filter.toCacheKey(),
                auth, request, "Entity stats (entities=" + body.size() + ")");
        return ResponseEntity.ok(body);
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    private static AnalyticsFilter build(Integer days, Instant from, Instant to,
                                         String entities, String actions,
                                         String actor, String actorPattern,
                                         String entityCode, String q) {
        return AnalyticsFilter.builder()
                .from(from)
                .to(to)
                .days(days)
                .entities(parseCsv(entities))
                .actions(parseCsv(actions == null ? null : actions.toUpperCase(Locale.ROOT)))
                .actor(actor)
                .actorPattern(actorPattern)
                .entityCode(entityCode)
                .q(q)
                .build();
    }

    private static Set<String> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) return null;
        Set<String> out = new LinkedHashSet<>();
        for (String part : csv.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out.isEmpty() ? null : out;
    }

    private static int clampPage(Integer page) {
        if (page == null || page < 0) return 0;
        return page;
    }

    private static int clampPageSize(Integer size) {
        if (size == null || size <= 0) return DEFAULT_PAGE_SIZE;
        return Math.min(size, MAX_RECENT);
    }

    private static AnalyticsService.SortDirection parseSort(String sort) {
        if (sort == null) return AnalyticsService.SortDirection.DESC;
        String norm = sort.trim().toLowerCase(Locale.ROOT);
        // Accept "asc", "desc", "occurredAt,asc", "occurredAt,desc" (Spring-style).
        if (norm.endsWith("asc"))  return AnalyticsService.SortDirection.ASC;
        if (norm.endsWith("desc")) return AnalyticsService.SortDirection.DESC;
        throw new IllegalArgumentException(
                "Invalid sort value: '" + sort + "'. Allowed: asc | desc");
    }

    private static int clampTop(Integer topN) {
        if (topN == null || topN <= 0) return DEFAULT_TOP;
        return Math.min(topN, MAX_TOP);
    }
}
