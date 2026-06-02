package ak.dev.khi_archive_platform.platform.dto.maqam;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;

/**
 * Admin-facing read of a single {@code maqam_audio_listen_sessions} row.
 * Used to render "who listened to what, when, and for how long" without
 * exposing internal IP / user-agent strings unless the caller is ADMIN.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MaqamListenSessionDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long maqamId;
    private String maqamCode;
    private Long teacherUserId;
    private String teacherUsername;
    private String sessionKey;
    private Instant startedAt;
    private Instant endedAt;
    private Long secondsListened;
    private Long lastPositionSeconds;

    /** Only populated for ADMIN callers. */
    private String ipAddress;
    private String userAgent;
}
