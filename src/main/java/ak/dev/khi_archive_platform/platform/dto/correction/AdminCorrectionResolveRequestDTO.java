package ak.dev.khi_archive_platform.platform.dto.correction;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AdminCorrectionResolveRequestDTO {

    @Size(max = 2000, message = "resolveNote must not exceed 2000 characters")
    private String resolveNote;
}
