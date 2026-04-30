package ak.dev.khi_archive_platform.platform.service.category;

import ak.dev.khi_archive_platform.platform.dto.category.CategoryCreateRequestDTO;
import ak.dev.khi_archive_platform.platform.dto.category.CategoryResponseDTO;
import ak.dev.khi_archive_platform.platform.dto.category.CategoryUpdateRequestDTO;
import ak.dev.khi_archive_platform.platform.enums.CategoryAuditAction;
import ak.dev.khi_archive_platform.platform.exceptions.CategoryAlreadyExistsException;
import ak.dev.khi_archive_platform.platform.exceptions.CategoryInUseException;
import ak.dev.khi_archive_platform.platform.exceptions.CategoryNotFoundException;
import ak.dev.khi_archive_platform.platform.model.category.Category;
import ak.dev.khi_archive_platform.platform.repo.category.CategoryRepository;
import ak.dev.khi_archive_platform.platform.repo.project.ProjectRepository;
import ak.dev.khi_archive_platform.platform.service.common.PaginationSupport;
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
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final ProjectRepository projectRepository;
    private final CategoryAuditService auditService;
    private final CategoryReadCache readCache;

    public CategoryResponseDTO create(CategoryCreateRequestDTO dto,
                                      Authentication authentication,
                                      HttpServletRequest request) {
        String categoryCode = CategoryCodeHelper.normalizeAndValidate(dto.getCategoryCode());

        if (categoryRepository.existsByCategoryCodeAndRemovedAtIsNull(categoryCode)) {
            throw new CategoryAlreadyExistsException("Category code already exists");
        }

        Category category = Category.builder()
                .categoryCode(categoryCode)
                .name(dto.getName())
                .description(dto.getDescription())
                .keywords(dto.getKeywords() != null ? new ArrayList<>(dto.getKeywords()) : new ArrayList<>())
                .build();

        touchCreateAudit(category, authentication);
        Category saved = categoryRepository.save(category);
        readCache.evictAll();
        auditService.record(saved, CategoryAuditAction.CREATE, authentication, request,
                "Created category with code=" + saved.getCategoryCode());
        return CategoryMapper.toResponse(saved);
    }

    public record BulkCreateResult(int requested, int inserted, int skippedDuplicates, long elapsedMs) {}

    /**
     * Bulk-insert categories in a single transaction with one cache eviction at the end.
     * Skips rows whose code already exists; one audit row records the batch summary.
     */
    public BulkCreateResult createAll(List<CategoryCreateRequestDTO> dtos,
                                      Authentication authentication,
                                      HttpServletRequest request) {
        if (dtos == null || dtos.isEmpty()) {
            return new BulkCreateResult(0, 0, 0, 0);
        }
        long start = System.currentTimeMillis();
        Instant now = Instant.now();
        String actor = resolveActorUsername(authentication);

        List<Category> toInsert = new ArrayList<>(dtos.size());
        int skipped = 0;
        for (CategoryCreateRequestDTO dto : dtos) {
            String code = CategoryCodeHelper.normalizeAndValidate(dto.getCategoryCode());
            if (categoryRepository.existsByCategoryCodeAndRemovedAtIsNull(code)) {
                skipped++;
                continue;
            }
            Category category = Category.builder()
                    .categoryCode(code)
                    .name(dto.getName())
                    .description(dto.getDescription())
                    .keywords(dto.getKeywords() != null ? new ArrayList<>(dto.getKeywords()) : new ArrayList<>())
                    .createdAt(now)
                    .updatedAt(now)
                    .createdBy(actor)
                    .updatedBy(actor)
                    .build();
            toInsert.add(category);
        }

        categoryRepository.saveAll(toInsert);
        readCache.evictAll();

        long elapsed = System.currentTimeMillis() - start;
        auditService.record(null, CategoryAuditAction.CREATE, authentication, request,
                "Bulk created categories: requested=" + dtos.size()
                        + " inserted=" + toInsert.size()
                        + " skippedDuplicates=" + skipped
                        + " elapsedMs=" + elapsed);
        return new BulkCreateResult(dtos.size(), toInsert.size(), skipped, elapsed);
    }

    /**
     * Fast path: served from Redis on cache hit; on miss, single JOIN FETCH query
     * loads categories + keywords without N+1, maps to DTOs, caches for 10 min.
     * Audit is always recorded (cache only fronts the read, not the audit).
     */
    @Transactional(readOnly = true)
    public Page<CategoryResponseDTO> getAll(Pageable pageable, Authentication authentication, HttpServletRequest request) {
        List<CategoryResponseDTO> all = readCache.getAllActive();
        Page<CategoryResponseDTO> page = PaginationSupport.sliceList(all, pageable);
        auditService.record(null, CategoryAuditAction.LIST, authentication, request,
                "Listed active categories (page=" + page.getNumber()
                        + " size=" + page.getSize()
                        + " returned=" + page.getNumberOfElements()
                        + " total=" + page.getTotalElements() + ")");
        return page;
    }

    private static final double SEARCH_SIMILARITY_THRESHOLD = 0.3;
    private static final int SEARCH_DEFAULT_LIMIT = 20;
    private static final int SEARCH_MAX_LIMIT = 100;

    /**
     * Typo-tolerant fuzzy search across name, description, code, and keywords.
     * Uses pg_trgm trigram similarity (GIN-indexed) — language-agnostic, fast.
     */
    @Transactional(readOnly = true)
    public List<CategoryResponseDTO> search(String query,
                                            Integer limit,
                                            Authentication authentication,
                                            HttpServletRequest request) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String normalized = query.trim();
        int effectiveLimit = (limit == null || limit <= 0)
                ? SEARCH_DEFAULT_LIMIT
                : Math.min(limit, SEARCH_MAX_LIMIT);

        List<CategoryResponseDTO> result = categoryRepository
                .searchByText(normalized, SEARCH_SIMILARITY_THRESHOLD, effectiveLimit)
                .stream()
                .map(CategoryMapper::toResponse)
                .toList();

        auditService.record(null, CategoryAuditAction.SEARCH, authentication, request,
                "Searched categories q=\"" + normalized + "\" limit=" + effectiveLimit + " hits=" + result.size());
        return result;
    }

    @Transactional(readOnly = true)
    public CategoryResponseDTO getByCategoryCode(String categoryCode,
                                                 Authentication authentication,
                                                 HttpServletRequest request) {
        String normalizedCategoryCode = CategoryCodeHelper.normalizeAndValidate(categoryCode);
        Category category = categoryRepository.findByCategoryCodeAndRemovedAtIsNull(normalizedCategoryCode)
                .orElseThrow(() -> new CategoryNotFoundException("Category not found: " + categoryCode));
        auditService.record(category, CategoryAuditAction.READ, authentication, request,
                "Read category");
        return CategoryMapper.toResponse(category);
    }

    public CategoryResponseDTO update(String categoryCode,
                                      CategoryUpdateRequestDTO dto,
                                      Authentication authentication,
                                      HttpServletRequest request) {
        String normalizedCategoryCode = CategoryCodeHelper.normalizeAndValidate(categoryCode);
        Category category = categoryRepository.findByCategoryCodeAndRemovedAtIsNull(normalizedCategoryCode)
                .orElseThrow(() -> new CategoryNotFoundException("Category not found: " + categoryCode));

        StringBuilder changes = new StringBuilder();
        if (dto.getName() != null && !dto.getName().equals(category.getName())) {
            changes.append("name: ").append(category.getName()).append(" -> ").append(dto.getName()).append(" | ");
            category.setName(dto.getName());
        }
        if (dto.getDescription() != null && !dto.getDescription().equals(category.getDescription())) {
            changes.append("description changed | ");
            category.setDescription(dto.getDescription());
        }
        if (dto.getKeywords() != null) {
            changes.append("keywords: ").append(category.getKeywords()).append(" -> ").append(dto.getKeywords()).append(" | ");
            category.setKeywords(new ArrayList<>(dto.getKeywords()));
        }

        touchUpdateAudit(category, authentication);
        Category saved = categoryRepository.save(category);
        readCache.evictAll();
        auditService.record(saved, CategoryAuditAction.UPDATE, authentication, request,
                changes.isEmpty() ? "Updated category (no field changes detected)" : "Updated category: " + trimTrailingSeparator(changes.toString()));
        return CategoryMapper.toResponse(saved);
    }

    /**
     * Soft delete (trash). Blocks if any active project still references this
     * category — projects must be retargeted or trashed first to avoid dangling
     * references in the trashable graph.
     */
    public void delete(String categoryCode,
                       Authentication authentication,
                       HttpServletRequest request) {
        String normalizedCategoryCode = CategoryCodeHelper.normalizeAndValidate(categoryCode);
        Category category = categoryRepository.findByCategoryCodeAndRemovedAtIsNull(normalizedCategoryCode)
                .orElseThrow(() -> new CategoryNotFoundException("Category not found: " + categoryCode));

        if (projectRepository.existsByCategoryAndRemovedAtIsNull(category)) {
            throw new CategoryInUseException("Category is used by active projects and cannot be sent to trash");
        }

        category.setRemovedAt(Instant.now());
        category.setRemovedBy(resolveActorUsername(authentication));
        Category saved = categoryRepository.save(category);
        readCache.evictAll();
        auditService.record(saved, CategoryAuditAction.DELETE, authentication, request,
                "Sent category to trash");
    }

    /**
     * Restore a category from trash. Admin-only.
     */
    public CategoryResponseDTO restore(String categoryCode,
                                       Authentication authentication,
                                       HttpServletRequest request) {
        requireAdminRole(authentication);
        String normalizedCategoryCode = CategoryCodeHelper.normalizeAndValidate(categoryCode);
        Category category = categoryRepository.findByCategoryCode(normalizedCategoryCode)
                .orElseThrow(() -> new CategoryNotFoundException("Category not found: " + categoryCode));

        if (category.getRemovedAt() == null) {
            throw new CategoryNotFoundException("Category is not in trash: " + categoryCode);
        }
        if (categoryRepository.existsByCategoryCodeAndRemovedAtIsNull(normalizedCategoryCode)) {
            throw new CategoryAlreadyExistsException("An active category with this code already exists: " + categoryCode);
        }

        category.setRemovedAt(null);
        category.setRemovedBy(null);
        category.setUpdatedAt(Instant.now());
        category.setUpdatedBy(resolveActorUsername(authentication));
        Category saved = categoryRepository.save(category);
        readCache.evictAll();
        auditService.record(saved, CategoryAuditAction.RESTORE, authentication, request,
                "Restored category from trash");
        return CategoryMapper.toResponse(saved);
    }

    /**
     * Permanently delete a category from the trash. Admin-only. The category
     * must already be in trash, and no project (active or trashed) may still
     * reference it — purge those projects first to keep the project_categories
     * join table consistent.
     */
    public void purge(String categoryCode,
                      Authentication authentication,
                      HttpServletRequest request) {
        requireAdminRole(authentication);
        String normalizedCategoryCode = CategoryCodeHelper.normalizeAndValidate(categoryCode);
        Category category = categoryRepository.findByCategoryCode(normalizedCategoryCode)
                .orElseThrow(() -> new CategoryNotFoundException("Category not found: " + categoryCode));

        if (category.getRemovedAt() == null) {
            throw new CategoryNotFoundException(
                    "Category must be in trash before permanent deletion. Trash it first.");
        }
        if (projectRepository.existsByCategory(category)) {
            throw new CategoryInUseException(
                    "Category is still referenced by projects (active or trashed). Purge those projects first.");
        }

        auditService.record(category, CategoryAuditAction.PURGE, authentication, request,
                "Permanently deleted category from trash");
        categoryRepository.delete(category);
        readCache.evictAll();
    }

    /**
     * List categories in the trash. Admin-only.
     */
    @Transactional(readOnly = true)
    public Page<CategoryResponseDTO> getTrash(Pageable pageable,
                                              Authentication authentication,
                                              HttpServletRequest request) {
        requireAdminRole(authentication);
        List<CategoryResponseDTO> all = categoryRepository.findAllByRemovedAtIsNotNull().stream()
                .map(CategoryMapper::toResponse)
                .toList();
        Page<CategoryResponseDTO> page = PaginationSupport.sliceList(all, pageable);
        auditService.record(null, CategoryAuditAction.LIST, authentication, request,
                "Listed category trash (page=" + page.getNumber()
                        + " size=" + page.getSize()
                        + " returned=" + page.getNumberOfElements()
                        + " total=" + page.getTotalElements() + ")");
        return page;
    }

    private void touchCreateAudit(Category category, Authentication authentication) {
        Instant now = Instant.now();
        String actor = resolveActorUsername(authentication);
        category.setCreatedAt(now);
        category.setUpdatedAt(now);
        category.setCreatedBy(actor);
        category.setUpdatedBy(actor);
    }

    private void touchUpdateAudit(Category category, Authentication authentication) {
        category.setUpdatedAt(Instant.now());
        category.setUpdatedBy(resolveActorUsername(authentication));
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
                .anyMatch("category:delete"::equals);
        if (!canHardDelete) {
            throw new AccessDeniedException("Only ADMIN can permanently delete category records");
        }
    }

    private String trimTrailingSeparator(String value) {
        return value.endsWith(" | ") ? value.substring(0, value.length() - 3) : value;
    }
}
