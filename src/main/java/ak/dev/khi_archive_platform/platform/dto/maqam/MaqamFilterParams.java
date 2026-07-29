package ak.dev.khi_archive_platform.platform.dto.maqam;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.Instant;

/**
 * Filter + sort parameters for the active maqam listing
 * ({@code GET /api/maqam}). Bound from the query string via
 * {@code @ModelAttribute}, the same style {@code AudioFilterParams} uses, so the
 * maqam admin table gets the same filter contract every other entity has.
 *
 * <p>Because a maqam record's classification lives on its teacher-vote panel
 * (rather than on flat columns), this adds a small family of panel-aware
 * filters on top of the usual text/date/number operators:
 * <ul>
 *   <li><b>Sort</b>: {@code sortBy} + {@code sortDirection} (asc|desc, default asc).
 *       Keys: {@code maqamCode}, {@code songName}, {@code producer},
 *       {@code duration}, {@code createdAt}, {@code updatedAt}.</li>
 *   <li><b>Long-text contains</b> (case-insensitive substring): {@code songName},
 *       {@code producer}, {@code maqamCode}, {@code archiveNote},
 *       {@code audioFileName}, {@code createdBy}, {@code updatedBy}.</li>
 *   <li><b>Numeric range</b> (inclusive, seconds): {@code durationSecondsMin/Max}.</li>
 *   <li><b>Date ranges</b> (inclusive ISO instants): {@code createdFrom/To},
 *       {@code updatedFrom/To}.</li>
 *   <li><b>Panel filters</b>: {@code teacherUserId} (record has that teacher on
 *       its panel), {@code teacherUsername} (contains, any panel member),
 *       {@code maqamType} (case-insensitive exact — any panel member voted it),
 *       {@code assignmentStatus} ({@code assigned}|{@code unassigned}),
 *       {@code voteStatus} ({@code none}|{@code partial}|{@code full}).</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MaqamFilterParams {

    private String sortBy;
    private String sortDirection;

    // ─── Long-text contains (case-insensitive substring) ──────────────────────────
    private String songName;
    private String producer;
    private String maqamCode;
    private String archiveNote;
    private String audioFileName;
    private String createdBy;
    private String updatedBy;

    // ─── Numeric range (audio duration, seconds; inclusive) ───────────────────────
    private Long durationSecondsMin;
    private Long durationSecondsMax;

    // ─── Date ranges (inclusive ISO instants) ─────────────────────────────────────
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) private Instant createdFrom;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) private Instant createdTo;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) private Instant updatedFrom;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) private Instant updatedTo;

    // ─── Teacher-panel filters ────────────────────────────────────────────────────
    /** Records where this user id is on the teacher panel. */
    private Long teacherUserId;
    /** Case-insensitive substring against any panel member's username. */
    private String teacherUsername;
    /** Case-insensitive exact maqam type voted by any panel member. */
    private String maqamType;
    /** {@code assigned} (has ≥1 teacher) | {@code unassigned} (empty panel). */
    private String assignmentStatus;
    /** {@code none} (no votes cast) | {@code partial} (some but not all voted)
     *  | {@code full} (every panel member voted). */
    private String voteStatus;

    /**
     * True only when no filter/sort param is set at all — the fully unfiltered,
     * unsorted request that takes the fast DB-paged path with the default order.
     */
    public boolean isEmpty() {
        return blank(sortBy) && blank(sortDirection) && !hasActiveFilters();
    }

    /**
     * True when at least one <em>non-sort</em> filter is set. A sort-only
     * request (just {@code sortBy}/{@code sortDirection}) returns {@code false}
     * here, which lets the service keep the fast DB-paged path and push the
     * ordering into the database instead of loading the whole visible set to
     * sort it in memory.
     */
    public boolean hasActiveFilters() {
        return !blank(songName) || !blank(producer) || !blank(maqamCode)
                || !blank(archiveNote) || !blank(audioFileName)
                || !blank(createdBy) || !blank(updatedBy)
                || durationSecondsMin != null || durationSecondsMax != null
                || createdFrom != null || createdTo != null
                || updatedFrom != null || updatedTo != null
                || teacherUserId != null || !blank(teacherUsername)
                || !blank(maqamType) || !blank(assignmentStatus) || !blank(voteStatus);
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
}
