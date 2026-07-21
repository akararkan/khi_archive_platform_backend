package ak.dev.khi_archive_platform.platform.dto.analytics;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.Map;

/**
 * Snapshot of public-vs-hidden visibility across the archive right now. Counts
 * ACTIVE rows only ({@code removed_at IS NULL}); it is a live snapshot, not
 * audit-activity, and is unaffected by any date-window filter.
 *
 * <p>Three views are provided:
 * <ul>
 *   <li>Projects — {@code projectsVisible} / {@code projectsHidden} from
 *       {@code projects.is_visible_to_public}.</li>
 *   <li>Media by type — {@link #mediaByType} keyed by {@code audio, video,
 *       image, text} each carrying the public/private split of
 *       {@code is_public}, plus the {@code mediaPublicTotal} /
 *       {@code mediaPrivateTotal} roll-ups.</li>
 *   <li>Items inside hidden projects — {@code itemsInHiddenProjects} counts
 *       active media whose parent project is itself hidden (so an item can be
 *       "public" yet still invisible to guests because its project is hidden),
 *       with {@code itemsInVisibleProjects} as the complement.</li>
 * </ul>
 *
 * <p>Note: person, category, maqam and physical_media have no visibility flag
 * and are therefore not represented here.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VisibilityStatsDTO implements Serializable {
    /** Active projects visible to the public. */
    private long projectsVisible;
    /** Active projects hidden from the public. */
    private long projectsHidden;
    /** projectsVisible + projectsHidden. */
    private long projectsTotal;

    /** Per media-type public/private split, keyed audio/video/image/text. */
    private Map<String, VisibilityTypeCountDTO> mediaByType;
    /** Active media flagged public across all four media types. */
    private long mediaPublicTotal;
    /** Active media flagged private across all four media types. */
    private long mediaPrivateTotal;

    /** Active media whose parent project is visible. */
    private long itemsInVisibleProjects;
    /** Active media whose parent project is hidden (item invisible to guests
     *  regardless of its own is_public flag). */
    private long itemsInHiddenProjects;

    /** When this snapshot was computed (server clock). */
    private Instant generatedAt;
}
