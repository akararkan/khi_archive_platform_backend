package ak.dev.khi_archive_platform.platform.service.common;

import java.util.Collection;
import java.util.List;

/**
 * Single source of truth for keyword normalisation.
 *
 * <p>Same algorithm as {@link Tags} (NFKC → trim → collapse whitespace →
 * lower-case → dedupe), but with a higher length cap ({@link #MAX_KEYWORD_LENGTH}
 * = 200 chars). Keywords are search-hint phrases — they can legitimately be
 * multi-word ({@code "traditional kurdish folk music"}) — whereas tags are
 * short labels.</p>
 *
 * <p>Kept as a separate facade rather than an overload of
 * {@link Tags#canonical} so that every call site explicitly states whether
 * the field is a tag or a keyword. The two fields have different intent and
 * the type-safe call ("Keywords vs Tags") prevents a future contributor from
 * accidentally truncating a long keyword by reusing the tag method.</p>
 */
public final class Keywords {

    /** Hard cap on a single keyword's character count — phrases allowed. */
    public static final int MAX_KEYWORD_LENGTH = 200;

    private Keywords() {}

    /**
     * Canonicalise + dedupe the supplied keyword collection. Returns a new
     * {@link java.util.ArrayList} suitable for assigning back to the entity.
     * Never returns {@code null} — an empty list is returned for null/empty
     * input so JPA collection-table deletion semantics still kick in.
     */
    public static List<String> canonical(Collection<String> raw) {
        return Tags.TextListCanonicalizer.canonical(raw, MAX_KEYWORD_LENGTH);
    }

    /**
     * Canonicalise a single keyword. Returns {@code null} if the input is
     * null, empty after trimming, or longer than {@link #MAX_KEYWORD_LENGTH}
     * after normalisation.
     */
    public static String canonicalOne(String raw) {
        return Tags.TextListCanonicalizer.canonicalOne(raw, MAX_KEYWORD_LENGTH);
    }
}
