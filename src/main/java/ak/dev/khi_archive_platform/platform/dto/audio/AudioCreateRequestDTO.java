package ak.dev.khi_archive_platform.platform.dto.audio;

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
public class AudioCreateRequestDTO extends AudioBaseRequestDTO {

    @AssertTrue(message = "Audio must be linked to either a person or an object.")
    public boolean isLinkedToPersonOrObject() {
        boolean hasPerson = getPersonCode() != null && !getPersonCode().isBlank();
        boolean hasObject = getObjectCode() != null && !getObjectCode().isBlank();
        return hasPerson ^ hasObject;
    }
}

