package ak.dev.khi_archive_platform.platform.enums;

public enum GuestCorrectionAuditAction {
    /** Guest submitted a new correction suggestion. */
    SUBMIT,
    /** Admin or guest viewed a single correction. */
    VIEW,
    /** Admin listed / searched corrections. */
    LIST,
    /** Admin forwarded correction to the employee who created the record. */
    FORWARD,
    /** Admin marked correction as resolved. */
    RESOLVE,
    /** Admin rejected correction. */
    REJECT,
    /** Admin soft-deleted correction. */
    REMOVE
}
