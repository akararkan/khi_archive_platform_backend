package ak.dev.khi_archive_platform.platform.service.guest;

import ak.dev.khi_archive_platform.platform.dto.guest.GuestAudioDTO;
import ak.dev.khi_archive_platform.platform.dto.guest.GuestImageDTO;
import ak.dev.khi_archive_platform.platform.dto.guest.GuestMediaFeedDTO;
import ak.dev.khi_archive_platform.platform.dto.guest.GuestTextDTO;
import ak.dev.khi_archive_platform.platform.dto.guest.GuestVideoDTO;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GuestSearchServiceFeedTest {

    @Test
    void feedIncludesAllFourMediaKindsByDefault() {
        PageRequest pageable = PageRequest.of(0, 10);

        Page<GuestImageDTO> images = new PageImpl<>(
                List.of(GuestImageDTO.builder().imageCode("IMG-1").build()),
                pageable,
                1
        );
        Page<GuestAudioDTO> audios = new PageImpl<>(
                List.of(GuestAudioDTO.builder().audioCode("AUD-1").build()),
                pageable,
                1
        );
        Page<GuestVideoDTO> videos = new PageImpl<>(
                List.of(GuestVideoDTO.builder().videoCode("VID-1").build()),
                pageable,
                1
        );
        Page<GuestTextDTO> texts = new PageImpl<>(
                List.of(GuestTextDTO.builder().textCode("TXT-1").build()),
                pageable,
                1
        );

        GuestSearchService service = new TestGuestSearchService(
                images, audios, videos, texts
        );

        GuestMediaFeedDTO feed = service.feedAll(
                null, null, null, null, null,
                null, null, null,
                null, null, null, null,
                null, null,
                null, null,
                pageable
        );

        assertNotNull(feed);
        assertEquals(List.of("image", "audio", "video", "text"), feed.getOrder());
        assertEquals(4L, feed.getTotalElements());
        assertEquals(0, feed.getPage());
        assertEquals(10, feed.getSize());
        assertEquals("image", feed.getImages().getKind());
        assertEquals("audio", feed.getAudios().getKind());
        assertEquals("video", feed.getVideos().getKind());
        assertEquals("text", feed.getTexts().getKind());
        assertEquals("IMG-1", feed.getImages().getContent().getFirst().getImageCode());
        assertEquals("AUD-1", feed.getAudios().getContent().getFirst().getAudioCode());
        assertEquals("VID-1", feed.getVideos().getContent().getFirst().getVideoCode());
        assertEquals("TXT-1", feed.getTexts().getContent().getFirst().getTextCode());
    }

    private static final class TestGuestSearchService extends GuestSearchService {

        private final Page<GuestImageDTO> images;
        private final Page<GuestAudioDTO> audios;
        private final Page<GuestVideoDTO> videos;
        private final Page<GuestTextDTO> texts;

        private TestGuestSearchService(Page<GuestImageDTO> images,
                                       Page<GuestAudioDTO> audios,
                                       Page<GuestVideoDTO> videos,
                                       Page<GuestTextDTO> texts) {
            super(null, null, null, null, null, null, null, null);
            this.images = images;
            this.audios = audios;
            this.videos = videos;
            this.texts = texts;
        }

        @Override
        public Page<GuestImageDTO> searchImages(String q,
                                                String projectCode,
                                                String categoryCode,
                                                String personCode,
                                                String language,
                                                String dialect,
                                                String region,
                                                String event,
                                                String location,
                                                String creatorArtistPhotographer,
                                                String contributor,
                                                String personShownInImage,
                                                String audience,
                                                String provenance,
                                                String photostory,
                                                String imageStatus,
                                                List<String> subjects,
                                                List<String> genres,
                                                List<String> colors,
                                                List<String> whereUsed,
                                                List<String> tags,
                                                List<String> keywords,
                                                Instant dateFrom,
                                                Instant dateTo,
                                                Instant publishedFrom,
                                                Instant publishedTo,
                                                String sortBy,
                                                String sortDirection,
                                                org.springframework.data.domain.Pageable pageable) {
            return images;
        }

        @Override
        public Page<GuestAudioDTO> searchAudios(String q,
                                                String projectCode,
                                                String categoryCode,
                                                String personCode,
                                                String language,
                                                String dialect,
                                                String form,
                                                String typeOfBasta,
                                                String typeOfMaqam,
                                                String typeOfComposition,
                                                String typeOfPerformance,
                                                String composer,
                                                String producer,
                                                String speaker,
                                                String singer,
                                                String poet,
                                                List<String> contributors,
                                                String recordingVenue,
                                                String city,
                                                String region,
                                                String audience,
                                                String lyrics,
                                                List<String> subjects,
                                                List<String> genres,
                                                List<String> tags,
                                                List<String> keywords,
                                                Instant dateFrom,
                                                Instant dateTo,
                                                Instant publishedFrom,
                                                Instant publishedTo,
                                                String sortBy,
                                                String sortDirection,
                                                org.springframework.data.domain.Pageable pageable) {
            return audios;
        }

        @Override
        public Page<GuestVideoDTO> searchVideos(String q,
                                                String projectCode,
                                                String categoryCode,
                                                String personCode,
                                                String language,
                                                String dialect,
                                                String region,
                                                String event,
                                                String location,
                                                String creatorArtistDirector,
                                                String producer,
                                                String contributor,
                                                String personShownInVideo,
                                                String subtitle,
                                                String audience,
                                                String provenance,
                                                String videoStatus,
                                                String publisher,
                                                List<String> subjects,
                                                List<String> genres,
                                                List<String> colors,
                                                List<String> whereUsed,
                                                List<String> tags,
                                                List<String> keywords,
                                                Instant dateFrom,
                                                Instant dateTo,
                                                Instant publishedFrom,
                                                Instant publishedTo,
                                                String sortBy,
                                                String sortDirection,
                                                org.springframework.data.domain.Pageable pageable) {
            return videos;
        }

        @Override
        public Page<GuestTextDTO> searchTexts(String q,
                                              String projectCode,
                                              String categoryCode,
                                              String personCode,
                                              String language,
                                              String dialect,
                                              String region,
                                              String documentType,
                                              String isbn,
                                              String author,
                                              String contributors,
                                              String script,
                                              String series,
                                              String edition,
                                              String volume,
                                              String printingHouse,
                                              String audience,
                                              String provenance,
                                              String publisher,
                                              List<String> subjects,
                                              List<String> genres,
                                              List<String> tags,
                                              List<String> keywords,
                                              Instant dateFrom,
                                              Instant dateTo,
                                              Instant publishedFrom,
                                              Instant publishedTo,
                                              Instant printDateFrom,
                                              Instant printDateTo,
                                              String sortBy,
                                              String sortDirection,
                                              org.springframework.data.domain.Pageable pageable) {
            return texts;
        }
    }
}
