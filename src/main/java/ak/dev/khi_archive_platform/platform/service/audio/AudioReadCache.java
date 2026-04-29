package ak.dev.khi_archive_platform.platform.service.audio;

import ak.dev.khi_archive_platform.platform.dto.audio.AudioResponseDTO;
import ak.dev.khi_archive_platform.platform.repo.audio.AudioRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Read-side cache for active audios. See {@code ImageReadCache} for the
 * full contract — same algorithm, different entity.
 */
@Component
public class AudioReadCache {

    static final String ACTIVE_CACHE = "audios:all";

    private final AudioRepository audioRepository;
    private final ObjectProvider<AudioService> audioServiceProvider;

    public AudioReadCache(AudioRepository audioRepository,
                          ObjectProvider<AudioService> audioServiceProvider) {
        this.audioRepository = audioRepository;
        this.audioServiceProvider = audioServiceProvider;
    }

    @Cacheable(ACTIVE_CACHE)
    @Transactional(readOnly = true)
    public List<AudioResponseDTO> getAllActive() {
        AudioService audioService = audioServiceProvider.getObject();
        return audioRepository.findAllByRemovedAtIsNull().stream()
                .map(audioService::toResponse)
                .toList();
    }

    @CacheEvict(value = ACTIVE_CACHE, allEntries = true)
    public void evictAll() {
        // Evicts every entry; called after any audio mutation.
    }
}
