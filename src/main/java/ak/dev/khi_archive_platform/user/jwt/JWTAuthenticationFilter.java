package ak.dev.khi_archive_platform.user.jwt;

import ak.dev.khi_archive_platform.common.exceptions.ApiErrorResponse;
import ak.dev.khi_archive_platform.common.exceptions.ApiErrorResponses;
import ak.dev.khi_archive_platform.common.exceptions.ErrorCategory;
import ak.dev.khi_archive_platform.common.exceptions.ErrorCode;
import ak.dev.khi_archive_platform.user.configs.AppCorsProperties;
import com.auth0.jwt.exceptions.AlgorithmMismatchException;
import com.auth0.jwt.exceptions.InvalidClaimException;
import com.auth0.jwt.exceptions.JWTDecodeException;
import com.auth0.jwt.exceptions.SignatureVerificationException;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static ak.dev.khi_archive_platform.user.consts.SecurityConstants.*;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;

/**
 * Authenticates each request from the JWT carried either in the
 * {@code Authorization: Bearer …} header or the auth cookie.
 *
 * <p>On the failure path the filter classifies the underlying auth0 exception
 * into a specific {@link ErrorCode} so the frontend can tell <em>why</em>
 * authentication failed and react accordingly:</p>
 * <ul>
 *   <li>{@code TOKEN_EXPIRED} — session has elapsed; prompt to log in again.</li>
 *   <li>{@code TOKEN_REVOKED} — token blacklisted (logout / forced session kill).</li>
 *   <li>{@code TOKEN_INVALID_SIGNATURE} — tampered or wrong-key token.</li>
 *   <li>{@code TOKEN_MALFORMED} — non-JWT garbage in the header/cookie.</li>
 *   <li>{@code TOKEN_INVALID} — any other auth0 verification failure.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class JWTAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(JWTAuthenticationFilter.class);

    private final JwtTokenProvider jwtTokenProvider;
    private final UserDetailsService userDetailsService;
    private final TokenService tokenService;
    private final JwtCookieService jwtCookieService;
    private final ObjectMapper objectMapper;
    private final AppCorsProperties corsProperties;

    /**
     * Public guest endpoints under {@code /api/guest/**} are fully token-free —
     * skipping the filter avoids parsing stale cookies, hitting the user table,
     * and returning 401/403 to anonymous browsers. Auth endpoints are also
     * exempt since they issue tokens rather than consume them.
     */
    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) return false;
        return uri.startsWith("/api/guest/")
                || uri.equals("/api/auth/login")
                || uri.equals("/api/auth/register")
                || uri.equals("/api/auth/register-with-image");
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {

        // Stamp CORS headers first so every short-circuit path (auth errors,
        // token failures, unexpected exceptions) returns a response the browser
        // can actually read instead of a generic CORS/network error.
        applyCorsHeaders(request, response);

        try {
            // Allow OPTIONS requests to proceed (for CORS preflight)
            if (request.getMethod().equalsIgnoreCase(OPTIONS_HTTP_METHOD)) {
                response.setStatus(HttpStatus.OK.value());
                filterChain.doFilter(request, response);
                return;
            }

            String token = resolveToken(request);
            if (!hasText(token)) {
                // No token present — let downstream filters decide. The auth
                // entry-point produces a uniform 401 if the endpoint is protected.
                filterChain.doFilter(request, response);
                return;
            }
            String username;

            try {
                // This will throw a specific auth0 exception for each failure mode.
                username = jwtTokenProvider.getSubject(token);
            } catch (TokenExpiredException ex) {
                logger.warn("JWT expired for request: {}", request.getRequestURI());
                jwtCookieService.clearAuthCookie(response);
                sendErrorResponse(response, HttpStatus.UNAUTHORIZED,
                        ErrorCode.TOKEN_EXPIRED, ErrorCategory.AUTHENTICATION,
                        "Your session has expired.",
                        "Sign in again to continue.",
                        request.getRequestURI(),
                        Map.of("reason", "expired"));
                return;
            } catch (SignatureVerificationException ex) {
                logger.warn("JWT signature mismatch for request: {}", request.getRequestURI());
                jwtCookieService.clearAuthCookie(response);
                sendErrorResponse(response, HttpStatus.UNAUTHORIZED,
                        ErrorCode.TOKEN_INVALID_SIGNATURE, ErrorCategory.AUTHENTICATION,
                        "Token signature is invalid.",
                        "Sign in again to obtain a fresh token.",
                        request.getRequestURI(),
                        Map.of("reason", "signature_mismatch"));
                return;
            } catch (AlgorithmMismatchException ex) {
                logger.warn("JWT algorithm mismatch for request: {}", request.getRequestURI());
                jwtCookieService.clearAuthCookie(response);
                sendErrorResponse(response, HttpStatus.UNAUTHORIZED,
                        ErrorCode.TOKEN_INVALID_SIGNATURE, ErrorCategory.AUTHENTICATION,
                        "Token uses an unexpected signing algorithm.",
                        "Sign in again to obtain a fresh token.",
                        request.getRequestURI(),
                        Map.of("reason", "algorithm_mismatch"));
                return;
            } catch (InvalidClaimException ex) {
                logger.warn("JWT claim invalid for request: {} — {}", request.getRequestURI(), ex.getMessage());
                jwtCookieService.clearAuthCookie(response);
                sendErrorResponse(response, HttpStatus.UNAUTHORIZED,
                        ErrorCode.TOKEN_INVALID, ErrorCategory.AUTHENTICATION,
                        "Token contains an invalid claim.",
                        "Sign in again to obtain a fresh token.",
                        request.getRequestURI(),
                        Map.of("reason", "invalid_claim"));
                return;
            } catch (JWTDecodeException ex) {
                logger.warn("JWT could not be decoded for request: {}", request.getRequestURI());
                jwtCookieService.clearAuthCookie(response);
                sendErrorResponse(response, HttpStatus.UNAUTHORIZED,
                        ErrorCode.TOKEN_MALFORMED, ErrorCategory.AUTHENTICATION,
                        "Token is malformed.",
                        "Clear the auth cookie / Authorization header and sign in again.",
                        request.getRequestURI(),
                        Map.of("reason", "malformed"));
                return;
            } catch (Exception ex) {
                logger.error("Invalid JWT for request: {}", request.getRequestURI(), ex);
                jwtCookieService.clearAuthCookie(response);
                sendErrorResponse(response, HttpStatus.UNAUTHORIZED,
                        ErrorCode.TOKEN_INVALID, ErrorCategory.AUTHENTICATION,
                        "Token verification failed.",
                        "Sign in again to obtain a fresh token.",
                        request.getRequestURI(),
                        null);
                return;
            }

            // Check if blacklisted (session invalidated/logout)
            if (tokenService.isTokenBlacklisted(token)) {
                logger.warn("Blacklisted token presented for user: {}", username);
                jwtCookieService.clearAuthCookie(response);
                sendErrorResponse(response, HttpStatus.UNAUTHORIZED,
                        ErrorCode.TOKEN_REVOKED, ErrorCategory.AUTHENTICATION,
                        "Your session has been invalidated.",
                        "Sign in again to continue.",
                        request.getRequestURI(),
                        Map.of("reason", "revoked"));
                return;
            }

            // ── FIX ──────────────────────────────────────────────────────────
            // Load a real UserDetails object so that @AuthenticationPrincipal
            // UserDetails resolves correctly in every controller.
            // Previously the principal was just a plain String (username), which
            // caused a NullPointerException whenever a controller injected
            // @AuthenticationPrincipal UserDetails principal.
            // ─────────────────────────────────────────────────────────────────
            if (hasText(username)) {
                // Reload the user fresh from DB on EVERY request so role and
                // extra-permission grants take effect immediately. Skipping this
                // when SecurityContext already has an Authentication (the prior
                // behaviour) only worked for stateless flows; if a session ever
                // persisted the context, granted permissions would be stuck at
                // login-time values until the user re-authenticated.
                UserDetails userDetails = userDetailsService.loadUserByUsername(username);
                List<GrantedAuthority> authorities = new ArrayList<>(userDetails.getAuthorities());
                Authentication authentication = jwtTokenProvider.getAuthentication(userDetails, authorities, request);
                SecurityContextHolder.getContext().setAuthentication(authentication);
                logger.debug("User '{}' authenticated with {} authorities", username, authorities.size());
            }

        } catch (Exception ex) {
            logger.error("Unexpected error in JWT filter", ex);
            sendErrorResponse(response, HttpStatus.INTERNAL_SERVER_ERROR,
                    ErrorCode.INTERNAL_SERVER_ERROR, ErrorCategory.SERVER_ERROR,
                    "An unexpected authentication error occurred.",
                    "Retry shortly; if the problem persists, contact support with the traceId.",
                    request.getRequestURI(),
                    null);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void sendErrorResponse(HttpServletResponse response,
                                   HttpStatus status,
                                   String errorCode,
                                   ErrorCategory category,
                                   String message,
                                   String hint,
                                   String path,
                                   Map<String, Object> details) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
        Map<String, Object> safeDetails = details == null ? null : new LinkedHashMap<>(details);
        ApiErrorResponse payload = ApiErrorResponses.of(status, errorCode, category, message, hint, path, safeDetails);
        objectMapper.writeValue(response.getWriter(), payload);
    }

    // Stamps CORS headers on every short-circuit error response so the browser
    // can read the JSON body instead of seeing a generic network/CORS error.
    private void applyCorsHeaders(HttpServletRequest request, HttpServletResponse response) {
        String origin = request.getHeader("Origin");
        if (origin == null) return;
        if (corsProperties.getAllowedOriginsList().contains(origin)) {
            response.setHeader("Access-Control-Allow-Origin", origin);
            response.setHeader("Access-Control-Allow-Credentials", "true");
            response.setHeader("Vary", "Origin");
        }
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
