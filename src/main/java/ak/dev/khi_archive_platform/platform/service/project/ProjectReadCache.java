package ak.dev.khi_archive_platform.platform.service.project;

import ak.dev.khi_archive_platform.platform.dto.project.ProjectResponseDTO;
import ak.dev.khi_archive_platform.platform.model.category.Category;
import ak.dev.khi_archive_platform.platform.model.project.Project;
import ak.dev.khi_archive_platform.platform.repo.project.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Read-side cache for active projects. Cached as DTOs (not entities) so the
 * Hibernate session is irrelevant on cache hit. Mutation paths in
 * {@link ProjectService} call {@link #evictAll()} to keep it consistent.
 *
 * <p>On miss, one main query loads projects; lazy collections (categories,
 * tags, keywords, person, person.personType) are loaded via
 * {@code hibernate.default_batch_fetch_size=1000}, so for 1000 projects there
 * are at most ~5 small secondary queries — no N+1.
 */
@Component
@RequiredArgsConstructor
public class ProjectReadCache {

    static final String ACTIVE_CACHE = "projects:all";

    private final ProjectRepository projectRepository;

    @Cacheable(ACTIVE_CACHE)
    @Transactional(readOnly = true)
    public List<ProjectResponseDTO> getAllActive() {
        return projectRepository.findAllActive().stream()
                .map(ProjectReadCache::toResponse)
                .toList();
    }

    @CacheEvict(value = ACTIVE_CACHE, allEntries = true)
    public void evictAll() {
        // Evicts every entry; called after any project mutation.
    }

    static ProjectResponseDTO toResponse(Project p) {
        List<ProjectResponseDTO.CategorySummary> categorySummaries = null;
        if (p.getCategories() != null) {
            categorySummaries = new ArrayList<>(p.getCategories().size());
            for (Category c : p.getCategories()) {
                categorySummaries.add(ProjectResponseDTO.CategorySummary.builder()
                        .id(c.getId())
                        .categoryCode(c.getCategoryCode())
                        .categoryName(c.getName())
                        .build());
            }
        }
        return ProjectResponseDTO.builder()
                .id(p.getId())
                .projectCode(p.getProjectCode())
                .projectName(p.getProjectName())
                .personId(p.getPerson() != null ? p.getPerson().getId() : null)
                .personCode(p.getPerson() != null ? p.getPerson().getPersonCode() : null)
                .personName(p.getPerson() != null ? p.getPerson().getFullName() : null)
                .categories(categorySummaries)
                .description(p.getDescription())
                .tags(p.getTags() != null ? new ArrayList<>(p.getTags()) : null)
                .keywords(p.getKeywords() != null ? new ArrayList<>(p.getKeywords()) : null)
                .createdAt(p.getCreatedAt())
                .updatedAt(p.getUpdatedAt())
                .removedAt(p.getRemovedAt())
                .createdBy(p.getCreatedBy())
                .updatedBy(p.getUpdatedBy())
                .removedBy(p.getRemovedBy())
                .build();
    }
}
