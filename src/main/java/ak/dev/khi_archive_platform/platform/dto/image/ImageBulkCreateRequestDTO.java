package ak.dev.khi_archive_platform.platform.dto.image;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.AssertTrue;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Bulk-create payload for Image. Same fields as the single-create DTO but
 * accepts the {@code imageFileUrl} directly instead of a multipart file part,
 * so 1000s of records can be inserted in one JSON call.
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(callSuper = false)
@JsonIgnoreProperties(ignoreUnknown = true)
@SuppressWarnings("unused")
public class ImageBulkCreateRequestDTO extends ImageBaseRequestDTO {

    private static final java.util.Set<String> VALID_VERSIONS = java.util.Set.of(
            "RAW", "MASTER", "RESTORED", "ARCHIVE", "ORIGINAL",
            "HIGH_RES", "PROFESSIONAL"
    );

    /** Pre-uploaded image URL (S3 or external). May be null/blank for fixtures. */
    private String imageFileUrl;

    @AssertTrue(message = "Project code is required for image creation.")
    public boolean isProjectCodePresent() {
        return getProjectCode() != null && !getProjectCode().isBlank();
    }

    @AssertTrue(message = "Image version is required and must be one of: RAW, MASTER, RESTORED, ARCHIVE, ORIGINAL, HIGH_RES, PROFESSIONAL.")
    public boolean isImageVersionValid() {
        String iv = getImageVersion();
        return iv != null && VALID_VERSIONS.contains(iv.toUpperCase(java.util.Locale.ROOT));
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
