package ak.dev.khi_archive_platform.platform.dto.project;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProjectUpdateRequestDTO {

    private String projectName;

    private String description;

    /** If provided, replaces the full list of category codes. */
    private List<String> categoryCodes;

    private List<String> tags;

    private List<String> keywords;

    /**
     * Optional. Updated project visibility flag. Null = no change.
     * <p>
     * The 4 media entities (Audio/Video/Image/Text) each carry their own
     * {@code isPublic} flag. When this value changes, callers can choose
     * what should happen to those flags via {@link #visibilityCascade}.
     * <p>
     * Front-end contract:
     * <ul>
     *   <li>To make a project public and all its media public: set
     *       {@code isVisibleToPublic=true} and {@code visibilityCascade=CASCADE}.</li>
     *   <li>To hide a project and all its media from guests: set
     *       {@code isVisibleToPublic=false} and {@code visibilityCascade=CASCADE}.</li>
     *   <li>To toggle the project flag only (custom per-media visibility):
     *       set {@code isVisibleToPublic} and {@code visibilityCascade=NONE}
     *       (or omit cascade — defaults to NONE).</li>
     * </ul>
     */
    private Boolean isVisibleToPublic;

    /**
     * Optional. Controls how an {@link #isVisibleToPublic} change propagates
     * to media. Values: {@code CASCADE} (every active Audio/Video/Image/Text
     * under this project receives the same {@code isPublic} value) or
     * {@code NONE} (default — only the project flag changes; per-media
     * visibility is preserved so admins can keep custom overrides).
     * <p>
     * Ignored unless {@link #isVisibleToPublic} is also provided. Case-insensitive.
     */
    private String visibilityCascade;
}
