package ak.dev.khi_archive_platform.user.service;

import ak.dev.khi_archive_platform.user.jwt.JwtTokenProvider;
import ak.dev.khi_archive_platform.user.model.Session;
import ak.dev.khi_archive_platform.user.model.TokenBlacklist;
import ak.dev.khi_archive_platform.user.repo.SessionRepository;
import ak.dev.khi_archive_platform.user.repo.TokenBlacklistRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class TokenService {

    private final TokenBlacklistRepository tokenBlacklistRepository;
    private final SessionRepository sessionRepository;
    private final JwtTokenProvider jwtTokenProvider;

    public boolean isTokenBlacklisted(String token) {
        if (token == null || token.isBlank()) {
            return true;
        }

        if (tokenBlacklistRepository.findByToken(token).isPresent()) {
            return true;
        }

        String sessionId = jwtTokenProvider.getSessionIdFromToken(token);
        if (sessionId == null || sessionId.isBlank()) {
            return true;
        }

        Optional<Session> sessionOpt = sessionRepository.findBySessionId(sessionId);
        if (sessionOpt.isEmpty()) {
            return true;
        }

        Session session = sessionOpt.get();
        return !Boolean.TRUE.equals(session.getIsActive())
                || session.getExpiresAt() == null
                || session.getExpiresAt().isBefore(Instant.now());
    }

    public void blacklistToken(String token) {
        if (token == null || token.isBlank()) {
            return;
        }

        Instant now = Instant.now();
        Instant expiresAt = getExpirationDateFromToken(token);

        tokenBlacklistRepository.findByToken(token).orElseGet(() -> {
            TokenBlacklist tokenBlacklist = new TokenBlacklist();
            tokenBlacklist.setToken(token);
            tokenBlacklist.setBlacklistedAt(now);
            tokenBlacklist.setExpiresAt(expiresAt != null ? expiresAt : now);
            return tokenBlacklistRepository.save(tokenBlacklist);
        });

        String sessionId = jwtTokenProvider.getSessionIdFromToken(token);
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }

        sessionRepository.findBySessionId(sessionId).ifPresent(session -> {
            session.setIsActive(false);
            session.setLogoutTimestamp(now);
            sessionRepository.save(session);
        });
    }

    public Instant getExpirationDateFromToken(String token) {
        return jwtTokenProvider.decodeToken(token).getExpiresAtAsInstant();
    }
}
