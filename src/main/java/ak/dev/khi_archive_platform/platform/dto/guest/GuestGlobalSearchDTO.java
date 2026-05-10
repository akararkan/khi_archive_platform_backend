package ak.dev.khi_archive_platform.platform.dto.guest;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * Universal /api/guest/search response — KCAC-style "everything at once"
 * payload. Each section carries the top-N matches plus the total count for
 * pagination links.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GuestGlobalSearchDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String query;

    private Section<GuestProjectDTO> projects;
    private Section<GuestCategoryDTO> categories;
    private Section<GuestPersonDTO> persons;
    private Section<GuestAudioDTO> audios;
    private Section<GuestVideoDTO> videos;
    private Section<GuestTextDTO> texts;
    private Section<GuestImageDTO> images;

    /** Total hits across every section — for the result-count headline. */
    public long total() {
        long t = 0;
        if (projects != null) t += projects.total;
        if (categories != null) t += categories.total;
        if (persons != null) t += persons.total;
        if (audios != null) t += audios.total;
        if (videos != null) t += videos.total;
        if (texts != null) t += texts.total;
        if (images != null) t += images.total;
        return t;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Section<T> implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private long total;
        private List<T> items;
    }
}
