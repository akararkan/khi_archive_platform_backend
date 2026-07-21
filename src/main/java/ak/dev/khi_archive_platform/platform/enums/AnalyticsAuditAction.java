package ak.dev.khi_archive_platform.platform.enums;

/**
 * Enumerates the analytics-console actions worth auditing. Each value maps
 * one-to-one to an {@code /api/analytics/...} endpoint so an admin reading
 * the audit trail can see exactly which view a colleague opened.
 *
 * <p>New values are safe to add: {@code AnalyticsAuditActionConstraintInitializer}
 * re-syncs the {@code analytics_audit_logs.action} CHECK constraint on every
 * boot, so no manual DDL is needed.
 */
public enum AnalyticsAuditAction {
    VIEW_OVERVIEW,
    VIEW_USER,
    VIEW_USERS,
    VIEW_FEED,
    VIEW_ACTIONS,
    VIEW_DAILY,
    VIEW_WEEKLY,
    VIEW_MONTHLY,
    VIEW_YEARLY,
    VIEW_ENTITY_STATS,
    VIEW_ACTION_CATALOG,
    VIEW_INVENTORY,
    VIEW_VISIBILITY,
    VIEW_MAQAM_OVERVIEW,
    VIEW_MAQAM_TEACHERS,
    VIEW_MAQAM_TEACHER
}
