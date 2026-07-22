package ak.dev.khi_archive_platform.platform.dto.physicalmedia;

import ak.dev.khi_archive_platform.platform.enums.DigitizationStatus;
import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Body for {@code POST /api/physical-media}. Mirrors every Excel column
 * except for the audit-only fields ({@code rowNumber}, {@code inventoryNumber})
 * which the caller may still supply if entering from a known sheet position.
 *
 * <p>All fields are optional except {@code physicalMediaType} and either
 * {@code title} or {@code physicalLabel} (enforced in the service so the
 * importer can reuse the same DTO without re-implementing validation).
 *
 * <p>{@link JsonAlias} entries accept the original Excel header for clients
 * that POST one row straight from the sheet without renaming.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PhysicalMediaCreateRequestDTO {

    @JsonAlias({"No.", "no"})
    private Integer rowNumber;

    /** Per-type sequence ("Number" in the sheet). <b>Ignored on manual
     *  {@code POST /api/physical-media}</b> — the server always assigns the
     *  next value for the chosen {@code physicalMediaType}, so the create form
     *  must not send it. Only the Excel importer honours a supplied value so it
     *  can round-trip the sheet. Preview the value the server will assign via
     *  {@code GET /api/physical-media/next-number?type=<physicalMediaType>}. */
    @JsonAlias({"Number", "number"})
    private Integer inventoryNumber;

    @Size(max = 200)
    @JsonAlias({"Physical Media Type"})
    private String physicalMediaType;

    @Size(max = 200)
    @JsonAlias({"جۆری بابەت(Media Category)", "Media Category"})
    private String mediaCategory;

    @JsonAlias({"ناوی بابەت (Title)", "Title"})
    private String title;

    /** Digital file size in gigabytes — free text (e.g. {@code 4.7},
     *  {@code 700 MB}). Repurposed from the retired sub-type column. */
    @Size(max = 200)
    @JsonAlias({"Size GB", "Size in GB", "Size (GB)", "sizeGb", "size_gb"})
    private String sizeGB;

    @Size(max = 200)
    @JsonAlias({"کۆد (Physical Label)", "Physical Label"})
    private String physicalLabel;

    /** Material size of the artefact (e.g. big / medium / normal / small). */
    @Size(max = 200)
    @JsonAlias({"قەبارە (Physical Size)", "قەبارە (Size)", "Physical Size", "Size"})
    private String physicalSize;

    @JsonAlias({"ناوەڕۆک (Content)", "Content"})
    private String content;

    @JsonAlias({"تێبینی بەشی ئارشیڤ Archive Dep Note", "Archive Dep Note"})
    private String archiveDepNote;

    /** Either the 0/1/2 integer (set via {@link #digitizationCode}) or the
     *  enum name itself (DIGITIZED / NOT_DIGITIZED / DUPLICATED). */
    @JsonAlias({"دیجیتایز Digitization", "Digitization"})
    private DigitizationStatus digitization;

    /** Convenience for clients that already have the 0/1/2 from the sheet —
     *  the service resolves this to {@link #digitization} when the enum
     *  field is null. */
    private Integer digitizationCode;

    @JsonAlias({"بەرهەمهێن(خاوەن) Owner", "Owner"})
    private String owner;

    @JsonAlias({"ساڵ Year", "Year"})
    private Integer year;

    @JsonAlias({"درێژایی خولەک Duration Min", "Duration Min"})
    private Integer durationMin;

    @JsonAlias({"ژمارەی تراک Track Numbers", "Track Numbers"})
    private Integer trackNumbers;

    @JsonAlias({"ناوی تراکەکان Track Name", "Track Name"})
    private String trackName;

    @Size(max = 50)
    @JsonAlias({"Extension"})
    private String extension;

    @Size(max = 100)
    @JsonAlias({"Bit Depth / Color Depth", "Bit Depth"})
    private String bitOrColorDepth;

    @Size(max = 100)
    @JsonAlias({"Sample Rate kHz/Frame Rate fps", "Sample Rate", "Frame Rate"})
    private String sampleOrFrameRate;

    @Size(max = 100)
    @JsonAlias({"Channels/Resolution", "Channels", "Resolution"})
    private String channelsOrResolution;

    @JsonAlias({"Playback Model"})
    private String playbackModel;

    @JsonAlias({"Capture Interface"})
    private String captureInterface;

    @JsonAlias({"Signal Interface(Cable Type)", "Signal Interface"})
    private String signalInterface;

    @JsonAlias({"Ingest Software"})
    private String ingestSoftware;

    @Size(max = 200)
    @JsonAlias({"Format/Codec", "Format", "Codec"})
    private String formatCodec;

    @JsonAlias({"ڕێکەوتی دیجیتایز/ گواستنەوە Digitize Date", "Digitize Date"})
    private LocalDate digitizeDate;

    @JsonAlias({"تاگ Tags", "Tags"})
    private String tags;

    /** Either {@code true}/{@code false} or the 0/1 from the sheet. */
    @JsonAlias({"خاوێنکردن Need to Clear", "Need to Clear"})
    private Boolean needToClear;

    /** Convenience: 0/1 from sheet — used when {@link #needToClear} is null. */
    private Integer needToClearCode;

    @JsonAlias({"تێبینی بەشی تیجیتایز Capture Dep Note", "Capture Dep Note"})
    private String captureDepNote;
}
