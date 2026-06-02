package ak.dev.khi_archive_platform.platform.dto.maqam;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Closes the listen session. {@code addSeconds} captures any final delta the
 * client buffered since the last {@code progress} call. {@code positionSeconds}
 * is the player offset at the moment of close (or the duration when the
 * player reached the end).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MaqamListenEndRequestDTO {

    @NotBlank(message = "sessionKey is required")
    @Size(max = 100)
    private String sessionKey;

    @PositiveOrZero
    private Long addSeconds;

    @PositiveOrZero
    private Long positionSeconds;
}
