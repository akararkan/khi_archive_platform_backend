package ak.dev.khi_archive_platform.user.jwt;

import ak.dev.khi_archive_platform.user.configs.JwtCookieProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

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
        Cookie[] cookies = request.getCookies();
        if (cookies == null || cookies.length == 0) {
            return null;
        }

        for (Cookie cookie : cookies) {
            if (properties.getCookieName().equals(cookie.getName())) {
                String value = cookie.getValue();
                return value == null || value.isBlank() ? null : value.trim();
            }
        }
        return null;
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
