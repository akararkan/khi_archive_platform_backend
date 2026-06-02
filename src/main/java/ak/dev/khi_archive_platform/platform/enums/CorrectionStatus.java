package ak.dev.khi_archive_platform.platform.enums;

public enum CorrectionStatus {
    /** Submitted by guest, awaiting admin review. */
    PENDING,
    /** Admin forwarded to the employee who created the record. */
    FORWARDED,
    /** Admin marked as resolved (employee applied the correction). */
    RESOLVED,
    /** Admin rejected the suggestion. */
    REJECTED
}
