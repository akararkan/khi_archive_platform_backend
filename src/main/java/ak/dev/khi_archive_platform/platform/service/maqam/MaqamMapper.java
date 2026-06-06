package ak.dev.khi_archive_platform.platform.service.maqam;

import ak.dev.khi_archive_platform.platform.dto.maqam.MaqamListenSessionDTO;
import ak.dev.khi_archive_platform.platform.dto.maqam.MaqamResponseDTO;
import ak.dev.khi_archive_platform.platform.dto.maqam.MaqamTeacherRecentDTO;
import ak.dev.khi_archive_platform.platform.dto.maqam.MaqamTeacherVoteDTO;
import ak.dev.khi_archive_platform.platform.model.maqam.ListOfMaqam;
import ak.dev.khi_archive_platform.platform.model.maqam.MaqamAudioListenSession;
import ak.dev.khi_archive_platform.platform.model.maqam.MaqamTeacherVote;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * Entity → DTO conversion. Kept manual (rather than MapStruct) so the
 * "never expose audio_file_url" rule is enforceable by inspection — there is
 * exactly one place where the entity-to-response translation happens and it
 * never reads {@code getAudioFileUrl()}.
 */
@Component
public class MaqamMapper {

    /**
     * @param baseStreamUrl base URL ("/api/maqam/{maqamCode}/stream") the
     *                      service layer pre-computes for the current
     *                      request scheme + host. Stored on the response so
     *                      teachers/employees never see the S3 link.
     */
    public MaqamResponseDTO toResponse(ListOfMaqam m, String baseStreamUrl, boolean includePeerVotes) {
        if (m == null) return null;

        List<MaqamTeacherVoteDTO> votes = m.getTeacherVotes() == null ? List.of()
                : m.getTeacherVotes().stream()
                    .sorted(Comparator.comparing(MaqamTeacherVote::getAssignedAt,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .map(this::toVoteDTO)
                    .toList();

        return MaqamResponseDTO.builder()
                .id(m.getId())
                .maqamCode(m.getMaqamCode())
                .songName(m.getSongName())
                .producer(m.getProducer())
                .audioFileName(m.getAudioFileName())
                .audioContentType(m.getAudioContentType())
                .audioFileSizeBytes(m.getAudioFileSizeBytes())
                .audioDurationSeconds(m.getAudioDurationSeconds())
                .streamUrl(baseStreamUrl)
                .archiveNote(m.getArchiveNote())
                .teacherVotes(includePeerVotes ? votes : votes)
                .createdAt(m.getCreatedAt())
                .updatedAt(m.getUpdatedAt())
                .removedAt(m.getRemovedAt())
                .createdBy(m.getCreatedBy())
                .updatedBy(m.getUpdatedBy())
                .removedBy(m.getRemovedBy())
                .build();
    }

    public MaqamTeacherVoteDTO toVoteDTO(MaqamTeacherVote v) {
        if (v == null) return null;
        return MaqamTeacherVoteDTO.builder()
                .voteId(v.getId())
                .teacherUserId(v.getTeacherUserId())
                .teacherUsername(v.getTeacherUsername())
                .teacherDisplayName(v.getTeacherDisplayName())
                .maqamType(v.getMaqamType())
                .teacherNote(v.getTeacherNote())
                .votedAt(v.getVotedAt())
                .updatedAt(v.getUpdatedAt())
                .assignedAt(v.getAssignedAt())
                .assignedBy(v.getAssignedBy())
                .totalListenSeconds(v.getTotalListenSeconds())
                .maxPositionSeconds(v.getMaxPositionSeconds())
                .lastListenAt(v.getLastListenAt())
                .build();
    }

    /**
     * Build the per-record row of the teacher's "recent activity" feed.
     *
     * <p>{@code lastActivityAt} is computed as the latest of
     * {@code lastListenAt}, {@code updatedAt}, {@code votedAt},
     * {@code assignedAt} — whichever is the most recent thing the teacher
     * did. {@code coverageRatio} = {@code totalListenSeconds / duration},
     * clamped to {@code [0, 1]}; null when duration is unknown.
     *
     * @param baseStreamUrl pre-computed {@code /api/maqam/{code}/stream}
     *                      URL so the row is playable inline without
     *                      another round-trip and without exposing S3.
     */
    public MaqamTeacherRecentDTO toRecent(MaqamTeacherVote v, ListOfMaqam m, String baseStreamUrl) {
        if (v == null || m == null) return null;

        Long duration = m.getAudioDurationSeconds();
        Long total = v.getTotalListenSeconds();
        Double coverage = (duration == null || duration <= 0 || total == null)
                ? null
                : Math.min(1.0, total.doubleValue() / duration.doubleValue());

        Instant lastActivity = latestOf(v.getLastListenAt(), v.getUpdatedAt(),
                v.getVotedAt(), v.getAssignedAt());

        return MaqamTeacherRecentDTO.builder()
                .voteId(v.getId())
                .maqamId(m.getId())
                .maqamCode(m.getMaqamCode())
                .songName(m.getSongName())
                .producer(m.getProducer())
                .archiveNote(m.getArchiveNote())
                .audioFileName(m.getAudioFileName())
                .audioDurationSeconds(duration)
                .streamUrl(baseStreamUrl)
                .maqamType(v.getMaqamType())
                .teacherNote(v.getTeacherNote())
                .hasVoted(v.getMaqamType() != null && !v.getMaqamType().isBlank())
                .votedAt(v.getVotedAt())
                .updatedAt(v.getUpdatedAt())
                .assignedAt(v.getAssignedAt())
                .assignedBy(v.getAssignedBy())
                .totalListenSeconds(total == null ? 0L : total)
                .maxPositionSeconds(v.getMaxPositionSeconds() == null ? 0L : v.getMaxPositionSeconds())
                .lastListenAt(v.getLastListenAt())
                .coverageRatio(coverage)
                .lastActivityAt(lastActivity)
                .recordCreatedAt(m.getCreatedAt())
                .recordUpdatedAt(m.getUpdatedAt())
                .build();
    }

    /** Returns the latest non-null Instant. Null when every input is null. */
    private static Instant latestOf(Instant... candidates) {
        Instant best = null;
        for (Instant c : candidates) {
            if (c == null) continue;
            if (best == null || c.isAfter(best)) best = c;
        }
        return best;
    }

    public MaqamListenSessionDTO toSessionDTO(MaqamAudioListenSession s, boolean adminView) {
        if (s == null) return null;
        return MaqamListenSessionDTO.builder()
                .id(s.getId())
                .maqamId(s.getListOfMaqamId())
                .maqamCode(s.getMaqamCode())
                .teacherUserId(s.getTeacherUserId())
                .teacherUsername(s.getTeacherUsername())
                .sessionKey(s.getSessionKey())
                .startedAt(s.getStartedAt())
                .endedAt(s.getEndedAt())
                .secondsListened(s.getSecondsListened())
                .lastPositionSeconds(s.getLastPositionSeconds())
                .ipAddress(adminView ? s.getIpAddress() : null)
                .userAgent(adminView ? s.getUserAgent() : null)
                .build();
    }
}
