package ak.dev.khi_archive_platform.platform.dto.correction;

import ak.dev.khi_archive_platform.platform.enums.CorrectionMediaType;
import ak.dev.khi_archive_platform.platform.enums.CorrectionStatus;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class GuestCorrectionResponseDTO {

    private Long id;
    private CorrectionMediaType mediaType;
    private String mediaCode;
    private String mediaTitle;
    private String targetField;
    private String currentValue;
    private String suggestedValue;
    private String note;

    // Guest info
    private Long guestUserId;
    private String guestUsername;
    private String guestDisplayName;

    private CorrectionStatus status;

    // Who originally created the record
    private String recordCreatedBy;

    // Forwarding info
    private String forwardedBy;
    private Instant forwardedAt;
    private String forwardNote;

    // Resolution info
    private String resolvedBy;
    private Instant resolvedAt;
    private String resolveNote;

    private Instant createdAt;
    private Instant updatedAt;
    private Instant removedAt;
}
