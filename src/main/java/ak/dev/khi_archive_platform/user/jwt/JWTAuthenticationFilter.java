package ak.dev.khi_archive_platform.user.jwt;

import ak.dev.khi_archive_platform.common.exceptions.ApiErrorResponse;
import com.auth0.jwt.exceptions.TokenExpiredException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import ak.dev.khi_archive_platform.user.service.TokenService;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static ak.dev.khi_archive_platform.user.consts.SecurityConstants.*;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;

@Component
@RequiredArgsConstructor
public class JWTAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(JWTAuthenticationFilter.class);

    private final JwtTokenProvider jwtTokenProvider;
    private final UserDetailsService userDetailsService;
    private final TokenService tokenService;
    private final JwtCookieService jwtCookieService;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {

        try {
            // Allow OPTIONS requests to proceed (for CORS preflight)
            if (request.getMethod().equalsIgnoreCase(OPTIONS_HTTP_METHOD)) {
                response.setStatus(HttpStatus.OK.value());
                filterChain.doFilter(request, response);
                return;
            }

            String token = resolveToken(request);
            if (!hasText(token)) {
                filterChain.doFilter(request, response);
                return;
            }
            String username;

            try {
                // This will throw TokenExpiredException if expired
                username = jwtTokenProvider.getSubject(token);
            } catch (TokenExpiredException ex) {
                logger.warn("Token expired for request: {}", request.getRequestURI());
                jwtCookieService.clearAuthCookie(response);
                sendErrorResponse(response, HttpStatus.UNAUTHORIZED, "TOKEN_EXPIRED", "Session expired, please login again", request.getRequestURI());
                return;
            } catch (Exception ex) {
                logger.error("Invalid token", ex);
                jwtCookieService.clearAuthCookie(response);
                sendErrorResponse(response, HttpStatus.FORBIDDEN, "INVALID_TOKEN", "Invalid token", request.getRequestURI());
                return;
            }

            // Check if blacklisted (session invalidated/logout)
            if (tokenService.isTokenBlacklisted(token)) {
                logger.warn("Token blacklisted for user: {}", username);
                jwtCookieService.clearAuthCookie(response);
                sendErrorResponse(response, HttpStatus.UNAUTHORIZED, "TOKEN_REVOKED", "Session invalidated, please login again", request.getRequestURI());
                return;
            }

            // ── FIX ──────────────────────────────────────────────────────────
            // Load a real UserDetails object so that @AuthenticationPrincipal
            // UserDetails resolves correctly in every controller.
            // Previously the principal was just a plain String (username), which
            // caused a NullPointerException whenever a controller injected
            // @AuthenticationPrincipal UserDetails principal.
            // ─────────────────────────────────────────────────────────────────
            if (hasText(username) && SecurityContextHolder.getContext().getAuthentication() == null) {
                UserDetails userDetails = userDetailsService.loadUserByUsername(username);
                // Pull authorities from the live User entity (via Role) on every
                // request — never from the JWT. JWT-frozen authorities go stale
                // whenever the role/permission model changes or a user's role
                // is updated, causing surprise 403s on otherwise-allowed calls.
                List<GrantedAuthority> authorities = new ArrayList<>(userDetails.getAuthorities());
                Authentication authentication = jwtTokenProvider.getAuthentication(userDetails, authorities, request);
                SecurityContextHolder.getContext().setAuthentication(authentication);
                logger.debug("User '{}' authenticated successfully", username);
            }

        } catch (Exception ex) {
            logger.error("Unexpected error in JWT filter", ex);
            sendErrorResponse(response, HttpStatus.INTERNAL_SERVER_ERROR, "SERVER_ERROR", "Internal server error", request.getRequestURI());
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void sendErrorResponse(HttpServletResponse response, HttpStatus status, String error, String message, String path) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), new ApiErrorResponse(
                Instant.now(),
                status.value(),
                error,
                message,
                path,
                null
        ));
    }

    private boolean hasText(String str) {
        return str != null && !str.trim().isEmpty();
    }

    private String resolveToken(HttpServletRequest request) {
        String authorizationHeader = request.getHeader(AUTHORIZATION);
        if (hasText(authorizationHeader) && authorizationHeader.startsWith(TOKEN_PREFIX)) {
            return authorizationHeader.substring(TOKEN_PREFIX.length()).trim();
        }
        return jwtCookieService.resolveToken(request);
    }
}