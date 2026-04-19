package ak.dev.khi_archive_platform.user.service;

import ak.dev.khi_archive_platform.user.consts.ValidationPatterns;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Programmatic, defense-in-depth validation for email and password.
 * <p>
 * DTO-level {@code @Valid} annotations handle the first line of defense.
 * This validator adds service-layer checks that Jakarta annotations cannot express:
 * <ul>
 *   <li>Blocking disposable / temporary email providers</li>
 *   <li>Ensuring minimum password length at the service layer</li>
 * </ul>
 */
@Component
@Log4j2
public class UserValidator {

    // ── Compiled regex patterns (compiled once, thread-safe) ─────────────────
    private static final Pattern EMAIL_PATTERN = Pattern.compile(ValidationPatterns.EMAIL);

    // ── Disposable-email domains (extend as needed) ─────────────────────────
    private static final Set<String> DISPOSABLE_EMAIL_DOMAINS = Set.of(
            "mailinator.com", "guerrillamail.com", "tempmail.com", "throwaway.email",
            "yopmail.com", "sharklasers.com", "guerrillamailblock.com", "grr.la",
            "dispostable.com", "trashmail.com", "mailnesia.com", "maildrop.cc",
            "fakeinbox.com", "10minutemail.com", "temp-mail.org", "getnada.com",
            "mohmal.com", "burnermail.io", "discard.email", "emailondeck.com",
            "crazymailing.com", "tempail.com", "trash-mail.com", "mintemail.com",
            "mailcatch.com", "tempr.email", "tempinbox.com"
    );

    // ═════════════════════════════════════════════════════════════════════════
    //  PUBLIC API
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Normalizes and validates an email address.
     *
     * @return the trimmed + lower-cased email
     * @throws IllegalArgumentException if validation fails
     */
    public String validateAndNormalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Email is required.");
        }

        String normalized = email.trim().toLowerCase();

        // ── Format check (safety net — DTO @Email may have been bypassed) ────
        if (!EMAIL_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "Email must be a valid address with a domain (e.g. user@example.com).");
        }

        // ── Length guard ──────────────────────────────────────────────────────
        if (normalized.length() > 160) {
            throw new IllegalArgumentException("Email must not exceed 160 characters.");
        }

        // ── Disposable-email block ───────────────────────────────────────────
        String domain = normalized.substring(normalized.indexOf('@') + 1);
        if (DISPOSABLE_EMAIL_DOMAINS.contains(domain)) {
            log.warn("Registration attempt with disposable email domain: {}", domain);
            throw new IllegalArgumentException(
                    "Disposable or temporary email addresses are not allowed. Please use a permanent email.");
        }

        return normalized;
    }

    /**
     * Validates a password — only minimum length is enforced (6 characters).
     *
     * @param password  the raw password
     * @param username  (unused — kept for API compatibility)
     * @param email     (unused — kept for API compatibility)
     * @param name      (unused — kept for API compatibility)
     * @throws IllegalArgumentException if validation fails
     */
    public void validatePassword(String password, String username, String email, String name) {
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("Password is required.");
        }

        if (password.length() < 6) {
            throw new IllegalArgumentException("Password must be at least 6 characters.");
        }

        if (password.length() > 128) {
            throw new IllegalArgumentException("Password must not exceed 128 characters.");
        }
    }

    /**
     * Validates that the new password is different from the current one.
     *
     * @param newPassword     the proposed new password (raw)
     * @param currentEncoded  the current BCrypt-encoded password
     * @param encoder         the password encoder
     */
    public void validatePasswordNotReused(String newPassword, String currentEncoded,
                                          org.springframework.security.crypto.password.PasswordEncoder encoder) {
        if (encoder.matches(newPassword, currentEncoded)) {
            throw new IllegalArgumentException(
                    "New password must be different from your current password.");
        }
    }

}

