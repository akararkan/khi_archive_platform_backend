package ak.dev.khi_archive_platform.platform.service.tag;

import ak.dev.khi_archive_platform.platform.dto.tag.TagSuggestionDTO;
import ak.dev.khi_archive_platform.platform.repo.tag.TagSuggestRepository;
import ak.dev.khi_archive_platform.platform.service.common.Tags;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Drives the {@code GET /api/tags/suggest} endpoint.
 *
 * <ul>
 *   <li>The public method runs the query through {@link Tags#canonicalOne}
 *       first so {@code "Sula"}, {@code "  SULA  "} and {@code "sula"} hit
 *       the same cache key and return the same answer.</li>
 *   <li>The lookup is delegated to a self-injected proxy so the
 *       {@code @Cacheable} annotation fires (Spring's AOP cache interception
 *       won't trigger on internal {@code this.…} calls).</li>
 *   <li>Caches per {@code (canonicalQ, limit)} pair — typing fast in the
 *       autocomplete is the dominant traffic pattern, and the underlying tag
 *       set changes slowly. Cache TTL is governed by the Redis cache
 *       manager's defaults; mutations evict the whole region via
 *       {@link #evictAll()}.</li>
 *   <li>Clamps {@code limit} into a sane range — autocomplete UIs rarely
 *       show more than 20 rows.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class TagSuggestService {

    public static final String CACHE = "tags:suggest";

    public static final int DEFAULT_LIMIT = 10;
    public static final int MAX_LIMIT = 25;
    public static final int MIN_QUERY_LEN = 1;

    private final TagSuggestRepository tagSuggestRepository;
    private final ObjectProvider<TagSuggestService> selfProvider;

    /** Public entry point — canonicalises the query before consulting the cache. */
    public List<TagSuggestionDTO> suggest(String q, Integer limit) {
        String canonical = Tags.canonicalOne(q);
        if (canonical == null || canonical.length() < MIN_QUERY_LEN) {
            return List.of();
        }
        int effective = (limit == null || limit <= 0)
                ? DEFAULT_LIMIT
                : Math.min(limit, MAX_LIMIT);
        // Go through the Spring proxy so @Cacheable triggers.
        return selfProvider.getObject().lookup(canonical, effective);
    }

    @Cacheable(value = CACHE, key = "T(java.util.Objects).hash(#canonical, #limit)")
    @Transactional(readOnly = true)
    public List<TagSuggestionDTO> lookup(String canonical, int limit) {
        return tagSuggestRepository.suggest(canonical, limit);
    }

    /**
     * Evict every cached suggestion. Called from any service that mutates
     * tags (audio/video/image/text/project save / delete / restore) so the
     * autocomplete reflects the change without waiting for TTL expiry.
     */
    @CacheEvict(value = CACHE, allEntries = true)
    public void evictAll() {
        // No body — annotation does the work.
    }
}
