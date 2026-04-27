package ak.dev.khi_archive_platform.platform.dto.text;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@SuppressWarnings("unused")
public class TextBaseRequestDTO {

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
    private List<String> genre;
    private String documentType;
    private String description;

    // Text Details
    private String script;
    private String transcription;
    private String isbn;
    private String assignmentNumber;
    private String edition;
    private String volume;
    private String series;
    private String textVersion;
    private Integer versionNumber;
    private Integer copyNumber;

    // Technical Metadata
    private String fileSize;
    private String extension;
    private String orientation;
    private Integer pageCount;
    private String size;
    private String physicalDimensions;

    // Language
    private String language;
    private String dialect;

    // People & Production
    private String author;
    private String contributors;
    private String printingHouse;
    private String audience;

    // Archival
    private String accrualMethod;
    private String provenance;
    private String textStatus;
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
    private Instant printDate;
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
