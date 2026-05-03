package ak.dev.khi_archive_platform.user.enums;

/**
 * Actions an admin can perform on a user record. One row per action goes into
 * {@code user_audit_logs} via {@code UserAuditService.record(...)}.
 */
public enum UserAuditAction {
    CREATE,
    UPDATE,
    DELETE,
    ROLE_CHANGE,
    GRANT_PERMISSIONS,
    REVOKE_PERMISSIONS,
    ACTIVATE,
    DEACTIVATE,
    READ,
    LIST
}
