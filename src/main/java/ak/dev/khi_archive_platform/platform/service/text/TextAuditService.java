package ak.dev.khi_archive_platform.platform.service.text;

import ak.dev.khi_archive_platform.platform.enums.TextAuditAction;
import ak.dev.khi_archive_platform.platform.model.text.Text;
import ak.dev.khi_archive_platform.platform.model.text.TextAuditLog;
import ak.dev.khi_archive_platform.platform.repo.text.TextAuditLogRepository;
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

import static ak.dev.khi_archive_platform.user.consts.SecurityConstants.TOKEN_PREFIX;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;

import java.time.Instant;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@SuppressWarnings("unused")
public class TextAuditService {

    private final TextAuditLogRepository auditLogRepository;
    private final SessionRepository sessionRepository;
    private final JwtCookieService jwtCookieService;
    private final JwtTokenProvider jwtTokenProvider;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TextAuditLog record(Text text,
                               TextAuditAction action,
                               Authentication authentication,
                               HttpServletRequest request,
                               String details) {
        Session session = resolveSession(request);
        User actorUser = resolveActorUser(authentication);

        TextAuditLog.TextAuditLogBuilder builder = TextAuditLog.builder()
                .textId(text != null ? text.getId() : null)
                .textCode(text != null ? text.getTextCode() : null)
                .textTitle(text != null ? text.getOriginalTitle() : null)
                .action(action)
                .actorUserId(actorUser != null ? actorUser.getUserId() : null)
                .actorUsername(actorUser != null ? actorUser.getUsername() : (authentication != null ? authentication.getName() : "anonymous"))
                .actorDisplayName(actorUser != null ? actorUser.getName() : (authentication != null ? authentication.getName() : "anonymous"))
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
                .occurredAt(Instant.now());

        if (text != null && text.getProject() != null) {
            builder.projectId(text.getProject().getId())
                    .projectCode(text.getProject().getProjectCode())
                    .projectName(text.getProject().getProjectName());

            if (text.getProject().getPerson() != null) {
                builder.personId(text.getProject().getPerson().getId())
                        .personCode(text.getProject().getPerson().getPersonCode())
                        .personName(text.getProject().getPerson().getFullName());
            }
            if (text.getProject().getCategories() != null && !text.getProject().getCategories().isEmpty()) {
                builder.categoryCodes(text.getProject().getCategories().stream()
                        .map(c -> c.getCategoryCode())
                        .collect(Collectors.joining(",")));
            }
        }

        return auditLogRepository.save(builder.build());
    }

    private Session resolveSession(HttpServletRequest request) {
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
        if (authentication == null) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof User user) {
            return user;
        }
        return null;
    }

    private String resolveAuthorities(Authentication authentication) {
        if (authentication == null) {
            return null;
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority != null && !authority.isBlank())
                .distinct()
                .collect(Collectors.joining(","));
    }

    private String resolvePermissions(Authentication authentication) {
        if (authentication == null) {
            return null;
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority != null && !authority.isBlank() && !authority.startsWith("ROLE_"))
                .distinct()
                .collect(Collectors.joining(","));
    }
}
