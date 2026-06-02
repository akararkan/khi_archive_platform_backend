package ak.dev.khi_archive_platform.platform.dto.maqam;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Opens a listen session. The client generates a {@code sessionKey} (any
 * unique string per play session — UUID v4 is canonical) and reuses it on
 * every subsequent {@code progress} / {@code end} call. {@code position} is
 * the seek offset (in seconds) the player started from; zero on fresh play.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MaqamListenStartRequestDTO {

    @NotBlank(message = "sessionKey is required")
    @Size(max = 100)
    private String sessionKey;

    @PositiveOrZero
    private Long startPositionSeconds;
}
