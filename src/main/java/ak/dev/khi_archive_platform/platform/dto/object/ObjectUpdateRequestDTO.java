package ak.dev.khi_archive_platform.platform.dto.object;

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
@SuppressWarnings("unused")
public class ObjectUpdateRequestDTO {

    private String objectName;
    private String categoryCode;
    private String description;
    private List<String> tags;
    private List<String> keywords;
}

