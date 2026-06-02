package ak.dev.khi_archive_platform.user.dto.admin;

import ak.dev.khi_archive_platform.user.enums.WarningSeverity;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body for {@code PUT /api/admin/warnings/{id}}. Every field is optional —
 * unset fields keep their current value. Acknowledged warnings stay
 * acknowledged after an edit, but the recipient is shown the "edited" badge.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserWarningUpdateRequestDTO {

    private WarningSeverity severity;

    @Size(max = 200, message = "title must be at most 200 characters")
    private String title;

    @Size(max = 4000, message = "message must be at most 4000 characters")
    private String message;
}
