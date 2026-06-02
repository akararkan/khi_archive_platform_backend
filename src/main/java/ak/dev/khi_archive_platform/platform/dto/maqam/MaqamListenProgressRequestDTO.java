package ak.dev.khi_archive_platform.platform.dto.maqam;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Periodic progress ping (every ~10s and on pause). {@code addSeconds} is
 * the delta of audio time advanced since the last progress call — the
 * server treats it additively so paused intervals don't accrue. {@code
 * positionSeconds} is the player's current cursor.
 *
 * <p>The server clamps {@code addSeconds} to a sensible upper bound (3×
 * interval at most) to protect against tampering or clock drift.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MaqamListenProgressRequestDTO {

    @NotBlank(message = "sessionKey is required")
    @Size(max = 100)
    private String sessionKey;

    @NotNull(message = "addSeconds is required")
    @PositiveOrZero
    private Long addSeconds;

    @NotNull(message = "positionSeconds is required")
    @PositiveOrZero
    private Long positionSeconds;
}
