package ak.dev.khi_archive_platform.user.dto.admin;

import ak.dev.khi_archive_platform.user.enums.WarningSeverity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body for {@code POST /api/admin/warnings}. The admin picks a target user,
 * a severity, and writes a short title + body. Message is HTML-escaped before
 * persistence.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserWarningCreateRequestDTO {

    @NotNull(message = "targetUserId is required")
    private Long targetUserId;

    /** Defaults to {@code WARNING} when null. */
    private WarningSeverity severity;

    @NotBlank(message = "title is required")
    @Size(max = 200, message = "title must be at most 200 characters")
    private String title;

    @NotBlank(message = "message is required")
    @Size(max = 4000, message = "message must be at most 4000 characters")
    private String message;
}
