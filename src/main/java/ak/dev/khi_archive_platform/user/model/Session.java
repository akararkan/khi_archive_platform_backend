package ak.dev.khi_archive_platform.user.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "sessions")
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class Session {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id; // Primary Key

    @Column(name = "session_id", unique = true, nullable = false)
    private String sessionId; // UUID or unique identifier

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user; // Reference to the User

    @Column(name = "device_info")
    private String deviceInfo; // User-Agent or device details

    @Column(name = "ip_address")
    private String ipAddress; // IP address of the session

    @Column(name = "login_timestamp", nullable = false)
    private Instant loginTimestamp; // When the session was created

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt; // When the session expires

    @Builder.Default
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true; // Active status

    // Optionally, you can add a logout timestamp
    @Column(name = "logout_timestamp")
    private Instant logoutTimestamp;
}
