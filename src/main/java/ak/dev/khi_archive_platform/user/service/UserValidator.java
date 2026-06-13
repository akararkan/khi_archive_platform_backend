package ak.dev.khi_archive_platform.user.service;

import ak.dev.khi_archive_platform.user.consts.ValidationPatterns;
import ak.dev.khi_archive_platform.user.enums.Role;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.InitialDirContext;
import java.util.Hashtable;
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

    // ── DNS lookup config — short timeouts so registration never hangs ───────
    private static final String DNS_TIMEOUT_MS  = "3000";
    private static final String DNS_RETRY_COUNT = "1";

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

    /**
     * Whether to verify the email domain has an MX (or fallback A/AAAA) record
     * via DNS. Default ON in prod; disable in tests or air-gapped environments
     * by setting {@code app.email.verify-mx=false}.
     */
    @Value("${app.email.verify-mx:true}")
    private boolean verifyMx;

    // ═════════════════════════════════════════════════════════════════════════
    //  PUBLIC API
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Normalizes and validates an email address for a given target role.
     * <p>
     * Always runs: blank check, regex format, length cap, disposable-domain block.
     * <p>
     * The DNS MX-record check runs <b>only when the target role is GUEST</b> —
     * employee and admin accounts are created/edited by an internal admin and
     * may legitimately use corporate / vanity domains whose DNS we don't want
     * to second-guess. Guests are public self-registrations, so we hold them
     * to a higher bar (the domain must actually be able to receive mail).
     *
     * @param email the raw email
     * @param role  the target user's role (use {@link Role#GUEST} when unknown
     *              to opt into the strictest check)
     * @return the trimmed + lower-cased email
     * @throws IllegalArgumentException if validation fails
     */
    public String validateAndNormalizeEmail(String email, Role role) {
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

        // ── DNS MX-record check (GUEST only) ─────────────────────────────────
        // EMPLOYEE / ADMIN are provisioned by an admin and may use corporate
        // domains whose DNS we shouldn't gate on; only public self-signups
        // (GUEST) must prove their domain accepts mail.
        if (verifyMx && role == Role.GUEST && !domainAcceptsMail(domain)) {
            log.warn("Guest registration attempt with non-deliverable email domain: {}", domain);
            throw new IllegalArgumentException(
                    "Email domain cannot receive mail. Please use a valid, real email address.");
        }

        return normalized;
    }

    /**
     * Returns {@code true} if {@code domain} has an MX record, or — falling
     * back to RFC 5321 §5 implicit-MX behaviour — an A/AAAA record. A network
     * or DNS error is treated as "fail open" (returns {@code true}) so a
     * temporarily flaky resolver never blocks legitimate signups.
     */
    private boolean domainAcceptsMail(String domain) {
        Hashtable<String, String> env = new Hashtable<>();
        env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
        env.put("com.sun.jndi.dns.timeout.initial", DNS_TIMEOUT_MS);
        env.put("com.sun.jndi.dns.timeout.retries", DNS_RETRY_COUNT);

        InitialDirContext ctx = null;
        try {
            ctx = new InitialDirContext(env);
            Attributes attrs = ctx.getAttributes(domain, new String[]{"MX", "A", "AAAA"});

            Attribute mx = attrs.get("MX");
            if (hasAnyValue(mx)) return true;

            // RFC 5321 §5: when no MX exists, mail servers fall back to A/AAAA.
            return hasAnyValue(attrs.get("A")) || hasAnyValue(attrs.get("AAAA"));
        } catch (NamingException e) {
            // Domain not found in DNS at all → definitely cannot receive mail.
            if (e.getClass().getSimpleName().contains("NameNotFound")) {
                return false;
            }
            // Resolver glitch (timeout, SERVFAIL, etc.) — fail open so a flaky
            // DNS path doesn't reject real users. Worst case: a fake email
            // slips through this layer; uniqueness + future verification flow
            // still protect the system.
            log.warn("DNS lookup error for domain '{}' — failing open: {}", domain, e.getMessage());
            return true;
        } finally {
            if (ctx != null) {
                try { ctx.close(); } catch (NamingException ignored) { /* nothing to do */ }
            }
        }
    }

    private boolean hasAnyValue(Attribute attr) {
        if (attr == null || attr.size() == 0) return false;
        try {
            NamingEnumeration<?> values = attr.getAll();
            return values.hasMore();
        } catch (NamingException e) {
            return false;
        }
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

