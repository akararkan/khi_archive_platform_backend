package ak.dev.khi_archive_platform.platform.service.maqam;

import ak.dev.khi_archive_platform.platform.dto.maqam.MaqamFilterParams;
import ak.dev.khi_archive_platform.platform.dto.maqam.MaqamResponseDTO;
import ak.dev.khi_archive_platform.platform.dto.maqam.MaqamTeacherVoteDTO;
import ak.dev.khi_archive_platform.platform.service.common.ArchiveTime;
import ak.dev.khi_archive_platform.platform.service.common.KurdishText;
import ak.dev.khi_archive_platform.platform.service.common.SortSupport;
import org.springframework.data.domain.Sort;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * In-memory filter + sort over a list of active {@link MaqamResponseDTO}.
 *
 * <p>Same engine shape as {@code AudioFilterSupport}: one linear pass with
 * cheap-first short-circuiting, then an optional comparator sort from a small
 * synonym whitelist. On top of the usual text/number/date operators it adds a
 * family of teacher-panel predicates ({@code teacherUserId}, {@code maqamType},
 * {@code assignmentStatus}, {@code voteStatus}) that read the record's
 * {@code teacherVotes} — because a maqam record's classification state lives on
 * its votes, not on flat columns. With no params it returns the source as-is.
 */
final class MaqamFilterSupport {

    private MaqamFilterSupport() {}

    static List<MaqamResponseDTO> applyFiltersAndSort(
            List<MaqamResponseDTO> source,
            MaqamFilterParams params) {

        if (source == null || source.isEmpty()) {
            return List.of();
        }
        if (params == null || params.isEmpty()) {
            return source;
        }

        // ── Pre-normalize needles once ─────────────────────────────────────────
        String q = lower(params.getQ());
        String songName = lower(params.getSongName());
        String producer = lower(params.getProducer());
        String maqamCode = lower(params.getMaqamCode());
        String archiveNote = lower(params.getArchiveNote());
        String audioFileName = lower(params.getAudioFileName());
        String createdBy = lower(params.getCreatedBy());
        String updatedBy = lower(params.getUpdatedBy());
        String removedBy = lower(params.getRemovedBy());
        String teacherUsername = lower(params.getTeacherUsername());
        String maqamType = lower(params.getMaqamType());
        String assignmentStatus = lower(params.getAssignmentStatus());
        String voteStatus = lower(params.getVoteStatus());
        Long teacherUserId = params.getTeacherUserId();

        // ── Linear scan ────────────────────────────────────────────────────────
        List<MaqamResponseDTO> filtered = new ArrayList<>(Math.min(source.size(), 256));
        for (MaqamResponseDTO m : source) {

            // Numeric range (audio duration, seconds)
            if (!withinLongRange(m.getAudioDurationSeconds(),
                    params.getDurationSecondsMin(), params.getDurationSecondsMax())) continue;

            // Date ranges — YYYY-MM-DD bounds resolved to instants in the archive zone
            if (!withinInstantRange(m.getCreatedAt(),
                    ArchiveTime.startOfDay(params.getCreatedFrom()),
                    ArchiveTime.endOfDay(params.getCreatedTo()))) continue;
            if (!withinInstantRange(m.getUpdatedAt(),
                    ArchiveTime.startOfDay(params.getUpdatedFrom()),
                    ArchiveTime.endOfDay(params.getUpdatedTo()))) continue;
            if (!withinInstantRange(m.getRemovedAt(),
                    ArchiveTime.startOfDay(params.getRemovedFrom()),
                    ArchiveTime.endOfDay(params.getRemovedTo()))) continue;

            // Long-text contains
            if (songName != null && !containsLower(m.getSongName(), songName)) continue;
            if (producer != null && !containsLower(m.getProducer(), producer)) continue;
            if (maqamCode != null && !containsLower(m.getMaqamCode(), maqamCode)) continue;
            if (archiveNote != null && !containsLower(m.getArchiveNote(), archiveNote)) continue;
            if (audioFileName != null && !containsLower(m.getAudioFileName(), audioFileName)) continue;
            if (createdBy != null && !containsLower(m.getCreatedBy(), createdBy)) continue;
            if (updatedBy != null && !containsLower(m.getUpdatedBy(), updatedBy)) continue;
            if (removedBy != null && !containsLower(m.getRemovedBy(), removedBy)) continue;

            // Teacher-panel predicates (walk the votes once each)
            List<MaqamTeacherVoteDTO> votes = m.getTeacherVotes() == null ? List.of() : m.getTeacherVotes();

            if (teacherUserId != null && !hasTeacher(votes, teacherUserId)) continue;
            if (teacherUsername != null && !anyUsernameContains(votes, teacherUsername)) continue;
            if (maqamType != null && !anyMaqamTypeEquals(votes, maqamType)) continue;
            if (assignmentStatus != null && !matchesAssignment(votes, assignmentStatus)) continue;
            if (voteStatus != null && !voteStatusOf(votes).equals(voteStatus)) continue;

            // Free-text q — broad OR across searchable fields + vote panel; last.
            if (q != null && !matchesQuery(m, votes, q)) continue;

            filtered.add(m);
        }

        // Always finish on a deterministic total order (chosen key, then id ASC
        // as the tiebreaker) so paging is stable across requests — mirrors the
        // ", id ASC" that SortSupport appends on the DB fast path.
        Comparator<MaqamResponseDTO> comparator = comparatorFor(params.getSortBy());
        if (comparator != null) {
            if ("desc".equalsIgnoreCase(params.getSortDirection())) {
                comparator = comparator.reversed();
            }
            comparator = comparator.thenComparing(
                    MaqamResponseDTO::getId, Comparator.nullsLast(Long::compareTo));
        } else {
            comparator = Comparator.comparing(
                    MaqamResponseDTO::getId, Comparator.nullsLast(Long::compareTo));
        }
        filtered.sort(comparator);

        return filtered;
    }

    // ─── Panel predicates ───────────────────────────────────────────────────────

    /** Case-insensitive substring across the record's searchable fields and its
     *  vote panel — powers the {@code q} free-text filter. */
    private static boolean matchesQuery(MaqamResponseDTO m, List<MaqamTeacherVoteDTO> votes, String qLower) {
        if (containsLower(m.getMaqamCode(), qLower)
                || containsLower(m.getSongName(), qLower)
                || containsLower(m.getProducer(), qLower)
                || containsLower(m.getArchiveNote(), qLower)
                || containsLower(m.getAudioFileName(), qLower)) {
            return true;
        }
        for (MaqamTeacherVoteDTO v : votes) {
            if (containsLower(v.getMaqamType(), qLower)
                    || containsLower(v.getTeacherUsername(), qLower)
                    || containsLower(v.getTeacherDisplayName(), qLower)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasTeacher(List<MaqamTeacherVoteDTO> votes, Long teacherUserId) {
        for (MaqamTeacherVoteDTO v : votes) {
            if (Objects.equals(v.getTeacherUserId(), teacherUserId)) return true;
        }
        return false;
    }

    private static boolean anyUsernameContains(List<MaqamTeacherVoteDTO> votes, String needleLower) {
        for (MaqamTeacherVoteDTO v : votes) {
            if (containsLower(v.getTeacherUsername(), needleLower)) return true;
        }
        return false;
    }

    private static boolean anyMaqamTypeEquals(List<MaqamTeacherVoteDTO> votes, String needleLower) {
        for (MaqamTeacherVoteDTO v : votes) {
            if (equalsLower(v.getMaqamType(), needleLower)) return true;
        }
        return false;
    }

    private static boolean matchesAssignment(List<MaqamTeacherVoteDTO> votes, String status) {
        boolean assigned = !votes.isEmpty();
        return switch (status) {
            case "assigned" -> assigned;
            case "unassigned" -> !assigned;
            default -> true; // unknown value → no-op filter
        };
    }

    /**
     * {@code none} — no vote has been cast; {@code full} — every assigned
     * teacher has cast a vote; {@code partial} — some but not all have.
     * A vote is "cast" once {@code votedAt} is set (mirrors the service, which
     * freezes {@code votedAt} on the first VOTE_CAST).
     */
    private static String voteStatusOf(List<MaqamTeacherVoteDTO> votes) {
        int assigned = votes.size();
        int voted = 0;
        for (MaqamTeacherVoteDTO v : votes) {
            if (v.getVotedAt() != null) voted++;
        }
        if (voted == 0) return "none";
        if (assigned > 0 && voted >= assigned) return "full";
        return "partial";
    }

    // ─── Predicates ───────────────────────────────────────────────────────────────

    static boolean withinInstantRange(Instant value, Instant from, Instant to) {
        if (from == null && to == null) return true;
        if (value == null) return false;
        if (from != null && value.isBefore(from)) return false;
        if (to != null && value.isAfter(to)) return false;
        return true;
    }

    static boolean withinLongRange(Long value, Long min, Long max) {
        if (min == null && max == null) return true;
        if (value == null) return false;
        if (min != null && value < min) return false;
        if (max != null && value > max) return false;
        return true;
    }

    static boolean equalsLower(String value, String needleLower) {
        return value != null && KurdishText.normalize(value).equals(needleLower);
    }

    static boolean containsLower(String value, String needleLower) {
        return value != null && KurdishText.normalize(value).contains(needleLower);
    }

    static String lower(String s) {
        if (s == null) return null;
        String t = KurdishText.normalize(s);
        return t.isEmpty() ? null : t;
    }

    // ─── Sort ─────────────────────────────────────────────────────────────────────

    private static Comparator<MaqamResponseDTO> comparatorFor(String sortBy) {
        if (sortBy == null || sortBy.isBlank()) return null;
        return switch (sortBy.toLowerCase(Locale.ROOT)) {
            case "maqamcode", "code" -> Comparator.comparing(
                    MaqamResponseDTO::getMaqamCode,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "songname", "song", "title", "name", "alpha", "alphabet", "alphabetical" -> Comparator.comparing(
                    MaqamResponseDTO::getSongName,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "producer", "singer" -> Comparator.comparing(
                    MaqamResponseDTO::getProducer,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "duration", "durationseconds", "audiodurationseconds" -> Comparator.comparing(
                    MaqamResponseDTO::getAudioDurationSeconds,
                    Comparator.nullsLast(Long::compareTo));
            case "createdat", "created", "added", "dateadded", "date_added" -> Comparator.comparing(
                    MaqamResponseDTO::getCreatedAt,
                    Comparator.nullsLast(Instant::compareTo));
            case "updatedat", "updated", "modified", "datemodified", "date_modified" -> Comparator.comparing(
                    MaqamResponseDTO::getUpdatedAt,
                    Comparator.nullsLast(Instant::compareTo));
            default -> null;
        };
    }

    // ─── DB-side sort (fast path) ───────────────────────────────────────────────

    /**
     * Translate {@code sortBy}/{@code sortDirection} into a Spring Data
     * {@link Sort} for the DB-paged fast path (sort-only requests — no
     * full-set in-memory load). Every maqam sort key maps to a real
     * {@code list_of_maqam} column and mirrors {@link #comparatorFor(String)}
     * (case-insensitive for text via {@code LOWER()}; NULL placement left to
     * the DB native default, which matches {@code Comparator.nullsLast}).
     * Returns {@link Sort#unsorted()} for a blank/unknown key.
     *
     * <p>Note: this is used only by the admin/employee listing, which reads
     * through a plain derived query. The teacher listing uses a
     * {@code SELECT DISTINCT … JOIN} that PostgreSQL will not let us
     * {@code ORDER BY LOWER(...)} on, so the service routes a sorting teacher
     * to the in-memory engine over their (small) assigned set.
     */
    static Sort resolveDbSort(String sortBy, String sortDirection) {
        if (sortBy == null || sortBy.isBlank()) return Sort.unsorted();
        return switch (sortBy.toLowerCase(Locale.ROOT)) {
            case "maqamcode", "code" -> SortSupport.ci("maqamCode", sortDirection);
            case "songname", "song", "title", "name", "alpha", "alphabet", "alphabetical" ->
                    SortSupport.ci("songName", sortDirection);
            case "producer", "singer" -> SortSupport.ci("producer", sortDirection);
            case "duration", "durationseconds", "audiodurationseconds" ->
                    SortSupport.plain("audioDurationSeconds", sortDirection);
            case "createdat", "created", "added", "dateadded", "date_added" ->
                    SortSupport.plain("createdAt", sortDirection);
            case "updatedat", "updated", "modified", "datemodified", "date_modified" ->
                    SortSupport.plain("updatedAt", sortDirection);
            default -> Sort.unsorted();
        };
    }

    /**
     * True when {@code sortBy} names a key we can sort in memory but cannot
     * push to the DB. None for maqam — every comparator key maps to a column —
     * so this is always {@code false}; kept for routing symmetry with the
     * other entities.
     */
    static boolean requiresInMemorySort(String sortBy, String sortDirection) {
        return comparatorFor(sortBy) != null && resolveDbSort(sortBy, sortDirection).isUnsorted();
    }
}
