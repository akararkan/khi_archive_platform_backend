package ak.dev.khi_archive_platform.user.api;

import ak.dev.khi_archive_platform.user.dto.UserResponseDTO;
import ak.dev.khi_archive_platform.user.service.UserProfileService;
import ak.dev.khi_archive_platform.user.dto.UpdateProfileRequestDTO;
import ak.dev.khi_archive_platform.user.dto.ChangePasswordRequestDTO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * Self-service profile endpoints for the currently authenticated user.
 * All routes are under /api/user  (singular — not /api/users)
 *
 * ── WHY Authentication INSTEAD OF @AuthenticationPrincipal UserDetails ───────
 * The JWT filter previously stored a plain String as the principal inside
 * UsernamePasswordAuthenticationToken.  Spring's @AuthenticationPrincipal
 * only unwraps the principal when it IS a UserDetails instance; otherwise it
 * silently injects null, causing a NullPointerException on principal.getUsername().
 *
 * Using Authentication#getName() is always safe: it delegates to
 * principal.toString() for String principals and to UserDetails#getUsername()
 * for UserDetails principals, so it survives both the old and the fixed filter.
 * ─────────────────────────────────────────────────────────────────────────────
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/user")
public class UserProfileAPI {

    private final UserProfileService userProfileService;

    // ── GET /api/user/me ─────────────────────────────────────────────────────
    @GetMapping("/me")
    public ResponseEntity<UserResponseDTO> getMe(Authentication auth) {
        return ResponseEntity.ok(userProfileService.getByUsername(auth.getName()));
    }

    // ── PUT /api/user/profile ─────────────────────────────────────────────────
    @PutMapping("/profile")
    public ResponseEntity<UserResponseDTO> updateProfile(
            Authentication auth,
            @Valid @RequestBody UpdateProfileRequestDTO dto
    ) {
        return ResponseEntity.ok(
                userProfileService.updateProfile(auth.getName(), dto)
        );
    }

    // ── PUT /api/user/password ────────────────────────────────────────────────
    @PutMapping("/password")
    public ResponseEntity<Map<String, String>> changePassword(
            Authentication auth,
            @Valid @RequestBody ChangePasswordRequestDTO dto
    ) {
        userProfileService.changePassword(auth.getName(), dto);
        return ResponseEntity.ok(Map.of("message", "Password updated successfully"));
    }

    // ── POST /api/user/profile-image ──────────────────────────────────────────
    @PostMapping(value = "/profile-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UserResponseDTO> uploadProfileImage(
            Authentication auth,
            @RequestPart("file") MultipartFile file
    ) {
        return ResponseEntity.ok(
                userProfileService.uploadProfileImage(auth.getName(), file)
        );
    }

    // ── DELETE /api/user/profile-image ────────────────────────────────────────
    @DeleteMapping("/profile-image")
    public ResponseEntity<UserResponseDTO> removeProfileImage(Authentication auth) {
        return ResponseEntity.ok(
                userProfileService.removeProfileImage(auth.getName())
        );
    }

    // ── DELETE /api/user/account ──────────────────────────────────────────────
    @DeleteMapping("/account")
    public ResponseEntity<Void> deleteAccount(Authentication auth) {
        userProfileService.deleteAccount(auth.getName());
        return ResponseEntity.noContent().build();
    }
}