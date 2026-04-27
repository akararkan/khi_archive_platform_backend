package ak.dev.khi_archive_platform.platform.model.image;

import ak.dev.khi_archive_platform.platform.model.project.Project;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "images",
        indexes = {
                @Index(name = "idx_image_code", columnList = "image_code"),
                @Index(name = "idx_image_project_id", columnList = "project_id"),
                @Index(name = "idx_image_removed_at", columnList = "removed_at")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Image {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Business key such as HASAZIRA_IMG_RAW_V1_Copy(1)_000001. */
    @Column(name = "image_code", unique = true, nullable = false, length = 255)
    private String imageCode;

    /** Image belongs to a project (collection). */
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
    @CollectionTable(name = "image_subjects", joinColumns = @JoinColumn(name = "image_id"))
    @Column(name = "subject", columnDefinition = "TEXT")
    private List<String> subject = new ArrayList<>();

    @Column(name = "form", columnDefinition = "TEXT")
    private String form;

    @Builder.Default
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "image_genres", joinColumns = @JoinColumn(name = "image_id"))
    @Column(name = "genre", columnDefinition = "TEXT")
    private List<String> genre = new ArrayList<>();

    @Column(name = "event", columnDefinition = "TEXT")
    private String event;

    @Column(name = "location", columnDefinition = "TEXT")
    private String location;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    // ─── Image Details ───────────────────────────────────────────────────────────

    @Column(name = "person_shown_in_image", columnDefinition = "TEXT")
    private String personShownInImage;

    @Builder.Default
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "image_colors", joinColumns = @JoinColumn(name = "image_id"))
    @Column(name = "color", columnDefinition = "TEXT")
    private List<String> colorOfImage = new ArrayList<>();

    @Column(name = "image_version")
    private String imageVersion;

    @Column(name = "version_number")
    private Integer versionNumber;

    @Column(name = "copy_number")
    private Integer copyNumber;

    @Builder.Default
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "image_usages", joinColumns = @JoinColumn(name = "image_id"))
    @Column(name = "usage_context", columnDefinition = "TEXT")
    private List<String> whereThisImageUsed = new ArrayList<>();

    // ─── Technical Metadata ──────────────────────────────────────────────────────

    @Column(name = "file_size", length = 100)
    private String fileSize;

    @Column(name = "extension", length = 50)
    private String extension;

    @Column(name = "orientation", length = 50)
    private String orientation;

    @Column(name = "dimension", length = 100)
    private String dimension;

    @Column(name = "bit_depth", length = 100)
    private String bitDepth;

    @Column(name = "dpi", length = 100)
    private String dpi;

    // ─── Equipment ───────────────────────────────────────────────────────────────

    @Column(name = "manufacturer")
    private String manufacturer;

    @Column(name = "model")
    private String model;

    @Column(name = "lens")
    private String lens;

    // ─── People & Production ─────────────────────────────────────────────────────

    @Column(name = "creator_artist_photographer", columnDefinition = "TEXT")
    private String creatorArtistPhotographer;

    @Column(name = "contributor", columnDefinition = "TEXT")
    private String contributor;

    @Column(name = "audience")
    private String audience;

    // ─── Archival ────────────────────────────────────────────────────────────────

    @Column(name = "accrual_method")
    private String accrualMethod;

    @Column(name = "provenance", columnDefinition = "TEXT")
    private String provenance;

    @Column(name = "photostory", columnDefinition = "TEXT")
    private String photostory;

    @Column(name = "image_status")
    private String imageStatus;

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
    @CollectionTable(name = "image_tags", joinColumns = @JoinColumn(name = "image_id"))
    @Column(name = "tag", columnDefinition = "TEXT")
    private List<String> tags = new ArrayList<>();

    @Builder.Default
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "image_keywords", joinColumns = @JoinColumn(name = "image_id"))
    @Column(name = "keyword", columnDefinition = "TEXT")
    private List<String> keywords = new ArrayList<>();

    // ─── Dates ───────────────────────────────────────────────────────────────────

    @Column(name = "date_created")
    private Instant dateCreated;

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

    @Column(name = "image_file_url", length = 1000)
    private String imageFileUrl;

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

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
