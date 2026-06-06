package ak.dev.khi_archive_platform.platform.service.physicalmedia;

import ak.dev.khi_archive_platform.platform.enums.PhysicalMediaAuditAction;
import ak.dev.khi_archive_platform.platform.model.physicalmedia.PhysicalMedia;
import ak.dev.khi_archive_platform.platform.model.physicalmedia.PhysicalMediaAuditLog;
import ak.dev.khi_archive_platform.platform.repo.physicalmedia.PhysicalMediaAuditLogRepository;
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
 * Writes {@code physical_media_audit_logs} entries. Mirrors
 * {@link ak.dev.khi_archive_platform.platform.service.audio.AudioAuditService}
 * so the analytics UNION sees one consistent envelope across every entity.
 *
 * <p>Each {@link #record} call is its own {@code REQUIRES_NEW} transaction
 * — same trick the other audit services use so a failed business commit
 * still leaves the audit row behind for forensics.
 */
@Service
@RequiredArgsConstructor
public class PhysicalMediaAuditService {

    private final PhysicalMediaAuditLogRepository auditLogRepository;
    private final SessionRepository sessionRepository;
    private final JwtCookieService jwtCookieService;
    private final JwtTokenProvider jwtTokenProvider;

    /**
     * Overload for catalog operations on {@code physical_media_types}.
     * Stores the catalog row's id in {@code physical_media_id} and its
     * name in {@code physical_label} so the analytics feed shows
     * "<actor> added type 'CD/DVD'" without a join. Other entity-specific
     * fields are left null — the analytics UNION reads only
     * {@code entity_id}/{@code entity_code}/{@code actor_*} so this is
     * a clean fit.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PhysicalMediaAuditLog recordTypeAction(Long typeId,
                                                  String typeName,
                                                  PhysicalMediaAuditAction action,
                                                  Authentication authentication,
                                                  HttpServletRequest request,
                                                  String details) {
        PhysicalMedia ghost = PhysicalMedia.builder()
                .id(typeId)
                .physicalLabel(typeName)
                .physicalMediaType(typeName)
                .title(typeName)
                .build();
        return record(ghost, action, authentication, request, details);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PhysicalMediaAuditLog record(PhysicalMedia entity,
                                        PhysicalMediaAuditAction action,
                                        Authentication authentication,
                                        HttpServletRequest request,
                                        String details) {
        Session session = resolveSession(request);
        User actorUser = resolveActorUser(authentication);

        PhysicalMediaAuditLog.PhysicalMediaAuditLogBuilder builder = PhysicalMediaAuditLog.builder()
                .physicalMediaId(entity != null ? entity.getId() : null)
                .physicalMediaCode(entity != null ? entity.getPmCode() : null)
                .physicalLabel(entity != null ? entity.getPhysicalLabel() : null)
                .title(entity != null ? entity.getTitle() : null)
                .physicalMediaType(entity != null ? entity.getPhysicalMediaType() : null)
                .action(action)
                .actorUserId(actorUser != null ? actorUser.getUserId() : null)
                .actorUsername(actorUser != null ? actorUser.getUsername()
                        : (authentication != null ? authentication.getName() : "anonymous"))
                .actorDisplayName(actorUser != null ? actorUser.getName()
                        : (authentication != null ? authentication.getName() : "anonymous"))
                .actorAuthorities(resolveAuthorities(authentication))
                .actorPermissions(resolvePermissions(authentication))
                .deviceInfo(session != null ? session.getDeviceInfo()
                        : (request == null ? null : request.getHeader("User-Agent")))
                .ipAddress(session != null ? session.getIpAddress()
                        : (request == null ? null : request.getRemoteAddr()))
                .sessionId(session != null ? session.getSessionId() : null)
                .sessionLoginTimestamp(session != null ? session.getLoginTimestamp() : null)
                .sessionExpiresAt(session != null ? session.getExpiresAt() : null)
                .sessionActive(session != null ? session.getIsActive() : null)
                .requestMethod(request == null ? null : request.getMethod())
                .requestPath(request == null ? null : request.getRequestURI())
                .details(details == null ? null : HtmlUtils.htmlEscape(details))
                .occurredAt(Instant.now());

        return auditLogRepository.save(builder.build());
    }

    private Session resolveSession(HttpServletRequest request) {
        if (request == null) return null;
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
        if (principal instanceof User user) return user;
        return null;
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
