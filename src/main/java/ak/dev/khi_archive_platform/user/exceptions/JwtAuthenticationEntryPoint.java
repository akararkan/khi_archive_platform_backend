package ak.dev.khi_archive_platform.user.exceptions;

import ak.dev.khi_archive_platform.common.exceptions.ApiErrorResponse;
import ak.dev.khi_archive_platform.common.exceptions.ApiErrorResponses;
import ak.dev.khi_archive_platform.common.exceptions.ErrorCategory;
import ak.dev.khi_archive_platform.common.exceptions.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

/**
 * Fires when an unauthenticated request hits a protected endpoint — the JWT
 * filter passed it through with no Authentication, the security chain demands
 * one, and lands here.
 *
 * <p>Reports as {@link ErrorCode#TOKEN_MISSING} when no credentials at all were
 * supplied, and {@link ErrorCode#AUTHENTICATION_FAILED} otherwise.</p>
 */
@Component
@lombok.RequiredArgsConstructor
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(@NonNull HttpServletRequest request,
                         @NonNull HttpServletResponse response,
                         @NonNull AuthenticationException authException) throws IOException {

        boolean credentialsMissing = authException instanceof InsufficientAuthenticationException
                || request.getHeader("Authorization") == null
                   && request.getCookies() == null;

        String code = credentialsMissing ? ErrorCode.TOKEN_MISSING : ErrorCode.AUTHENTICATION_FAILED;
        String message = credentialsMissing
                ? "Authentication is required to access this resource."
                : (authException.getMessage() == null ? "Authentication failed." : authException.getMessage());
        String hint = "Sign in and retry the request — include the Bearer token in the 'Authorization' header or auth cookie.";

        writeUnauthorized(response, code, message, hint, request.getRequestURI());
    }

    private void writeUnauthorized(HttpServletResponse response,
                                   String errorCode,
                                   String message,
                                   String hint,
                                   String path) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(org.springframework.http.MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
        ApiErrorResponse payload = ApiErrorResponses.of(
                HttpStatus.UNAUTHORIZED,
                errorCode,
                ErrorCategory.AUTHENTICATION,
                message,
                hint,
                path,
                Map.of()
        );
        objectMapper.writeValue(response.getWriter(), payload);
    }
}
