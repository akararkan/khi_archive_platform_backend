package ak.dev.khi_archive_platform.platform.service.keyword;

import ak.dev.khi_archive_platform.platform.dto.keyword.KeywordSuggestionDTO;
import ak.dev.khi_archive_platform.platform.repo.keyword.KeywordSuggestRepository;
import ak.dev.khi_archive_platform.platform.service.common.Keywords;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Drives the {@code GET /api/keywords/suggest} endpoint.
 *
 * <p>Mirror of
 * {@link ak.dev.khi_archive_platform.platform.service.tag.TagSuggestService} —
 * canonicalises the query (so {@code "Folk music"} / {@code "folk music"} /
 * {@code "  folk  music  "} share a cache key), delegates through a Spring
 * proxy so {@code @Cacheable} fires, clamps the limit.</p>
 */
@Service
@RequiredArgsConstructor
public class KeywordSuggestService {

    public static final String CACHE = "keywords:suggest";

    public static final int DEFAULT_LIMIT = 10;
    public static final int MAX_LIMIT = 25;
    public static final int MIN_QUERY_LEN = 1;

    private final KeywordSuggestRepository keywordSuggestRepository;
    private final ObjectProvider<KeywordSuggestService> selfProvider;

    /** Public entry point — canonicalises the query before consulting the cache. */
    public List<KeywordSuggestionDTO> suggest(String q, Integer limit) {
        String canonical = Keywords.canonicalOne(q);
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
    public List<KeywordSuggestionDTO> lookup(String canonical, int limit) {
        return keywordSuggestRepository.suggest(canonical, limit);
    }

    /**
     * Evict every cached suggestion. Called from any service that mutates
     * keywords (audio/video/image/text/project/category save/delete/restore)
     * so the autocomplete reflects the change without waiting for TTL expiry.
     */
    @CacheEvict(value = CACHE, allEntries = true)
    public void evictAll() {
        // No body — annotation does the work.
    }
}
