package ak.dev.khi_archive_platform.platform.dto.project;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectResponseDTO {
    private Long id;
    private String projectCode;
    private String projectName;
    private Long personId;
    private String personCode;
    private String personName;
    private List<CategorySummary> categories;
    private String description;
    private List<String> tags;
    private List<String> keywords;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant removedAt;
    private String createdBy;
    private String updatedBy;
    private String removedBy;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CategorySummary {
        private Long id;
        private String categoryCode;
        private String categoryName;
    }
}
