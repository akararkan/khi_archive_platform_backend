package ak.dev.khi_archive_platform.platform.service.guest;

import ak.dev.khi_archive_platform.platform.dto.guest.GuestAudioDTO;
import ak.dev.khi_archive_platform.platform.dto.guest.GuestCategorySummaryDTO;
import ak.dev.khi_archive_platform.platform.dto.guest.GuestImageDTO;
import ak.dev.khi_archive_platform.platform.dto.guest.GuestMediaHitDTO;
import ak.dev.khi_archive_platform.platform.dto.guest.GuestPersonSummaryDTO;
import ak.dev.khi_archive_platform.platform.dto.guest.GuestTextDTO;
import ak.dev.khi_archive_platform.platform.dto.guest.GuestVideoDTO;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns "how well does this item answer the query" into one comparable number.
 *
 * <p>The per-kind SQL already ranks each kind's own rows, but those orderings
 * are four separate scales — a top audio and a top image cannot be interleaved
 * from them. This scorer re-ranks the surviving hits on a single scale, in
 * Java, so a mixed result list is ordered by how well each item actually
 * matches rather than by which kind it happens to be.
 *
 * <p><b>How the number is built.</b> The query is split into tokens. Each
 * token scores against every field group (title, creator, person, tags, …) as
 * <em>group weight × match strength</em>, where the strength is 3 for an exact
 * field, 2 for a field that starts with the token, 1.6 for a word inside the
 * field that starts with it, and 1 for any other substring. A token keeps only
 * its best group. The token scores are averaged, then three bonuses apply:
 * every token matching somewhere, the whole phrase appearing verbatim, and a
 * small nudge for items that are currently trending. Ties fall back to the
 * SQL ordering the row arrived in — see
 * {@link GuestMediaSearchService}.
 *
 * <p>The groups that contributed are reported back as
 * {@link GuestMediaHitDTO#getMatchedIn()} so the website can explain a hit
 * ("matched in title, singer") instead of showing an unexplained result.
 */
final class GuestMediaRelevanceScorer {

    private GuestMediaRelevanceScorer() {}

    // ── Field-group weights ──────────────────────────────────────────────────
    private static final double W_TITLE       = 10.0;
    private static final double W_CODE        = 9.0;
    private static final double W_CREATOR     = 8.0;
    private static final double W_PERSON      = 8.0;
    private static final double W_PROJECT     = 5.0;
    private static final double W_TAGS        = 4.0;
    private static final double W_KEYWORDS    = 4.0;
    private static final double W_CATEGORY    = 4.0;
    private static final double W_SUBJECT     = 3.0;
    private static final double W_GENRE       = 3.0;
    private static final double W_PLACE       = 2.0;
    private static final double W_DESCRIPTION = 1.0;

    // ── Bonuses ──────────────────────────────────────────────────────────────
    /** Every token in the query matched somewhere on this item. */
    private static final double BONUS_ALL_TOKENS = 6.0;
    /** The multi-word query appears verbatim in a title. */
    private static final double BONUS_PHRASE_TITLE = 8.0;
    /** The multi-word query appears verbatim in some other field. */
    private static final double BONUS_PHRASE_OTHER = 2.0;
    /** Trending items in the top {@value #TRENDING_BONUS_DEPTH} get a small lift. */
    private static final int TRENDING_BONUS_DEPTH = 20;

    /** How many field groups {@code matchedIn} reports, strongest first. */
    private static final int MATCHED_IN_LIMIT = 5;

    /** The result of scoring one hit. */
    record Scored(double score, List<String> matchedIn) {}

    /** Splits a query the same way the SQL layer does: whitespace, letters/digits required. */
    static List<String> tokenize(String query) {
        if (query == null) return List.of();
        String trimmed = query.trim();
        if (trimmed.isEmpty()) return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String raw : trimmed.split("\\s+")) {
            String t = raw.trim().toLowerCase(Locale.ROOT);
            if (t.isEmpty()) continue;
            if (!t.matches(".*[\\p{L}\\p{N}].*")) continue;
            out.add(t);
        }
        return new ArrayList<>(out);
    }

    /**
     * Scores one hit against a tokenized query.
     *
     * @param hit    the flattened card, with its full kind-specific DTO still attached
     * @param tokens the tokenized query — an empty list yields a zero score
     * @param phrase the whole trimmed query, lowercased, for the verbatim bonus
     */
    static Scored score(GuestMediaHitDTO hit, List<String> tokens, String phrase) {
        if (hit == null || tokens.isEmpty()) return new Scored(0.0, List.of());

        Map<String, List<String>> groups = fieldGroups(hit);
        Map<String, Double> contribution = new LinkedHashMap<>();

        double total = 0.0;
        int matchedTokens = 0;

        for (String token : tokens) {
            double best = 0.0;
            String bestGroup = null;
            for (Map.Entry<String, List<String>> entry : groups.entrySet()) {
                double weight = weightOf(entry.getKey());
                double strongest = 0.0;
                for (String value : entry.getValue()) {
                    strongest = Math.max(strongest, strength(value, token));
                    if (strongest >= 3.0) break;
                }
                if (strongest <= 0.0) continue;
                double weighted = weight * strongest;
                contribution.merge(entry.getKey(), weighted, Double::sum);
                if (weighted > best) {
                    best = weighted;
                    bestGroup = entry.getKey();
                }
            }
            if (bestGroup != null) matchedTokens++;
            total += best;
        }

        double score = total / tokens.size();
        if (matchedTokens == tokens.size()) score += BONUS_ALL_TOKENS;

        if (phrase != null && phrase.indexOf(' ') >= 0) {
            if (containsAny(groups.get("title"), phrase)) {
                score += BONUS_PHRASE_TITLE;
            } else {
                for (Map.Entry<String, List<String>> entry : groups.entrySet()) {
                    if (containsAny(entry.getValue(), phrase)) {
                        score += BONUS_PHRASE_OTHER;
                        break;
                    }
                }
            }
        }

        Integer rank = hit.getTrendingRank();
        if (hit.isTrending() && rank != null && rank >= 1 && rank <= TRENDING_BONUS_DEPTH) {
            score += (TRENDING_BONUS_DEPTH + 1 - rank) * 0.1;
        }

        List<String> matchedIn = contribution.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, Double>>comparingDouble(e -> -e.getValue())
                        .thenComparing(Map.Entry::getKey))
                .limit(MATCHED_IN_LIMIT)
                .map(Map.Entry::getKey)
                .toList();

        return new Scored(round(score), matchedIn);
    }

    // ─── Field groups ─────────────────────────────────────────────────────────

    /**
     * Collects the searchable text of one hit into named, lowercased groups.
     * Values come from the card plus the attached kind-specific DTO, which
     * carries the extras the card drops (lyrics, transcription, event, …).
     */
    private static Map<String, List<String>> fieldGroups(GuestMediaHitDTO hit) {
        Map<String, List<String>> groups = new LinkedHashMap<>();

        put(groups, "title", hit.getTitle(), hit.getSubtitle(),
                hit.getTitleInCentralKurdish(), hit.getRomanizedTitle());
        put(groups, "code", hit.getCode());
        put(groups, "creator", hit.getCreator());

        GuestPersonSummaryDTO person = hit.getPerson();
        if (person != null) {
            put(groups, "person", person.getFullName(), person.getNickname(),
                    person.getRomanizedName(), person.getPersonCode());
        }

        put(groups, "project", hit.getProjectName(), hit.getProjectCode());

        List<GuestCategorySummaryDTO> categories = hit.getCategories();
        if (categories != null && !categories.isEmpty()) {
            List<String> names = new ArrayList<>(categories.size() * 2);
            for (GuestCategorySummaryDTO c : categories) {
                if (c == null) continue;
                names.add(c.getName());
                names.add(c.getCategoryCode());
            }
            putAll(groups, "category", names);
        }

        putAll(groups, "tags", hit.getTags());
        putAll(groups, "keywords", hit.getKeywords());
        putAll(groups, "subject", hit.getSubject());
        putAll(groups, "genre", hit.getGenre());
        put(groups, "place", hit.getRegion(), hit.getLanguage(), hit.getDialect());
        put(groups, "description", hit.getDescription(), hit.getDocumentType());

        addKindExtras(groups, hit);
        return groups;
    }

    /**
     * Adds the searchable fields that only exist on one kind. These land in
     * existing groups rather than kind-specific ones so {@code matchedIn}
     * stays a small, stable vocabulary the frontend can label.
     */
    private static void addKindExtras(Map<String, List<String>> groups, GuestMediaHitDTO hit) {
        GuestAudioDTO a = hit.getAudio();
        if (a != null) {
            append(groups, "creator", a.getSinger(), a.getSpeaker(), a.getPoet(),
                    a.getComposer(), a.getProducer());
            appendAll(groups, "creator", a.getContributors());
            append(groups, "place", a.getCity(), a.getRecordingVenue());
            append(groups, "description", a.getDescription(), a.getAbstractText(),
                    a.getLyrics(), a.getForm(), a.getTypeOfMaqam(), a.getTypeOfBasta());
        }
        GuestVideoDTO v = hit.getVideo();
        if (v != null) {
            append(groups, "creator", v.getCreatorArtistDirector(), v.getProducer(),
                    v.getContributor(), v.getPersonShownInVideo());
            append(groups, "place", v.getLocation(), v.getEvent());
            append(groups, "description", v.getDescription(), v.getSubtitle());
        }
        GuestImageDTO i = hit.getImage();
        if (i != null) {
            append(groups, "creator", i.getCreatorArtistPhotographer(), i.getContributor(),
                    i.getPersonShownInImage());
            append(groups, "place", i.getLocation(), i.getEvent());
            append(groups, "description", i.getDescription(), i.getPhotostory(), i.getForm());
        }
        GuestTextDTO t = hit.getText();
        if (t != null) {
            append(groups, "creator", t.getAuthor(), t.getContributors(), t.getPrintingHouse());
            append(groups, "description", t.getDescription(), t.getTranscription(),
                    t.getScript(), t.getSeries(), t.getEdition(), t.getVolume(), t.getIsbn());
        }
    }

    private static double weightOf(String group) {
        return switch (group) {
            case "title"       -> W_TITLE;
            case "code"        -> W_CODE;
            case "creator"     -> W_CREATOR;
            case "person"      -> W_PERSON;
            case "project"     -> W_PROJECT;
            case "tags"        -> W_TAGS;
            case "keywords"    -> W_KEYWORDS;
            case "category"    -> W_CATEGORY;
            case "subject"     -> W_SUBJECT;
            case "genre"       -> W_GENRE;
            case "place"       -> W_PLACE;
            default            -> W_DESCRIPTION;
        };
    }

    /**
     * How strongly {@code value} answers {@code token}: exact field beats a
     * field that starts with it, which beats a word inside that starts with
     * it, which beats a bare substring.
     */
    private static double strength(String value, String token) {
        if (value == null || value.isEmpty()) return 0.0;
        if (value.equals(token)) return 3.0;
        if (value.startsWith(token)) return 2.0;
        int idx = value.indexOf(token);
        if (idx < 0) return 0.0;
        while (idx > 0) {
            if (!Character.isLetterOrDigit(value.charAt(idx - 1))) return 1.6;
            idx = value.indexOf(token, idx + 1);
            if (idx < 0) break;
        }
        return 1.0;
    }

    private static boolean containsAny(List<String> values, String phrase) {
        if (values == null) return false;
        for (String v : values) {
            if (v != null && v.contains(phrase)) return true;
        }
        return false;
    }

    // ─── Group building helpers (all values arrive lowercased and trimmed) ───

    private static void put(Map<String, List<String>> groups, String group, String... values) {
        putAll(groups, group, Arrays.asList(values));
    }

    private static void putAll(Map<String, List<String>> groups, String group, List<String> values) {
        List<String> cleaned = clean(values);
        if (!cleaned.isEmpty()) groups.put(group, cleaned);
    }

    private static void append(Map<String, List<String>> groups, String group, String... values) {
        appendAll(groups, group, Arrays.asList(values));
    }

    private static void appendAll(Map<String, List<String>> groups, String group, List<String> values) {
        List<String> cleaned = clean(values);
        if (cleaned.isEmpty()) return;
        groups.computeIfAbsent(group, k -> new ArrayList<>()).addAll(cleaned);
    }

    private static List<String> clean(List<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        List<String> out = new ArrayList<>(values.size());
        for (String v : values) {
            if (v == null) continue;
            String t = v.trim();
            if (t.isEmpty()) continue;
            out.add(t.toLowerCase(Locale.ROOT));
        }
        return out;
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
