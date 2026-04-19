package ak.dev.khi_archive_platform.user.consts;

/**
 * Compile-time constants for reusable validation regex patterns.
 * <p>
 * These are {@code static final String} so they can be referenced
 * directly inside Jakarta annotation attributes (which require
 * compile-time constants).
 */
public final class ValidationPatterns {

    private ValidationPatterns() {}

    // ── EMAIL ─────────────────────────────────────────────────────────────────
    /**
     * Stricter email pattern than Hibernate's default.
     * <ul>
     *   <li>Local part: letters, digits, {@code . _ % + -}</li>
     *   <li>Requires {@code @}</li>
     *   <li>Domain: letters, digits, {@code . -}</li>
     *   <li>Requires at least one dot followed by 2–10 TLD letters</li>
     * </ul>
     * Examples that PASS : {@code user@example.com}, {@code first.last+tag@sub.domain.org}
     * Examples that FAIL : {@code user@localhost}, {@code @example.com}, {@code user@.com}
     */
    public static final String EMAIL =
            "^[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,10}$";

    // ── PASSWORD ──────────────────────────────────────────────────────────────
    /**
     * Minimum-length password pattern.
     * <p>Requires only:
     * <ul>
     *   <li>At least <strong>6</strong> characters</li>
     *   <li>No upper-bound enforced here (use {@code @Size} alongside for max)</li>
     * </ul>
     */
    public static final String PASSWORD = "^.{6,}$";

    // ── USERNAME ──────────────────────────────────────────────────────────────
    /** Allows letters, digits, and underscores only (no spaces, no special chars). */
    public static final String USERNAME = "^[A-Za-z0-9_]+$";

    /**
     * Same as {@link #USERNAME} but also accepts an empty string —
     * used on optional update DTOs where an empty value means "no change".
     */
    public static final String USERNAME_OR_EMPTY = "^$|^[A-Za-z0-9_]+$";
}

