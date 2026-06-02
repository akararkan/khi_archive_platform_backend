package ak.dev.khi_archive_platform.platform.service.correction;

import ak.dev.khi_archive_platform.platform.enums.GuestCorrectionAuditAction;
import ak.dev.khi_archive_platform.platform.model.correction.GuestCorrection;
import ak.dev.khi_archive_platform.platform.model.correction.GuestCorrectionAuditLog;
import ak.dev.khi_archive_platform.platform.repo.correction.GuestCorrectionAuditLogRepository;
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

@Service
@RequiredArgsConstructor
@SuppressWarnings("unused")
public class GuestCorrectionAuditService {

    private final GuestCorrectionAuditLogRepository auditLogRepository;
    private final SessionRepository sessionRepository;
    private final JwtCookieService jwtCookieService;
    private final JwtTokenProvider jwtTokenProvider;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public GuestCorrectionAuditLog record(GuestCorrection correction,
                                          GuestCorrectionAuditAction action,
                                          Authentication authentication,
                                          HttpServletRequest request,
                                          String details) {
        Session session = resolveSession(request);
        User actorUser = resolveActorUser(authentication);

        GuestCorrectionAuditLog log = GuestCorrectionAuditLog.builder()
                .correctionId(correction != null ? correction.getId() : null)
                .mediaType(correction != null ? correction.getMediaType() : null)
                .mediaCode(correction != null ? correction.getMediaCode() : null)
                .mediaTitle(correction != null ? correction.getMediaTitle() : null)
                .targetField(correction != null ? correction.getTargetField() : null)
                .action(action)
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
        String authHeader = request.getHeader(AUTHORIZATION);
        if (authHeader != null && authHeader.startsWith(TOKEN_PREFIX)) {
            return authHeader.substring(TOKEN_PREFIX.length()).trim();
        }
        return jwtCookieService.resolveToken(request);
    }

    private User resolveActorUser(Authentication authentication) {
        if (authentication == null) return null;
        Object principal = authentication.getPrincipal();
        return principal instanceof User u ? u : null;
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
