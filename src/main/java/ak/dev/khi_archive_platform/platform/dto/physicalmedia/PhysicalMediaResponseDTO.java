package ak.dev.khi_archive_platform.platform.dto.physicalmedia;

import ak.dev.khi_archive_platform.platform.enums.DigitizationStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Read shape returned by every {@code /api/physical-media} endpoint. Plain
 * data — no relationships exposed — so the frontend can render a sheet-like
 * table without extra lookups.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PhysicalMediaResponseDTO {

    private Long id;
    private String pmCode;

    private Integer rowNumber;
    private Integer inventoryNumber;
    private String physicalMediaType;
    private String mediaCategory;
    private String title;
    private String sizeGB;
    private String physicalLabel;
    private String physicalSize;
    private String content;
    private String archiveDepNote;

    private DigitizationStatus digitization;
    /** Convenience for the UI: numeric encoding of {@link #digitization}. */
    private Integer digitizationCode;

    private String owner;
    private Integer year;
    private Integer durationMin;
    private Integer trackNumbers;
    private String trackName;
    private String extension;
    private String bitOrColorDepth;
    private String sampleOrFrameRate;
    private String channelsOrResolution;
    private String playbackModel;
    private String captureInterface;
    private String signalInterface;
    private String ingestSoftware;
    private String formatCodec;
    private LocalDate digitizeDate;
    private String tags;
    private Boolean needToClear;
    /** Convenience for the UI: numeric encoding of {@link #needToClear}. */
    private Integer needToClearCode;
    private String captureDepNote;

    private String source;
    private String createdBy;
    private String updatedBy;
    private String removedBy;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant removedAt;
    private Long version;
}
