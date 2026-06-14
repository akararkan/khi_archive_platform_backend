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
 * Public-facing audio shape. Excludes path/volume/directory, lcc, bit-rate,
 * bit-depth, sample-rate, file size, version internals, audit fields.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GuestAudioDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private String audioCode;

    private String projectCode;
    private String projectName;

    /** Portrait of the project's owning person — drives avatar in card grids. */
    private String personMediaPortrait;

    /** Linked person (via project). Null for untitled projects. */
    private GuestPersonSummaryDTO person;

    /** Categories of the owning project — drives the Categories row on detail pages. */
    private List<GuestCategorySummaryDTO> categories;

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
    private String poet;
    private List<String> contributors;

    private String language;
    private String dialect;
    private String typeOfComposition;
    private String typeOfPerformance;
    private String lyrics;

    private String recordingVenue;
    private String city;
    private String region;
    private String audience;

    private List<String> tags;
    private List<String> keywords;

    private Instant dateCreated;
    private Instant datePublished;
    private Instant dateModified;

    private String copyright;
    private String rightOwner;
    private Instant dateCopyrighted;
    private String licenseType;
    private String availability;
    private String owner;
    private String publisher;

    /** Public S3 URL of the audio asset. */
    private String audioFileUrl;

    // ── Trending metadata (injected after search, null when not trending) ──
    private boolean isTrending;
    private Integer trendingRank;
    private Double  trendingScore;
}
