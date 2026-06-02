package ak.dev.khi_archive_platform.platform.dto.correction;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AdminCorrectionForwardRequestDTO {

    /**
     * Optional override: the employee to notify instead of the record's
     * original creator. When null the service resolves the target from
     * {@code GuestCorrection.recordCreatedBy}.
     */
    private Long targetEmployeeId;

    @Size(max = 2000, message = "forwardNote must not exceed 2000 characters")
    private String forwardNote;
}
