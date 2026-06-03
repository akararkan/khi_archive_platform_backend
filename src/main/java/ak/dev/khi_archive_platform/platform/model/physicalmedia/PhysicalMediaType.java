package ak.dev.khi_archive_platform.platform.model.physicalmedia;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Catalog of allowed values for {@code PhysicalMedia.physicalMediaType}
 * (Audio Cassette, CD/DVD, VHS Cassette, …) plus the nine technical-capture
 * defaults that travel with each type.
 *
 * <p>Why a catalog instead of a Java enum: the team adds an unfamiliar
 * media type roughly once a year (a new tape format, a new camera ingest
 * chain). With a Java enum every new type would mean a redeploy. With a
 * catalog row an admin opens the catalog screen, adds the type + its
 * defaults, and the frontend autofills new records the same day.
 *
 * <p>Per-type defaults captured here:
 * <ul>
 *   <li>{@code extension}, {@code bitOrColorDepth}, {@code sampleOrFrameRate},
 *       {@code channelsOrResolution} — capture-format technicals</li>
 *   <li>{@code playbackModel}, {@code captureInterface},
 *       {@code signalInterface}, {@code ingestSoftware},
 *       {@code formatCodec} — capture-chain hardware + software</li>
 * </ul>
 *
 * <p>These are <em>defaults</em>, not constraints: a record can override
 * any of them at create time (or via PATCH). The autofill is a UI
 * convenience that copies the catalog values into the form on type
 * selection, then the user edits whatever differs for this specific row.
 *
 * <p>The defaults live on the catalog so changing them is one update for
 * the whole team — and the historic value already stamped on every
 * existing {@code PhysicalMedia} row stays untouched (no surprise
 * rewrites of past inventory).
 */
@Entity
@Table(name = "physical_media_types",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_pmt_name", columnNames = "name")
        },
        indexes = {
                @Index(name = "idx_pmt_name", columnList = "name")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PhysicalMediaType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Display name. Used as the foreign-key value referenced by
     *  {@code physical_media.physical_media_type}. Unique. */
    @Column(name = "name", nullable = false, length = 200)
    private String name;

    /** Optional human-readable description shown in the admin catalog UI. */
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    // ─── Nine technical defaults (mirrors the entity column names) ───────────

    @Column(name = "extension", length = 50)
    private String extension;

    @Column(name = "bit_or_color_depth", length = 100)
    private String bitOrColorDepth;

    @Column(name = "sample_or_frame_rate", length = 100)
    private String sampleOrFrameRate;

    @Column(name = "channels_or_resolution", length = 100)
    private String channelsOrResolution;

    @Column(name = "playback_model", columnDefinition = "TEXT")
    private String playbackModel;

    @Column(name = "capture_interface", columnDefinition = "TEXT")
    private String captureInterface;

    @Column(name = "signal_interface", columnDefinition = "TEXT")
    private String signalInterface;

    @Column(name = "ingest_software", columnDefinition = "TEXT")
    private String ingestSoftware;

    @Column(name = "format_codec", length = 200)
    private String formatCodec;

    // ─── Audit envelope ──────────────────────────────────────────────────────

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "created_by", length = 120)
    private String createdBy;

    @Column(name = "updated_by", length = 120)
    private String updatedBy;

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
