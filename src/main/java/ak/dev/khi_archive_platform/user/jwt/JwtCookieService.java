package ak.dev.khi_archive_platform.user.jwt;

import ak.dev.khi_archive_platform.user.configs.JwtCookieProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class JwtCookieService {

    private final JwtCookieProperties properties;

    public void addAuthCookie(HttpServletResponse response, String token) {
        response.addHeader(HttpHeaders.SET_COOKIE, buildCookie(token, tokenLifetimeSeconds()).toString());
    }

    public void clearAuthCookie(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, buildCookie("", 0).toString());
    }

    public String resolveToken(HttpServletRequest request) {
        List<String> tokens = resolveTokens(request);
        return tokens.isEmpty() ? null : tokens.getFirst();
    }

    /**
     * Returns <em>every</em> distinct value the browser sent under the auth
     * cookie name, in the order received.
     *
     * <p>A browser happily holds several cookies with the same name when they
     * were set for different paths or hosts (a stale one from an earlier
     * deployment, say). It then sends all of them on one request. Returning
     * only the first meant a single stale cookie could shadow a perfectly
     * valid one and fail every request with TOKEN_INVALID_SIGNATURE — and
     * because {@link #clearAuthCookie} can only delete the cookie at the
     * configured path, logging out never cleared the shadowing duplicate.
     * The caller tries each candidate instead.</p>
     */
    public List<String> resolveTokens(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null || cookies.length == 0) {
            return List.of();
        }

        Set<String> tokens = new LinkedHashSet<>();
        for (Cookie cookie : cookies) {
            if (!properties.getCookieName().equals(cookie.getName())) {
                continue;
            }
            String value = cookie.getValue();
            if (value != null && !value.isBlank()) {
                tokens.add(value.trim());
            }
        }
        return new ArrayList<>(tokens);
    }

    private ResponseCookie buildCookie(String token, long maxAgeSeconds) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(properties.getCookieName(), token)
                .httpOnly(properties.isCookieHttpOnly())
                .secure(properties.isCookieSecure())
                .path(properties.getCookiePath())
                .maxAge(maxAgeSeconds);

        String sameSite = properties.getCookieSameSite();
        if (sameSite != null && !sameSite.isBlank()) {
            builder.sameSite(sameSite);
        }

        return builder.build();
    }

    /**
     * Keep the browser cookie and JWT on one clock. A separate cookie lifetime
     * previously defaulted to one day while the JWT lived for three days,
     * causing browsers to silently stop sending an otherwise valid token.
     */
    private long tokenLifetimeSeconds() {
        long expirationMs = properties.getExpirationMs();
        if (expirationMs <= 0) {
            throw new IllegalStateException("jwt.expiration-ms must be greater than zero");
        }
        return Math.max(1L, Math.ceilDiv(expirationMs, 1_000L));
    }
}
