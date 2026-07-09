package ak.dev.khi_archive_platform.user.configs;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
@ConfigurationProperties(prefix = "app.cors")
public class AppCorsProperties {

    // These origins are always allowed regardless of what CORS_ALLOWED_ORIGINS env var says.
    private static final List<String> ALWAYS_ALLOWED_ORIGINS = List.of(
            "http://localhost:5173",
            "http://localhost:3000",
            "https://khi-archive-platform-frontend.vercel.app",
            "https://khi-archive-platform.s3.us-east-1.amazonaws.com"
    );

    private String allowedOrigins = "";
    private String allowedMethods = "GET,POST,PUT,DELETE,OPTIONS,PATCH";
    private String allowedHeaders = "*";
    private boolean allowCredentials = true;
    private long maxAge = 3600;

    public List<String> getAllowedOriginsList() {
        Set<String> merged = new LinkedHashSet<>(ALWAYS_ALLOWED_ORIGINS);
        merged.addAll(splitCsv(allowedOrigins));
        return List.copyOf(merged);
    }

    public List<String> getAllowedMethodsList() {
        return splitCsv(allowedMethods);
    }

    public List<String> getAllowedHeadersList() {
        return splitCsv(allowedHeaders);
    }

    private List<String> splitCsv(String value) {
        return Arrays.stream(value == null ? new String[0] : value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    public String getAllowedOrigins() { return allowedOrigins; }
    public void setAllowedOrigins(String allowedOrigins) { this.allowedOrigins = allowedOrigins; }

    public String getAllowedMethods() { return allowedMethods; }
    public void setAllowedMethods(String allowedMethods) { this.allowedMethods = allowedMethods; }

    public String getAllowedHeaders() { return allowedHeaders; }
    public void setAllowedHeaders(String allowedHeaders) { this.allowedHeaders = allowedHeaders; }

    public boolean isAllowCredentials() { return allowCredentials; }
    public void setAllowCredentials(boolean allowCredentials) { this.allowCredentials = allowCredentials; }

    public long getMaxAge() { return maxAge; }
    public void setMaxAge(long maxAge) { this.maxAge = maxAge; }
}
