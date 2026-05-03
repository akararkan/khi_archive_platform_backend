package ak.dev.khi_archive_platform.user.service;

import ak.dev.khi_archive_platform.user.enums.UserAuditAction;
import ak.dev.khi_archive_platform.user.jwt.JwtCookieService;
import ak.dev.khi_archive_platform.user.jwt.JwtTokenProvider;
import ak.dev.khi_archive_platform.user.model.Session;
import ak.dev.khi_archive_platform.user.model.User;
import ak.dev.khi_archive_platform.user.model.UserAuditLog;
import ak.dev.khi_archive_platform.user.repo.SessionRepository;
import ak.dev.khi_archive_platform.user.repo.UserAuditLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;

import java.time.Instant;
import java.util.Collection;
import java.util.stream.Collectors;

import static ak.dev.khi_archive_platform.user.consts.SecurityConstants.TOKEN_PREFIX;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;

/**
 * Records each admin user-management action. Mirrors the entity-level
 * {@code *AuditService} pattern: REQUIRES_NEW so the audit row commits even
 * if the surrounding transaction rolls back.
 */
@Service
@RequiredArgsConstructor
public class UserAuditService {

    private final UserAuditLogRepository auditLogRepository;
    private final SessionRepository sessionRepository;
    private final JwtCookieService jwtCookieService;
    private final JwtTokenProvider jwtTokenProvider;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UserAuditLog record(User target,
                               UserAuditAction action,
                               String previousRole,
                               String newRole,
                               Collection<String> permissionsChanged,
                               Authentication authentication,
                               HttpServletRequest request,
                               String details) {
        Session session = resolveSession(request);
        User actorUser = resolveActorUser(authentication);

        UserAuditLog log = UserAuditLog.builder()
                .action(action)
                .targetUserId(target != null ? target.getUserId() : null)
                .targetUsername(target != null ? target.getUsername() : null)
                .targetDisplayName(target != null ? target.getName() : null)
                .targetEmail(target != null ? target.getEmail() : null)
                .previousRole(previousRole)
                .newRole(newRole)
                .permissionsChanged(permissionsChanged == null || permissionsChanged.isEmpty()
                        ? null : String.join(",", permissionsChanged))
                .actorUserId(actorUser != null ? actorUser.getUserId() : null)
                .actorUsername(actorUser != null ? actorUser.getUsername()
                        : (authentication != null ? authentication.getName() : "anonymous"))
                .actorDisplayName(actorUser != null ? actorUser.getName()
                        : (authentication != null ? authentication.getName() : "anonymous"))
                .actorAuthorities(joinAuthorities(authentication, false))
                .actorPermissions(joinAuthorities(authentication, true))
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

    /** {@code permissionsOnly=true} drops anything starting with {@code ROLE_}. */
    private String joinAuthorities(Authentication authentication, boolean permissionsOnly) {
        if (authentication == null) return null;
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a != null && !a.isBlank())
                .filter(a -> !permissionsOnly || !a.startsWith("ROLE_"))
                .distinct()
                .collect(Collectors.joining(","));
    }
}
