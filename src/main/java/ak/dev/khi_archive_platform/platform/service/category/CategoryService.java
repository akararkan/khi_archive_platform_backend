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
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
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
        auditService.record(saved, CategoryAuditAction.CREATE, authentication, request,
                "Created category with code=" + saved.getCategoryCode());
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<CategoryResponseDTO> getAll(Authentication authentication, HttpServletRequest request) {
        List<CategoryResponseDTO> result = categoryRepository.findAllByRemovedAtIsNull().stream()
                .map(this::toResponse)
                .toList();
        auditService.record(null, CategoryAuditAction.LIST, authentication, request,
                "Listed active categories");
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
        return toResponse(category);
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
        auditService.record(saved, CategoryAuditAction.UPDATE, authentication, request,
                changes.isEmpty() ? "Updated category (no field changes detected)" : "Updated category: " + trimTrailingSeparator(changes.toString()));
        return toResponse(saved);
    }

    /**
     * Soft remove — marks the category as removed but keeps data in the database.
     */
    public void remove(String categoryCode,
                       Authentication authentication,
                       HttpServletRequest request) {
        String normalizedCategoryCode = CategoryCodeHelper.normalizeAndValidate(categoryCode);
        Category category = categoryRepository.findByCategoryCodeAndRemovedAtIsNull(normalizedCategoryCode)
                .orElseThrow(() -> new CategoryNotFoundException("Category not found: " + categoryCode));

        if (projectRepository.existsByCategoryAndRemovedAtIsNull(category)) {
            throw new CategoryInUseException("Category is used by active projects and cannot be removed");
        }

        category.setRemovedAt(Instant.now());
        category.setRemovedBy(resolveActorUsername(authentication));
        Category saved = categoryRepository.save(category);
        auditService.record(saved, CategoryAuditAction.REMOVE, authentication, request,
                "Removed category (soft delete)");
    }

    /**
     * Hard delete — permanently removes the row from the database.
     * Restricted to ADMIN and SUPER_ADMIN roles only.
     */
    public void delete(String categoryCode,
                       Authentication authentication,
                       HttpServletRequest request) {
        requireAdminRole(authentication);
        String normalizedCategoryCode = CategoryCodeHelper.normalizeAndValidate(categoryCode);
        Category category = categoryRepository.findByCategoryCodeAndRemovedAtIsNull(normalizedCategoryCode)
                .or(() -> categoryRepository.findAll().stream()
                        .filter(c -> c.getCategoryCode().equals(normalizedCategoryCode))
                        .findFirst())
                .orElseThrow(() -> new CategoryNotFoundException("Category not found: " + categoryCode));

        if (projectRepository.existsByCategoryAndRemovedAtIsNull(category)) {
            throw new CategoryInUseException("Category is used by active projects and cannot be permanently deleted");
        }

        auditService.record(category, CategoryAuditAction.DELETE, authentication, request,
                "Permanently deleted category");
        categoryRepository.delete(category);
    }

    private CategoryResponseDTO toResponse(Category category) {
        return CategoryResponseDTO.builder()
                .id(category.getId())
                .categoryCode(category.getCategoryCode())
                .name(category.getName())
                .description(category.getDescription())
                .keywords(category.getKeywords() != null ? new ArrayList<>(category.getKeywords()) : null)
                .createdAt(category.getCreatedAt())
                .updatedAt(category.getUpdatedAt())
                .removedAt(category.getRemovedAt())
                .createdBy(category.getCreatedBy())
                .updatedBy(category.getUpdatedBy())
                .removedBy(category.getRemovedBy())
                .build();
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
        boolean isAdmin = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> "ROLE_ADMIN".equals(a) || "ROLE_SUPER_ADMIN".equals(a));
        if (!isAdmin) {
            throw new AccessDeniedException("Only ADMIN or SUPER_ADMIN can permanently delete records");
        }
    }

    private String trimTrailingSeparator(String value) {
        return value.endsWith(" | ") ? value.substring(0, value.length() - 3) : value;
    }
}
