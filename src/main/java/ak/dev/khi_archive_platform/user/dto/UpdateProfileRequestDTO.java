package ak.dev.khi_archive_platform.user.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateProfileRequestDTO {
    @Size(min = 3, max = 80, message = "Username must be between 3 and 80 characters")
    @Pattern(regexp = "^$|^[A-Za-z0-9_]+$", message = "Username can contain only letters, numbers, and underscores")
    private String username;

    @Size(max = 120, message = "Name must not exceed 120 characters")
    private String name;
}