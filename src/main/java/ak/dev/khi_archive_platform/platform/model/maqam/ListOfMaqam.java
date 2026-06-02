package ak.dev.khi_archive_platform.platform.model.maqam;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A song record curated by the archive team and routed to a small panel of
 * maqam teachers (1–3 per record) for classification. The "upstream" half of
 * the record — id, song name, producer (singer), audio file, archive note —
 * is owned by employees and admins. The "voting" half lives on
 * {@link MaqamTeacherVote}, one row per assigned teacher, and is mutated only
 * by that teacher.
 *
 * <p>The audio file is stored on S3 but never returned as a direct URL by the
 * API. Playback happens through the {@code /api/maqam/{maqamCode}/stream}
 * range-aware endpoint, which streams bytes inline without a download
 * disposition. This implements the project-wide "no downloads" rule for
 * maqam audio (and keeps {@code MaqamAudioListenSession} accountable for
 * every byte served).
 */
@Entity
@Table(name = "list_of_maqam",
        indexes = {
                @Index(name = "idx_maqam_code", columnList = "maqam_code"),
                @Index(name = "idx_maqam_removed_at", columnList = "removed_at"),
                @Index(name = "idx_maqam_created_at", columnList = "created_at")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ListOfMaqam {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Business key used in every URL and audit row. Format MAQAM_000001. */
    @Column(name = "maqam_code", unique = true, nullable = false, length = 100)
    private String maqamCode;

    @Column(name = "song_name", nullable = false, columnDefinition = "TEXT")
    private String songName;

    /** Producer = singer of the recording. */
    @Column(name = "producer", nullable = false, columnDefinition = "TEXT")
    private String producer;

    /** Internal S3 URL — never returned by the API. Reads go through the
     *  streaming endpoint so we can audit byte ranges + listen seconds. */
    @Column(name = "audio_file_url", nullable = false, length = 1000)
    private String audioFileUrl;

    /** Original filename of the upload. Surfaced in DTOs so admins/teachers
     *  see what the upstream file was called without exposing the URL. */
    @Column(name = "audio_file_name", length = 500)
    private String audioFileName;

    /** MIME type recorded at upload, used by the streaming endpoint to set
     *  the {@code Content-Type} of every range response. */
    @Column(name = "audio_content_type", length = 120)
    private String audioContentType;

    @Column(name = "audio_file_size_bytes")
    private Long audioFileSizeBytes;

    /** Best-effort duration in seconds. Optional: when missing the listen
     *  tracker still records absolute seconds, just can't compute "% heard". */
    @Column(name = "audio_duration_seconds")
    private Long audioDurationSeconds;

    /** Editorial note attached by the archive team. Visible to everyone who
     *  can read the record. Teachers may not edit it. */
    @Column(name = "archive_note", columnDefinition = "TEXT")
    private String archiveNote;

    /**
     * Teacher votes attached to this record. Capped at {@link #MAX_TEACHERS}
     * (validated in the service layer because JPA doesn't enforce row-count
     * limits on collections). Eager-load is intentional: every read of a
     * record renders the panel summary alongside the song fields.
     */
    public static final int MIN_TEACHERS = 1;
    public static final int MAX_TEACHERS = 3;

    @Builder.Default
    @OneToMany(mappedBy = "listOfMaqam", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<MaqamTeacherVote> teacherVotes = new ArrayList<>();

    // ─── Audit ───────────────────────────────────────────────────────────────────

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "removed_at")
    private Instant removedAt;

    @Column(name = "created_by", length = 120)
    private String createdBy;

    @Column(name = "updated_by", length = 120)
    private String updatedBy;

    @Column(name = "removed_by", length = 120)
    private String removedBy;

    @Version
    @org.hibernate.annotations.ColumnDefault("0")
    @Column(name = "version", nullable = false)
    private Long version;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (version == null) version = 0L;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
