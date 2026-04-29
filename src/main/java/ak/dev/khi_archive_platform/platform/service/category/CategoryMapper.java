package ak.dev.khi_archive_platform.platform.service.category;

import ak.dev.khi_archive_platform.platform.dto.category.CategoryResponseDTO;
import ak.dev.khi_archive_platform.platform.model.category.Category;

import java.util.ArrayList;

final class CategoryMapper {

    private CategoryMapper() {}

    static CategoryResponseDTO toResponse(Category category) {
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
}
