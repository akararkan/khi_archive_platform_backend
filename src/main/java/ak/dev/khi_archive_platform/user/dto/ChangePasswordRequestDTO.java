package ak.dev.khi_archive_platform.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ChangePasswordRequestDTO {

    /** The user's existing password — only length-checked, not complexity (it was already stored). */
    @NotBlank(message = "Current password is required")
    @Size(min = 6, max = 128, message = "Current password must be at least 6 characters")
    private String currentPassword;

    /** Must be at least 6 characters. */
    @NotBlank(message = "New password is required")
    @Size(min = 6, max = 128, message = "New password must be at least 6 characters")
    private String newPassword;

    /** Must be identical to {@code newPassword} — verified in the service layer. */
    @NotBlank(message = "Confirm password is required")
    @Size(min = 6, max = 128, message = "Confirm password must be at least 6 characters")
    private String confirmPassword;
}