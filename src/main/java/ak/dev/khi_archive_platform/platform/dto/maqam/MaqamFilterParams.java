package ak.dev.khi_archive_platform.platform.dto.maqam;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

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
 *   <li><b>Date ranges</b> ({@code YYYY-MM-DD}, resolved to day bounds in the
 *       archive zone — see {@code ArchiveTime}): {@code createdFrom/To},
 *       {@code updatedFrom/To}, {@code removedFrom/To}.</li>
 *   <li><b>Free-text</b>: {@code q} — substring across key fields + vote panel,
 *       combinable with all filters/sort.</li>
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

    /**
     * Optional free-text query — case-insensitive substring matched across the
     * record's key fields <em>and</em> its vote panel (maqam code, song,
     * producer, archive note, file name, voted maqam types, teacher names).
     * Unlike the dedicated ranked {@code /search} endpoint, {@code q} composes
     * with every filter and sort below, so "type <i>wedding</i> and keep the
     * unvoted ones" is a single request.
     */
    private String q;

    // ─── Long-text contains (case-insensitive substring) ──────────────────────────
    private String songName;
    private String producer;
    private String maqamCode;
    private String archiveNote;
    private String audioFileName;
    private String createdBy;
    private String updatedBy;
    /** Contains-match on who trashed the record. Meaningful on the trash
     *  listing; inert on the active list. */
    private String removedBy;

    // ─── Numeric range (audio duration, seconds; inclusive) ───────────────────────
    private Long durationSecondsMin;
    private Long durationSecondsMax;

    // ─── Date ranges (YYYY-MM-DD, resolved in the archive zone) ───────────────────
    // Bare calendar dates; the backend resolves each day's bounds in
    // Asia/Baghdad (ArchiveTime), so every client agrees without sending offsets.
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) private LocalDate createdFrom;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) private LocalDate createdTo;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) private LocalDate updatedFrom;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) private LocalDate updatedTo;
    /** Inclusive range over {@code removedAt} — "what did we trash last week?".
     *  Meaningful on the trash listing. */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) private LocalDate removedFrom;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) private LocalDate removedTo;

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
        return !blank(q)
                || !blank(songName) || !blank(producer) || !blank(maqamCode)
                || !blank(archiveNote) || !blank(audioFileName)
                || !blank(createdBy) || !blank(updatedBy) || !blank(removedBy)
                || durationSecondsMin != null || durationSecondsMax != null
                || createdFrom != null || createdTo != null
                || updatedFrom != null || updatedTo != null
                || removedFrom != null || removedTo != null
                || teacherUserId != null || !blank(teacherUsername)
                || !blank(maqamType) || !blank(assignmentStatus) || !blank(voteStatus);
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
}
