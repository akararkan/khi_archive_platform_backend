package ak.dev.khi_archive_platform.platform.dto.correction;

import ak.dev.khi_archive_platform.platform.enums.CorrectionMediaType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class GuestCorrectionSubmitRequestDTO {

    @NotNull(message = "mediaType is required (AUDIO, VIDEO, IMAGE, TEXT)")
    private CorrectionMediaType mediaType;

    @NotBlank(message = "mediaCode is required")
    @Size(max = 255)
    private String mediaCode;

    @NotBlank(message = "targetField is required")
    @Size(max = 100, message = "targetField must not exceed 100 characters")
    private String targetField;

    @Size(max = 5000, message = "currentValue must not exceed 5000 characters")
    private String currentValue;

    @NotBlank(message = "suggestedValue is required")
    @Size(max = 5000, message = "suggestedValue must not exceed 5000 characters")
    private String suggestedValue;

    @Size(max = 2000, message = "note must not exceed 2000 characters")
    private String note;
}
