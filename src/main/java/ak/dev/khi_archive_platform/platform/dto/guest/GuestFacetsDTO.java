package ak.dev.khi_archive_platform.platform.dto.guest;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * Sidebar facets — the counts that drive KCAC's left-hand filter checkboxes.
 * One bucket per facet, each ordered by count desc then label asc.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GuestFacetsDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private MediaTypeBucket mediaTypes;
    private List<Bucket> categories;
    private List<Bucket> persons;
    private List<Bucket> languages;
    private List<Bucket> dialects;
    private List<Bucket> regions;
    private List<Bucket> genres;
    private List<Bucket> tags;
    private List<Bucket> keywords;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MediaTypeBucket implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private long audios;
        private long videos;
        private long texts;
        private long images;
        private long projects;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Bucket implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        /** Stable code for filter binding (e.g. category code or person code). Optional. */
        private String code;
        private String label;
        private long count;
    }
}
