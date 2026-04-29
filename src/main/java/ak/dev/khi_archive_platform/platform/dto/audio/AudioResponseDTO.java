package ak.dev.khi_archive_platform.platform.dto.audio;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@SuppressWarnings("unused")
public class AudioResponseDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private String audioCode;
    private Long projectId;
    private String projectCode;
    private String projectName;
    private Long personId;
    private String personCode;
    private String personName;
    private List<String> categoryCodes;
    private String audioFileUrl;
    private String fullName;
    private String volumeName;
    private String directoryName;
    private String pathInExternal;
    private String autoPath;
    private String originTitle;
    private String alterTitle;
    private String centralKurdishTitle;
    private String romanizedTitle;
    private String form;
    private String typeOfBasta;
    private String typeOfMaqam;
    private List<String> genre;
    private String abstractText;
    private String description;
    private String speaker;
    private String producer;
    private String composer;
    private List<String> contributors;
    private String language;
    private String dialect;
    private String typeOfComposition;
    private String typeOfPerformance;
    private String lyrics;
    private String poet;
    private String recordingVenue;
    private String city;
    private String region;
    private Instant dateCreated;
    private Instant datePublished;
    private Instant dateModified;
    private String audience;
    private List<String> tags;
    private List<String> keywords;
    private Boolean physicalAvailability;
    private String physicalLabel;
    private String locationArchive;
    private String degitizedBy;
    private String degitizationEquipment;
    private String audioFileNote;
    private String audioChannel;
    private String fileExtension;
    private String fileSize;
    private String bitRate;
    private String bitDepth;
    private String sampleRate;
    private Integer audioQualityOutOf10;
    private String audioVersion;
    private Integer versionNumber;
    private Integer copyNumber;
    private String lccClassification;
    private String accrualMethod;
    private String provenance;
    private String copyright;
    private String rightOwner;
    private Instant dateCopyrighted;
    private String availability;
    private String licenseType;
    private String usageRights;
    private String owner;
    private String publisher;
    private String archiveLocalNote;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant removedAt;
    private String createdBy;
    private String updatedBy;
    private String removedBy;
}
