package ak.dev.khi_archive_platform.platform.service.analytics;

import ak.dev.khi_archive_platform.platform.dto.analytics.MaqamTeacherOverviewDTO;
import ak.dev.khi_archive_platform.platform.dto.analytics.TeacherActivityDTO;
import ak.dev.khi_archive_platform.platform.repo.maqam.ListOfMaqamRepository;
import ak.dev.khi_archive_platform.platform.repo.maqam.MaqamAudioListenSessionRepository;
import ak.dev.khi_archive_platform.platform.repo.maqam.MaqamTeacherVoteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Aggregate analytics for the maqam teacher-classification workflow. Sourced
 * directly from the operational tables — {@code maqam_teacher_votes}
 * (assignment + vote + rolled-up listen aggregates),
 * {@code maqam_audio_listen_sessions} (granular play sessions) and the live
 * {@code list_of_maqam} table — NOT from the audit-log union (which drops the
 * teacher / maqam_type / seconds_listened columns).
 *
 * <p>All record-scoped metrics count ACTIVE records only (parent
 * {@code removed_at IS NULL}). Computed live; only ever read by admins.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MaqamAnalyticsService {

    private final ListOfMaqamRepository maqamRepository;
    private final MaqamTeacherVoteRepository voteRepository;
    private final MaqamAudioListenSessionRepository listenSessionRepository;

    /** Team-level classification progress plus a per-teacher leaderboard. */
    public MaqamTeacherOverviewDTO getOverview() {
        // Fetch the per-teacher listen rollup once and reuse it for both the
        // leaderboard and the global totals (the query is uncached).
        Map<Long, long[]> listenByTeacher = loadListenByTeacher();
        List<TeacherActivityDTO> teachers = buildTeacherLeaderboard(listenByTeacher);

        long totalAssignments = teachers.stream().mapToLong(TeacherActivityDTO::getAssignedRecords).sum();
        long totalVotesCast = teachers.stream().mapToLong(TeacherActivityDTO::getVotesCast).sum();

        // Record-level classification buckets from the per-record vote stats.
        long unclassified = 0, partial = 0, full = 0, consensus = 0, disagreement = 0;
        for (Object[] r : maqamRepository.perRecordVoteStats()) {
            long assigned = longOf(r[1]);
            long voted = longOf(r[2]);
            long distinctTypes = longOf(r[3]);
            if (voted == 0) {
                unclassified++;
            } else if (voted < assigned) {
                partial++;
            } else { // voted == assigned && assigned > 0 → fully voted
                full++;
                // distinctTypes counts distinct non-null maqam_type values.
                // Exactly one → the panel agreed; two or more → they disagree.
                // Zero (every vote left the type blank) is neither.
                if (distinctTypes == 1) consensus++;
                else if (distinctTypes >= 2) disagreement++;
            }
        }

        // Global listen totals — summed from the leaderboard rows so the
        // headline always reconciles with the per-teacher table (same active
        // scope, same sources), matching the totalAssignments/totalVotesCast
        // pattern above. Every teacher who listened to an active record also
        // has an active-record vote row, so no listener is dropped.
        long totalListenSessions = teachers.stream().mapToLong(TeacherActivityDTO::getListenSessions).sum();
        long totalListenSeconds = teachers.stream().mapToLong(TeacherActivityDTO::getTotalListenSeconds).sum();

        Map<String, Long> typeDistribution = new LinkedHashMap<>();
        for (Object[] r : maqamRepository.maqamTypeDistribution()) {
            typeDistribution.put((String) r[0], longOf(r[1]));
        }

        return MaqamTeacherOverviewDTO.builder()
                .totalTeachers(teachers.size())
                .totalActiveRecords(maqamRepository.countByRemovedAtIsNull())
                .totalAssignments(totalAssignments)
                .totalVotesCast(totalVotesCast)
                .totalPendingVotes(totalAssignments - totalVotesCast)
                .recordsUnclassified(unclassified)
                .recordsPartiallyVoted(partial)
                .recordsFullyVoted(full)
                .recordsWithConsensus(consensus)
                .recordsWithDisagreement(disagreement)
                .totalListenSessions(totalListenSessions)
                .totalListenSeconds(totalListenSeconds)
                .maqamTypeDistribution(typeDistribution)
                .teachers(teachers)
                .generatedAt(Instant.now())
                .build();
    }

    /** The per-teacher leaderboard on its own (ordered by votes then listening). */
    public List<TeacherActivityDTO> getTeachers() {
        return buildTeacherLeaderboard(loadListenByTeacher());
    }

    /**
     * One teacher's activity, matched case-insensitively on the snapshotted
     * teacher username. Returns {@code null} when the username is not on any
     * active-record panel.
     */
    public TeacherActivityDTO getTeacher(String username) {
        if (username == null || username.isBlank()) return null;
        String needle = username.trim().toLowerCase(Locale.ROOT);
        return buildTeacherLeaderboard(loadListenByTeacher()).stream()
                .filter(t -> t.getTeacherUsername() != null
                        && t.getTeacherUsername().toLowerCase(Locale.ROOT).equals(needle))
                .findFirst()
                .orElse(null);
    }

    // ─── Internal ────────────────────────────────────────────────────────────

    /** Listen-session stats keyed by teacherUserId: {@code [sessions, seconds,
     *  distinctRecords]}, over active records only. */
    private Map<Long, long[]> loadListenByTeacher() {
        Map<Long, long[]> listenByTeacher = new LinkedHashMap<>();
        for (Object[] r : listenSessionRepository.listenStatsByTeacher()) {
            Long teacherId = r[0] == null ? null : ((Number) r[0]).longValue();
            if (teacherId == null) continue;
            listenByTeacher.put(teacherId, new long[]{longOf(r[1]), longOf(r[2]), longOf(r[3])});
        }
        return listenByTeacher;
    }

    private List<TeacherActivityDTO> buildTeacherLeaderboard(Map<Long, long[]> listenByTeacher) {
        List<TeacherActivityDTO> out = new ArrayList<>();
        for (Object[] r : voteRepository.teacherVoteStats()) {
            Long teacherId = r[0] == null ? null : ((Number) r[0]).longValue();
            long assigned = longOf(r[3]);
            long voted = longOf(r[4]);
            long[] listen = teacherId == null ? null : listenByTeacher.get(teacherId);

            out.add(TeacherActivityDTO.builder()
                    .teacherUserId(teacherId)
                    .teacherUsername((String) r[1])
                    .teacherDisplayName((String) r[2])
                    .assignedRecords(assigned)
                    .votesCast(voted)
                    .pendingVotes(assigned - voted)
                    .distinctMaqamTypes(longOf(r[5]))
                    .totalListenSeconds(longOf(r[6]))
                    .maxPositionSeconds(longOf(r[7]))
                    .listenSessions(listen == null ? 0 : listen[0])
                    .recordsListened(listen == null ? 0 : listen[2])
                    .firstVotedAt(instantOf(r[8]))
                    .lastListenAt(instantOf(r[9]))
                    .build());
        }

        out.sort(Comparator
                .comparingLong(TeacherActivityDTO::getVotesCast).reversed()
                .thenComparing(Comparator.comparingLong(TeacherActivityDTO::getTotalListenSeconds).reversed()));
        return out;
    }

    private static long longOf(Object value) {
        if (value == null) return 0L;
        if (value instanceof Number n) return n.longValue();
        return Long.parseLong(value.toString());
    }

    private static Instant instantOf(Object value) {
        if (value == null) return null;
        if (value instanceof Instant i) return i;
        if (value instanceof Timestamp ts) return ts.toInstant();
        if (value instanceof OffsetDateTime odt) return odt.toInstant();
        if (value instanceof LocalDateTime ldt) return ldt.toInstant(ZoneOffset.UTC);
        return null;
    }
}
