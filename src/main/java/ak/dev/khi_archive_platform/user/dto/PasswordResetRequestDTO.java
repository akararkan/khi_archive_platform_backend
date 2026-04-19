package ak.dev.khi_archive_platform.user.dto;

import ak.dev.khi_archive_platform.user.consts.ValidationPatterns;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PasswordResetRequestDTO {

    @NotBlank(message = "Email is required")
    @Email(
        regexp  = ValidationPatterns.EMAIL,
        message = "Email must be a valid address with a domain (e.g. user@example.com)"
    )
    @Size(max = 160, message = "Email must not exceed 160 characters")
    private String email;

    @NotBlank(message = "Reset token is required")
    private String resetToken;

    @NotBlank(message = "New password is required")
    @Size(min = 6, max = 128, message = "New password must be at least 6 characters")
    private String newPassword;

    @NotBlank(message = "Confirm password is required")
    @Size(min = 6, max = 128, message = "Confirm password must be at least 6 characters")
    private String confirmPassword;
}
