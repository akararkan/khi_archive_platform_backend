package ak.dev.khi_archive_platform.platform.dto.object;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import ak.dev.khi_archive_platform.user.consts.ValidationPatterns;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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
public class ObjectCreateRequestDTO {

    @Size(max = 160, message = "Object code must not exceed 160 characters")
    @Pattern(regexp = ValidationPatterns.OBJECT_CODE_OR_EMPTY, message = "Object code must match format KHI_OBJ_CATEGORYCODE")
    private String objectCode;

    @NotBlank(message = "Object name is required")
    private String objectName;

    @NotBlank(message = "Category code is required")
    @Size(max = 120, message = "Category code must not exceed 120 characters")
    @Pattern(regexp = ValidationPatterns.CATEGORY_CODE, message = "Category code must contain only letters, numbers, underscores, or hyphens")
    private String categoryCode;

    private String description;
    private List<String> tags;
    private List<String> keywords;
}

