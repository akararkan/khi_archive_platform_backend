package ak.dev.khi_archive_platform.platform.dto.khilogo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KhiLogoResponseDTO implements Serializable {
    private Long id;
    private String imageUrl;
    private Instant createdAt;
    private Instant updatedAt;
}
