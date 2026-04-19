package ak.dev.khi_archive_platform.user.dto;

import ak.dev.khi_archive_platform.user.enums.Role;
import lombok.*;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserResponseDTO {
    private Long userId;
    private String name;
    private String username;
    private String email;
    private Role role;
    private Boolean isActivated;

    // Profile images
    private String profileImage;    // Path/URL to stored image
    private String imageUrl;        // URL from an external account source

    // Additional info
    private String provider;        // account source label
    private Instant createdAt;
    private Instant updatedAt;
    /** Needed by frontend to show password-expiry warning */
    private Instant passwordExpiryDate;
}