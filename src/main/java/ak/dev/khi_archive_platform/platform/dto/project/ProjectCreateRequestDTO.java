package ak.dev.khi_archive_platform.platform.dto.project;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
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
public class ProjectCreateRequestDTO {

    @NotBlank(message = "Project name is required")
    private String projectName;

    /**
     * Optional project code supplied by the frontend.
     * If blank, the backend falls back to its legacy generator.
     */
    private String projectCode;

    /** Person code — if null or blank, this is a non-person project. */
    private String personCode;

    /** At least one category code is required. */
    @NotEmpty(message = "At least one category code is required")
    private List<String> categoryCodes;

    private String description;

    private List<String> tags;

    private List<String> keywords;

    /**
     * Optional. Whether this project is visible to guest/public APIs.
     * Defaults to true when omitted. Frontend send {@code false} for projects
     * that should be hidden from guests at creation time.
     */
    private Boolean isVisibleToPublic;
}
