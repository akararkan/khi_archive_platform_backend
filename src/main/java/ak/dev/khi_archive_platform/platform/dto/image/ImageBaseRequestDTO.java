package ak.dev.khi_archive_platform.platform.dto.image;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@SuppressWarnings("unused")
public class ImageBaseRequestDTO {

    private String projectCode;

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
    private String form;
    private List<String> genre;
    private String event;
    private String location;
    private String description;

    // Image Details
    private String personShownInImage;
    private List<String> colorOfImage;
    private String imageVersion;
    private Integer versionNumber;
    private Integer copyNumber;
    private List<String> whereThisImageUsed;

    // Technical Metadata
    private String fileSize;
    private String extension;
    private String orientation;
    private String dimension;
    private String bitDepth;
    private String dpi;

    // Equipment
    private String manufacturer;
    private String model;
    private String lens;

    // People & Production
    private String creatorArtistPhotographer;
    private String contributor;
    private String audience;

    // Archival
    private String accrualMethod;
    private String provenance;
    private String photostory;
    private String imageStatus;
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
}
