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
import ak.dev.khi_archive_platform.platform.repo.object.ObjectAttributeRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final ObjectAttributeRepository objectRepository;
    private final CategoryAuditService auditService;

    public CategoryResponseDTO create(CategoryCreateRequestDTO dto,
                                      Authentication authentication,
                                      HttpServletRequest request) {
        String categoryCode = resolveCategoryCodeForCreate(dto.getCategoryCode(), dto.getName());

        if (categoryRepository.existsByCategoryCodeAndDeletedAtIsNull(categoryCode)) {
            throw new CategoryAlreadyExistsException("Category code already exists");
        }

        Category category = Category.builder()
                .categoryCode(categoryCode)
                .name(dto.getName())
                .description(dto.getDescription())
                .build();

        touchCreateAudit(category, authentication);
        Category saved = categoryRepository.save(category);
        auditService.record(saved, CategoryAuditAction.CREATE, authentication, request,
                "Created category with code=" + saved.getCategoryCode());
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<CategoryResponseDTO> getAll(Authentication authentication, HttpServletRequest request) {
        List<CategoryResponseDTO> result = categoryRepository.findAllByDeletedAtIsNull().stream()
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
        Category category = categoryRepository.findByCategoryCodeAndDeletedAtIsNull(normalizedCategoryCode)
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
        Category category = categoryRepository.findByCategoryCodeAndDeletedAtIsNull(normalizedCategoryCode)
                .orElseThrow(() -> new CategoryNotFoundException("Category not found: " + categoryCode));

        StringBuilder changes = new StringBuilder();
        if (dto.getName() != null && !dto.getName().equals(category.getName())) {
            changes.append("name: ").append(category.getName()).append(" -> ").append(dto.getName()).append(" | ");
            category.setName(dto.getName());
        }
        if (dto.getDescription() != null && !dto.getDescription().equals(category.getDescription())) {
            changes.append("description: ").append(category.getDescription()).append(" -> ").append(dto.getDescription()).append(" | ");
            category.setDescription(dto.getDescription());
        }

        touchUpdateAudit(category, authentication);
        Category saved = categoryRepository.save(category);
        auditService.record(saved, CategoryAuditAction.UPDATE, authentication, request,
                changes.isEmpty() ? "Updated category (no field changes detected)" : "Updated category: " + trimTrailingSeparator(changes.toString()));
        return toResponse(saved);
    }

    public void delete(String categoryCode,
                       Authentication authentication,
                       HttpServletRequest request) {
        String normalizedCategoryCode = CategoryCodeHelper.normalizeAndValidate(categoryCode);
        Category category = categoryRepository.findByCategoryCodeAndDeletedAtIsNull(normalizedCategoryCode)
                .orElseThrow(() -> new CategoryNotFoundException("Category not found: " + categoryCode));

        if (objectRepository.existsByCategoryAndDeletedAtIsNull(category)) {
            throw new CategoryInUseException("Category is used by active objects and cannot be deleted");
        }

        category.setDeletedAt(Instant.now());
        category.setDeletedBy(resolveActorUsername(authentication));
        Category saved = categoryRepository.save(category);
        auditService.record(saved, CategoryAuditAction.DELETE, authentication, request,
                "Deleted category");
    }

    private CategoryResponseDTO toResponse(Category category) {
        return CategoryResponseDTO.builder()
                .id(category.getId())
                .categoryCode(category.getCategoryCode())
                .name(category.getName())
                .description(category.getDescription())
                .createdAt(category.getCreatedAt())
                .updatedAt(category.getUpdatedAt())
                .deletedAt(category.getDeletedAt())
                .createdBy(category.getCreatedBy())
                .updatedBy(category.getUpdatedBy())
                .deletedBy(category.getDeletedBy())
                .build();
    }

    private String resolveCategoryCodeForCreate(String categoryCode, String name) {
        if (categoryCode == null || categoryCode.isBlank()) {
            long nextSequence = categoryRepository.count() + 1;
            return CategoryCodeHelper.generate(name, nextSequence);
        }
        return CategoryCodeHelper.normalizeAndValidate(categoryCode);
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

    private String trimTrailingSeparator(String value) {
        return value.endsWith(" | ") ? value.substring(0, value.length() - 3) : value;
    }
}

