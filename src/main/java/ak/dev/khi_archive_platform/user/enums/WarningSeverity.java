package ak.dev.khi_archive_platform.user.enums;

/**
 * Severity of an admin-issued user warning. Drives UI styling (info badge vs.
 * red alert) and the order in which warnings are surfaced to the recipient.
 *
 * <ul>
 *   <li>{@code INFO} — a heads-up. No action required from the user.</li>
 *   <li>{@code WARNING} — work-quality concern. User is expected to read,
 *       acknowledge and improve.</li>
 *   <li>{@code CRITICAL} — repeated or severe issue. Logged and may be
 *       followed by an admin lock/deactivation.</li>
 * </ul>
 */
public enum WarningSeverity {
    INFO,
    WARNING,
    CRITICAL
}
