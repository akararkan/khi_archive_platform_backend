package ak.dev.khi_archive_platform.user.dto;

import ak.dev.khi_archive_platform.user.consts.ValidationPatterns;
import ak.dev.khi_archive_platform.user.enums.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserUpdateRequestDTO {

    /** Optional — updates the display name when provided. */
    @Size(max = 120, message = "Name must not exceed 120 characters")
    private String name;

    /** Optional — updates the username when provided. */
    @Size(min = 3, max = 80, message = "Username must be between 3 and 80 characters")
    @Pattern(
        regexp  = ValidationPatterns.USERNAME_OR_EMPTY,
        message = "Username can contain only letters, numbers, and underscores"
    )
    private String username;

    /** Optional — updates the email address when provided. */
    @Email(
        regexp  = ValidationPatterns.EMAIL,
        message = "Email must be a valid address with a domain (e.g. user@example.com)"
    )
    @Size(max = 160, message = "Email must not exceed 160 characters")
    private String email;

    /** Optional — updates the password when provided. */
    @Size(min = 6, max = 128, message = "Password must be at least 6 characters")
    private String password;

    /** Optional — changes the user role when provided. */
    private Role role;

    /** Optional — enables or disables the user account when provided. */
    private Boolean isActivated;

    /** Optional — when true, removes the current profile image. */
    private Boolean removeProfileImage;  // Flag to explicitly remove image
}