package ak.dev.khi_archive_platform.platform.service.analytics;

import ak.dev.khi_archive_platform.platform.enums.AnalyticsAuditAction;
import ak.dev.khi_archive_platform.platform.model.analytics.AnalyticsAuditLog;
import ak.dev.khi_archive_platform.platform.repo.analytics.AnalyticsAuditLogRepository;
import ak.dev.khi_archive_platform.user.jwt.JwtCookieService;
import ak.dev.khi_archive_platform.user.jwt.JwtTokenProvider;
import ak.dev.khi_archive_platform.user.model.Session;
import ak.dev.khi_archive_platform.user.model.User;
import ak.dev.khi_archive_platform.user.repo.SessionRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;

import java.time.Instant;
import java.util.stream.Collectors;

import static ak.dev.khi_archive_platform.user.consts.SecurityConstants.TOKEN_PREFIX;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;

/**
 * Writes one row per analytics-console view. Mirrors the entity-level
 * {@code *AuditService} classes (REQUIRES_NEW so the audit row is committed
 * even if the surrounding read transaction rolls back).
 */
@Service
@RequiredArgsConstructor
public class AnalyticsAuditService {

    private final AnalyticsAuditLogRepository auditLogRepository;
    private final SessionRepository sessionRepository;
    private final JwtCookieService jwtCookieService;
    private final JwtTokenProvider jwtTokenProvider;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AnalyticsAuditLog record(AnalyticsAuditAction action,
                                    String filterSummary,
                                    Authentication authentication,
                                    HttpServletRequest request,
                                    String details) {
        Session session = resolveSession(request);
        User actorUser = resolveActorUser(authentication);

        AnalyticsAuditLog log = AnalyticsAuditLog.builder()
                .action(action)
                .filterSummary(filterSummary)
                .actorUserId(actorUser != null ? actorUser.getUserId() : null)
                .actorUsername(actorUser != null ? actorUser.getUsername()
                        : (authentication != null ? authentication.getName() : "anonymous"))
                .actorDisplayName(actorUser != null ? actorUser.getName()
                        : (authentication != null ? authentication.getName() : "anonymous"))
                .actorAuthorities(resolveAuthorities(authentication))
                .actorPermissions(resolvePermissions(authentication))
                .deviceInfo(session != null ? session.getDeviceInfo() : request.getHeader("User-Agent"))
                .ipAddress(session != null ? session.getIpAddress() : request.getRemoteAddr())
                .sessionId(session != null ? session.getSessionId() : null)
                .sessionLoginTimestamp(session != null ? session.getLoginTimestamp() : null)
                .sessionExpiresAt(session != null ? session.getExpiresAt() : null)
                .sessionActive(session != null ? session.getIsActive() : null)
                .requestMethod(request.getMethod())
                .requestPath(request.getRequestURI())
                .details(details == null ? null : HtmlUtils.htmlEscape(details))
                .occurredAt(Instant.now())
                .build();

        return auditLogRepository.save(log);
    }

    private Session resolveSession(HttpServletRequest request) {
        String token = resolveToken(request);
        if (token == null || token.isBlank()) return null;
        String sessionId = jwtTokenProvider.getSessionIdFromToken(token);
        if (sessionId == null || sessionId.isBlank()) return null;
        return sessionRepository.findBySessionId(sessionId).orElse(null);
    }

    private String resolveToken(HttpServletRequest request) {
        String authorizationHeader = request.getHeader(AUTHORIZATION);
        if (authorizationHeader != null && authorizationHeader.startsWith(TOKEN_PREFIX)) {
            return authorizationHeader.substring(TOKEN_PREFIX.length()).trim();
        }
        return jwtCookieService.resolveToken(request);
    }

    private User resolveActorUser(Authentication authentication) {
        if (authentication == null) return null;
        Object principal = authentication.getPrincipal();
        return principal instanceof User user ? user : null;
    }

    private String resolveAuthorities(Authentication authentication) {
        if (authentication == null) return null;
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a != null && !a.isBlank())
                .distinct()
                .collect(Collectors.joining(","));
    }

    private String resolvePermissions(Authentication authentication) {
        if (authentication == null) return null;
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a != null && !a.isBlank() && !a.startsWith("ROLE_"))
                .distinct()
                .collect(Collectors.joining(","));
    }
}
