package ak.dev.khi_archive_platform.platform.dto.text;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.AssertTrue;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(callSuper = false)
@JsonIgnoreProperties(ignoreUnknown = true)
@SuppressWarnings("unused")
public class TextCreateRequestDTO extends TextBaseRequestDTO {

    private static final java.util.Set<String> VALID_VERSIONS = java.util.Set.of(
            "RAW", "MASTER", "RESTORED", "ARCHIVE", "ORIGINAL",
            "DIGITIZED", "PROFESSIONAL"
    );

    @AssertTrue(message = "Project code is required for text creation.")
    public boolean isProjectCodePresent() {
        return getProjectCode() != null && !getProjectCode().isBlank();
    }

    @AssertTrue(message = "Text version is required and must be one of: RAW, MASTER, RESTORED, ARCHIVE, ORIGINAL, DIGITIZED, PROFESSIONAL.")
    public boolean isTextVersionValid() {
        String tv = getTextVersion();
        return tv != null && VALID_VERSIONS.contains(tv.toUpperCase(java.util.Locale.ROOT));
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
