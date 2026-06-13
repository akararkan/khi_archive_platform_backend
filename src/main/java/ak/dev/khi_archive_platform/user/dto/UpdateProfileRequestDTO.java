package ak.dev.khi_archive_platform.user.dto;

import ak.dev.khi_archive_platform.user.consts.ValidationPatterns;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Self-service profile-update DTO. Every field is optional — only the fields
 * that are non-null and non-blank are applied. Sending an empty body is a
 * valid no-op so the same DTO can be reused for partial updates from the
 * profile screen (name only, email only, etc.).
 */
@Data
public class UpdateProfileRequestDTO {

    @Size(min = 3, max = 80, message = "Username must be between 3 and 80 characters")
    @Pattern(regexp = ValidationPatterns.USERNAME_OR_EMPTY,
             message = "Username can contain only letters, numbers, and underscores")
    private String username;

    @Size(max = 120, message = "Name must not exceed 120 characters")
    private String name;

    @Email(regexp = ValidationPatterns.EMAIL,
           message = "Email must be a valid address with a domain (e.g. user@example.com)")
    @Size(max = 160, message = "Email must not exceed 160 characters")
    private String email;
}
