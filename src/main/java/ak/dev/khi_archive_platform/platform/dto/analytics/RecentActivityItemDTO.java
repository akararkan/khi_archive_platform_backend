package ak.dev.khi_archive_platform.platform.dto.analytics;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

/**
 * One audit-log row, normalised across the seven entity tables. Returned
 * by the activity feed in chronological order (newest first).
 *
 * <p>Carries the full actor + request context captured at audit time so the
 * admin console can show "who did what, from where, with which permissions"
 * without an extra round-trip.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecentActivityItemDTO implements Serializable {
    /** "audio", "video", "image", "text", "project", "category", "person". */
    private String entity;
    private Long entityId;
    private String entityCode;
    private String action;
    private Instant occurredAt;

    private Long actorUserId;
    private String actorUsername;
    private String actorDisplayName;
    /** Comma-separated raw authority strings (roles + permissions). */
    private String actorAuthorities;
    /** Comma-separated permission strings (authorities minus ROLE_*). */
    private String actorPermissions;

    private String requestMethod;
    private String requestPath;
    private String ipAddress;
    private String deviceInfo;
    private String sessionId;

    private String details;
}
