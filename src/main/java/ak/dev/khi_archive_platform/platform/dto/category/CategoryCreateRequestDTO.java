package ak.dev.khi_archive_platform.platform.dto.category;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import ak.dev.khi_archive_platform.user.consts.ValidationPatterns;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CategoryCreateRequestDTO {
    @NotBlank(message = "Category code is required")
    @Pattern(regexp = ValidationPatterns.CATEGORY_CODE, message = "Category code must contain only letters, numbers, underscores, or hyphens")
    private String categoryCode;

    @NotBlank(message = "Category name is required")
    private String name;

    private String description;
}

