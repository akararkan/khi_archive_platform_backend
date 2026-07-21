package ak.dev.khi_archive_platform.platform.service.analytics;

import ak.dev.khi_archive_platform.platform.dto.analytics.InventoryStatsDTO;
import ak.dev.khi_archive_platform.platform.dto.analytics.InventoryTypeCountDTO;
import ak.dev.khi_archive_platform.platform.dto.analytics.VisibilityStatsDTO;
import ak.dev.khi_archive_platform.platform.dto.analytics.VisibilityTypeCountDTO;
import ak.dev.khi_archive_platform.platform.repo.audio.AudioRepository;
import ak.dev.khi_archive_platform.platform.repo.category.CategoryRepository;
import ak.dev.khi_archive_platform.platform.repo.image.ImageRepository;
import ak.dev.khi_archive_platform.platform.repo.maqam.ListOfMaqamRepository;
import ak.dev.khi_archive_platform.platform.repo.person.PersonRepository;
import ak.dev.khi_archive_platform.platform.repo.physicalmedia.PhysicalMediaRepository;
import ak.dev.khi_archive_platform.platform.repo.project.ProjectRepository;
import ak.dev.khi_archive_platform.platform.repo.text.TextRepository;
import ak.dev.khi_archive_platform.platform.repo.video.VideoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Live-inventory and visibility statistics. Unlike {@link AnalyticsService}
 * (which aggregates the {@code *_audit_logs} activity trail), this service
 * counts rows in the operational tables themselves, so the numbers reflect the
 * current state of the archive — not what happened in a date window.
 *
 * <p>Every count is a cheap {@code SELECT COUNT(*)} against an indexed
 * {@code removed_at} / flag column, so the whole snapshot is a handful of
 * counting queries. Results are computed live (no cache) — they change on every
 * create / trash / restore / visibility toggle and are only ever read by admins.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InventoryAnalyticsService {

    private final AudioRepository audioRepository;
    private final VideoRepository videoRepository;
    private final ImageRepository imageRepository;
    private final TextRepository textRepository;
    private final ListOfMaqamRepository maqamRepository;
    private final PhysicalMediaRepository physicalMediaRepository;
    private final ProjectRepository projectRepository;
    private final PersonRepository personRepository;
    private final CategoryRepository categoryRepository;

    /**
     * Total / active / trashed counts per item type. Keys mirror
     * {@code AnalyticsService.ENTITY_KEYS}: audio, video, image, text, maqam,
     * physical_media, project, person, category.
     */
    public InventoryStatsDTO getInventory() {
        Map<String, InventoryTypeCountDTO> byType = new LinkedHashMap<>();

        byType.put("audio", typeCount(audioRepository.countByRemovedAtIsNull(), audioRepository.countByRemovedAtIsNotNull()));
        byType.put("video", typeCount(videoRepository.countByRemovedAtIsNull(), videoRepository.countByRemovedAtIsNotNull()));
        byType.put("image", typeCount(imageRepository.countByRemovedAtIsNull(), imageRepository.countByRemovedAtIsNotNull()));
        byType.put("text", typeCount(textRepository.countByRemovedAtIsNull(), textRepository.countByRemovedAtIsNotNull()));
        byType.put("maqam", typeCount(maqamRepository.countByRemovedAtIsNull(), maqamRepository.countByRemovedAtIsNotNull()));
        byType.put("physical_media", typeCount(physicalMediaRepository.countByRemovedAtIsNull(), physicalMediaRepository.countByRemovedAtIsNotNull()));
        byType.put("project", typeCount(projectRepository.countByRemovedAtIsNull(), projectRepository.countByRemovedAtIsNotNull()));
        byType.put("person", typeCount(personRepository.countByRemovedAtIsNull(), personRepository.countByRemovedAtIsNotNull()));
        byType.put("category", typeCount(categoryRepository.countByRemovedAtIsNull(), categoryRepository.countByRemovedAtIsNotNull()));

        long totalActive = 0;
        long totalTrashed = 0;
        for (InventoryTypeCountDTO c : byType.values()) {
            totalActive += c.getActive();
            totalTrashed += c.getTrashed();
        }

        return InventoryStatsDTO.builder()
                .byType(byType)
                .totalActive(totalActive)
                .totalTrashed(totalTrashed)
                .grandTotal(totalActive + totalTrashed)
                .generatedAt(Instant.now())
                .build();
    }

    /**
     * Public-vs-hidden visibility across projects and the four media types,
     * plus how many active items live inside hidden projects.
     */
    public VisibilityStatsDTO getVisibility() {
        Map<String, VisibilityTypeCountDTO> mediaByType = new LinkedHashMap<>();
        mediaByType.put("audio", visCount(audioRepository.countActivePublic(), audioRepository.countActivePrivate()));
        mediaByType.put("video", visCount(videoRepository.countActivePublic(), videoRepository.countActivePrivate()));
        mediaByType.put("image", visCount(imageRepository.countActivePublic(), imageRepository.countActivePrivate()));
        mediaByType.put("text", visCount(textRepository.countActivePublic(), textRepository.countActivePrivate()));

        long mediaPublicTotal = 0;
        long mediaPrivateTotal = 0;
        for (VisibilityTypeCountDTO c : mediaByType.values()) {
            mediaPublicTotal += c.getPublicCount();
            mediaPrivateTotal += c.getPrivateCount();
        }

        long itemsInVisibleProjects =
                audioRepository.countActiveInVisibleProjects()
                        + videoRepository.countActiveInVisibleProjects()
                        + imageRepository.countActiveInVisibleProjects()
                        + textRepository.countActiveInVisibleProjects();
        long itemsInHiddenProjects =
                audioRepository.countActiveInHiddenProjects()
                        + videoRepository.countActiveInHiddenProjects()
                        + imageRepository.countActiveInHiddenProjects()
                        + textRepository.countActiveInHiddenProjects();

        long projectsVisible = projectRepository.countActiveVisible();
        long projectsHidden = projectRepository.countActiveHidden();

        return VisibilityStatsDTO.builder()
                .projectsVisible(projectsVisible)
                .projectsHidden(projectsHidden)
                .projectsTotal(projectsVisible + projectsHidden)
                .mediaByType(mediaByType)
                .mediaPublicTotal(mediaPublicTotal)
                .mediaPrivateTotal(mediaPrivateTotal)
                .itemsInVisibleProjects(itemsInVisibleProjects)
                .itemsInHiddenProjects(itemsInHiddenProjects)
                .generatedAt(Instant.now())
                .build();
    }

    private static InventoryTypeCountDTO typeCount(long active, long trashed) {
        return InventoryTypeCountDTO.builder()
                .active(active)
                .trashed(trashed)
                .total(active + trashed)
                .build();
    }

    private static VisibilityTypeCountDTO visCount(long publicCount, long privateCount) {
        return VisibilityTypeCountDTO.builder()
                .publicCount(publicCount)
                .privateCount(privateCount)
                .total(publicCount + privateCount)
                .build();
    }
}
