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
}
