package ak.dev.khi_archive_platform.platform.service.video;

import ak.dev.khi_archive_platform.platform.dto.video.VideoResponseDTO;
import ak.dev.khi_archive_platform.platform.repo.video.VideoRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Read-side cache for active videos. See {@code ImageReadCache} for the
 * full contract — same algorithm, different entity.
 */
@Component
public class VideoReadCache {

    static final String ACTIVE_CACHE = "videos:all";

    private final VideoRepository videoRepository;
    private final ObjectProvider<VideoService> videoServiceProvider;

    public VideoReadCache(VideoRepository videoRepository,
                          ObjectProvider<VideoService> videoServiceProvider) {
        this.videoRepository = videoRepository;
        this.videoServiceProvider = videoServiceProvider;
    }

    @Cacheable(ACTIVE_CACHE)
    @Transactional(readOnly = true)
    public List<VideoResponseDTO> getAllActive() {
        VideoService videoService = videoServiceProvider.getObject();
        return videoRepository.findAllByRemovedAtIsNull().stream()
                .map(videoService::toResponse)
                .toList();
    }

    @CacheEvict(value = ACTIVE_CACHE, allEntries = true)
    public void evictAll() {
        // Evicts every entry; called after any video mutation.
    }
}
