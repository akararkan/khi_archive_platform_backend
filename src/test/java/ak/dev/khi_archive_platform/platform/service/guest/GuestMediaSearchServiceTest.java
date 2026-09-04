package ak.dev.khi_archive_platform.platform.service.guest;

import ak.dev.khi_archive_platform.platform.dto.guest.GuestAudioDTO;
import ak.dev.khi_archive_platform.platform.dto.guest.GuestImageDTO;
import ak.dev.khi_archive_platform.platform.dto.guest.GuestMediaHitDTO;
import ak.dev.khi_archive_platform.platform.dto.guest.GuestMediaItemDTO;
import ak.dev.khi_archive_platform.platform.dto.guest.GuestMediaSearchDTO;
import ak.dev.khi_archive_platform.platform.dto.guest.GuestMediaSearchParams;
import ak.dev.khi_archive_platform.platform.dto.guest.GuestPersonSummaryDTO;
import ak.dev.khi_archive_platform.platform.dto.guest.GuestTextDTO;
import ak.dev.khi_archive_platform.platform.dto.guest.GuestVideoDTO;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuestMediaSearchServiceTest {

    private static final Instant OLD = Instant.parse("1975-05-01T00:00:00Z");
    private static final Instant NEW = Instant.parse("2015-05-01T00:00:00Z");

    // ─── Merging and counts ───────────────────────────────────────────────────

    @Test
    void mergesAllFourKindsAndCountsEachSeparately() {
        GuestMediaSearchService service = service(
                List.of(audio("AUD-1", "Hasan Zirak Live"), audio("AUD-2", "Another Concert")),
                List.of(video("VID-1", "Hasan Zirak On Stage")),
                List.of(image("IMG-1", "Portrait")),
                List.of(text("TXT-1", "Songbook")));

        GuestMediaSearchDTO result = service.search(params(p -> p.setQ("Hasan Zirak")), 0, 24);

        assertEquals("Hasan Zirak", result.getQuery());
        assertEquals("all", result.getType());
        assertEquals("relevance", result.getSort());
        assertEquals(List.of("audio", "video", "image", "text"), result.getOrder());

        assertEquals(2, result.getCounts().getAudio());
        assertEquals(1, result.getCounts().getVideo());
        assertEquals(1, result.getCounts().getImage());
        assertEquals(1, result.getCounts().getText());
        assertEquals(5, result.getCounts().getTotal());

        assertEquals(5, result.getTotalElements());
        assertEquals(5, result.getContent().size());
        assertTrue(result.isFirst());
        assertTrue(result.isLast());
        assertFalse(result.isHasNext());
        assertFalse(result.isTruncated());
    }

    @Test
    void typeFilterNarrowsResultsButNeverTheTabCounts() {
        GuestMediaSearchService service = service(
                List.of(audio("AUD-1", "Hasan Zirak Live")),
                List.of(video("VID-1", "Hasan Zirak On Stage")),
                List.of(image("IMG-1", "Hasan Zirak Portrait")),
                List.of(text("TXT-1", "Hasan Zirak Songbook")));

        GuestMediaSearchDTO result = service.search(
                params(p -> { p.setQ("Hasan Zirak"); p.setType(List.of("audio", "video")); }), 0, 24);

        assertEquals("audio,video", result.getType());
        assertEquals(2, result.getContent().size());
        assertTrue(result.getContent().stream().allMatch(h ->
                h.getType().equals("audio") || h.getType().equals("video")));

        // The tab bar still needs every number.
        assertEquals(1, result.getCounts().getImage());
        assertEquals(1, result.getCounts().getText());
        assertEquals(4, result.getCounts().getTotal());
    }

    @Test
    void publicAliasesSelectTheRightKinds() {
        GuestMediaSearchService service = service(
                List.of(audio("AUD-1", "Live")),
                List.of(),
                List.of(image("IMG-1", "Portrait")),
                List.of(text("TXT-1", "Songbook")));

        GuestMediaSearchDTO result = service.search(
                params(p -> p.setType(List.of("sounds,files"))), 0, 24);

        assertEquals("audio,text", result.getType());
        assertEquals(List.of("AUD-1", "TXT-1"),
                result.getContent().stream().map(GuestMediaHitDTO::getCode).sorted().toList());
    }

    // ─── Ranking ──────────────────────────────────────────────────────────────

    @Test
    void ranksTitleMatchesAboveIncidentalOnes() {
        GuestImageDTO incidental = image("IMG-1", "Untitled");
        incidental.setDescription("A crowd photo taken at a Hasan Zirak concert");

        GuestMediaSearchService service = service(
                List.of(),
                List.of(),
                List.of(incidental),
                List.of(text("TXT-1", "Hasan Zirak — Collected Songs")));

        GuestMediaSearchDTO result = service.search(params(p -> p.setQ("Hasan Zirak")), 0, 24);

        assertEquals(List.of("TXT-1", "IMG-1"),
                result.getContent().stream().map(GuestMediaHitDTO::getCode).toList());
        assertTrue(result.getContent().getFirst().getScore()
                > result.getContent().getLast().getScore());
        assertTrue(result.getContent().getFirst().getMatchedIn().contains("title"));
        assertEquals(List.of("description"), result.getContent().getLast().getMatchedIn());
    }

    @Test
    void aMatchOnTheOwningPersonOutranksAMatchOnFreeText() {
        GuestAudioDTO byPerson = audio("AUD-1", "Untitled Recording");
        byPerson.setPerson(GuestPersonSummaryDTO.builder()
                .personCode("PER-1").fullName("Hasan Zirak").build());

        GuestVideoDTO byDescription = video("VID-1", "Untitled Reel");
        byDescription.setDescription("footage mentioning hasan zirak once");

        GuestMediaSearchService service = service(
                List.of(byPerson), List.of(byDescription), List.of(), List.of());

        GuestMediaSearchDTO result = service.search(params(p -> p.setQ("Hasan Zirak")), 0, 24);

        assertEquals("AUD-1", result.getContent().getFirst().getCode());
        assertTrue(result.getContent().getFirst().getMatchedIn().contains("person"));
    }

    @Test
    void sortNewestIgnoresRelevanceAndIsTheDefaultWithoutAKeyword() {
        GuestAudioDTO older = audio("AUD-OLD", "Old Recording");
        older.setDateCreated(OLD);
        GuestAudioDTO newer = audio("AUD-NEW", "New Recording");
        newer.setDateCreated(NEW);

        GuestMediaSearchService service = service(
                List.of(older, newer), List.of(), List.of(), List.of());

        GuestMediaSearchDTO browse = service.search(params(p -> { }), 0, 24);
        assertEquals("newest", browse.getSort());
        assertEquals("", browse.getQuery());
        assertEquals(List.of("AUD-NEW", "AUD-OLD"),
                browse.getContent().stream().map(GuestMediaHitDTO::getCode).toList());

        GuestMediaSearchDTO oldest = service.search(params(p -> p.setSort("oldest")), 0, 24);
        assertEquals(List.of("AUD-OLD", "AUD-NEW"),
                oldest.getContent().stream().map(GuestMediaHitDTO::getCode).toList());
    }

    // ─── Shaping ──────────────────────────────────────────────────────────────

    @Test
    void fullPayloadIsAttachedOnlyWhenAskedFor() {
        GuestMediaSearchService service = service(
                List.of(audio("AUD-1", "Live")), List.of(), List.of(), List.of());

        GuestMediaHitDTO lean = service.search(params(p -> { }), 0, 24).getContent().getFirst();
        assertNull(lean.getAudio());
        assertEquals("/api/guest/audio/AUD-1/stream", lean.getMediaUrl());
        assertEquals("/api/guest/media/audio/AUD-1", lean.getDetailUrl());

        GuestMediaHitDTO full = service.search(params(p -> p.setInclude("full")), 0, 24)
                .getContent().getFirst();
        assertNotNull(full.getAudio());
        assertEquals("AUD-1", full.getAudio().getAudioCode());
        assertNull(full.getVideo());
    }

    @Test
    void groupByTypeSplitsTheSameResultsIntoPerKindSections() {
        GuestMediaSearchService service = service(
                List.of(audio("AUD-1", "Live"), audio("AUD-2", "Live Again")),
                List.of(video("VID-1", "Reel")),
                List.of(),
                List.of());

        GuestMediaSearchDTO result = service.search(params(p -> p.setGroupBy("type")), 0, 24);

        assertNotNull(result.getGroups());
        assertEquals("audio", result.getGroups().getAudio().getKind());
        assertEquals(2, result.getGroups().getAudio().getTotalElements());
        assertEquals(1, result.getGroups().getVideo().getTotalElements());
        assertEquals(0, result.getGroups().getImage().getTotalElements());
        assertTrue(result.getGroups().getText().isEmpty());
        // Sections carry the same lean cards as the merged list.
        assertNull(result.getGroups().getAudio().getContent().getFirst().getAudio());
    }

    @Test
    void facetsAreCountedOverTheMatchedSetAndOnlyWhenRequested() {
        GuestAudioDTO kurdish = audio("AUD-1", "Live");
        kurdish.setLanguage("Kurdish");
        kurdish.setTags(List.of("concert", "1975"));
        kurdish.setDateCreated(OLD);
        kurdish.setPerson(GuestPersonSummaryDTO.builder()
                .personCode("PER-1").fullName("Hasan Zirak").build());

        GuestAudioDTO alsoKurdish = audio("AUD-2", "Live Again");
        alsoKurdish.setLanguage("Kurdish");
        alsoKurdish.setTags(List.of("concert"));
        alsoKurdish.setDateCreated(NEW);

        GuestMediaSearchService service = service(
                List.of(kurdish, alsoKurdish), List.of(), List.of(), List.of());

        assertNull(service.search(params(p -> { }), 0, 24).getFacets());

        GuestMediaSearchDTO.Facets facets =
                service.search(params(p -> p.setFacets(true)), 0, 24).getFacets();

        assertEquals(1, facets.getLanguages().size());
        assertEquals("Kurdish", facets.getLanguages().getFirst().getLabel());
        assertEquals(2, facets.getLanguages().getFirst().getCount());
        assertEquals(List.of("concert", "1975"),
                facets.getTags().stream().map(b -> b.getLabel()).toList());
        assertEquals(List.of("1970s", "2010s"),
                facets.getDecades().stream().map(b -> b.getLabel()).toList());
        assertEquals("PER-1", facets.getPersons().getFirst().getCode());
        assertEquals("Hasan Zirak", facets.getPersons().getFirst().getLabel());
    }

    @Test
    void decadeFilterKeepsOnlyItemsFromThatDecade() {
        GuestAudioDTO older = audio("AUD-OLD", "Old");
        older.setDateCreated(OLD);
        GuestAudioDTO newer = audio("AUD-NEW", "New");
        newer.setDateCreated(NEW);

        GuestMediaSearchService service = service(
                List.of(older, newer), List.of(), List.of(), List.of());

        GuestMediaSearchDTO result = service.search(params(p -> p.setDecade("1970s")), 0, 24);

        assertEquals(1, result.getCounts().getAudio());
        assertEquals("AUD-OLD", result.getContent().getFirst().getCode());
    }

    @Test
    void pagesTheMergedList() {
        GuestMediaSearchService service = service(
                List.of(audio("AUD-1", "A"), audio("AUD-2", "B"), audio("AUD-3", "C")),
                List.of(), List.of(), List.of());

        GuestMediaSearchDTO first = service.search(params(p -> p.setSort("title")), 0, 2);
        assertEquals(2, first.getNumberOfElements());
        assertEquals(3, first.getTotalElements());
        assertEquals(2, first.getTotalPages());
        assertTrue(first.isHasNext());
        assertFalse(first.isHasPrevious());

        GuestMediaSearchDTO second = service.search(params(p -> p.setSort("title")), 1, 2);
        assertEquals(List.of("AUD-3"),
                second.getContent().stream().map(GuestMediaHitDTO::getCode).toList());
        assertTrue(second.isLast());
        assertTrue(second.isHasPrevious());
    }

    @Test
    void sizeIsClampedAndPagesPastTheEndComeBackEmptyNotOutOfBounds() {
        GuestMediaSearchService service = service(
                List.of(audio("AUD-1", "A")), List.of(), List.of(), List.of());

        assertEquals(GuestMediaSearchService.MAX_PAGE_SIZE,
                service.search(params(p -> { }), 0, 5_000).getSize());
        assertEquals(GuestMediaSearchService.DEFAULT_PAGE_SIZE,
                service.search(params(p -> { }), 0, null).getSize());

        GuestMediaSearchDTO past = service.search(params(p -> { }), 99, 24);
        assertTrue(past.getContent().isEmpty());
        assertEquals(1, past.getTotalElements());
    }

    // ─── Detail ───────────────────────────────────────────────────────────────

    @Test
    void detailReturnsTheKindSpecificPayloadPlusTheFlatCard() {
        GuestMediaSearchService service = service(List.of(), List.of(), List.of(), List.of());

        GuestMediaItemDTO item = service.getItem("sound", "AUD-1", false).orElseThrow();

        assertEquals("audio", item.getType());
        assertEquals("AUD-1", item.getCode());
        assertNotNull(item.getAudio());
        assertNull(item.getVideo());
        assertEquals("Hasan Zirak Live", item.getItem().getTitle());
        assertNull(item.getItem().getAudio());
        assertNull(item.getRelated());
    }

    @Test
    void detailIsEmptyForUnknownTypesAndUnknownCodes() {
        GuestMediaSearchService service = service(List.of(), List.of(), List.of(), List.of());

        assertTrue(service.getItem("project", "PRJ-1", false).isEmpty());
        assertTrue(service.getItem("audio", "AUD-MISSING", false).isEmpty());
        assertTrue(service.getItem(null, "AUD-1", false).isEmpty());
        assertTrue(service.getItem("audio", "  ", false).isEmpty());
    }

    // ─── Fixtures ─────────────────────────────────────────────────────────────

    private static GuestMediaSearchParams params(java.util.function.Consumer<GuestMediaSearchParams> tweak) {
        GuestMediaSearchParams p = new GuestMediaSearchParams();
        tweak.accept(p);
        return p;
    }

    private static GuestAudioDTO audio(String code, String title) {
        return GuestAudioDTO.builder()
                .audioCode(code)
                .originTitle(title)
                .audioFileUrl("/api/guest/audio/" + code + "/stream")
                .build();
    }

    private static GuestVideoDTO video(String code, String title) {
        return GuestVideoDTO.builder()
                .videoCode(code)
                .originalTitle(title)
                .videoFileUrl("/api/guest/video/" + code + "/stream")
                .build();
    }

    private static GuestImageDTO image(String code, String title) {
        return GuestImageDTO.builder()
                .imageCode(code)
                .originalTitle(title)
                .imageFileUrl("/api/guest/image/" + code + "/view")
                .build();
    }

    private static GuestTextDTO text(String code, String title) {
        return GuestTextDTO.builder()
                .textCode(code)
                .originalTitle(title)
                .textFileUrl("/api/guest/text/" + code + "/read")
                .build();
    }

    private static GuestMediaSearchService service(List<GuestAudioDTO> audios,
                                                   List<GuestVideoDTO> videos,
                                                   List<GuestImageDTO> images,
                                                   List<GuestTextDTO> texts) {
        return new GuestMediaSearchService(
                new StubSearchService(audios, videos, images, texts),
                new StubTrendingService());
    }

    /**
     * Returns fixed result pages for the four per-kind searches, so the merge,
     * ranking, paging and shaping can be asserted without a database.
     */
    private static final class StubSearchService extends GuestSearchService {

        private final List<GuestAudioDTO> audios;
        private final List<GuestVideoDTO> videos;
        private final List<GuestImageDTO> images;
        private final List<GuestTextDTO> texts;

        private StubSearchService(List<GuestAudioDTO> audios,
                                  List<GuestVideoDTO> videos,
                                  List<GuestImageDTO> images,
                                  List<GuestTextDTO> texts) {
            super(null, null, null, null, null, null, null, null);
            this.audios = audios;
            this.videos = videos;
            this.images = images;
            this.texts = texts;
        }

        private static <T> Page<T> page(List<T> rows) {
            return new PageImpl<>(rows, PageRequest.of(0, Math.max(1, rows.size())), rows.size());
        }

        @Override
        public Page<GuestAudioDTO> searchAudios(String q, String projectCode, String categoryCode,
                                                String personCode, String language, String dialect,
                                                String form, String typeOfBasta, String typeOfMaqam,
                                                String typeOfComposition, String typeOfPerformance,
                                                String composer, String producer, String speaker,
                                                String singer, String poet, List<String> contributors,
                                                String recordingVenue, String city, String region,
                                                String audience, String lyrics, List<String> subjects,
                                                List<String> genres, List<String> tags, List<String> keywords,
                                                Instant dateFrom, Instant dateTo, Instant publishedFrom,
                                                Instant publishedTo, String sortBy, String sortDirection,
                                                Pageable pageable) {
            return page(audios);
        }

        @Override
        public Page<GuestVideoDTO> searchVideos(String q, String projectCode, String categoryCode,
                                                String personCode, String language, String dialect,
                                                String region, String event, String location,
                                                String creatorArtistDirector, String producer,
                                                String contributor, String personShownInVideo,
                                                String subtitle, String audience, String provenance,
                                                String videoStatus, String publisher, List<String> subjects,
                                                List<String> genres, List<String> colors, List<String> whereUsed,
                                                List<String> tags, List<String> keywords, Instant dateFrom,
                                                Instant dateTo, Instant publishedFrom, Instant publishedTo,
                                                String sortBy, String sortDirection, Pageable pageable) {
            return page(videos);
        }

        @Override
        public Page<GuestImageDTO> searchImages(String q, String projectCode, String categoryCode,
                                                String personCode, String language, String dialect,
                                                String region, String event, String location,
                                                String creatorArtistPhotographer, String contributor,
                                                String personShownInImage, String audience, String provenance,
                                                String photostory, String imageStatus, List<String> subjects,
                                                List<String> genres, List<String> colors, List<String> whereUsed,
                                                List<String> tags, List<String> keywords, Instant dateFrom,
                                                Instant dateTo, Instant publishedFrom, Instant publishedTo,
                                                String sortBy, String sortDirection, Pageable pageable) {
            return page(images);
        }

        @Override
        public Page<GuestTextDTO> searchTexts(String q, String projectCode, String categoryCode,
                                              String personCode, String language, String dialect,
                                              String region, String documentType, String isbn,
                                              String author, String contributors, String script,
                                              String series, String edition, String volume,
                                              String printingHouse, String audience, String provenance,
                                              String publisher, List<String> subjects, List<String> genres,
                                              List<String> tags, List<String> keywords, Instant dateFrom,
                                              Instant dateTo, Instant publishedFrom, Instant publishedTo,
                                              Instant printDateFrom, Instant printDateTo, String sortBy,
                                              String sortDirection, Pageable pageable) {
            return page(texts);
        }

        @Override
        public Optional<GuestAudioDTO> getAudioByCode(String audioCode) {
            return "AUD-1".equals(audioCode)
                    ? Optional.of(audio("AUD-1", "Hasan Zirak Live"))
                    : Optional.empty();
        }
    }

    /** Swallows the trending writes — they are fire-and-forget in production too. */
    private static final class StubTrendingService extends GuestTrendingService {
        private StubTrendingService() {
            super(null, null, null, null, null, null, null, null);
        }

        @Override
        public void logSearch(String query) {
            // no-op
        }

        @Override
        public void logView(String entityType, String entityCode) {
            // no-op
        }
    }
}
