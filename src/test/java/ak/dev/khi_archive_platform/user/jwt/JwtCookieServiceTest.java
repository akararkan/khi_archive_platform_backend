package ak.dev.khi_archive_platform.user.jwt;

import ak.dev.khi_archive_platform.user.configs.JwtCookieProperties;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class JwtCookieServiceTest {

    @Test
    void authCookieUsesTheConfiguredJwtLifetime() {
        JwtCookieProperties properties = new JwtCookieProperties();
        properties.setExpirationMs(259_200_000L);
        properties.setCookieName("khi_auth_token");
        properties.setCookieHttpOnly(true);
        properties.setCookieSecure(true);
        properties.setCookieSameSite("None");
        properties.setCookiePath("/");

        MockHttpServletResponse response = new MockHttpServletResponse();
        new JwtCookieService(properties).addAuthCookie(response, "token-value");

        assertThat(response.getHeader("Set-Cookie"))
                .contains("khi_auth_token=token-value")
                .contains("Max-Age=259200")
                .contains("Path=/")
                .contains("Secure")
                .contains("HttpOnly")
                .contains("SameSite=None");
    }

    @Test
    void resolveTokensReturnsEveryDuplicateAuthCookie() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(
                new Cookie("khi_auth_token", "stale-token"),
                new Cookie("other", "ignored"),
                new Cookie("khi_auth_token", "  fresh-token  "),
                new Cookie("khi_auth_token", ""));

        assertThat(new JwtCookieService(propertiesForCookie("khi_auth_token")).resolveTokens(request))
                .containsExactly("stale-token", "fresh-token");
    }

    @Test
    void resolveTokenIgnoresRequestsWithoutAuthCookie() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("other", "ignored"));

        JwtCookieService service = new JwtCookieService(propertiesForCookie("khi_auth_token"));
        assertThat(service.resolveToken(request)).isNull();
        assertThat(service.resolveTokens(request)).isEmpty();
    }

    private JwtCookieProperties propertiesForCookie(String name) {
        JwtCookieProperties properties = new JwtCookieProperties();
        properties.setCookieName(name);
        return properties;
    }
}
