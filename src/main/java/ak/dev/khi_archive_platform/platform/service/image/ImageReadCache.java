package ak.dev.khi_archive_platform.platform.service.image;

import ak.dev.khi_archive_platform.platform.dto.image.ImageResponseDTO;
import ak.dev.khi_archive_platform.platform.repo.image.ImageRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Read-side cache for active images. Cached as DTOs (not entities) so the
 * Hibernate session is irrelevant on cache hit. Mutation paths in
 * {@link ImageService} call {@link #evictAll()} to keep it consistent.
 *
 * <p>On miss, one main query loads images; lazy fields (project + person +
 * categories, plus subjects/genres/colors/usages/tags/keywords) are loaded
 * via Hibernate's {@code default_batch_fetch_size=1000} — no N+1.
 *
 * <p>{@link ImageService} is resolved through an {@link ObjectProvider} to
 * break the cache↔service↔cache cycle at construction time.
 */
@Component
public class ImageReadCache {

    static final String ACTIVE_CACHE = "images:all";

    private final ImageRepository imageRepository;
    private final ObjectProvider<ImageService> imageServiceProvider;

    public ImageReadCache(ImageRepository imageRepository,
                          ObjectProvider<ImageService> imageServiceProvider) {
        this.imageRepository = imageRepository;
        this.imageServiceProvider = imageServiceProvider;
    }

    @Cacheable(ACTIVE_CACHE)
    @Transactional(readOnly = true)
    public List<ImageResponseDTO> getAllActive() {
        ImageService imageService = imageServiceProvider.getObject();
        return imageRepository.findAllByRemovedAtIsNull().stream()
                .map(imageService::toResponse)
                .toList();
    }

    @CacheEvict(value = ACTIVE_CACHE, allEntries = true)
    public void evictAll() {
        // Evicts every entry; called after any image mutation.
    }
}
