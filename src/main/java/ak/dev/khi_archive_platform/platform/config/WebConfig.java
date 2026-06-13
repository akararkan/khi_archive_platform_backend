package ak.dev.khi_archive_platform.platform.config;

import ak.dev.khi_archive_platform.user.configs.AppCorsProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final AppCorsProperties corsProperties;

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
