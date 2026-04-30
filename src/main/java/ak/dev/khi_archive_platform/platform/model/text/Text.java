package ak.dev.khi_archive_platform.platform.model.text;

import ak.dev.khi_archive_platform.platform.model.project.Project;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "texts",
        indexes = {
                @Index(name = "idx_text_code", columnList = "text_code"),
                @Index(name = "idx_text_project_id", columnList = "project_id"),
                @Index(name = "idx_text_removed_at", columnList = "removed_at")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Text {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Business key such as HASAZIRA_TXT_RAW_V1_Copy(1)_000001. */
    @Column(name = "text_code", unique = true, nullable = false, length = 255)
    private String textCode;

    /** Text belongs to a project (collection). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    // ─── File & Path ─────────────────────────────────────────────────────────────

    @Column(name = "file_name", columnDefinition = "TEXT")
    private String fileName;

    @Column(name = "volume_name")
    private String volumeName;

    @Column(name = "directory")
    private String directory;

    @Column(name = "path_in_external_volume", length = 512)
    private String pathInExternalVolume;

    @Column(name = "auto_path", length = 512)
    private String autoPath;

    // ─── Titles ──────────────────────────────────────────────────────────────────

    @Column(name = "original_title", columnDefinition = "TEXT")
    private String originalTitle;

    @Column(name = "alternative_title", columnDefinition = "TEXT")
    private String alternativeTitle;

    @Column(name = "title_in_central_kurdish", columnDefinition = "TEXT")
    private String titleInCentralKurdish;

    @Column(name = "romanized_title", columnDefinition = "TEXT")
    private String romanizedTitle;

    // ─── Classification ──────────────────────────────────────────────────────────

    @Builder.Default
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "text_subjects", joinColumns = @JoinColumn(name = "text_id"))
    @Column(name = "subject", columnDefinition = "TEXT")
    private List<String> subject = new ArrayList<>();

    @Builder.Default
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "text_genres", joinColumns = @JoinColumn(name = "text_id"))
    @Column(name = "genre", columnDefinition = "TEXT")
    private List<String> genre = new ArrayList<>();

    @Column(name = "document_type")
    private String documentType;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    // ─── Text Details ────────────────────────────────────────────────────────────

    @Column(name = "script")
    private String script;

    @Column(name = "transcription", columnDefinition = "TEXT")
    private String transcription;

    @Column(name = "isbn")
    private String isbn;

    @Column(name = "assignment_number")
    private String assignmentNumber;

    @Column(name = "edition")
    private String edition;

    @Column(name = "volume")
    private String volume;

    @Column(name = "series")
    private String series;

    @Column(name = "text_version")
    private String textVersion;

    @Column(name = "version_number")
    private Integer versionNumber;

    @Column(name = "copy_number")
    private Integer copyNumber;

    // ─── Technical Metadata ──────────────────────────────────────────────────────

    @Column(name = "file_size", length = 100)
    private String fileSize;

    @Column(name = "extension", length = 50)
    private String extension;

    @Column(name = "orientation", length = 50)
    private String orientation;

    @Column(name = "page_count")
    private Integer pageCount;

    @Column(name = "size")
    private String size;

    @Column(name = "physical_dimensions")
    private String physicalDimensions;

    // ─── Language ────────────────────────────────────────────────────────────────

    @Column(name = "language")
    private String language;

    @Column(name = "dialect")
    private String dialect;

    // ─── People & Production ─────────────────────────────────────────────────────

    @Column(name = "author", columnDefinition = "TEXT")
    private String author;

    @Column(name = "contributors", columnDefinition = "TEXT")
    private String contributors;

    @Column(name = "printing_house")
    private String printingHouse;

    @Column(name = "audience")
    private String audience;

    // ─── Archival ────────────────────────────────────────────────────────────────

    @Column(name = "accrual_method")
    private String accrualMethod;

    @Column(name = "provenance", columnDefinition = "TEXT")
    private String provenance;

    @Column(name = "text_status")
    private String textStatus;

    @Column(name = "archive_cataloging", columnDefinition = "TEXT")
    private String archiveCataloging;

    @Column(name = "physical_availability", nullable = false)
    private boolean physicalAvailability;

    @Column(name = "physical_label", columnDefinition = "TEXT")
    private String physicalLabel;

    @Column(name = "location_in_archive_room", columnDefinition = "TEXT")
    private String locationInArchiveRoom;

    @Column(name = "lcc_classification")
    private String lccClassification;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    // ─── Tags & Keywords ─────────────────────────────────────────────────────────

    @Builder.Default
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "text_tags", joinColumns = @JoinColumn(name = "text_id"))
    @Column(name = "tag", columnDefinition = "TEXT")
    private List<String> tags = new ArrayList<>();

    @Builder.Default
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "text_keywords", joinColumns = @JoinColumn(name = "text_id"))
    @Column(name = "keyword", columnDefinition = "TEXT")
    private List<String> keywords = new ArrayList<>();

    // ─── Dates ───────────────────────────────────────────────────────────────────

    @Column(name = "date_created")
    private Instant dateCreated;

    @Column(name = "print_date")
    private Instant printDate;

    @Column(name = "date_modified")
    private Instant dateModified;

    @Column(name = "date_published")
    private Instant datePublished;

    // ─── Rights ──────────────────────────────────────────────────────────────────

    @Column(name = "copyright", columnDefinition = "TEXT")
    private String copyright;

    @Column(name = "right_owner", columnDefinition = "TEXT")
    private String rightOwner;

    @Column(name = "date_copyrighted")
    private Instant dateCopyrighted;

    @Column(name = "license_type")
    private String licenseType;

    @Column(name = "usage_rights", columnDefinition = "TEXT")
    private String usageRights;

    @Column(name = "availability")
    private String availability;

    @Column(name = "owner", columnDefinition = "TEXT")
    private String owner;

    @Column(name = "publisher", columnDefinition = "TEXT")
    private String publisher;

    // ─── File URL ────────────────────────────────────────────────────────────────

    @Column(name = "text_file_url", length = 1000)
    private String textFileUrl;

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
