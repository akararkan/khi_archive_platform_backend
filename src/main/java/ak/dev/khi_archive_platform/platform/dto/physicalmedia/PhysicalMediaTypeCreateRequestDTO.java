package ak.dev.khi_archive_platform.platform.dto.physicalmedia;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body for {@code POST /api/physical-media/types}. {@code name} is the
 * only required field; the nine technical defaults are optional so an
 * admin can register the type now and fill the capture chain in later.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PhysicalMediaTypeCreateRequestDTO {

    @NotBlank
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
