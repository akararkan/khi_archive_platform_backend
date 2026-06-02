package ak.dev.khi_archive_platform.platform.service.maqam;

import ak.dev.khi_archive_platform.platform.enums.MaqamAuditAction;
import ak.dev.khi_archive_platform.platform.model.maqam.ListOfMaqam;
import ak.dev.khi_archive_platform.platform.model.maqam.MaqamAuditLog;
import ak.dev.khi_archive_platform.platform.repo.maqam.MaqamAuditLogRepository;
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
 * Writes one row per consequential action on the maqam domain. Mirrors
 * {@code AudioAuditService} closely so the cross-entity analytics UNION
 * sees a familiar shape — extends it with optional teacher / vote / listen
 * context so the teacher-engagement views can be built from this table
 * alone without joining back to the operational tables.
 */
@Service
@RequiredArgsConstructor
public class MaqamAuditService {

    private final MaqamAuditLogRepository auditLogRepository;
    private final SessionRepository sessionRepository;
    private final JwtCookieService jwtCookieService;
    private final JwtTokenProvider jwtTokenProvider;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public MaqamAuditLog record(ListOfMaqam maqam,
                                MaqamAuditAction action,
                                Authentication authentication,
                                HttpServletRequest request,
                                String details) {
        return record(maqam, action, authentication, request, details, null, null, null, null);
    }

    /**
     * Full-context record. Pass nulls for any field that doesn't apply to
     * this action (e.g. {@code teacherUser} is null for CRUD by an admin).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public MaqamAuditLog record(ListOfMaqam maqam,
                                MaqamAuditAction action,
                                Authentication authentication,
                                HttpServletRequest request,
                                String details,
                                MaqamTeacherContext teacherContext,
                                String maqamType,
                                MaqamListenContext listenContext,
                                String sessionKey) {
        Session session = resolveSession(request);
        User actorUser = resolveActorUser(authentication);

        MaqamAuditLog.MaqamAuditLogBuilder builder = MaqamAuditLog.builder()
                .maqamId(maqam != null ? maqam.getId() : null)
                .maqamCode(maqam != null ? maqam.getMaqamCode() : null)
                .songName(maqam != null ? maqam.getSongName() : null)
                .producer(maqam != null ? maqam.getProducer() : null)
                .action(action)
                .actorUserId(actorUser != null ? actorUser.getUserId() : null)
                .actorUsername(actorUser != null ? actorUser.getUsername() : (authentication != null ? authentication.getName() : "anonymous"))
                .actorDisplayName(actorUser != null ? actorUser.getName() : (authentication != null ? authentication.getName() : "anonymous"))
                .actorAuthorities(resolveAuthorities(authentication))
                .actorPermissions(resolvePermissions(authentication))
                .deviceInfo(session != null ? session.getDeviceInfo() : (request != null ? request.getHeader("User-Agent") : null))
                .ipAddress(session != null ? session.getIpAddress() : (request != null ? request.getRemoteAddr() : null))
                .sessionId(session != null ? session.getSessionId() : null)
                .sessionLoginTimestamp(session != null ? session.getLoginTimestamp() : null)
                .sessionExpiresAt(session != null ? session.getExpiresAt() : null)
                .sessionActive(session != null ? session.getIsActive() : null)
                .requestMethod(request != null ? request.getMethod() : null)
                .requestPath(request != null ? request.getRequestURI() : null)
                .details(details == null ? null : HtmlUtils.htmlEscape(details))
                .maqamType(maqamType)
                .sessionKey(sessionKey)
                .occurredAt(Instant.now());

        if (teacherContext != null) {
            builder.teacherUserId(teacherContext.userId())
                    .teacherUsername(teacherContext.username())
                    .teacherDisplayName(teacherContext.displayName());
        }
        if (listenContext != null) {
            builder.secondsListened(listenContext.secondsListened())
                    .positionSeconds(listenContext.positionSeconds());
        }

        return auditLogRepository.save(builder.build());
    }

    public record MaqamTeacherContext(Long userId, String username, String displayName) { }

    public record MaqamListenContext(Long secondsListened, Long positionSeconds) { }

    private Session resolveSession(HttpServletRequest request) {
        if (request == null) return null;
        String token = resolveToken(request);
        if (token == null || token.isBlank()) {
            return null;
        }
        String sessionId = jwtTokenProvider.getSessionIdFromToken(token);
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
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
        if (principal instanceof User user) return user;
        return null;
    }

    private String resolveAuthorities(Authentication authentication) {
        if (authentication == null) return null;
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority != null && !authority.isBlank())
                .distinct()
                .collect(Collectors.joining(","));
    }

    private String resolvePermissions(Authentication authentication) {
        if (authentication == null) return null;
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority != null && !authority.isBlank() && !authority.startsWith("ROLE_"))
                .distinct()
                .collect(Collectors.joining(","));
    }
}
