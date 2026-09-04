package ak.dev.khi_archive_platform.platform.service.guest;

import ak.dev.khi_archive_platform.platform.dto.guest.GuestAudioDTO;
import ak.dev.khi_archive_platform.platform.dto.guest.GuestImageDTO;
import ak.dev.khi_archive_platform.platform.dto.guest.GuestMediaHitDTO;
import ak.dev.khi_archive_platform.platform.dto.guest.GuestTextDTO;
import ak.dev.khi_archive_platform.platform.dto.guest.GuestVideoDTO;

import java.util.List;

/**
 * Flattens the four kind-specific guest DTOs onto the one
 * {@link GuestMediaHitDTO} card shape used by the website search.
 *
 * <p>Nothing is invented here and nothing new is exposed: every value is
 * copied straight from the {@code Guest…DTO} that the public API already
 * returns, so the flattening can never leak a field the per-kind endpoints
 * keep private. The full kind-specific DTO is always attached — the caller
 * decides whether to keep it (see
 * {@link GuestMediaSearchService#stripFullPayload}).
 */
final class GuestMediaHitMapper {

    private GuestMediaHitMapper() {}

    /** Descriptions longer than this are cut on a word boundary and suffixed with "…". */
    static final int DESCRIPTION_LIMIT = 320;

    static final String TYPE_AUDIO = "audio";
    static final String TYPE_VIDEO = "video";
    static final String TYPE_IMAGE = "image";
    static final String TYPE_TEXT  = "text";

    /** Public display order of the kinds. */
    static final List<String> TYPE_ORDER = List.of(TYPE_AUDIO, TYPE_VIDEO, TYPE_IMAGE, TYPE_TEXT);

    // ─── Audio ────────────────────────────────────────────────────────────────

    static GuestMediaHitDTO toHit(GuestAudioDTO a) {
        if (a == null) return null;
        String[] titles = titles(a.getOriginTitle(), a.getCentralKurdishTitle(),
                a.getRomanizedTitle(), a.getAlterTitle(), a.getAudioCode());
        String[] creator = creator(
                "singer", a.getSinger(),
                "speaker", a.getSpeaker(),
                "poet", a.getPoet(),
                "composer", a.getComposer(),
                "producer", a.getProducer());

        return GuestMediaHitDTO.builder()
                .type(TYPE_AUDIO)
                .code(a.getAudioCode())
                .id(a.getId())
                .title(titles[0])
                .subtitle(titles[1])
                .titleInCentralKurdish(a.getCentralKurdishTitle())
                .romanizedTitle(a.getRomanizedTitle())
                .description(shorten(firstNonBlank(a.getDescription(), a.getAbstractText())))
                .creator(creator[1])
                .creatorRole(creator[0])
                .projectCode(a.getProjectCode())
                .projectName(a.getProjectName())
                .person(a.getPerson())
                .categories(a.getCategories())
                .language(a.getLanguage())
                .dialect(a.getDialect())
                .region(a.getRegion())
                .subject(a.getSubject())
                .genre(a.getGenre())
                .tags(a.getTags())
                .keywords(a.getKeywords())
                .duration(a.getDuration())
                .dateCreated(a.getDateCreated())
                .datePublished(a.getDatePublished())
                .mediaUrl(a.getAudioFileUrl())
                .thumbnailUrl(blankToNull(a.getPersonMediaPortrait()))
                .detailUrl(detailUrl(TYPE_AUDIO, a.getAudioCode()))
                .isTrending(a.isTrending())
                .trendingRank(a.getTrendingRank())
                .trendingScore(a.getTrendingScore())
                .matchedIn(List.of())
                .audio(a)
                .build();
    }

    // ─── Video ────────────────────────────────────────────────────────────────

    static GuestMediaHitDTO toHit(GuestVideoDTO v) {
        if (v == null) return null;
        String[] titles = titles(v.getOriginalTitle(), v.getTitleInCentralKurdish(),
                v.getRomanizedTitle(), v.getAlternativeTitle(), v.getVideoCode());
        String[] creator = creator(
                "creatorArtistDirector", v.getCreatorArtistDirector(),
                "producer", v.getProducer(),
                "contributor", v.getContributor(),
                "personShownInVideo", v.getPersonShownInVideo(),
                "publisher", v.getPublisher());

        return GuestMediaHitDTO.builder()
                .type(TYPE_VIDEO)
                .code(v.getVideoCode())
                .id(v.getId())
                .title(titles[0])
                .subtitle(titles[1])
                .titleInCentralKurdish(v.getTitleInCentralKurdish())
                .romanizedTitle(v.getRomanizedTitle())
                .description(shorten(v.getDescription()))
                .creator(creator[1])
                .creatorRole(creator[0])
                .projectCode(v.getProjectCode())
                .projectName(v.getProjectName())
                .person(v.getPerson())
                .categories(v.getCategories())
                .language(v.getLanguage())
                .dialect(v.getDialect())
                .region(v.getRegion())
                .subject(v.getSubject())
                .genre(v.getGenre())
                .tags(v.getTags())
                .keywords(v.getKeywords())
                .duration(v.getDuration())
                .dateCreated(v.getDateCreated())
                .datePublished(v.getDatePublished())
                .mediaUrl(v.getVideoFileUrl())
                .thumbnailUrl(blankToNull(v.getPersonMediaPortrait()))
                .detailUrl(detailUrl(TYPE_VIDEO, v.getVideoCode()))
                .isTrending(v.isTrending())
                .trendingRank(v.getTrendingRank())
                .trendingScore(v.getTrendingScore())
                .matchedIn(List.of())
                .video(v)
                .build();
    }

    // ─── Image ────────────────────────────────────────────────────────────────

    static GuestMediaHitDTO toHit(GuestImageDTO i) {
        if (i == null) return null;
        String[] titles = titles(i.getOriginalTitle(), i.getTitleInCentralKurdish(),
                i.getRomanizedTitle(), i.getAlternativeTitle(), i.getImageCode());
        String[] creator = creator(
                "creatorArtistPhotographer", i.getCreatorArtistPhotographer(),
                "contributor", i.getContributor(),
                "personShownInImage", i.getPersonShownInImage(),
                "publisher", i.getPublisher(),
                null, null);

        return GuestMediaHitDTO.builder()
                .type(TYPE_IMAGE)
                .code(i.getImageCode())
                .id(i.getId())
                .title(titles[0])
                .subtitle(titles[1])
                .titleInCentralKurdish(i.getTitleInCentralKurdish())
                .romanizedTitle(i.getRomanizedTitle())
                .description(shorten(i.getDescription()))
                .creator(creator[1])
                .creatorRole(creator[0])
                .projectCode(i.getProjectCode())
                .projectName(i.getProjectName())
                .person(i.getPerson())
                .categories(i.getCategories())
                .language(i.getLanguage())
                .dialect(i.getDialect())
                .region(i.getRegion())
                .subject(i.getSubject())
                .genre(i.getGenre())
                .tags(i.getTags())
                .keywords(i.getKeywords())
                .dateCreated(i.getDateCreated())
                .datePublished(i.getDatePublished())
                .mediaUrl(i.getImageFileUrl())
                // An image is its own thumbnail.
                .thumbnailUrl(firstNonBlank(i.getImageFileUrl(), i.getPersonMediaPortrait()))
                .detailUrl(detailUrl(TYPE_IMAGE, i.getImageCode()))
                .isTrending(i.isTrending())
                .trendingRank(i.getTrendingRank())
                .trendingScore(i.getTrendingScore())
                .matchedIn(List.of())
                .image(i)
                .build();
    }

    // ─── Text ─────────────────────────────────────────────────────────────────

    static GuestMediaHitDTO toHit(GuestTextDTO t) {
        if (t == null) return null;
        String[] titles = titles(t.getOriginalTitle(), t.getTitleInCentralKurdish(),
                t.getRomanizedTitle(), t.getAlternativeTitle(), t.getTextCode());
        String[] creator = creator(
                "author", t.getAuthor(),
                "contributors", t.getContributors(),
                "publisher", t.getPublisher(),
                "printingHouse", t.getPrintingHouse(),
                null, null);

        return GuestMediaHitDTO.builder()
                .type(TYPE_TEXT)
                .code(t.getTextCode())
                .id(t.getId())
                .title(titles[0])
                .subtitle(titles[1])
                .titleInCentralKurdish(t.getTitleInCentralKurdish())
                .romanizedTitle(t.getRomanizedTitle())
                .description(shorten(t.getDescription()))
                .creator(creator[1])
                .creatorRole(creator[0])
                .projectCode(t.getProjectCode())
                .projectName(t.getProjectName())
                .person(t.getPerson())
                .categories(t.getCategories())
                .language(t.getLanguage())
                .dialect(t.getDialect())
                .region(t.getRegion())
                .subject(t.getSubject())
                .genre(t.getGenre())
                .tags(t.getTags())
                .keywords(t.getKeywords())
                .pageCount(t.getPageCount())
                .documentType(t.getDocumentType())
                .dateCreated(t.getDateCreated())
                .datePublished(t.getDatePublished())
                .mediaUrl(t.getTextFileUrl())
                .thumbnailUrl(firstNonBlank(t.getCoverImageUrl(), t.getPersonMediaPortrait()))
                .detailUrl(detailUrl(TYPE_TEXT, t.getTextCode()))
                .isTrending(t.isTrending())
                .trendingRank(t.getTrendingRank())
                .trendingScore(t.getTrendingScore())
                .matchedIn(List.of())
                .text(t)
                .build();
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    static String detailUrl(String type, String code) {
        return code == null ? null : "/api/guest/media/" + type + "/" + code;
    }

    /**
     * Picks the headline and the runner-up from the four title columns.
     *
     * @return a two-slot array: {@code [title, subtitle]}. {@code subtitle} is
     *         null when there is no distinct second title.
     */
    private static String[] titles(String original, String kurdish, String romanized,
                                   String alternative, String code) {
        String title = firstNonBlank(original, kurdish, romanized, alternative, code);
        String subtitle = null;
        for (String candidate : new String[]{original, kurdish, romanized, alternative}) {
            String c = blankToNull(candidate);
            if (c != null && !c.equalsIgnoreCase(title)) {
                subtitle = c;
                break;
            }
        }
        return new String[]{title, subtitle};
    }

    /**
     * Walks the kind's credit fields in priority order and returns the first
     * one that carries a value.
     *
     * @return a two-slot array: {@code [role, name]}, both null when the item
     *         credits nobody.
     */
    private static String[] creator(String role1, String name1,
                                    String role2, String name2,
                                    String role3, String name3,
                                    String role4, String name4,
                                    String role5, String name5) {
        String[][] pairs = {
                {role1, name1}, {role2, name2}, {role3, name3}, {role4, name4}, {role5, name5}
        };
        for (String[] pair : pairs) {
            String name = blankToNull(pair[1]);
            if (name != null) return new String[]{pair[0], name};
        }
        return new String[]{null, null};
    }

    /** Cuts long descriptions on a word boundary so cards stay uniform. */
    static String shorten(String s) {
        String v = blankToNull(s);
        if (v == null || v.length() <= DESCRIPTION_LIMIT) return v;
        int cut = v.lastIndexOf(' ', DESCRIPTION_LIMIT);
        if (cut < DESCRIPTION_LIMIT / 2) cut = DESCRIPTION_LIMIT;
        return v.substring(0, cut).stripTrailing() + "…";
    }

    static String firstNonBlank(String... candidates) {
        for (String c : candidates) {
            String v = blankToNull(c);
            if (v != null) return v;
        }
        return null;
    }

    static String blankToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
