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
    public static final String EMAIL =
            "^[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,10}$";

    // ── USERNAME ──────────────────────────────────────────────────────────────
    public static final String USERNAME = "^[A-Za-z0-9_]+$";

    // ── PERSON CODE ───────────────────────────────────────────────────────────
    public static final String PERSON_CODE = "^[A-Za-z0-9_-]+$";

    // ── CATEGORY CODE ─────────────────────────────────────────────────────────
    public static final String CATEGORY_CODE = "^[A-Za-z0-9_-]+$";

    // ── PROJECT CODE ──────────────────────────────────────────────────────────
    /** Project codes: PERSONCODE_CATEGORYCODE or UNTITLED_CATEGORYCODE */
    public static final String PROJECT_CODE = "^[A-Za-z0-9_-]+$";

    // ── AUDIO CODE ────────────────────────────────────────────────────────────
    /** Audio codes: PARENT_AUD_VERSION_VN_Copy(CN)_SEQUENCE */
    public static final String AUDIO_CODE = "^[A-Za-z0-9_-]+_AUD_(RAW|MASTER)_V[0-9]+_Copy\\([0-9]+\\)_[0-9]{6}$";

    /**
     * Same as {@link #USERNAME} but also accepts an empty string —
     * used on optional update DTOs where an empty value means "no change".
     */
    public static final String USERNAME_OR_EMPTY = "^$|^[A-Za-z0-9_]+$";
}
