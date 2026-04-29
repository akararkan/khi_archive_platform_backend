package ak.dev.khi_archive_platform.platform.dto.audio;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.AssertTrue;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Bulk-create payload for Audio. Same fields as the single-create DTO but
 * accepts the {@code audioFileUrl} directly instead of a multipart file part.
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(callSuper = false)
@JsonIgnoreProperties(ignoreUnknown = true)
@SuppressWarnings("unused")
public class AudioBulkCreateRequestDTO extends AudioBaseRequestDTO {

    /** Pre-uploaded audio URL (S3 or external). May be null/blank for fixtures. */
    private String audioFileUrl;

    @AssertTrue(message = "Project code is required for audio creation.")
    public boolean isProjectCodePresent() {
        return getProjectCode() != null && !getProjectCode().isBlank();
    }

    @AssertTrue(message = "Audio version is required and must be RAW or MASTER.")
    public boolean isAudioVersionValid() {
        String av = getAudioVersion();
        return av != null && (av.equalsIgnoreCase("RAW") || av.equalsIgnoreCase("MASTER"));
    }

    @AssertTrue(message = "Version number is required and must be at least 1.")
    public boolean isVersionNumberValid() {
        Integer vn = getVersionNumber();
        return vn != null && vn >= 1;
    }

    @AssertTrue(message = "Copy number is required and must be at least 1.")
    public boolean isCopyNumberValid() {
        Integer cn = getCopyNumber();
        return cn != null && cn >= 1;
    }
}
