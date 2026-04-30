package ak.dev.khi_archive_platform.platform.enums;

/**
 * Enumerates the analytics-console actions worth auditing. Each value maps
 * one-to-one to an {@code /api/analytics/...} endpoint so an admin reading
 * the audit trail can see exactly which view a colleague opened.
 */
public enum AnalyticsAuditAction {
    VIEW_OVERVIEW,
    VIEW_USER,
    VIEW_USERS,
    VIEW_FEED,
    VIEW_ACTIONS,
    VIEW_DAILY,
    VIEW_ENTITY_STATS
}
