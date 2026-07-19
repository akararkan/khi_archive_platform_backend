package ak.dev.khi_archive_platform.user.configs;

import ak.dev.khi_archive_platform.user.jwt.JWTAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import ak.dev.khi_archive_platform.user.exceptions.JwtAccessDeniedHandler;
import ak.dev.khi_archive_platform.user.exceptions.JwtAuthenticationEntryPoint;
import org.springframework.security.config.Customizer;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JWTAuthenticationFilter            jwtAuthenticationFilter;
    private final AuthenticationProvider             authenticationProvider;
    private final JwtAuthenticationEntryPoint        jwtAuthenticationEntryPoint;
    private final JwtAccessDeniedHandler             jwtAccessDeniedHandler;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)

                // STATELESS for JWT — no HTTP session, no SecurityContext caching.
                // Without this the SecurityContextPersistenceFilter caches the
                // first-request Authentication for the life of the session, so
                // role/permission grants only take effect after logout+login.
                .sessionManagement(sm -> sm
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )

                .authenticationProvider(authenticationProvider)

                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(jwtAuthenticationEntryPoint)
                        .accessDeniedHandler(jwtAccessDeniedHandler)
                )

                .authorizeHttpRequests(auth -> auth

                        // ── Preflight ──────────────────────────────────────────────
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                        // ── Public auth endpoints (no token yet) ──────────────────
                        .requestMatchers(
                                "/api/auth/register",
                                "/api/auth/register-with-image",
                                "/api/auth/login"
                        ).permitAll()

                        // ── Public guest browse/search (no token required) ────────
                        // Read-only API; the controllers only define GET handlers.
                        // Permit every method so anonymous browsers (and CORS
                        // preflights) never get blocked here.
                        // This also covers the media stream proxies:
                        //   /api/guest/audio/{code}/stream
                        //   /api/guest/video/{code}/stream
                        //   /api/guest/image/{code}/view
                        // No S3 URL is ever sent to the browser — bytes are proxied
                        // through the API, gated by removedAt IS NULL checks.
                        .requestMatchers("/api/guest/**").permitAll()

                        // ── Everything under /api/** requires a valid token ───────
                        // Fine-grained role/permission checks live on the
                        // controller methods via @PreAuthorize.
                        .requestMatchers("/api/**").authenticated()

                        // ── Everything else: must be authenticated ────────────────
                        .anyRequest().authenticated()
                )

                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                ;

        return http.build();
    }

}
