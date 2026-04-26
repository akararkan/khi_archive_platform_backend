package ak.dev.khi_archive_platform.platform.dto.video;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@SuppressWarnings("unused")
public class VideoResponseDTO {
    private Long id;
    private String videoCode;
    private Long projectId;
    private String projectCode;
    private String projectName;
    private Long personId;
    private String personCode;
    private String personName;
    private List<String> categoryCodes;
    private String videoFileUrl;

    // File & Path
    private String fileName;
    private String volumeName;
    private String directory;
    private String pathInExternalVolume;
    private String autoPath;

    // Titles
    private String originalTitle;
    private String alternativeTitle;
    private String titleInCentralKurdish;
    private String romanizedTitle;

    // Classification
    private List<String> subject;
    private List<String> genre;
    private String event;
    private String location;
    private String description;

    // Video Details
    private String personShownInVideo;
    private List<String> colorOfVideo;
    private String videoVersion;
    private Integer versionNumber;
    private Integer copyNumber;
    private List<String> whereThisVideoUsed;

    // Technical Metadata
    private String fileSize;
    private String extension;
    private String orientation;
    private String dimension;
    private String resolution;
    private String duration;
    private String bitDepth;
    private String frameRate;
    private String overallBitRate;
    private String videoCodec;
    private String audioCodec;
    private String audioChannels;

    // Language & Subtitle
    private String language;
    private String dialect;
    private String subtitle;

    // People & Production
    private String creatorArtistDirector;
    private String producer;
    private String contributor;
    private String audience;

    // Archival
    private String accrualMethod;
    private String provenance;
    private String videoStatus;
    private String archiveCataloging;
    private Boolean physicalAvailability;
    private String physicalLabel;
    private String locationInArchiveRoom;
    private String lccClassification;
    private String note;

    // Tags & Keywords
    private List<String> tags;
    private List<String> keywords;

    // Dates
    private Instant dateCreated;
    private Instant dateModified;
    private Instant datePublished;

    // Rights
    private String copyright;
    private String rightOwner;
    private Instant dateCopyrighted;
    private String licenseType;
    private String usageRights;
    private String availability;
    private String owner;
    private String publisher;

    // Audit
    private Instant createdAt;
    private Instant updatedAt;
    private Instant removedAt;
    private String createdBy;
    private String updatedBy;
    private String removedBy;
}
