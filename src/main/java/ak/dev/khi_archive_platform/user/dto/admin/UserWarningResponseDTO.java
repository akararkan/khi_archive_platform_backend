package ak.dev.khi_archive_platform.user.dto.admin;

import ak.dev.khi_archive_platform.user.enums.WarningSeverity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

/**
 * Response shape for every warning endpoint — admin list/get and the
 * recipient's {@code /api/warnings/me} list both return this shape so the
 * frontend has one rendering path.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserWarningResponseDTO implements Serializable {
    private Long id;
    private Long targetUserId;
    private String targetUsername;
    private Long actorUserId;
    private String actorUsername;
    private String actorDisplayName;
    private WarningSeverity severity;
    private String title;
    private String message;
    private boolean acknowledged;
    private Instant acknowledgedAt;
    private Instant createdAt;
    /** Set when an admin revoked the warning. Only ever non-null in admin
     *  responses; recipient-facing endpoints never return revoked rows. */
    private Instant removedAt;
}
