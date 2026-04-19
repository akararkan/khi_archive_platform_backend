package ak.dev.khi_archive_platform.user.service;

import ak.dev.khi_archive_platform.S3Service;
import ak.dev.khi_archive_platform.user.dto.ChangePasswordRequestDTO;
import ak.dev.khi_archive_platform.user.dto.UpdateProfileRequestDTO;
import ak.dev.khi_archive_platform.user.dto.UserResponseDTO;
import ak.dev.khi_archive_platform.user.exceptions.UserAlreadyExistsException;
import ak.dev.khi_archive_platform.user.exceptions.UserNotFoundException;
import ak.dev.khi_archive_platform.user.model.User;
import ak.dev.khi_archive_platform.user.repo.SessionRepository;
import ak.dev.khi_archive_platform.user.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * Handles all self-service profile operations:
 *   GET me · update profile · change password · upload/remove image · delete account.
 *
 * Profile images are now stored in S3 under the folder "user_profile_images/".
 * The full public S3 URL is persisted in users_tbl.profile_image, so the
 * frontend can use it directly — no Spring static-resource handler needed.
 *
 * ── WHY THIS WAS BROKEN ──────────────────────────────────────────────────────
 * The old implementation saved files to the local filesystem
 * (uploads/profile-images/<uuid>.jpg) and stored that relative path in the DB.
 * When the browser requested GET /uploads/profile-images/<uuid>.jpg, Spring
 * tried to resolve it as a classpath static resource, found nothing, and threw
 * NoResourceFoundException → 500.  Storing an S3 URL fixes this completely.
 * ─────────────────────────────────────────────────────────────────────────────
 */
@Service
@Transactional
@RequiredArgsConstructor
@Log4j2
public class UserProfileService {

    // ── S3 folder for all user profile pictures ───────────────────────────────
    private static final String S3_PROFILE_FOLDER = "user_profile_images";

    private static final long         MAX_FILE_SIZE   = 5 * 1024 * 1024L; // 5 MB
    private static final List<String> ALLOWED_TYPES   =
            Arrays.asList("image/jpeg", "image/png", "image/gif", "image/webp");
    private static final Duration     PASSWORD_EXPIRY = Duration.ofDays(90);

    private final UserRepository    userRepository;
    private final SessionRepository sessionRepository;
    private final PasswordEncoder   passwordEncoder;
    private final S3Service         s3Service;          // ← injected; replaces local-disk logic
    private final UserValidator     userValidator;

    // ── helpers ──────────────────────────────────────────────────────────────

    private User requireUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() ->
                        new UserNotFoundException("User not found: " + username));
    }

    // ── GET me ───────────────────────────────────────────────────────────────

    public UserResponseDTO getByUsername(String username) {
        return toResponse(requireUser(username));
    }

    // ── Update profile ───────────────────────────────────────────────────────

    public UserResponseDTO updateProfile(String username, UpdateProfileRequestDTO dto) {
        User user = requireUser(username);

        if (dto.getUsername() != null
                && !dto.getUsername().equals(user.getUsername())
                && userRepository.findByUsername(dto.getUsername()).isPresent()) {
            throw new UserAlreadyExistsException("ناوی بەکارهێنەر پێشتر بەکارهاتووە");
        }

        if (dto.getUsername() != null && !dto.getUsername().isBlank()) {
            user.setUsername(dto.getUsername());
        }
        if (dto.getName() != null) {
            user.setName(dto.getName());
        }

        user.setUpdatedAt(Instant.now());
        return toResponse(userRepository.save(user));
    }

    // ── Change password ──────────────────────────────────────────────────────

    public void changePassword(String username, ChangePasswordRequestDTO dto) {
        User user = requireUser(username);
        if (!passwordEncoder.matches(dto.getCurrentPassword(), user.getPassword())) {
            throw new BadCredentialsException("وشەی نهێنیی ئێستا هەڵەیە");
        }
        // confirmPassword must equal newPassword (DTO-level format already validated by @Valid)
        if (!dto.getNewPassword().equals(dto.getConfirmPassword())) {
            throw new IllegalArgumentException(
                    "New password and confirm password do not match.");
        }
        // Validate password (complexity + personal-info + blocklist + sequences)
        userValidator.validatePassword(dto.getNewPassword(), user.getUsername(), user.getEmail(), user.getName());

        // Prevent reuse of the current password
        userValidator.validatePasswordNotReused(dto.getNewPassword(), user.getPassword(), passwordEncoder);

        user.setPassword(passwordEncoder.encode(dto.getNewPassword()));
        user.setPasswordExpiryDate(Instant.now().plus(PASSWORD_EXPIRY));
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);
    }

    // ── Profile image — UPLOAD ────────────────────────────────────────────────

    public UserResponseDTO uploadProfileImage(String username, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("فایلەکە بەتاڵە");
        }

        // ── validate ─────────────────────────────────────────────────────────
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("قەبارەی وێنە دەبێت لە ٥ مێگابایت کەمتر بێت");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_TYPES.contains(contentType)) {
            throw new IllegalArgumentException("تەنها JPEG, PNG, GIF, WebP قبوڵ دەکرێت");
        }

        User user = requireUser(username);
        String oldImage = user.getProfileImage();

        // ── upload new image to S3 → returns full https:// public URL ─────────
        String s3Url = uploadToProfileFolder(file);

        user.setProfileImage(s3Url);   // store the full https:// URL
        user.setUpdatedAt(Instant.now());
        User saved = userRepository.save(user);

        deleteS3Image(oldImage);

        log.info("Profile image uploaded to S3 for user '{}': {}", username, s3Url);
        return toResponse(saved);
    }

    // ── Profile image — REMOVE ────────────────────────────────────────────────

    public UserResponseDTO removeProfileImage(String username) {
        User user = requireUser(username);
        deleteS3Image(user.getProfileImage());
        user.setProfileImage(null);
        user.setUpdatedAt(Instant.now());
        log.info("Profile image removed for user '{}'", username);
        return toResponse(userRepository.save(user));
    }

    // ── Delete account ────────────────────────────────────────────────────────

    public void deleteAccount(String username) {
        User user = requireUser(username);
        deleteS3Image(user.getProfileImage());
        var sessions = sessionRepository.findByUser(user);
        if (sessions != null) sessionRepository.deleteAll(sessions);
        userRepository.delete(user);
        log.info("Account deleted for user: {}", username);
    }

    // ── S3 helpers ────────────────────────────────────────────────────────────

    /**
     * Uploads raw image bytes to S3 under "user_profile_images/" and returns
     * the public URL.
     */
    private String uploadToProfileFolder(byte[] bytes, String originalFilename, String contentType) {
        return s3Service.upload(bytes, S3_PROFILE_FOLDER, originalFilename, contentType);
    }

    private String uploadToProfileFolder(MultipartFile file) {
        return s3Service.uploadProfileImage(file);
    }

    /**
     * Deletes an image from S3 only if the path is a full S3 URL belonging to
     * our bucket.  Local filesystem paths (legacy data) are silently skipped.
     */
    private void deleteS3Image(String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) return;

        if (imageUrl.startsWith("http") && s3Service.isOurS3Url(imageUrl)) {
            s3Service.deleteFile(imageUrl);
        } else {
            // Legacy local path — nothing to do; file may no longer exist
            log.debug("Skipping delete for non-S3 profile image path: {}", imageUrl);
        }
    }


    // ── DTO mapper ────────────────────────────────────────────────────────────

    private UserResponseDTO toResponse(User u) {
        return UserResponseDTO.builder()
                .userId(u.getUserId())
                .name(u.getName())
                .username(u.getUsername())
                .email(u.getEmail())
                .role(u.getRole())
                .isActivated(u.getIsActivated())
                .profileImage(u.getProfileImage())   // full S3 URL or null
                .imageUrl(u.getImageUrl())            // external account image URL
                .provider(u.getProvider())
                .createdAt(u.getCreatedAt())
                .updatedAt(u.getUpdatedAt())
                .passwordExpiryDate(u.getPasswordExpiryDate())
                .build();
    }
}