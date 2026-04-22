package ak.dev.khi_archive_platform.platform.dto.object;

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
@SuppressWarnings("unused")
public class ObjectResponseDTO {
	private Long id;
	private String objectCode;
	private String objectName;
	private Long categoryId;
	private String categoryCode;
	private String categoryName;
	private String description;
	private List<String> tags;
	private List<String> keywords;
	private Instant createdAt;
	private Instant updatedAt;
	private Instant deletedAt;
	private String createdBy;
	private String updatedBy;
	private String deletedBy;
}
