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
 * Public-facing text/document shape. Excludes path/directory, lcc, version
 * internals, file-size, physical-dimensions, audit fields.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GuestTextDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private String textCode;

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
    private List<String> genre;
    private String documentType;
    private String description;

    private String script;
    private String transcription;
    private String isbn;
    private String edition;
    private String volume;
    private String series;

    private String language;
    private String dialect;
    private String region;

    private String author;
    private String contributors;
    private String printingHouse;
    private String audience;

    private List<String> tags;
    private List<String> keywords;

    private Integer pageCount;

    private Instant dateCreated;
    private Instant printDate;
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

    /** Public S3 URL of the text asset. */
    private String textFileUrl;

    // ── Trending metadata ──────────────────────────────────────────────────
    private boolean isTrending;
    private Integer trendingRank;
    private Double  trendingScore;
}
