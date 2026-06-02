package ak.dev.khi_archive_platform.platform.dto.guest;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.List;

/**
 * Public-facing image shape. Excludes path/directory, lcc, dpi, dimension,
 * bit-depth, file-size, version internals, audit fields.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GuestImageDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private String imageCode;

    private String projectCode;
    private String projectName;

    /** Portrait of the project's owning person — drives avatar in card grids. */
    private String personMediaPortrait;

    /** Linked person (via project). Null for untitled projects. */
    private GuestPersonSummaryDTO person;

    /** Categories of the owning project — drives the Categories row on detail pages. */
    private List<GuestCategorySummaryDTO> categories;

    private String originalTitle;
    private String alternativeTitle;
    private String titleInCentralKurdish;
    private String romanizedTitle;

    private List<String> subject;
    private String form;
    private List<String> genre;
    private String event;
    private String location;
    private String description;

    private String personShownInImage;
    private List<String> colorOfImage;

    private String manufacturer;
    private String model;
    private String lens;

    private String creatorArtistPhotographer;
    private String contributor;
    private String audience;
    private String photostory;

    private List<String> tags;
    private List<String> keywords;
    private List<String> whereThisImageUsed;

    private Instant dateCreated;
    private Instant dateModified;
    private Instant datePublished;

    private String copyright;
    private String rightOwner;
    private Instant dateCopyrighted;
    private String licenseType;
    private String usageRights;
    private String availability;
    private String owner;
    private String publisher;

    /** Public S3 URL of the image asset. */
    private String imageFileUrl;
}
