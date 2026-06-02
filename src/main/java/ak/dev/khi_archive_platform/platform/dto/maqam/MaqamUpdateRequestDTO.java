package ak.dev.khi_archive_platform.platform.dto.maqam;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Patch payload for {@code PATCH /api/maqam/{maqamCode}}. Every field is
 * optional; only non-null fields are applied. The audio file is replaced by
 * sending a fresh {@code file} multipart part — sending {@code data} only
 * leaves the existing file untouched.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MaqamUpdateRequestDTO {

    @Size(max = 1000)
    private String songName;

    @Size(max = 500)
    private String producer;

    @Size(max = 10_000)
    private String archiveNote;
}
