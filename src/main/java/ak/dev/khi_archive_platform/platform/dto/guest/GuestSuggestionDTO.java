package ak.dev.khi_archive_platform.platform.dto.guest;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * Single autocomplete row. {@code kind} tells the UI which icon/route to use
 * (project | category | person | audio | video | text | image | tag | keyword).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GuestSuggestionDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String value;
    private String kind;
    /** Optional code so the UI can deep-link straight to the entity page. */
    private String code;
}
