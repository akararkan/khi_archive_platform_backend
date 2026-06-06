package ak.dev.khi_archive_platform.platform.dto.items;

import ak.dev.khi_archive_platform.platform.dto.audio.AudioResponseDTO;
import ak.dev.khi_archive_platform.platform.dto.image.ImageResponseDTO;
import ak.dev.khi_archive_platform.platform.dto.text.TextResponseDTO;
import ak.dev.khi_archive_platform.platform.dto.video.VideoResponseDTO;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.List;

/**
 * Unified row for the GET /api/items endpoint. Carries shared fields at the
 * top so the frontend can render lists/cards without unwrapping the payload,
 * plus the FULL original DTO under the matching type slot ({@link #audio},
 * {@link #video}, {@link #image}, {@link #text}) so detail views can show
 * every field. Exactly one type slot is populated per row.
 * <p>
 * Why both summary + payload: the summary fields drive search/sort/filter on
 * the server (no reflection, no polymorphism, just direct field access) and
 * give the frontend a uniform shape; the payload gives "all the details" the
 * user asked for without forcing the frontend to make a follow-up GET.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ItemDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    // ── Discriminator & identifiers ──────────────────────────────────────────
    private ItemType type;
    private Long id;
    /** Business code: audioCode / videoCode / imageCode / textCode depending on type. */
    private String code;
    /** Best display title resolved per type (origin/original/alter/romanized/code fallback). */
    private String title;

    // ── Project (collection) info ────────────────────────────────────────────
    private Long projectId;
    private String projectCode;
    private String projectName;
    private Boolean projectVisibleToPublic;

    // ── Person (via project) ─────────────────────────────────────────────────
    private Long personId;
    private String personCode;
    private String personName;

    // ── Categories (via project) ─────────────────────────────────────────────
    private List<String> categoryCodes;

    // ── File ─────────────────────────────────────────────────────────────────
    private String fileUrl;
    private String fileExtension;
    private String fileSize;

    // ── Common metadata used for filter/sort ─────────────────────────────────
    private String language;
    private String dialect;
    private List<String> tags;
    private List<String> keywords;

    // ── Visibility ───────────────────────────────────────────────────────────
    /** Public flag of this specific media record. */
    private Boolean isPublic;

    // ── Audit ────────────────────────────────────────────────────────────────
    private Instant createdAt;
    private Instant updatedAt;
    private Instant removedAt;
    private String createdBy;
    private String updatedBy;
    private String removedBy;

    // ── Full original DTOs — exactly one is non-null, matched by 'type' ──────
    private AudioResponseDTO audio;
    private VideoResponseDTO video;
    private ImageResponseDTO image;
    private TextResponseDTO text;
}
