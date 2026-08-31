package ak.dev.khi_archive_platform.user.jwt;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.TokenExpiredException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.auth0.jwt.interfaces.JWTVerifier;
import ak.dev.khi_archive_platform.user.model.Session;
import ak.dev.khi_archive_platform.user.model.User;
import ak.dev.khi_archive_platform.user.repo.SessionRepository;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static ak.dev.khi_archive_platform.user.consts.SecurityConstants.*;

@Component
@RequiredArgsConstructor
public class JwtTokenProvider {
    private static final Logger logger = LoggerFactory.getLogger(JwtTokenProvider.class);

    /** Minimum key length recommended for HMAC-SHA256 (RFC 7518 §3.2: at least the hash size). */
    private static final int MIN_SECRET_BYTES = 32;

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration-ms:86400000}")
    private long expirationTime;

    private final SessionRepository sessionRepository;

    /**
     * Signing key and verifier are built once. Rebuilding them per request was
     * pure waste, and — more importantly — leaving the secret unvalidated meant
     * an empty or swapped {@code JWT_SECRET} was only discovered later, as a
     * flood of {@code TOKEN_INVALID_SIGNATURE} on every authenticated request.
     */
    private Algorithm algorithm;
    private JWTVerifier verifier;
    private String secretFingerprint;

    @PostConstruct
    void initSigningKey() {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "jwt.secret is empty. Set the JWT_SECRET environment variable to a stable, "
                            + "non-blank value; without it no token can be signed or verified.");
        }

        int keyBytes = secret.getBytes(StandardCharsets.UTF_8).length;
        if (keyBytes < MIN_SECRET_BYTES) {
            logger.warn("jwt.secret is only {} bytes; HMAC-SHA256 wants at least {}. "
                            + "Use a longer secret when you next rotate it.",
                    keyBytes, MIN_SECRET_BYTES);
        }

        this.algorithm = Algorithm.HMAC256(secret);
        this.verifier = JWT.require(algorithm)
                .withIssuer(AKAR_ARKAN)
                .withAudience(AKAR_ARKAN_ADMINISTRATION)
                .build();
        this.secretFingerprint = fingerprintOf(secret);

        // The fingerprint is a one-way digest, never the secret itself. If this
        // value differs from the previous boot, every token issued before the
        // restart is now invalid — which is exactly what a sudden burst of
        // "JWT signature mismatch" warnings means.
        logger.info("JWT signing key loaded (fingerprint {}), tokens valid for {} ms",
                secretFingerprint, expirationTime);
    }

    /** Short, non-reversible identifier of the active signing key, safe to log. */
    public String getSecretFingerprint() {
        return secretFingerprint;
    }

    private static String fingerprintOf(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 12);
        } catch (Exception e) {
            return "unavailable";
        }
    }

    public String generateToken(User user, HttpServletRequest request) {
        try {
            String[] claims = extractUserAuthorities(user);
            Instant now = Instant.now();
            Instant expiration = now.plusMillis(expirationTime);

            Session session = Session.builder()
                    .sessionId(UUID.randomUUID().toString())
                    .user(user)
                    .deviceInfo(request.getHeader("User-Agent"))
                    .ipAddress(request.getRemoteAddr())
                    .loginTimestamp(now)
                    .expiresAt(expiration)
                    .isActive(true)
                    .build();

            sessionRepository.save(session);

            return JWT.create()
                    .withIssuer(AKAR_ARKAN)
                    .withAudience(AKAR_ARKAN_ADMINISTRATION)
                    .withIssuedAt(Date.from(now))
                    .withSubject(user.getUsername())
                    .withClaim(ID_CLAIM, user.getUserId())
                    .withClaim(ROLE, user.getRole().name())
                    .withArrayClaim(AUTHORITIES, claims)
                    .withClaim("sessionId", session.getSessionId())
                    .withExpiresAt(Date.from(expiration))
                    .sign(algorithm);

        } catch (Exception e) {
            logger.error("Error generating JWT token", e);
            throw new RuntimeException("Failed to generate JWT token", e);
        }
    }

    private String[] extractUserAuthorities(User user) {
        return user.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toArray(String[]::new);
    }

    /**
     * ── FIX ──────────────────────────────────────────────────────────────────
     * Principal is now a UserDetails instance instead of a plain String.
     * This makes @AuthenticationPrincipal UserDetails resolve correctly in
     * every controller (e.g. UserProfileAPI#getMe).
     * ─────────────────────────────────────────────────────────────────────────
     */
    public Authentication getAuthentication(UserDetails userDetails,
                                            List<GrantedAuthority> authorities,
                                            HttpServletRequest request) {
        UsernamePasswordAuthenticationToken authToken =
                new UsernamePasswordAuthenticationToken(userDetails, null, authorities);
        authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        return authToken;
    }

    /**
     * Validates token (checks signature and expiration).
     * Throws TokenExpiredException if token is expired.
     */
    public String getSubject(String token) throws TokenExpiredException {
        JWTVerifier verifier = createJWTVerifier();
        return verifier.verify(token).getSubject();
    }

    private JWTVerifier createJWTVerifier() {
        return verifier;
    }

    public List<GrantedAuthority> getAuthorities(String token) {
        String[] claims = extractAuthoritiesFromToken(token);
        return Arrays.stream(claims)
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());
    }

    public String[] extractAuthoritiesFromToken(String token) {
        try {
            JWTVerifier verifier = createJWTVerifier();
            return verifier.verify(token).getClaim(AUTHORITIES).asArray(String.class);
        } catch (Exception e) {
            logger.error("Failed to extract authorities from token", e);
            return new String[0];
        }
    }

    public Long getUserIdFromToken(String token) {
        try {
            DecodedJWT decodedJWT = decodeToken(token);
            return decodedJWT.getClaim(ID_CLAIM).asLong();
        } catch (Exception e) {
            logger.error("Failed to extract user ID from token", e);
            throw new IllegalArgumentException("Invalid token", e);
        }
    }

    public DecodedJWT decodeToken(String token) {
        JWTVerifier verifier = createJWTVerifier();
        return verifier.verify(token);
    }

    public String getSessionIdFromToken(String token) {
        try {
            DecodedJWT decodedJWT = decodeToken(token);
            return decodedJWT.getClaim("sessionId").asString();
        } catch (Exception e) {
            logger.error("Failed to extract session ID from token", e);
            return null;
        }
    }
}