package ak.dev.khi_archive_platform.platform.service.common;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Canonicalises a string for case- and script-insensitive comparison, so
 * filters over Sorani Kurdish (Arabic-script) text stop silently missing values
 * that look identical but differ by codepoint or invisible characters.
 *
 * <p>Arabic script has no upper/lower case, so {@code String.toLowerCase()} /
 * SQL {@code LOWER()} are no-ops for it — a "case-insensitive" equals then
 * degrades into a byte-for-byte match, and a trailing space, a Zero-Width
 * Non-Joiner, or an Arabic Yeh vs a Kurdish Yeh makes visually-identical text
 * compare unequal. This is applied to <b>both sides</b> of every string
 * equals/contains comparison in the {@code *FilterSupport} engines; it is never
 * persisted.
 *
 * <p>Steps, in order:
 * <ol>
 *   <li>Unicode NFC (compose canonically).</li>
 *   <li>Fold interchangeable letters to one form:
 *       Arabic Yeh {@code U+064A} / Alef-Maksura {@code U+0649} to Kurdish Yeh
 *       {@code U+06CC}; Arabic Kaf {@code U+0643} to Keheh {@code U+06A9}.</li>
 *   <li>Drop joiners / formatting: tatweel {@code U+0640}, ZWNJ {@code U+200C},
 *       ZWJ {@code U+200D}, ZWSP {@code U+200B}, BOM {@code U+FEFF}, the Arabic
 *       tashkeel (harakat) {@code U+064B..U+0652}, and superscript alef
 *       {@code U+0670}.</li>
 *   <li>Collapse every whitespace run to a single space and trim.</li>
 *   <li>Lower-case ({@code Locale.ROOT}) for any Latin characters mixed in.</li>
 * </ol>
 *
 * <p><b>Scope:</b> this fixes codepoint/whitespace variants, NOT spelling drift
 * — two words that differ by a real letter stay distinct. For that, drive the
 * filter from a controlled vocabulary / distinct-values dropdown (e.g.
 * {@code GET /api/maqam/maqam-types}).
 */
public final class KurdishText {

    private KurdishText() {}

    // Interchangeable letters folded to a single canonical form.
    private static final char ARABIC_YEH       = 0x064A;
    private static final char ALEF_MAKSURA     = 0x0649;
    private static final char KURDISH_YEH      = 0x06CC;
    private static final char ARABIC_KAF       = 0x0643;
    private static final char KEHEH            = 0x06A9;
    // Characters dropped entirely.
    private static final char TATWEEL          = 0x0640;
    private static final char ZWSP             = 0x200B;
    private static final char ZWNJ             = 0x200C;
    private static final char ZWJ              = 0x200D;
    private static final char BOM              = 0xFEFF;
    private static final char SUPERSCRIPT_ALEF = 0x0670;
    private static final char TASHKEEL_START   = 0x064B;
    private static final char TASHKEEL_END     = 0x0652;

    /**
     * Canonical form used for matching. {@code null} in → {@code null} out;
     * blank/whitespace-only in → empty string.
     */
    public static String normalize(String s) {
        if (s == null) return null;
        if (s.isEmpty()) return s;

        String nfc = Normalizer.isNormalized(s, Normalizer.Form.NFC)
                ? s
                : Normalizer.normalize(s, Normalizer.Form.NFC);

        StringBuilder sb = new StringBuilder(nfc.length());
        boolean pendingSpace = false;
        boolean seenNonSpace = false;
        for (int i = 0; i < nfc.length(); i++) {
            char c = nfc.charAt(i);

            if (Character.isWhitespace(c) || Character.getType(c) == Character.SPACE_SEPARATOR) {
                if (seenNonSpace) pendingSpace = true;
                continue;
            }

            char mapped = fold(c);
            if (mapped == '\0') continue; // dropped (joiner / diacritic / tatweel)

            if (pendingSpace) {
                sb.append(' ');
                pendingSpace = false;
            }
            sb.append(mapped);
            seenNonSpace = true;
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    /** Folds one char to its canonical form; returns {@code '\0'} to drop it. */
    private static char fold(char c) {
        if (c == ARABIC_YEH || c == ALEF_MAKSURA) return KURDISH_YEH;
        if (c == ARABIC_KAF) return KEHEH;
        if (c == TATWEEL || c == ZWSP || c == ZWNJ || c == ZWJ || c == BOM || c == SUPERSCRIPT_ALEF) return '\0';
        if (c >= TASHKEEL_START && c <= TASHKEEL_END) return '\0'; // Arabic harakat
        return c;
    }
}
