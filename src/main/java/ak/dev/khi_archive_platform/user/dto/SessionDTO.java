package ak.dev.khi_archive_platform.user.dto;

import lombok.Data;

import java.time.Instant;

@Data
public class SessionDTO {
    private String sessionId;
    private String deviceInfo;
    private String ipAddress;
    private Instant loginTimestamp;
    private Instant expiresAt;
    private Boolean isActive;
    private Instant logoutTimestamp;
}
