package ak.dev.khi_archive_platform.user.consts;

/**
 * Compile-time constants for reusable validation regex patterns.
 * <p>
 * These are {@code static final String} so they can be referenced
 * directly inside Jakarta annotation attributes (which require
 * compile-time constants).
 */
@SuppressWarnings("unused")
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

    // ── USERNAME ──────────────────────────────────────────────────────────────
    /** Allows letters, digits, and underscores only (no spaces, no special chars). */
    public static final String USERNAME = "^[A-Za-z0-9_]+$";

    // ── PERSON CODE ───────────────────────────────────────────────────────────
    /** Person codes use letters, digits, underscores, or hyphens. */
    public static final String PERSON_CODE = "^[A-Za-z0-9_-]+$";

    // ── CATEGORY CODE ─────────────────────────────────────────────────────────
    /** Category codes are plain strings using letters, digits, underscores, or hyphens. */
    public static final String CATEGORY_CODE = "^[A-Za-z0-9_-]+$";

    // ── OBJECT CODE ───────────────────────────────────────────────────────────
    /** Object codes must follow the format {@code OBJ_CATEGORYCODE}. */
    public static final String OBJECT_CODE = "^OBJ_[A-Za-z0-9_-]+$";

    /** Object codes on create may be omitted, so an empty value is also allowed. */
    public static final String OBJECT_CODE_OR_EMPTY = "^$|^OBJ_[A-Za-z0-9_-]+$";

    // ── AUDIO CODE ────────────────────────────────────────────────────────────
    /** Audio codes must follow the format {@code <NAME>_AUDIO_<RAW|MASTER>_V<N>_COPY<N>_000001}. */
    public static final String AUDIO_CODE = "^[A-Za-z0-9_-]+_AUDIO_(RAW|MASTER)_V[0-9]+_COPY[0-9]+_[0-9]{6}$";

    /** Legacy alias kept for compatibility with existing code paths. */
    public static final String OBJECT_CATEGORY_CODE = OBJECT_CODE;

    /**
     * Same as {@link #USERNAME} but also accepts an empty string —
     * used on optional update DTOs where an empty value means "no change".
     */
    public static final String USERNAME_OR_EMPTY = "^$|^[A-Za-z0-9_]+$";
}

