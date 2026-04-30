package ak.dev.khi_archive_platform.platform.model.audio;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import ak.dev.khi_archive_platform.platform.model.project.Project;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "audios",
        indexes = {
                @Index(name = "idx_audio_code", columnList = "audio_code"),
                @Index(name = "idx_audio_project_id", columnList = "project_id"),
                @Index(name = "idx_audio_removed_at", columnList = "removed_at")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Audio {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Business key such as HASAZIRA_AUD_RAW_V1_Copy(1)_000001. */
    @Column(name = "audio_code", unique = true, nullable = false, length = 255)
    private String audioCode;

    /** Audio belongs to a project (collection). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(name = "fullname", columnDefinition = "TEXT")
    private String fullname;

    @Column(name = "volume_name")
    private String volumeName;

    @Column(name = "directory_name")
    private String directoryName;

    @Column(name = "path_in_external", length = 512)
    private String path_in_external;

    @Column(name = "auto_path", length = 512)
    private String auto_path;

    @Column(name = "origin_title", columnDefinition = "TEXT")
    private String originTitle;

    @Column(name = "alter_title", columnDefinition = "TEXT")
    private String alterTitle;

    @Column(name = "central_kurdish_title", columnDefinition = "TEXT")
    private String central_kurdish_title;

    @Column(name = "romanized_title", columnDefinition = "TEXT")
    private String romanized_title;

    @Column(name = "form", columnDefinition = "TEXT")
    private String form;

    @Column(name = "type_of_basta")
    private String typeOfBasta;

    @Column(name = "type_of_maqam")
    private String typeOfMaqam;

    @Builder.Default
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "audio_genres", joinColumns = @JoinColumn(name = "audio_id"))
    @Column(name = "genre", columnDefinition = "TEXT")
    private List<String> genre = new ArrayList<>();

    @Column(name = "abstract_text", columnDefinition = "TEXT")
    private String abstractText;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "speaker", columnDefinition = "TEXT")
    private String speaker;

    @Column(name = "producer", columnDefinition = "TEXT")
    private String producer;

    @Column(name = "composer", columnDefinition = "TEXT")
    private String composer;

    @Builder.Default
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "audio_contributors", joinColumns = @JoinColumn(name = "audio_id"))
    @Column(name = "contributor", columnDefinition = "TEXT")
    private List<String> contributors = new ArrayList<>();

    @Column(name = "language")
    private String language;

    @Column(name = "dialect")
    private String dialect;

    @Column(name = "type_of_composition")
    private String typeOfComposition;

    @Column(name = "type_of_performance")
    private String typeOfPerformance;

    @Column(name = "lyrics", columnDefinition = "TEXT")
    private String lyrics;

    @Column(name = "poet", columnDefinition = "TEXT")
    private String poet;

    @Column(name = "recording_venue")
    private String recording_venue;

    @Column(name = "city")
    private String city;

    @Column(name = "region")
    private String region;

    @Column(name = "date_created")
    private Instant date_created;

    @Column(name = "date_published")
    private Instant date_published;

    @Column(name = "date_modified")
    private Instant date_modified;

    @Column(name = "audience")
    private String audience;

    @Builder.Default
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "audio_tags", joinColumns = @JoinColumn(name = "audio_id"))
    @Column(name = "tag", columnDefinition = "TEXT")
    private List<String> tags = new ArrayList<>();

    @Builder.Default
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "audio_keywords", joinColumns = @JoinColumn(name = "audio_id"))
    @Column(name = "keyword", columnDefinition = "TEXT")
    private List<String> keywords = new ArrayList<>();

    @Column(name = "physical_availability", nullable = false)
    private boolean physicalAvailability;

    @Column(name = "physical_label", columnDefinition = "TEXT")
    private String physicalLabel;

    @Column(name = "location_archive", columnDefinition = "TEXT")
    private String locationArchive;

    @Column(name = "degitized_by", columnDefinition = "TEXT")
    private String degitizedBy;

    @Column(name = "degitization_equipment", columnDefinition = "TEXT")
    private String degitizationEquipment;

    @Column(name = "audio_file_note", columnDefinition = "TEXT")
    private String audioFileNote;

    @Column(name = "audio_channel", length = 100)
    private String audioChannel;

    @Column(name = "file_extension", length = 50)
    private String fileExtension;

    @Column(name = "file_size", length = 100)
    private String fileSize;

    @Column(name = "bit_rate", length = 100)
    private String bitRate;

    @Column(name = "bit_depth", length = 100)
    private String bitDepth;

    @Column(name = "sample_rate", length = 100)
    private String sampleRate;

    @Column(name = "audio_quality_out_of_10")
    private Integer audioQualityOutOf10;

    @Column(name = "audio_version")
    private String audioVersion;

    @Column(name = "version_number")
    private Integer versionNumber;

    @Column(name = "copy_number")
    private Integer copyNumber;

    @Column(name = "lcc_classification")
    private String lcc_classification;

    @Column(name = "accrual_method")
    private String accrualMethod;

    @Column(name = "provenance", columnDefinition = "TEXT")
    private String provenance;

    @Column(name = "copyright", columnDefinition = "TEXT")
    private String copyright;

    @Column(name = "right_owner", columnDefinition = "TEXT")
    private String rightOwner;

    @Column(name = "date_copyrighted")
    private Instant dateCopyRighted;

    @Column(name = "availability")
    private String availability;

    @Column(name = "license_type")
    private String licenseType;

    @Column(name = "usage_rights", columnDefinition = "TEXT")
    private String usageRights;

    @Column(name = "owner", columnDefinition = "TEXT")
    private String owner;

    @Column(name = "publisher", columnDefinition = "TEXT")
    private String publisher;

    @Column(name = "archive_local_note", columnDefinition = "TEXT")
    private String archiveLocalNote;

    @Column(name = "audio_file_url", length = 1000)
    private String audioFileUrl;

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

    /**
     * Optimistic-locking version. JPA bumps it automatically on every save;
     * concurrent updates that read a stale version trip
     * {@code ObjectOptimisticLockingFailureException} → translated to HTTP 409.
     */
    @jakarta.persistence.Version
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
