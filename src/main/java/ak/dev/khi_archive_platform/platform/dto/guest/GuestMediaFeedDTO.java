package ak.dev.khi_archive_platform.platform.dto.guest;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * Grouped public media feed for the guest browse page.
 *
 * <p>The response keeps each media kind in its own section so the frontend can
 * render photos, sounds, videos, and texts without one large kind hiding the
 * others on page 0.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GuestMediaFeedDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Fixed display order: image, audio, video, text. */
    private List<String> order;

    private Section<GuestImageDTO> images;
    private Section<GuestAudioDTO> audios;
    private Section<GuestVideoDTO> videos;
    private Section<GuestTextDTO> texts;

    /** Sum of all selected section totals. */
    private long totalElements;

    /** Shared page request applied independently to each selected section. */
    private int page;
    private int size;

    /** True when at least one selected section has another page. */
    private boolean hasNext;
    private boolean hasPrevious;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Section<T> implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        private String kind;
        private List<T> content;
        private int page;
        private int size;
        private long totalElements;
        private int totalPages;
        private int numberOfElements;
        private boolean first;
        private boolean last;
        private boolean empty;
    }
}
