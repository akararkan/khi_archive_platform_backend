package ak.dev.khi_archive_platform.platform.dto.guest;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * Response of {@code GET /api/guest/media/{type}/{code}} — the detail page
 * for one media item, whatever its kind.
 *
 * <p>A search result carries {@code type} and {@code code}; handing those two
 * back to this endpoint returns the full record without the caller having to
 * know that audios live under {@code /audios} and files under {@code /texts}.
 * The kind-specific payload is on exactly one of {@link #audio},
 * {@link #video}, {@link #image}, {@link #text} — the one naming
 * {@link #type} — and {@link #item} repeats it in the same flat card shape
 * used by the search results, so headers and breadcrumbs need no branching.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GuestMediaItemDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** {@code audio} | {@code video} | {@code image} | {@code text}. */
    private String type;

    private String code;

    /** The same flat shape the search returns, for the page header. */
    private GuestMediaHitDTO item;

    // ── Exactly one of these is non-null, matching {@link #type} ─────────────

    private GuestAudioDTO audio;
    private GuestVideoDTO video;
    private GuestImageDTO image;
    private GuestTextDTO text;

    /**
     * Other public media from the same project, this item excluded — the
     * "More from this collection" rail. Empty when the item has no project or
     * the project holds nothing else public. Omitted when {@code related=false}.
     */
    private List<GuestMediaHitDTO> related;
}
