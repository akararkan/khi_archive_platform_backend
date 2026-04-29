package ak.dev.khi_archive_platform.platform.service.text;

import ak.dev.khi_archive_platform.platform.dto.text.TextResponseDTO;
import ak.dev.khi_archive_platform.platform.repo.text.TextRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Read-side cache for active text records. See {@code ImageReadCache} for the
 * full contract — same algorithm, different entity.
 */
@Component
public class TextReadCache {

    static final String ACTIVE_CACHE = "texts:all";

    private final TextRepository textRepository;
    private final ObjectProvider<TextService> textServiceProvider;

    public TextReadCache(TextRepository textRepository,
                         ObjectProvider<TextService> textServiceProvider) {
        this.textRepository = textRepository;
        this.textServiceProvider = textServiceProvider;
    }

    @Cacheable(ACTIVE_CACHE)
    @Transactional(readOnly = true)
    public List<TextResponseDTO> getAllActive() {
        TextService textService = textServiceProvider.getObject();
        return textRepository.findAllByRemovedAtIsNull().stream()
                .map(textService::toResponse)
                .toList();
    }

    @CacheEvict(value = ACTIVE_CACHE, allEntries = true)
    public void evictAll() {
        // Evicts every entry; called after any text mutation.
    }
}
