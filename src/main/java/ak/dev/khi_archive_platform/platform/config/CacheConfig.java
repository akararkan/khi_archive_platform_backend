package ak.dev.khi_archive_platform.platform.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;

/**
 * Enables Spring's caching abstraction. Spring Boot auto-configures a
 * RedisCacheManager from `spring.cache.*` and `spring.data.redis.*` settings
 * (10-minute TTL, JDK serialization). Cached values must be Serializable.
 */
@Configuration
@EnableCaching
public class CacheConfig {
}
