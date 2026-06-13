package ak.dev.khi_archive_platform.user.exceptions;

import ak.dev.khi_archive_platform.common.exceptions.ApiErrorResponse;
import ak.dev.khi_archive_platform.common.exceptions.ApiErrorResponses;
import ak.dev.khi_archive_platform.common.exceptions.ErrorCategory;
import ak.dev.khi_archive_platform.common.exceptions.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fires when an <em>authenticated</em> request is denied by the security
 * filter chain — typically because {@code @PreAuthorize} or a path matcher
 * forbids it.
 *
 * <p>Includes the actor's username and authorities so the frontend can show
 * an actionable "you have X, you need Y" message without a second round-trip.
 * The matching {@code AccessDeniedException} handler in the
 * {@code @RestControllerAdvice} catches denials that surface during request
 * processing — this handler covers denials at the filter layer.</p>
 */
@Component
@lombok.RequiredArgsConstructor
public class JwtAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {

        Map<String, Object> details = new LinkedHashMap<>();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null) {
            details.put("actor", auth.getName());
            details.put("actorAuthorities", auth.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .filter(a -> a != null && !a.isBlank())
                    .distinct()
                    .sorted()
                    .toList());
        }
        details.put("requestMethod", request.getMethod());

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(org.springframework.http.MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());

        ApiErrorResponse payload = ApiErrorResponses.of(
                HttpStatus.FORBIDDEN,
                ErrorCode.ACCESS_DENIED,
                ErrorCategory.AUTHORIZATION,
                "You don't have permission to perform this action.",
                "Ask an administrator to grant the missing permission for this endpoint.",
                request.getRequestURI(),
                details
        );
        objectMapper.writeValue(response.getWriter(), payload);
    }
}
