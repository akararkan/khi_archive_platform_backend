package ak.dev.khi_archive_platform.platform.service.common;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Single source of truth for tag normalisation.
 *
 * <p>Every entity that has a {@code List<String> tags} field (Audio, Video,
 * Image, Text, Project) must run user-supplied tags through {@link #canonical}
 * before persisting them.</p>
 *
 * <p>The actual normalisation algorithm lives in {@link TextListCanonicalizer}
 * so the sibling {@link Keywords} utility can reuse it with a different
 * length cap.</p>
 *
 * <p>The output is:</p>
 * <ol>
 *   <li><b>Unicode-normalised (NFKC)</b> — half-width forms, ligatures and
 *       compatibility characters collapse to their canonical form so
 *       {@code "Quran"}, {@code "ｑｕｒａｎ"} and {@code "Q‌uran"} hash
 *       to the same string.</li>
 *   <li><b>Trimmed + internal whitespace collapsed</b> — multiple spaces
 *       become one space; leading/trailing whitespace removed.</li>
 *   <li><b>Lower-cased</b> using {@code Locale.ROOT} — case-insensitive
 *       uniqueness. {@code "Sulaimaniyah"} and {@code "sulaimaniyah"} are
 *       treated as the same tag.</li>
 *   <li><b>Deduplicated, first-occurrence wins</b> — if the same canonical
 *       form appears multiple times we keep the first.</li>
 *   <li><b>Blanks dropped</b> — null / empty / whitespace-only entries are
 *       removed entirely.</li>
 *   <li><b>Length-capped</b> at {@link #MAX_TAG_LENGTH} chars — anything
 *       longer is rejected silently rather than truncated, because a
 *       truncated tag would silently collide with an unrelated one.</li>
 * </ol>
 */
public final class Tags {

    /** Hard cap on a single tag's character count — tags are short labels. */
    public static final int MAX_TAG_LENGTH = 64;

    private Tags() {}

    /**
     * Canonicalise + dedupe the supplied tag collection. Returns a new
     * {@link ArrayList} suitable for assigning back to the entity. Never
     * returns {@code null} — an empty list is returned for null/empty input
     * so JPA collection-table deletion semantics still kick in.
     */
    public static List<String> canonical(Collection<String> raw) {
        return TextListCanonicalizer.canonical(raw, MAX_TAG_LENGTH);
    }

    /**
     * Canonicalise a single tag. Returns {@code null} if the input is null,
     * empty after trimming, or longer than {@link #MAX_TAG_LENGTH} after
     * normalisation.
     */
    public static String canonicalOne(String raw) {
        return TextListCanonicalizer.canonicalOne(raw, MAX_TAG_LENGTH);
    }

    /**
     * Internal helper shared by {@link Tags} and {@link Keywords} — only the
     * length cap differs between the two. Kept package-private so callers
     * always go through the typed facade and see the intent (tag vs. keyword)
     * at the call site.
     */
    static final class TextListCanonicalizer {

        private TextListCanonicalizer() {}

        static List<String> canonical(Collection<String> raw, int maxLen) {
            if (raw == null || raw.isEmpty()) {
                return new ArrayList<>(0);
            }
            LinkedHashSet<String> seen = new LinkedHashSet<>(raw.size());
            for (String entry : raw) {
                String c = canonicalOne(entry, maxLen);
                if (c != null) seen.add(c);
            }
            return new ArrayList<>(seen);
        }

        static String canonicalOne(String raw, int maxLen) {
            if (raw == null) return null;
            String s = Normalizer.normalize(raw, Normalizer.Form.NFKC);
            s = s.replace('‌', ' ').replace('‍', ' '); // zero-width joiners → space
            s = s.trim().replaceAll("\\s+", " ");
            if (s.isEmpty() || s.length() > maxLen) return null;
            return s.toLowerCase(Locale.ROOT);
        }
    }
}
