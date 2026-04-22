package ak.dev.khi_archive_platform.platform.service.object;

import ak.dev.khi_archive_platform.platform.dto.object.ObjectCreateRequestDTO;
import ak.dev.khi_archive_platform.platform.dto.object.ObjectResponseDTO;
import ak.dev.khi_archive_platform.platform.dto.object.ObjectUpdateRequestDTO;
import ak.dev.khi_archive_platform.platform.enums.ArchiveObjectAuditAction;
import ak.dev.khi_archive_platform.platform.model.category.Category;
import ak.dev.khi_archive_platform.platform.model.object.ObjectAttribute;
import ak.dev.khi_archive_platform.platform.repo.category.CategoryRepository;
import ak.dev.khi_archive_platform.platform.repo.object.ObjectAttributeRepository;
import ak.dev.khi_archive_platform.platform.service.category.CategoryCodeHelper;
import ak.dev.khi_archive_platform.user.consts.ValidationPatterns;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Arrays;
import java.util.Collections;
import java.util.stream.Collectors;
import java.util.Locale;

@Service
@RequiredArgsConstructor
@Transactional
public class ObjectAttributeService {

    private final ObjectAttributeRepository objectRepository;
    private final CategoryRepository categoryRepository;
    private final ObjectAttributeAuditService objectAuditService;

    public ObjectResponseDTO create(ObjectCreateRequestDTO dto,
                                    Authentication authentication,
                                    HttpServletRequest request) {
        Category category = resolveCategory(dto.getCategoryCode());
        String objectCode = resolveObjectCodeForCreate(dto.getObjectCode(), category);

        if (objectRepository.existsByObjectCodeAndDeletedAtIsNull(objectCode)) {
            throw new IllegalArgumentException("Object code already exists");
        }

        ObjectAttribute object = ObjectAttribute.builder()
                .objectCode(objectCode)
                .objectName(dto.getObjectName())
                .category(category)
                .description(dto.getDescription())
                .tags(joinList(dto.getTags()))
                .keywords(joinList(dto.getKeywords()))
                .build();
        category.attachObject(object);

        touchCreateAudit(object, authentication);
        ObjectAttribute saved = objectRepository.save(object);
        objectAuditService.record(saved, ArchiveObjectAuditAction.CREATE, authentication, request,
                "Created object record with code=" + saved.getObjectCode() + " category=" + saved.getCategory().getCategoryCode());
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<ObjectResponseDTO> getAll(Authentication authentication, HttpServletRequest request) {
        List<ObjectResponseDTO> result = objectRepository.findAllByDeletedAtIsNull().stream()
                .map(this::toResponse)
                .toList();
        objectAuditService.record(null, ArchiveObjectAuditAction.LIST, authentication, request,
                "Listed active object records");
        return result;
    }

    @Transactional(readOnly = true)
    public ObjectResponseDTO getByObjectCode(String objectCode,
                                             Authentication authentication,
                                             HttpServletRequest request) {
        String normalizedObjectCode = normalizeObjectCode(objectCode);
        ObjectAttribute object = objectRepository.findByObjectCodeAndDeletedAtIsNull(normalizedObjectCode)
                .orElseThrow(() -> new IllegalArgumentException("Object not found: " + objectCode));
        objectAuditService.record(object, ArchiveObjectAuditAction.READ, authentication, request,
                "Read object record");
        return toResponse(object);
    }

    public ObjectResponseDTO update(String objectCode,
                                    ObjectUpdateRequestDTO dto,
                                    Authentication authentication,
                                    HttpServletRequest request) {
        String normalizedObjectCode = normalizeObjectCode(objectCode);
        ObjectAttribute object = objectRepository.findByObjectCodeAndDeletedAtIsNull(normalizedObjectCode)
                .orElseThrow(() -> new IllegalArgumentException("Object not found: " + objectCode));

        StringBuilder changes = new StringBuilder();

        if (dto.getObjectName() != null && !dto.getObjectName().equals(object.getObjectName())) {
            changes.append("objectName: ").append(object.getObjectName()).append(" -> ").append(dto.getObjectName()).append(" | ");
            object.setObjectName(dto.getObjectName());
        }
        if (dto.getCategoryCode() != null && !dto.getCategoryCode().isBlank()) {
            Category newCategory = resolveCategory(dto.getCategoryCode());
            if (object.getCategory() == null || !newCategory.getCategoryCode().equals(object.getCategory().getCategoryCode())) {
                Category oldCategory = object.getCategory();
                changes.append("category: ")
                        .append(oldCategory == null ? "null" : oldCategory.getCategoryCode())
                        .append(" -> ")
                        .append(newCategory.getCategoryCode())
                        .append(" | ");
                if (oldCategory != null) {
                    oldCategory.detachObject(object);
                }
                newCategory.attachObject(object);
                object.setCategory(newCategory);
            }
        }
        if (dto.getDescription() != null && !dto.getDescription().equals(object.getDescription())) {
            changes.append("description: ").append(object.getDescription()).append(" -> ").append(dto.getDescription()).append(" | ");
            object.setDescription(dto.getDescription());
        }
        if (dto.getTags() != null && !joinList(dto.getTags()).equals(object.getTags())) {
            changes.append("tags: ").append(object.getTags()).append(" -> ").append(dto.getTags()).append(" | ");
            object.setTags(joinList(dto.getTags()));
        }
        if (dto.getKeywords() != null && !joinList(dto.getKeywords()).equals(object.getKeywords())) {
            changes.append("keywords: ").append(object.getKeywords()).append(" -> ").append(dto.getKeywords()).append(" | ");
            object.setKeywords(joinList(dto.getKeywords()));
        }

        touchUpdateAudit(object, authentication);
        ObjectAttribute saved = objectRepository.save(object);
        objectAuditService.record(saved, ArchiveObjectAuditAction.UPDATE, authentication, request,
                changes.isEmpty() ? "Updated object record (no field changes detected)" : "Updated object record: " + trimTrailingSeparator(changes.toString()));
        return toResponse(saved);
    }

    public void delete(String objectCode,
                       Authentication authentication,
                       HttpServletRequest request) {
        String normalizedObjectCode = normalizeObjectCode(objectCode);
        ObjectAttribute object = objectRepository.findByObjectCodeAndDeletedAtIsNull(normalizedObjectCode)
                .orElseThrow(() -> new IllegalArgumentException("Object not found: " + objectCode));

        object.setDeletedAt(Instant.now());
        object.setDeletedBy(resolveActorUsername(authentication));
        ObjectAttribute saved = objectRepository.save(object);
        objectAuditService.record(saved, ArchiveObjectAuditAction.DELETE, authentication, request,
                "Deleted object record");
    }

    private ObjectResponseDTO toResponse(ObjectAttribute object) {
        return ObjectResponseDTO.builder()
                .id(object.getId())
                .objectCode(object.getObjectCode())
                .objectName(object.getObjectName())
                .categoryId(object.getCategory() != null ? object.getCategory().getId() : null)
                .categoryCode(object.getCategory() != null ? object.getCategory().getCategoryCode() : null)
                .categoryName(object.getCategory() != null ? object.getCategory().getName() : null)
                .description(object.getDescription())
                .tags(splitToList(object.getTags()))
                .keywords(splitToList(object.getKeywords()))
                .createdAt(object.getCreatedAt())
                .updatedAt(object.getUpdatedAt())
                .deletedAt(object.getDeletedAt())
                .createdBy(object.getCreatedBy())
                .updatedBy(object.getUpdatedBy())
                .deletedBy(object.getDeletedBy())
                .build();
    }

    private void touchCreateAudit(ObjectAttribute object, Authentication authentication) {
        Instant now = Instant.now();
        String actor = resolveActorUsername(authentication);
        object.setCreatedAt(now);
        object.setUpdatedAt(now);
        object.setCreatedBy(actor);
        object.setUpdatedBy(actor);
    }

    private void touchUpdateAudit(ObjectAttribute object, Authentication authentication) {
        object.setUpdatedAt(Instant.now());
        object.setUpdatedBy(resolveActorUsername(authentication));
    }

    private String resolveActorUsername(Authentication authentication) {
        return authentication == null ? "anonymous" : authentication.getName();
    }

    private String normalizeObjectCode(String objectCode) {
        if (objectCode == null || objectCode.trim().isBlank()) {
            throw new IllegalArgumentException("Object code is required");
        }

        String trimmed = objectCode.trim();
        if (!trimmed.matches(ValidationPatterns.OBJECT_CODE)) {
            throw new IllegalArgumentException("Object code must match format KHI_OBJ_CATEGORYCODE_00001");
        }
        return trimmed;
    }

    private Category resolveCategory(String categoryCode) {
        String normalizedCategoryCode = CategoryCodeHelper.normalizeAndValidate(categoryCode);
        return categoryRepository.findByCategoryCodeAndDeletedAtIsNull(normalizedCategoryCode)
                .orElseThrow(() -> new IllegalArgumentException("Category not found: " + categoryCode));
    }

    private String resolveObjectCodeForCreate(String objectCode, Category category) {
        String normalizedCategoryCode = CategoryCodeHelper.normalizeAndValidate(category.getCategoryCode());
        if (objectCode == null || objectCode.trim().isBlank()) {
            long nextSequence = objectRepository.countByCategory(category) + 1;
            return generateObjectCode(normalizedCategoryCode, nextSequence);
        }

        String normalizedObjectCode = normalizeObjectCode(objectCode);
        String expectedPrefix = "KHI_OBJ_" + normalizedCategoryCode + "_";
        if (!normalizedObjectCode.startsWith(expectedPrefix)) {
            throw new IllegalArgumentException("Object code must include the selected category code: " + normalizedCategoryCode);
        }
        return normalizedObjectCode;
    }

    private String generateObjectCode(String categoryCode, long sequence) {
        return "KHI_OBJ_" + categoryCode + "_" + String.format(Locale.ROOT, "%05d", sequence);
    }

    private String trimTrailingSeparator(String value) {
        return value.endsWith(" | ") ? value.substring(0, value.length() - 3) : value;
    }

    private String joinList(List<String> items) {
        if (items == null) return null;
        List<String> cleaned = items.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(String::trim)
                .collect(Collectors.toList());
        if (cleaned.isEmpty()) return null;
        return String.join(",", cleaned);
    }

    private List<String> splitToList(String s) {
        if (s == null || s.isBlank()) return Collections.emptyList();
        return Arrays.stream(s.split(","))
                .map(String::trim)
                .filter(str -> !str.isEmpty())
                .collect(Collectors.toList());
    }
}

