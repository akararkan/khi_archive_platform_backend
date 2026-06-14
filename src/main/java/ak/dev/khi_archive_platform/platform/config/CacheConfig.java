package ak.dev.khi_archive_platform.platform.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * In-process Caffeine cache replacing Redis.
 *
 * Caffeine uses the W-TinyLFU eviction algorithm — near-optimal hit rate
 * with O(1) reads and zero network latency (JVM heap only).
 *
 * Each cache is tuned individually:
 *   - "all-items" caches (categories, audios, …) hold ONE entry (the full
 *     active list) so maximumSize=1 is correct — eviction never fires in
 *     practice; TTL keeps data fresh after mutations.
 *   - Autocomplete (tags, keywords) hold one entry per (query, limit) pair;
 *     capped at 1 000 entries, 10-minute TTL.
 *   - Analytics caches hold per-(user/filter) results; shorter TTL so the
 *     dashboard stays accurate without a manual refresh.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        SimpleCacheManager manager = new SimpleCacheManager();
        manager.setCaches(List.of(
                // ── Entity list caches (one entry each) ──────────────────────
                build("categories:all",   1,    10),
                build("audios:all",       1,    10),
                build("images:all",       1,    10),
                build("videos:all",       1,    10),
                build("texts:all",        1,    10),
                build("projects:all",     1,    10),
                build("persons:all",      1,    10),

                // ── Autocomplete suggestion caches ───────────────────────────
                build("tags:suggest",     1_000, 10),
                build("keywords:suggest", 1_000, 10),

                // ── Analytics caches ─────────────────────────────────────────
                build("analytics:user.v2",     200, 5),
                build("analytics:overview.v2",  50, 5),
                build("analytics:users.v2",     50, 5),

                // ── Auth caches (eliminate per-request DB queries) ────────────
                // UserDetails: cached 1 min so permission grants take effect quickly.
                build("users:details", 500, 1),

                // ── Trending (recomputed at most every 5 minutes) ─────────────
                build("trending:results",  1, 5),
                build("trending:snapshot", 1, 5)
        ));
        return manager;
    }

    private static CaffeineCache build(String name, long maxSize, long ttlMinutes) {
        return new CaffeineCache(name,
                Caffeine.newBuilder()
                        .maximumSize(maxSize)
                        .expireAfterWrite(ttlMinutes, TimeUnit.MINUTES)
                        .build());
    }
}
