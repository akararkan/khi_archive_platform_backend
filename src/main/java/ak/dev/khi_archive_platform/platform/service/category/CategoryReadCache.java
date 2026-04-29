package ak.dev.khi_archive_platform.platform.service.category;

import ak.dev.khi_archive_platform.platform.dto.category.CategoryResponseDTO;
import ak.dev.khi_archive_platform.platform.repo.category.CategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Read-side cache for active categories. Cached as DTOs (not entities)
 * so Hibernate session state is irrelevant on cache hit.
 *
 * Mutation paths must call {@link #evictAll()} to keep the cache consistent.
 */
@Component
@RequiredArgsConstructor
public class CategoryReadCache {

    static final String ACTIVE_CACHE = "categories:all";

    private final CategoryRepository categoryRepository;

    @Cacheable(ACTIVE_CACHE)
    @Transactional(readOnly = true)
    public List<CategoryResponseDTO> getAllActive() {
        return categoryRepository.findAllActiveWithKeywords().stream()
                .map(CategoryMapper::toResponse)
                .toList();
    }

    @CacheEvict(value = ACTIVE_CACHE, allEntries = true)
    public void evictAll() {
        // Evict all entries; called after any category mutation.
    }
}
