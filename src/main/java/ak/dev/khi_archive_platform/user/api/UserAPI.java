package ak.dev.khi_archive_platform.user.api;

import ak.dev.khi_archive_platform.user.consts.ValidationPatterns;
import ak.dev.khi_archive_platform.user.dto.LoginRequestDTO;
import ak.dev.khi_archive_platform.user.dto.PasswordResetRequestDTO;
import ak.dev.khi_archive_platform.user.dto.RegisterRequestDTO;
import ak.dev.khi_archive_platform.user.jwt.JwtCookieService;
import ak.dev.khi_archive_platform.user.jwt.Token;
import ak.dev.khi_archive_platform.user.model.User;
import ak.dev.khi_archive_platform.user.repo.SessionRepository;
import ak.dev.khi_archive_platform.user.service.TokenService;
import ak.dev.khi_archive_platform.user.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
@Validated
@RequestMapping("/api/auth")
public class UserAPI {

    private final UserService    userService;
    private final TokenService   tokenService;
    private final SessionRepository sessionRepository;
    private final JwtCookieService jwtCookieService;

    // ── REGISTER (JSON, no image) ─────────────────────────────────────────────
    @PostMapping("/register")
    public ResponseEntity<Token> register(
            @Valid @RequestBody RegisterRequestDTO dto,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        return withAuthCookie(userService.register(dto, null, request), response);
    }

    // ── REGISTER with optional profile image (multipart) ─────────────────────
    @PostMapping(value = "/register-with-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Token> registerWithImage(
            @Valid @RequestPart("data") RegisterRequestDTO dto,
            @RequestPart(value = "image", required = false) MultipartFile profileImage,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        return withAuthCookie(userService.register(dto, profileImage, request), response);
    }

    // ── LOGIN (username OR email) ─────────────────────────────────────────────
    @PostMapping("/login")
    public ResponseEntity<Token> login(
            @Valid @RequestBody LoginRequestDTO dto,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        return withAuthCookie(userService.login(dto, request), response);
    }

    // ── REQUEST PASSWORD-RESET TOKEN ─────────────────────────────────────────
    @PostMapping("/reset-token")
    public ResponseEntity<String> createResetToken(
            @RequestParam
            @NotBlank(message = "Email is required")
            @Email(
                regexp  = ValidationPatterns.EMAIL,
                message = "Email must be a valid address with a domain (e.g. user@example.com)"
            )
            @Size(max = 160, message = "Email must not exceed 160 characters")
            String email) {
        return userService.createPasswordResetToken(email);
    }

    // ── RESET PASSWORD (requires token) ──────────────────────────────────────
    @PostMapping("/reset-password")
    public ResponseEntity<String> resetPassword(@Valid @RequestBody PasswordResetRequestDTO dto) {
        return userService.resetPassword(dto);
    }

    // ── LOGOUT (invalidate current session / blacklist token) ─────────────────
    @PostMapping("/logout")
    public ResponseEntity<String> logout(
            HttpServletRequest request,
            HttpServletResponse response,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader
    ) {
        String token = extractToken(request, authorizationHeader);
        if (token == null || token.isBlank()) {
            jwtCookieService.clearAuthCookie(response);
            return ResponseEntity.badRequest().body("Authentication token is missing");
        }
        tokenService.blacklistToken(token);
        jwtCookieService.clearAuthCookie(response);
        return ResponseEntity.ok("Successfully logged out");
    }

    // ── LOGOUT ALL DEVICES (invalidate every active session for this user) ────
    /**
     * Marks every active Session row for the current user as inactive,
     * and blacklists the current token so it stops working immediately.
     *
     * Called by UserProfile.vue → "دەرچوون لە ھەموو ئامێرەکان"
     */
    @PostMapping("/logout-all")
    public ResponseEntity<String> logoutAll(
            @AuthenticationPrincipal UserDetails principal,
            HttpServletRequest request,
            HttpServletResponse response,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader
    ) {
        if (principal == null) {
            return ResponseEntity.status(401).body("Not authenticated");
        }

        // 1. Deactivate all sessions in DB for this user
        User user = (User) principal;
        var sessions = sessionRepository.findByUser(user);
        if (sessions != null && !sessions.isEmpty()) {
            sessions.forEach(s -> {
                s.setIsActive(false);
                s.setLogoutTimestamp(java.time.Instant.now());
            });
            sessionRepository.saveAll(sessions);
        }

        // 2. Blacklist the current token
        String token = extractToken(request, authorizationHeader);
        if (token != null && !token.isBlank()) {
            tokenService.blacklistToken(token);
        }
        jwtCookieService.clearAuthCookie(response);

        return ResponseEntity.ok("Logged out from all devices successfully");
    }

    private ResponseEntity<Token> withAuthCookie(ResponseEntity<Token> serviceResponse, HttpServletResponse response) {
        Token body = serviceResponse.getBody();
        if (serviceResponse.getStatusCode().is2xxSuccessful() && body != null && body.getToken() != null && !body.getToken().isBlank()) {
            jwtCookieService.addAuthCookie(response, body.getToken());
        }
        return serviceResponse;
    }

    private String extractToken(HttpServletRequest request, String authorizationHeader) {
        if (authorizationHeader != null && authorizationHeader.startsWith("Bearer ")) {
            String token = authorizationHeader.substring("Bearer ".length()).trim();
            if (!token.isEmpty()) {
                return token;
            }
        }
        return jwtCookieService.resolveToken(request);
    }
}