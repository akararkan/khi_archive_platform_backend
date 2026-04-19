package ak.dev.khi_archive_platform.user.configs;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "jwt")
public class JwtCookieProperties {

    private String cookieName = "khi_auth_token";
    private boolean cookieSecure;
    private boolean cookieHttpOnly = true;
    private String cookieSameSite = "Strict";
    private String cookiePath = "/";
    private long cookieMaxAge = 86400;

    public String getCookieName() {
        return cookieName;
    }

    public void setCookieName(String cookieName) {
        this.cookieName = cookieName;
    }

    public boolean isCookieSecure() {
        return cookieSecure;
    }

    public void setCookieSecure(boolean cookieSecure) {
        this.cookieSecure = cookieSecure;
    }

    public boolean isCookieHttpOnly() {
        return cookieHttpOnly;
    }

    public void setCookieHttpOnly(boolean cookieHttpOnly) {
        this.cookieHttpOnly = cookieHttpOnly;
    }

    public String getCookieSameSite() {
        return cookieSameSite;
    }

    public void setCookieSameSite(String cookieSameSite) {
        this.cookieSameSite = cookieSameSite;
    }

    public String getCookiePath() {
        return cookiePath;
    }

    public void setCookiePath(String cookiePath) {
        this.cookiePath = cookiePath;
    }

    public long getCookieMaxAge() {
        return cookieMaxAge;
    }

    public void setCookieMaxAge(long cookieMaxAge) {
        this.cookieMaxAge = cookieMaxAge;
    }
}

