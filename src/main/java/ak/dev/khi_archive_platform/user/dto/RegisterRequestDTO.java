package ak.dev.khi_archive_platform.user.dto;

import ak.dev.khi_archive_platform.user.consts.ValidationPatterns;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegisterRequestDTO {

    @NotBlank(message = "Name is required")
    @Size(max = 120, message = "Name must not exceed 120 characters")
    private String name;

    @NotBlank(message = "Username is required")
    @Size(min = 3, max = 80, message = "Username must be between 3 and 80 characters")
    @Pattern(
        regexp = ValidationPatterns.USERNAME,
        message = "Username can contain only letters, numbers, and underscores"
    )
    private String username;

    @NotBlank(message = "Email is required")
    @Email(
        regexp  = ValidationPatterns.EMAIL,
        message = "Email must be a valid address with a domain (e.g. user@example.com)"
    )
    @Size(max = 160, message = "Email must not exceed 160 characters")
    private String email;

    @NotBlank(message = "Password is required")
    @Size(min = 6, max = 128, message = "Password must be at least 6 characters")
    private String password;

    // Note: profileImage is handled separately as MultipartFile in Controller
}