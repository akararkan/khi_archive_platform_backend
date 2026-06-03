package ak.dev.khi_archive_platform.platform.dto.physicalmedia;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body for {@code PATCH /api/physical-media/types/{id}}. All fields are
 * optional; {@code null} means "leave alone" (standard PATCH semantics).
 * The name can be edited but the service rejects collisions against an
 * existing different type.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PhysicalMediaTypeUpdateRequestDTO {

    @Size(max = 200)
    private String name;

    private String description;

    @Size(max = 50)  private String extension;
    @Size(max = 100) private String bitOrColorDepth;
    @Size(max = 100) private String sampleOrFrameRate;
    @Size(max = 100) private String channelsOrResolution;

    private String playbackModel;
    private String captureInterface;
    private String signalInterface;
    private String ingestSoftware;

    @Size(max = 200) private String formatCodec;
}
