package ak.dev.khi_archive_platform.platform.service.project;

import ak.dev.khi_archive_platform.platform.dto.project.ProjectCreateRequestDTO;
import ak.dev.khi_archive_platform.platform.dto.project.ProjectResponseDTO;
import ak.dev.khi_archive_platform.platform.dto.project.ProjectUpdateRequestDTO;
import ak.dev.khi_archive_platform.platform.enums.ProjectAuditAction;
import ak.dev.khi_archive_platform.platform.exceptions.*;
import ak.dev.khi_archive_platform.platform.model.category.Category;
import ak.dev.khi_archive_platform.platform.model.person.Person;
import ak.dev.khi_archive_platform.platform.model.project.Project;
import ak.dev.khi_archive_platform.platform.repo.audio.AudioRepository;
import ak.dev.khi_archive_platform.platform.repo.category.CategoryRepository;
import ak.dev.khi_archive_platform.platform.repo.person.PersonRepository;
import ak.dev.khi_archive_platform.platform.repo.project.ProjectRepository;
import ak.dev.khi_archive_platform.platform.repo.video.VideoRepository;
import ak.dev.khi_archive_platform.platform.service.category.CategoryCodeHelper;
import ak.dev.khi_archive_platform.platform.service.common.PaginationSupport;
import ak.dev.khi_archive_platform.user.consts.ValidationPatterns;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final PersonRepository personRepository;
    private final CategoryRepository categoryRepository;
    private final AudioRepository audioRepository;
    private final VideoRepository videoRepository;
    private final ProjectAuditService auditService;
    private final ProjectReadCache readCache;

    public ProjectResponseDTO create(ProjectCreateRequestDTO dto,
                                     Authentication authentication,
                                     HttpServletRequest request) {
        if (dto == null) {
            throw new ProjectValidationException("Project payload is required");
        }
        if (dto.getCategoryCodes() == null || dto.getCategoryCodes().isEmpty()) {
            throw new ProjectValidationException("At least one category code is required");
        }

        List<Category> categories = resolveCategories(dto.getCategoryCodes());
        Person person = resolvePerson(dto.getPersonCode());

        String projectCode = generateProjectCode(person, categories);
        if (projectRepository.existsByProjectCodeAndRemovedAtIsNull(projectCode)) {
            throw new ProjectAlreadyExistsException("Project already exists with code: " + projectCode);
        }

        Project project = Project.builder()
                .projectCode(projectCode)
                .projectName(dto.getProjectName())
                .person(person)
                .categories(new ArrayList<>(categories))
                .description(dto.getDescription())
                .tags(dto.getTags() != null ? new ArrayList<>(dto.getTags()) : new ArrayList<>())
                .keywords(dto.getKeywords() != null ? new ArrayList<>(dto.getKeywords()) : new ArrayList<>())
                .build();

        touchCreateAudit(project, authentication);
        Project saved = projectRepository.save(project);
        readCache.evictAll();

        String categorySummary = categories.stream()
                .map(Category::getCategoryCode)
                .collect(Collectors.joining(", "));
        auditService.record(saved, ProjectAuditAction.CREATE, authentication, request,
                "Created project with code=" + saved.getProjectCode()
                        + " person=" + (person != null ? person.getPersonCode() : "UNTITLED")
                        + " categories=[" + categorySummary + "]");
        return toResponse(saved);
    }

    public record BulkCreateResult(int requested, int inserted, int skipped, long elapsedMs) {}

    /**
     * Bulk-create projects in a single transaction. Project codes are
     * auto-generated using an in-memory per-prefix counter so we don't issue a
     * count() per insert. Rows whose generated code already exists are skipped.
     * One audit row records the batch summary.
     */
    public BulkCreateResult createAll(List<ProjectCreateRequestDTO> dtos,
                                      Authentication authentication,
                                      HttpServletRequest request) {
        if (dtos == null || dtos.isEmpty()) {
            return new BulkCreateResult(0, 0, 0, 0);
        }
        long start = System.currentTimeMillis();
        Instant now = Instant.now();
        String actor = resolveActorUsername(authentication);

        // Prefix → next sequence number. Untitled projects share one counter;
        // person-coded projects each have their own.
        Map<String, Long> nextSeq = new HashMap<>();

        List<Project> toInsert = new ArrayList<>(dtos.size());
        int skipped = 0;
        for (ProjectCreateRequestDTO dto : dtos) {
            if (dto == null
                    || dto.getProjectName() == null || dto.getProjectName().isBlank()
                    || dto.getCategoryCodes() == null || dto.getCategoryCodes().isEmpty()) {
                skipped++;
                continue;
            }
            List<Category> categories;
            Person person;
            try {
                categories = resolveCategories(dto.getCategoryCodes());
                person = resolvePerson(dto.getPersonCode());
            } catch (Exception e) {
                skipped++;
                continue;
            }

            String prefix = person != null
                    ? person.getPersonCode().toUpperCase(Locale.ROOT)
                    : "UNTITLED";
            long seq = nextSeq.computeIfAbsent(prefix, p -> (person != null
                    ? projectRepository.countByPerson(person)
                    : projectRepository.countByPersonIsNull()) + 1L);
            String projectCode = prefix + "_PROJ_" + String.format(Locale.ROOT, "%06d", seq);
            nextSeq.put(prefix, seq + 1);

            if (projectRepository.existsByProjectCodeAndRemovedAtIsNull(projectCode)) {
                skipped++;
                continue;
            }

            Project project = Project.builder()
                    .projectCode(projectCode)
                    .projectName(dto.getProjectName())
                    .person(person)
                    .categories(new ArrayList<>(categories))
                    .description(dto.getDescription())
                    .tags(dto.getTags() != null ? new ArrayList<>(dto.getTags()) : new ArrayList<>())
                    .keywords(dto.getKeywords() != null ? new ArrayList<>(dto.getKeywords()) : new ArrayList<>())
                    .createdAt(now)
                    .updatedAt(now)
                    .createdBy(actor)
                    .updatedBy(actor)
                    .build();
            toInsert.add(project);
        }

        projectRepository.saveAll(toInsert);
        readCache.evictAll();

        long elapsed = System.currentTimeMillis() - start;
        auditService.record(null, ProjectAuditAction.CREATE, authentication, request,
                "Bulk created projects: requested=" + dtos.size()
                        + " inserted=" + toInsert.size()
                        + " skipped=" + skipped
                        + " elapsedMs=" + elapsed);
        return new BulkCreateResult(dtos.size(), toInsert.size(), skipped, elapsed);
    }

    /**
     * Fast path: served from Redis on cache hit. On miss, one query loads
     * active projects; collections are batched-fetched (no N+1) thanks to
     * Hibernate's {@code default_batch_fetch_size}, then mapped to DTOs and
     * cached for 10 minutes. Audit is always recorded (cache fronts the read
     * but not the audit row).
     */
    @Transactional(readOnly = true)
    public Page<ProjectResponseDTO> getAll(Pageable pageable, Authentication authentication, HttpServletRequest request) {
        List<ProjectResponseDTO> all = readCache.getAllActive();
        Page<ProjectResponseDTO> page = PaginationSupport.sliceList(all, pageable);
        auditService.record(null, ProjectAuditAction.LIST, authentication, request,
                "Listed active projects (page=" + page.getNumber()
                        + " size=" + page.getSize()
                        + " returned=" + page.getNumberOfElements()
                        + " total=" + page.getTotalElements() + ")");
        return page;
    }

    @Transactional(readOnly = true)
    public ProjectResponseDTO getByProjectCode(String projectCode,
                                               Authentication authentication,
                                               HttpServletRequest request) {
        String normalized = normalizeProjectCode(projectCode);
        Project project = projectRepository.findByProjectCodeAndRemovedAtIsNull(normalized)
                .orElseThrow(() -> new ProjectNotFoundException("Project not found: " + projectCode));
        auditService.record(project, ProjectAuditAction.READ, authentication, request,
                "Read project record");
        return toResponse(project);
    }

    public ProjectResponseDTO update(String projectCode,
                                     ProjectUpdateRequestDTO dto,
                                     Authentication authentication,
                                     HttpServletRequest request) {
        String normalized = normalizeProjectCode(projectCode);
        Project project = projectRepository.findByProjectCodeAndRemovedAtIsNull(normalized)
                .orElseThrow(() -> new ProjectNotFoundException("Project not found: " + projectCode));

        StringBuilder changes = new StringBuilder();
        if (dto.getProjectName() != null && !dto.getProjectName().equals(project.getProjectName())) {
            changes.append("projectName: ").append(project.getProjectName()).append(" -> ").append(dto.getProjectName()).append(" | ");
            project.setProjectName(dto.getProjectName());
        }
        if (dto.getDescription() != null && !dto.getDescription().equals(project.getDescription())) {
            changes.append("description changed | ");
            project.setDescription(dto.getDescription());
        }
        if (dto.getCategoryCodes() != null) {
            List<Category> newCategories = resolveCategories(dto.getCategoryCodes());
            List<String> oldCodes = project.getCategories().stream().map(Category::getCategoryCode).toList();
            List<String> newCodes = newCategories.stream().map(Category::getCategoryCode).toList();
            changes.append("categories: ").append(oldCodes).append(" -> ").append(newCodes).append(" | ");
            project.setCategories(new ArrayList<>(newCategories));
        }
        if (dto.getTags() != null) {
            changes.append("tags: ").append(project.getTags()).append(" -> ").append(dto.getTags()).append(" | ");
            project.setTags(new ArrayList<>(dto.getTags()));
        }
        if (dto.getKeywords() != null) {
            changes.append("keywords: ").append(project.getKeywords()).append(" -> ").append(dto.getKeywords()).append(" | ");
            project.setKeywords(new ArrayList<>(dto.getKeywords()));
        }

        touchUpdateAudit(project, authentication);
        Project saved = projectRepository.save(project);
        readCache.evictAll();
        String detail = changes.isEmpty()
                ? "Updated project (no field changes detected)"
                : "Updated project: " + trimTrailingSeparator(changes.toString());
        auditService.record(saved, ProjectAuditAction.UPDATE, authentication, request, detail);
        return toResponse(saved);
    }

    /**
     * Soft remove — marks the project as removed but keeps data in the database.
     */
    public void remove(String projectCode,
                       Authentication authentication,
                       HttpServletRequest request) {
        String normalized = normalizeProjectCode(projectCode);
        Project project = projectRepository.findByProjectCodeAndRemovedAtIsNull(normalized)
                .orElseThrow(() -> new ProjectNotFoundException("Project not found: " + projectCode));

        project.setRemovedAt(Instant.now());
        project.setRemovedBy(resolveActorUsername(authentication));
        Project saved = projectRepository.save(project);
        readCache.evictAll();
        auditService.record(saved, ProjectAuditAction.REMOVE, authentication, request,
                "Removed project (soft delete)");
    }

    /**
     * Hard delete — permanently removes the row from the database.
     * Restricted to ADMIN role (authority {@code project:delete}) only.
     */
    public void delete(String projectCode,
                       Authentication authentication,
                       HttpServletRequest request) {
        requireAdminRole(authentication);
        String normalized = normalizeProjectCode(projectCode);
        Project project = projectRepository.findByProjectCodeAndRemovedAtIsNull(normalized)
                .or(() -> projectRepository.findAll().stream()
                        .filter(p -> p.getProjectCode().equals(normalized))
                        .findFirst())
                .orElseThrow(() -> new ProjectNotFoundException("Project not found: " + projectCode));

        if (audioRepository.countByProject(project) > 0 || videoRepository.countByProject(project) > 0) {
            throw new ProjectInUseException("Project has associated media files and cannot be permanently deleted");
        }

        auditService.record(project, ProjectAuditAction.DELETE, authentication, request,
                "Permanently deleted project");
        projectRepository.delete(project);
        readCache.evictAll();
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    /**
     * Generates a unique project code with a sequence number.
     * <p>
     * One person can have many projects, so the code includes a sequence:
     * <ul>
     *   <li>Person project: PERSONCODE_PROJ_000001, PERSONCODE_PROJ_000002, ...</li>
     *   <li>Untitled project: UNTITLED_PROJ_000001, UNTITLED_PROJ_000002, ...</li>
     * </ul>
     */
    private String generateProjectCode(Person person, List<Category> categories) {
        String prefix = person != null
                ? person.getPersonCode().toUpperCase(Locale.ROOT)
                : "UNTITLED";

        long sequence = (person != null
                ? projectRepository.countByPerson(person)
                : projectRepository.countByPersonIsNull()) + 1;

        return prefix + "_PROJ_" + String.format(Locale.ROOT, "%06d", sequence);
    }

    private List<Category> resolveCategories(List<String> categoryCodes) {
        if (categoryCodes == null || categoryCodes.isEmpty()) {
            throw new ProjectValidationException("At least one category code is required");
        }
        List<Category> categories = new ArrayList<>();
        for (String code : categoryCodes) {
            String normalized = CategoryCodeHelper.normalizeAndValidate(code);
            Category category = categoryRepository.findByCategoryCodeAndRemovedAtIsNull(normalized)
                    .orElseThrow(() -> new CategoryNotFoundException("Category not found: " + code));
            categories.add(category);
        }
        return categories;
    }

    private Person resolvePerson(String personCode) {
        if (personCode == null || personCode.isBlank()) {
            return null;
        }
        String trimmed = personCode.trim();
        if (!trimmed.matches(ValidationPatterns.PERSON_CODE)) {
            throw new ProjectValidationException("Invalid person code format: " + trimmed);
        }
        return personRepository.findByPersonCodeAndRemovedAtIsNull(trimmed)
                .orElseThrow(() -> new PersonNotFoundException("Person not found: " + trimmed));
    }

    private String normalizeProjectCode(String projectCode) {
        if (projectCode == null || projectCode.isBlank()) {
            throw new ProjectValidationException("Project code is required");
        }
        return projectCode.trim();
    }

    private void touchCreateAudit(Project project, Authentication authentication) {
        Instant now = Instant.now();
        String actor = resolveActorUsername(authentication);
        project.setCreatedAt(now);
        project.setUpdatedAt(now);
        project.setCreatedBy(actor);
        project.setUpdatedBy(actor);
    }

    private void touchUpdateAudit(Project project, Authentication authentication) {
        project.setUpdatedAt(Instant.now());
        project.setUpdatedBy(resolveActorUsername(authentication));
    }

    private String resolveActorUsername(Authentication authentication) {
        return authentication == null ? "anonymous" : authentication.getName();
    }

    private void requireAdminRole(Authentication authentication) {
        if (authentication == null) {
            throw new AccessDeniedException("Authentication is required for this operation");
        }
        boolean canHardDelete = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("project:delete"::equals);
        if (!canHardDelete) {
            throw new AccessDeniedException("Only ADMIN can permanently delete project records");
        }
    }

    private ProjectResponseDTO toResponse(Project project) {
        List<ProjectResponseDTO.CategorySummary> categorySummaries = null;
        if (project.getCategories() != null) {
            categorySummaries = project.getCategories().stream()
                    .map(c -> ProjectResponseDTO.CategorySummary.builder()
                            .id(c.getId())
                            .categoryCode(c.getCategoryCode())
                            .categoryName(c.getName())
                            .build())
                    .toList();
        }

        return ProjectResponseDTO.builder()
                .id(project.getId())
                .projectCode(project.getProjectCode())
                .projectName(project.getProjectName())
                .personId(project.getPerson() != null ? project.getPerson().getId() : null)
                .personCode(project.getPerson() != null ? project.getPerson().getPersonCode() : null)
                .personName(project.getPerson() != null ? project.getPerson().getFullName() : null)
                .categories(categorySummaries)
                .description(project.getDescription())
                .tags(project.getTags() != null ? new ArrayList<>(project.getTags()) : null)
                .keywords(project.getKeywords() != null ? new ArrayList<>(project.getKeywords()) : null)
                .createdAt(project.getCreatedAt())
                .updatedAt(project.getUpdatedAt())
                .removedAt(project.getRemovedAt())
                .createdBy(project.getCreatedBy())
                .updatedBy(project.getUpdatedBy())
                .removedBy(project.getRemovedBy())
                .build();
    }

    private String trimTrailingSeparator(String value) {
        return value.endsWith(" | ") ? value.substring(0, value.length() - 3) : value;
    }
}
