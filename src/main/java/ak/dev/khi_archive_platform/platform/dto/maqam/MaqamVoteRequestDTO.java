package ak.dev.khi_archive_platform.platform.dto.maqam;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Payload for a teacher casting or updating their vote on a maqam record.
 * Calls land at {@code POST /api/maqam/{maqamCode}/vote}; on first call the
 * row is created (action = {@code VOTE_CAST}), on subsequent calls only the
 * fields supplied are updated (action = {@code VOTE_UPDATED}).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MaqamVoteRequestDTO {

    /** Free-form maqam classification. The organisation deliberately keeps
     *  this open — teachers may write "Rast", "Bayati Shuri", or a longer
     *  qualifier such as "Husseini with Saba ending". */
    @NotBlank(message = "maqamType is required")
    @Size(max = 1000)
    private String maqamType;

    /** Optional reasoning / note from the teacher. */
    @Size(max = 10_000)
    private String teacherNote;
}
