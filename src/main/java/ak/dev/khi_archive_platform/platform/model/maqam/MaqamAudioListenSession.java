package ak.dev.khi_archive_platform.platform.model.maqam;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Granular per-listen log: one row per "play session" of a maqam audio file
 * by a teacher. The session model is intentionally generous — the front-end
 * issues a {@code start} call when the audio element first plays, periodic
 * {@code progress} pings (every ~10s and on pause), and an {@code end} call
 * when the element fires {@code ended} or unmounts.
 *
 * <p>{@code secondsListened} is the cumulative time the audio element has
 * actually advanced during this session, computed from the client-reported
 * elapsed timestamps. It is NOT wall-clock-since-start (a paused teacher
 * doesn't accrue seconds). The vote aggregate on
 * {@link MaqamTeacherVote#getTotalListenSeconds} is updated from these
 * rows so admins can audit per-teacher engagement.
 */
@Entity
@Table(name = "maqam_audio_listen_sessions",
        indexes = {
                @Index(name = "idx_mals_maqam", columnList = "list_of_maqam_id"),
                @Index(name = "idx_mals_teacher", columnList = "teacher_user_id"),
                @Index(name = "idx_mals_started_at", columnList = "started_at"),
                @Index(name = "idx_mals_session_key", columnList = "session_key")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MaqamAudioListenSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "list_of_maqam_id", nullable = false)
    private Long listOfMaqamId;

    @Column(name = "maqam_code", length = 100)
    private String maqamCode;

    @Column(name = "teacher_user_id", nullable = false)
    private Long teacherUserId;

    @Column(name = "teacher_username", nullable = false, length = 80)
    private String teacherUsername;

    /** Client-supplied opaque ID used to merge multiple progress pings for
     *  the same play session into one row. Format is up to the front-end
     *  (UUID v4 typically). */
    @Column(name = "session_key", length = 100, nullable = false)
    private String sessionKey;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Builder.Default
    @Column(name = "seconds_listened", nullable = false)
    private Long secondsListened = 0L;

    /** Audio cursor at the end of the last progress ping. Lets the UI
     *  resume mid-track. */
    @Builder.Default
    @Column(name = "last_position_seconds", nullable = false)
    private Long lastPositionSeconds = 0L;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    @Column(name = "http_session_id", length = 120)
    private String httpSessionId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (startedAt == null) startedAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
