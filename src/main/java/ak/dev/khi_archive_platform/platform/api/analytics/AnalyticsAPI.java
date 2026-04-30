package ak.dev.khi_archive_platform.platform.api.analytics;

import ak.dev.khi_archive_platform.platform.dto.analytics.ActionStatsDTO;
import ak.dev.khi_archive_platform.platform.dto.analytics.AnalyticsFilter;
import ak.dev.khi_archive_platform.platform.dto.analytics.DailyBucketDTO;
import ak.dev.khi_archive_platform.platform.dto.analytics.EntityStatsDTO;
import ak.dev.khi_archive_platform.platform.dto.analytics.FeedPageDTO;
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
 *   <li>{@code actions} — CSV: CREATE,READ,LIST,SEARCH,UPDATE,DELETE,REMOVE,RESTORE,PURGE</li>
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

    private static final int DEFAULT_RECENT = 50;
    private static final int MAX_RECENT = 500;
    private static final int DEFAULT_TOP = 10;
    private static final int MAX_TOP = 100;
    private static final int DEFAULT_PAGE_SIZE = 50;

    private final AnalyticsService analyticsService;
    private final AnalyticsAuditService auditService;

    /** Calling admin's own activity picture. */
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
            @RequestParam(value = "recent",      required = false) Integer recent,
            Authentication auth,
            HttpServletRequest request) {
        AnalyticsFilter filter = build(days, from, to, entities, actions, null, actorPattern, entityCode, q);
        int recentLimit = clampRecent(recent);
        UserActivityDTO body = analyticsService.getUserActivity(auth.getName(), filter, recentLimit);
        auditService.record(AnalyticsAuditAction.VIEW_USER, filter.toCacheKey(), auth, request,
                "Self activity (recent=" + recentLimit + ")");
        return ResponseEntity.ok(body);
    }

    /** Any user's activity. */
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
            @RequestParam(value = "recent",      required = false) Integer recent,
            Authentication auth,
            HttpServletRequest request) {
        AnalyticsFilter filter = build(days, from, to, entities, actions, null, null, entityCode, q);
        int recentLimit = clampRecent(recent);
        UserActivityDTO body = analyticsService.getUserActivity(username, filter, recentLimit);
        auditService.record(AnalyticsAuditAction.VIEW_USER, filter.toCacheKey() + ":target=" + username,
                auth, request, "User activity for " + username + " (recent=" + recentLimit + ")");
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
     * Cross-entity activity feed in chronological order, paginated. {@code page}
     * and {@code size} default to 0/50; cap at 500 per page.
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
            Authentication auth,
            HttpServletRequest request) {
        AnalyticsFilter filter = build(days, from, to, entities, actions, actor, actorPattern, entityCode, q);
        int p = page == null ? 0 : Math.max(0, page);
        int s = size == null ? DEFAULT_PAGE_SIZE : Math.max(1, Math.min(size, MAX_RECENT));
        FeedPageDTO body = analyticsService.getFeed(filter, p, s);
        auditService.record(AnalyticsAuditAction.VIEW_FEED,
                filter.toCacheKey() + ":page=" + p + ":size=" + s, auth, request,
                "Activity feed (page=" + p + " size=" + s + " total=" + body.getTotalElements() + ")");
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

    private static int clampRecent(Integer limit) {
        if (limit == null || limit <= 0) return DEFAULT_RECENT;
        return Math.min(limit, MAX_RECENT);
    }

    private static int clampTop(Integer topN) {
        if (topN == null || topN <= 0) return DEFAULT_TOP;
        return Math.min(topN, MAX_TOP);
    }
}
