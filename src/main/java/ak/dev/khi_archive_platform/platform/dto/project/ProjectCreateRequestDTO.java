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

    /** Person code — if null or blank, project is treated as "Untitled Project". */
    private String personCode;

    /** At least one category code is required. */
    @NotEmpty(message = "At least one category code is required")
    private List<String> categoryCodes;

    private String description;

    private List<String> tags;

    private List<String> keywords;
}
