package ak.dev.khi_archive_platform.platform.dto.guest;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.List;

/**
 * One row in the unified /api/guest/results feed. Carries enough context for
 * the frontend to render a card without a second fetch — {@code kind} picks
 * the template, the inline {@code audio|video|text|image} payload supplies
 * the type-specific fields, and the project/person/category headers drive
 * the breadcrumb and "why this matched" badge.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GuestUnifiedResultDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** {@code audio | video | text | image} — drives card template + deep-link route. */
    private String kind;

    /** Higher = better match. Title hit > person/project hit > tag/keyword hit. */
    private double score;

    /** Comma-separated reasons the row matched: {@code title, person, project, tag, keyword}. */
    private List<String> matchedOn;

    private String code;
    private String title;

    private String projectCode;
    private String projectName;

    private String personCode;
    private String personName;
    /** Portrait of the project's owning person — drives avatar in card grids. */
    private String personMediaPortrait;

    private List<CategoryRef> categories;

    private Instant dateCreated;

    /** Inline payload — exactly one of these is set, matching {@code kind}. */
    private GuestAudioDTO audio;
    private GuestVideoDTO video;
    private GuestTextDTO text;
    private GuestImageDTO image;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CategoryRef implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String code;
        private String name;
    }
}
