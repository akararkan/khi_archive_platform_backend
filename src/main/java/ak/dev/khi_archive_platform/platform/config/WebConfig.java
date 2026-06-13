package ak.dev.khi_archive_platform.platform.config;

import ak.dev.khi_archive_platform.user.configs.AppCorsProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final AppCorsProperties corsProperties;

    /**
     * Standalone CorsFilter at the absolute highest priority — runs BEFORE
     * Spring Security. This guarantees that every response (200, 401, 403, 500…)
     * carries the correct CORS headers so the browser can always read the body.
     * Without this, Spring Security 403/401 error responses escape without CORS
     * headers and the browser shows a generic "CORS error" instead of the real one.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(corsProperties.getAllowedOriginsList());
        config.setAllowedMethods(corsProperties.getAllowedMethodsList());
        config.addAllowedHeader("*");
        config.setAllowCredentials(true);
        config.setMaxAge(corsProperties.getMaxAge());

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }

    // Second layer: MVC-level CORS as a fallback for any non-security path.
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(corsProperties.getAllowedOriginsList().toArray(String[]::new))
                .allowedMethods(corsProperties.getAllowedMethodsList().toArray(String[]::new))
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(corsProperties.getMaxAge());
    }
}
