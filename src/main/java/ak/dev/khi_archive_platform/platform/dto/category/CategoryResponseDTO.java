package ak.dev.khi_archive_platform.platform.dto.category;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CategoryResponseDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private String categoryCode;
    private String name;
    private String description;
    private List<String> keywords;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant removedAt;
    private String createdBy;
    private String updatedBy;
    private String removedBy;
}
