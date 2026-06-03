package ak.dev.khi_archive_platform.platform.dto.physicalmedia;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Wire shape for one catalog entry. The frontend keeps a list of these
 * cached on the create / edit screen so picking a type can autofill the
 * nine technical fields without an extra round-trip.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PhysicalMediaTypeResponseDTO {
    private Long id;
    private String name;
    private String description;

    private String extension;
    private String bitOrColorDepth;
    private String sampleOrFrameRate;
    private String channelsOrResolution;
    private String playbackModel;
    private String captureInterface;
    private String signalInterface;
    private String ingestSoftware;
    private String formatCodec;

    private String createdBy;
    private String updatedBy;
    private Instant createdAt;
    private Instant updatedAt;
    private Long version;
}
