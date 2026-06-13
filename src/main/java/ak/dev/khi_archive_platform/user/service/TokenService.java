package ak.dev.khi_archive_platform.user.service;

import ak.dev.khi_archive_platform.user.jwt.JwtTokenProvider;
import ak.dev.khi_archive_platform.user.model.Session;
import ak.dev.khi_archive_platform.user.model.TokenBlacklist;
import ak.dev.khi_archive_platform.user.repo.SessionRepository;
import ak.dev.khi_archive_platform.user.repo.TokenBlacklistRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class TokenService {

    private final TokenBlacklistRepository tokenBlacklistRepository;
    private final SessionRepository sessionRepository;
    private final JwtTokenProvider jwtTokenProvider;

    // In-memory token validity cache.
    // Key   = raw JWT string
    // Value = true  → token is BLACKLISTED (invalid)
    //         false → token is VALID (not blacklisted)
    //
    // Valid tokens are cached for 2 minutes so DB is skipped on repeat requests.
    // Blacklisted tokens (logout) are cached as `true` immediately and never
    // expire early — they stay until natural TTL (2 min) then fall off,
    // at which point the DB blacklist is the authoritative source.
    private final Cache<String, Boolean> tokenValidityCache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(2, TimeUnit.MINUTES)
            .build();

    public boolean isTokenBlacklisted(String token) {
        if (token == null || token.isBlank()) {
            return true;
        }

        // Fast path: cache hit — skip both DB queries entirely.
        Boolean cached = tokenValidityCache.getIfPresent(token);
        if (cached != null) {
            return cached;
        }

        // Slow path: check DB, then warm the cache.
        boolean blacklisted = checkBlacklistedInDb(token);
        tokenValidityCache.put(token, blacklisted);
        return blacklisted;
    }

    public void blacklistToken(String token) {
        if (token == null || token.isBlank()) {
            return;
        }

        // Mark invalid in cache immediately so the very next request sees it.
        tokenValidityCache.put(token, true);

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

    private boolean checkBlacklistedInDb(String token) {
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
}
