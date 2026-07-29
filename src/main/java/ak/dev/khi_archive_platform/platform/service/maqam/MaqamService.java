package ak.dev.khi_archive_platform.platform.service.maqam;

import ak.dev.khi_archive_platform.S3Service;
import ak.dev.khi_archive_platform.platform.dto.maqam.MaqamCreateRequestDTO;
import ak.dev.khi_archive_platform.platform.dto.maqam.MaqamFilterParams;
import ak.dev.khi_archive_platform.platform.dto.maqam.MaqamListenSessionDTO;
import ak.dev.khi_archive_platform.platform.dto.maqam.MaqamListenSummaryDTO;
import ak.dev.khi_archive_platform.platform.dto.maqam.MaqamResponseDTO;
import ak.dev.khi_archive_platform.platform.dto.maqam.MaqamTeacherAssignmentDTO;
import ak.dev.khi_archive_platform.platform.dto.maqam.MaqamTeacherRecentDTO;
import ak.dev.khi_archive_platform.platform.dto.maqam.MaqamUpdateRequestDTO;
import ak.dev.khi_archive_platform.platform.service.common.PaginationSupport;
import ak.dev.khi_archive_platform.platform.enums.MaqamAuditAction;
import ak.dev.khi_archive_platform.platform.exceptions.MaqamAccessDeniedException;
import ak.dev.khi_archive_platform.platform.exceptions.MaqamNotFoundException;
import ak.dev.khi_archive_platform.platform.exceptions.MaqamValidationException;
import ak.dev.khi_archive_platform.platform.model.maqam.ListOfMaqam;
import ak.dev.khi_archive_platform.platform.model.maqam.MaqamAudioListenSession;
import ak.dev.khi_archive_platform.platform.model.maqam.MaqamTeacherVote;
import ak.dev.khi_archive_platform.platform.repo.maqam.ListOfMaqamRepository;
import ak.dev.khi_archive_platform.platform.repo.maqam.MaqamAudioListenSessionRepository;
import ak.dev.khi_archive_platform.platform.repo.maqam.MaqamTeacherVoteRepository;
import ak.dev.khi_archive_platform.platform.service.common.CodeGenLock;
import ak.dev.khi_archive_platform.user.enums.Role;
import ak.dev.khi_archive_platform.user.model.User;
import ak.dev.khi_archive_platform.user.repo.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Coordinates every operation on List-of-Maqam records.
 *
 * <p>Splits the surface area into three concentric circles:
 * <ol>
 *   <li><b>Record CRUD</b> ({@link #create}, {@link #update}, {@link #delete},
 *       {@link #restore}, {@link #purge}) — open to {@code maqam:create} /
 *       {@code maqam:update} / {@code maqam:delete} holders. Mutations on
 *       the song fields go through here.</li>
 *   <li><b>Teacher roster</b> ({@link #assignTeachers}) — admin only. The
 *       1–3 cap is enforced here so JPA's lack of row-count validation on
 *       collections doesn't bite us.</li>
 *   <li><b>Reading</b> ({@link #getByCode}, {@link #listActive}, …) — open
 *       to {@code maqam:read}. Teachers are filtered to the records they
 *       are assigned to; admins and employees see everything.</li>
 * </ol>
 *
 * <p>The {@code MAQAM_FOLDER} S3 prefix isolates these files so they're easy
 * to audit and lifecycle-rule separately from the long-form audio archive.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class MaqamService {

    private static final String MAQAM_FOLDER = "maqam-audio";
    private static final String MAQAM_CODE_PREFIX = "MAQAM";
    private static final String MAQAM_CODE_LOCK_NAMESPACE = "maqam-code-gen";

    private final ListOfMaqamRepository maqamRepository;
    private final MaqamTeacherVoteRepository voteRepository;
    private final MaqamAudioListenSessionRepository sessionRepository;
    private final UserRepository userRepository;
    private final S3Service s3Service;
    private final MaqamMapper mapper;
    private final MaqamAuditService auditService;
    private final CodeGenLock codeGenLock;

    // ─── Create / Update / Delete ────────────────────────────────────────────

    public MaqamResponseDTO create(MaqamCreateRequestDTO dto,
                                   MultipartFile audioFile,
                                   Authentication auth,
                                   HttpServletRequest request) {
        validateAudio(audioFile);
        validateCreateBasics(dto);

        codeGenLock.lock(MAQAM_CODE_LOCK_NAMESPACE);

        long sequence = maqamRepository.count() + 1;
        String maqamCode = String.format(Locale.ROOT, "%s_%06d", MAQAM_CODE_PREFIX, sequence);

        String s3Url = s3Service.upload(audioFile, MAQAM_FOLDER);

        ListOfMaqam record = ListOfMaqam.builder()
                .maqamCode(maqamCode)
                .songName(dto.getSongName().trim())
                .producer(dto.getProducer().trim())
                .archiveNote(trimOrNull(dto.getArchiveNote()))
                .audioFileUrl(s3Url)
                .audioFileName(audioFile.getOriginalFilename())
                .audioContentType(audioFile.getContentType())
                .audioFileSizeBytes(audioFile.getSize())
                .createdBy(actorName(auth))
                .updatedBy(actorName(auth))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        ListOfMaqam saved = maqamRepository.save(record);

        if (dto.getTeacherUserIds() != null && !dto.getTeacherUserIds().isEmpty()) {
            applyTeacherRoster(saved, dto.getTeacherUserIds(), actorName(auth), auth, request, false);
        }

        auditService.record(saved, MaqamAuditAction.CREATE, auth, request,
                "song='" + saved.getSongName() + "', producer='" + saved.getProducer()
                        + "', teachers=" + (dto.getTeacherUserIds() == null ? 0 : dto.getTeacherUserIds().size()));

        return mapper.toResponse(saved, buildStreamUrl(request, saved.getMaqamCode()), true);
    }

    public MaqamResponseDTO update(String maqamCode,
                                   MaqamUpdateRequestDTO dto,
                                   MultipartFile audioFile,
                                   Authentication auth,
                                   HttpServletRequest request) {
        ListOfMaqam record = mustFindActive(maqamCode);

        StringBuilder details = new StringBuilder();
        if (dto.getSongName() != null && !dto.getSongName().isBlank()) {
            details.append("songName,");
            record.setSongName(dto.getSongName().trim());
        }
        if (dto.getProducer() != null && !dto.getProducer().isBlank()) {
            details.append("producer,");
            record.setProducer(dto.getProducer().trim());
        }
        if (dto.getArchiveNote() != null) {
            details.append("archiveNote,");
            record.setArchiveNote(trimOrNull(dto.getArchiveNote()));
        }
        if (audioFile != null && !audioFile.isEmpty()) {
            validateAudio(audioFile);
            String oldUrl = record.getAudioFileUrl();
            String s3Url = s3Service.upload(audioFile, MAQAM_FOLDER);
            record.setAudioFileUrl(s3Url);
            record.setAudioFileName(audioFile.getOriginalFilename());
            record.setAudioContentType(audioFile.getContentType());
            record.setAudioFileSizeBytes(audioFile.getSize());
            if (oldUrl != null && !oldUrl.isBlank()) {
                s3Service.deleteByUrl(oldUrl);
            }
            details.append("audioFile,");
        }
        record.setUpdatedBy(actorName(auth));
        ListOfMaqam saved = maqamRepository.save(record);

        auditService.record(saved, MaqamAuditAction.UPDATE, auth, request,
                "fields=" + (details.length() == 0 ? "<none>" : details.substring(0, details.length() - 1)));

        return mapper.toResponse(saved, buildStreamUrl(request, saved.getMaqamCode()), true);
    }

    public void delete(String maqamCode, Authentication auth, HttpServletRequest request) {
        ListOfMaqam record = mustFindActive(maqamCode);
        record.setRemovedAt(Instant.now());
        record.setRemovedBy(actorName(auth));
        maqamRepository.save(record);
        auditService.record(record, MaqamAuditAction.REMOVE, auth, request, "soft-trashed");
    }

    public MaqamResponseDTO restore(String maqamCode, Authentication auth, HttpServletRequest request) {
        ListOfMaqam record = maqamRepository.findByMaqamCode(maqamCode)
                .orElseThrow(() -> new MaqamNotFoundException("Maqam record not found: " + maqamCode));
        if (record.getRemovedAt() == null) {
            throw new MaqamValidationException("Maqam record is not in trash: " + maqamCode);
        }
        record.setRemovedAt(null);
        record.setRemovedBy(null);
        record.setUpdatedBy(actorName(auth));
        ListOfMaqam saved = maqamRepository.save(record);
        auditService.record(saved, MaqamAuditAction.RESTORE, auth, request, "restored from trash");
        return mapper.toResponse(saved, buildStreamUrl(request, saved.getMaqamCode()), true);
    }

    public void purge(String maqamCode, Authentication auth, HttpServletRequest request) {
        ListOfMaqam record = maqamRepository.findByMaqamCode(maqamCode)
                .orElseThrow(() -> new MaqamNotFoundException("Maqam record not found: " + maqamCode));
        if (record.getRemovedAt() == null) {
            throw new MaqamValidationException("Maqam record must be trashed before purge: " + maqamCode);
        }
        String s3Url = record.getAudioFileUrl();
        auditService.record(record, MaqamAuditAction.PURGE, auth, request,
                "permanent deletion incl. S3 file");
        maqamRepository.delete(record);
        if (s3Url != null && !s3Url.isBlank()) {
            s3Service.deleteByUrl(s3Url);
        }
    }

    // ─── Teacher roster ──────────────────────────────────────────────────────

    public MaqamResponseDTO assignTeachers(String maqamCode,
                                           MaqamTeacherAssignmentDTO dto,
                                           Authentication auth,
                                           HttpServletRequest request) {
        ListOfMaqam record = mustFindActive(maqamCode);
        applyTeacherRoster(record, dto.getTeacherUserIds(), actorName(auth), auth, request, true);
        ListOfMaqam saved = maqamRepository.save(record);
        return mapper.toResponse(saved, buildStreamUrl(request, saved.getMaqamCode()), true);
    }

    private void applyTeacherRoster(ListOfMaqam record,
                                    List<Long> requestedTeacherIds,
                                    String actorUsername,
                                    Authentication auth,
                                    HttpServletRequest request,
                                    boolean auditChanges) {
        List<Long> distinct = requestedTeacherIds.stream().distinct().toList();
        if (distinct.size() < ListOfMaqam.MIN_TEACHERS || distinct.size() > ListOfMaqam.MAX_TEACHERS) {
            throw new MaqamValidationException(
                    "Teacher panel must contain " + ListOfMaqam.MIN_TEACHERS + "–"
                            + ListOfMaqam.MAX_TEACHERS + " distinct teachers (got " + distinct.size() + ")");
        }
        List<User> teachers = userRepository.findAllById(distinct);
        if (teachers.size() != distinct.size()) {
            throw new MaqamValidationException("One or more teacher user IDs were not found");
        }
        for (User u : teachers) {
            if (u.getRole() != Role.TEACHER) {
                throw new MaqamValidationException(
                        "User '" + u.getUsername() + "' is not a TEACHER (role=" + u.getRole() + ")");
            }
            if (Boolean.FALSE.equals(u.getIsActivated())) {
                throw new MaqamValidationException("Teacher account is deactivated: " + u.getUsername());
            }
        }

        Map<Long, User> byId = new LinkedHashMap<>();
        for (User u : teachers) byId.put(u.getUserId(), u);

        List<MaqamTeacherVote> current = new ArrayList<>(
                record.getTeacherVotes() == null ? List.of() : record.getTeacherVotes());

        // Remove teachers no longer on the panel.
        for (MaqamTeacherVote existing : current) {
            if (!byId.containsKey(existing.getTeacherUserId())) {
                record.getTeacherVotes().remove(existing);
                if (auditChanges) {
                    auditService.record(record, MaqamAuditAction.TEACHER_REMOVED, auth, request,
                            "removed teacher=" + existing.getTeacherUsername(),
                            new MaqamAuditService.MaqamTeacherContext(
                                    existing.getTeacherUserId(),
                                    existing.getTeacherUsername(),
                                    existing.getTeacherDisplayName()),
                            null, null, null);
                }
            }
        }

        Set<Long> existingIds = current.stream()
                .filter(v -> byId.containsKey(v.getTeacherUserId()))
                .map(MaqamTeacherVote::getTeacherUserId)
                .collect(java.util.stream.Collectors.toSet());

        // Add teachers freshly on the panel.
        for (Long teacherId : distinct) {
            if (existingIds.contains(teacherId)) continue;
            User u = byId.get(teacherId);
            MaqamTeacherVote vote = MaqamTeacherVote.builder()
                    .listOfMaqam(record)
                    .teacherUserId(u.getUserId())
                    .teacherUsername(u.getUsername())
                    .teacherDisplayName(u.getName())
                    .assignedAt(Instant.now())
                    .assignedBy(actorUsername)
                    .totalListenSeconds(0L)
                    .maxPositionSeconds(0L)
                    .build();
            record.getTeacherVotes().add(vote);
            if (auditChanges) {
                auditService.record(record, MaqamAuditAction.TEACHER_ASSIGNED, auth, request,
                        "assigned teacher=" + u.getUsername(),
                        new MaqamAuditService.MaqamTeacherContext(u.getUserId(), u.getUsername(), u.getName()),
                        null, null, null);
            }
        }
    }

    // ─── Reads ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public MaqamResponseDTO getByCode(String maqamCode, Authentication auth, HttpServletRequest request) {
        ListOfMaqam record = mustFindActive(maqamCode);
        ensureCallerMaySeeRecord(record, auth);
        auditService.record(record, MaqamAuditAction.READ, auth, request, null);
        return mapper.toResponse(record, buildStreamUrl(request, record.getMaqamCode()), true);
    }

    /**
     * Active records the caller can see, with optional filter + sort.
     *
     * <p>The role-scoped visibility split is preserved on <em>both</em> paths:
     * teachers only ever see records they are assigned to, admins/employees see
     * every active record.
     *
     * <p>Two paths, chosen by {@link MaqamFilterParams#isEmpty()}:
     * <ul>
     *   <li><b>No params</b> — the original fast DB-paged query; only one page
     *       is loaded. Behaviour is exactly what it was before filters.</li>
     *   <li><b>Any param present</b> — load the caller's full visible set, map
     *       to DTOs (each carrying its per-request {@code streamUrl}), run the
     *       same in-memory filter+sort every other entity uses via
     *       {@link MaqamFilterSupport}, then slice into a {@link Page} with
     *       {@link PaginationSupport}. No read-cache is used here — the
     *       per-request stream URL and the high write-rate of votes/listen
     *       tracking make a shared DTO cache a poor fit, so the full-set load
     *       runs only on the filtered path.</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public Page<MaqamResponseDTO> listActive(Pageable pageable,
                                             MaqamFilterParams filter,
                                             Authentication auth,
                                             HttpServletRequest request) {
        MaqamFilterParams params = filter == null ? new MaqamFilterParams() : filter;
        boolean teacher = isTeacher(auth);
        boolean sortPresent = params.getSortBy() != null && !params.getSortBy().isBlank();

        // Teachers read through a DISTINCT join query that PostgreSQL won't let
        // us ORDER BY LOWER(...) on, so a sorting teacher falls to the in-memory
        // engine over their (small) assigned set. Admins/employees push the sort
        // straight to the DB.
        boolean inMemory = params.hasActiveFilters()
                || MaqamFilterSupport.requiresInMemorySort(params.getSortBy(), params.getSortDirection())
                || (teacher && sortPresent);

        Page<MaqamResponseDTO> page;
        if (inMemory) {
            List<ListOfMaqam> rows = teacher
                    ? maqamRepository.findAssignedToTeacher(currentUserId(auth), Pageable.unpaged()).getContent()
                    : maqamRepository.findAllByRemovedAtIsNull();
            List<MaqamResponseDTO> all = rows.stream()
                    .map(m -> mapper.toResponse(m, buildStreamUrl(request, m.getMaqamCode()), true))
                    .toList();
            List<MaqamResponseDTO> view = MaqamFilterSupport.applyFiltersAndSort(all, params);
            page = PaginationSupport.sliceList(view, pageable);
        } else {
            Pageable effective = effectivePageable(pageable, params);
            Page<ListOfMaqam> src = teacher
                    ? maqamRepository.findAssignedToTeacher(currentUserId(auth), effective)
                    : maqamRepository.findAllByRemovedAtIsNull(effective);
            page = src.map(m -> mapper.toResponse(m, buildStreamUrl(request, m.getMaqamCode()), true));
        }

        auditService.record(null, MaqamAuditAction.LIST, auth, request,
                "size=" + page.getNumberOfElements()
                        + " total=" + page.getTotalElements()
                        + auditSuffix(params, inMemory));
        return page;
    }

    /**
     * Soft-trashed records with the same optional filter + sort as
     * {@link #listActive}. This endpoint is admin-only ({@code maqam:delete}),
     * so there is no teacher-visibility branch — every trashed record is in
     * scope. Same two-path shape: unfiltered → fast DB-paged trash query;
     * filtered → load the full trashed set, filter+sort in memory, slice.
     */
    @Transactional(readOnly = true)
    public Page<MaqamResponseDTO> listTrash(Pageable pageable,
                                            MaqamFilterParams filter,
                                            Authentication auth,
                                            HttpServletRequest request) {
        MaqamFilterParams params = filter == null ? new MaqamFilterParams() : filter;
        boolean inMemory = params.hasActiveFilters()
                || MaqamFilterSupport.requiresInMemorySort(params.getSortBy(), params.getSortDirection());

        Page<MaqamResponseDTO> page;
        if (inMemory) {
            List<MaqamResponseDTO> all = maqamRepository.findAllByRemovedAtIsNotNull()
                    .stream()
                    .map(m -> mapper.toResponse(m, buildStreamUrl(request, m.getMaqamCode()), true))
                    .toList();
            List<MaqamResponseDTO> view = MaqamFilterSupport.applyFiltersAndSort(all, params);
            page = PaginationSupport.sliceList(view, pageable);
        } else {
            page = maqamRepository.findAllByRemovedAtIsNotNull(effectivePageable(pageable, params))
                    .map(m -> mapper.toResponse(m, buildStreamUrl(request, m.getMaqamCode()), true));
        }

        auditService.record(null, MaqamAuditAction.LIST, auth, request,
                "trash size=" + page.getNumberOfElements()
                        + " total=" + page.getTotalElements()
                        + auditSuffix(params, inMemory));
        return page;
    }

    @Transactional(readOnly = true)
    public List<MaqamResponseDTO> search(String q, int limit, Authentication auth, HttpServletRequest request) {
        if (q == null || q.isBlank()) return List.of();
        int safeLimit = Math.max(1, Math.min(limit, 100));
        List<ListOfMaqam> rows = maqamRepository.searchByText(q.trim(),
                org.springframework.data.domain.PageRequest.of(0, safeLimit));
        if (isTeacher(auth)) {
            Long me = currentUserId(auth);
            rows = rows.stream()
                    .filter(m -> m.getTeacherVotes() != null && m.getTeacherVotes().stream()
                            .anyMatch(v -> Objects.equals(v.getTeacherUserId(), me)))
                    .toList();
        }
        auditService.record(null, MaqamAuditAction.SEARCH, auth, request, "q=" + q + " hits=" + rows.size());
        return rows.stream()
                .map(m -> mapper.toResponse(m, buildStreamUrl(request, m.getMaqamCode()), true))
                .toList();
    }

    // ─── Voting (teacher-only) ──────────────────────────────────────────────

    public MaqamResponseDTO upsertVote(String maqamCode,
                                       String maqamType,
                                       String teacherNote,
                                       Authentication auth,
                                       HttpServletRequest request) {
        ListOfMaqam record = mustFindActive(maqamCode);
        User actor = mustGetCurrentUser(auth);
        if (actor.getRole() != Role.TEACHER) {
            throw new MaqamAccessDeniedException("Only teachers may cast votes");
        }
        MaqamTeacherVote vote = voteRepository
                .findByListOfMaqamAndTeacherUserId(record, actor.getUserId())
                .orElseThrow(() -> new MaqamAccessDeniedException(
                        "You are not assigned to this maqam record: " + maqamCode));

        boolean firstTime = vote.getVotedAt() == null;
        if (maqamType != null && !maqamType.isBlank()) {
            vote.setMaqamType(maqamType.trim());
        }
        if (teacherNote != null) {
            vote.setTeacherNote(trimOrNull(teacherNote));
        }
        Instant now = Instant.now();
        if (firstTime) vote.setVotedAt(now);
        vote.setUpdatedAt(now);
        voteRepository.save(vote);

        MaqamAuditAction action = firstTime ? MaqamAuditAction.VOTE_CAST : MaqamAuditAction.VOTE_UPDATED;
        auditService.record(record, action, auth, request,
                "vote='" + vote.getMaqamType() + "'",
                new MaqamAuditService.MaqamTeacherContext(actor.getUserId(), actor.getUsername(), actor.getName()),
                vote.getMaqamType(), null, null);

        ListOfMaqam refreshed = mustFindActive(maqamCode);
        return mapper.toResponse(refreshed, buildStreamUrl(request, refreshed.getMaqamCode()), true);
    }

    /** Admin-only: delete a single teacher's vote (e.g. after misconduct). */
    public MaqamResponseDTO deleteVote(String maqamCode,
                                       Long teacherUserId,
                                       Authentication auth,
                                       HttpServletRequest request) {
        ListOfMaqam record = mustFindActive(maqamCode);
        MaqamTeacherVote vote = voteRepository
                .findByListOfMaqamAndTeacherUserId(record, teacherUserId)
                .orElseThrow(() -> new MaqamNotFoundException(
                        "No vote by teacher " + teacherUserId + " on maqam " + maqamCode));
        vote.setMaqamType(null);
        vote.setTeacherNote(null);
        vote.setVotedAt(null);
        vote.setUpdatedAt(Instant.now());
        voteRepository.save(vote);
        auditService.record(record, MaqamAuditAction.VOTE_DELETED, auth, request,
                "cleared vote for teacherId=" + teacherUserId,
                new MaqamAuditService.MaqamTeacherContext(
                        vote.getTeacherUserId(),
                        vote.getTeacherUsername(),
                        vote.getTeacherDisplayName()),
                null, null, null);
        return mapper.toResponse(mustFindActive(maqamCode),
                buildStreamUrl(request, record.getMaqamCode()), true);
    }

    // ─── Listen tracking ────────────────────────────────────────────────────

    private static final long PROGRESS_PING_MAX_SECONDS = 60L;

    public MaqamListenSessionDTO startListenSession(String maqamCode,
                                                    String sessionKey,
                                                    Long startPositionSeconds,
                                                    Authentication auth,
                                                    HttpServletRequest request) {
        ListOfMaqam record = mustFindActive(maqamCode);
        User teacher = ensureTeacherAssignedTo(record, auth);

        MaqamAudioListenSession session = sessionRepository
                .findBySessionKeyAndTeacherUserId(sessionKey, teacher.getUserId())
                .orElseGet(() -> MaqamAudioListenSession.builder()
                        .listOfMaqamId(record.getId())
                        .maqamCode(record.getMaqamCode())
                        .teacherUserId(teacher.getUserId())
                        .teacherUsername(teacher.getUsername())
                        .sessionKey(sessionKey)
                        .startedAt(Instant.now())
                        .secondsListened(0L)
                        .lastPositionSeconds(0L)
                        .ipAddress(request.getRemoteAddr())
                        .userAgent(safeHeader(request, "User-Agent"))
                        .httpSessionId(request.getSession(false) == null ? null : request.getSession(false).getId())
                        .build());
        if (startPositionSeconds != null) {
            session.setLastPositionSeconds(startPositionSeconds);
        }
        sessionRepository.save(session);

        auditService.record(record, MaqamAuditAction.LISTEN_STARTED, auth, request,
                "session=" + sessionKey,
                new MaqamAuditService.MaqamTeacherContext(
                        teacher.getUserId(), teacher.getUsername(), teacher.getName()),
                null,
                new MaqamAuditService.MaqamListenContext(0L, session.getLastPositionSeconds()),
                sessionKey);

        return mapper.toSessionDTO(session, hasRole(auth, "ADMIN"));
    }

    public MaqamListenSessionDTO recordProgress(String maqamCode,
                                                String sessionKey,
                                                long addSeconds,
                                                long positionSeconds,
                                                Authentication auth,
                                                HttpServletRequest request,
                                                boolean isEnd) {
        ListOfMaqam record = mustFindActive(maqamCode);
        User teacher = ensureTeacherAssignedTo(record, auth);

        MaqamAudioListenSession session = sessionRepository
                .findBySessionKeyAndTeacherUserId(sessionKey, teacher.getUserId())
                .orElseThrow(() -> new MaqamValidationException(
                        "No open listen session with key '" + sessionKey + "'"));

        long clampedAdd = Math.max(0L, Math.min(addSeconds, PROGRESS_PING_MAX_SECONDS));
        if (record.getAudioDurationSeconds() != null && record.getAudioDurationSeconds() > 0) {
            // Total seconds listened can't exceed duration of the audio.
            long room = record.getAudioDurationSeconds() - session.getSecondsListened();
            clampedAdd = Math.max(0L, Math.min(clampedAdd, room));
        }
        session.setSecondsListened(session.getSecondsListened() + clampedAdd);
        session.setLastPositionSeconds(Math.max(positionSeconds, session.getLastPositionSeconds()));
        Instant now = Instant.now();
        if (isEnd) session.setEndedAt(now);
        sessionRepository.save(session);

        // Bump the per-teacher aggregate on the vote row.
        MaqamTeacherVote vote = voteRepository
                .findByListOfMaqamAndTeacherUserId(record, teacher.getUserId())
                .orElse(null);
        if (vote != null && clampedAdd > 0) {
            voteRepository.bumpListen(vote.getId(), clampedAdd, positionSeconds, now);
        }

        MaqamAuditAction action = isEnd ? MaqamAuditAction.LISTEN_ENDED : MaqamAuditAction.LISTEN_PROGRESS;
        auditService.record(record, action, auth, request,
                "session=" + sessionKey + " add=" + clampedAdd + "s pos=" + positionSeconds + "s",
                new MaqamAuditService.MaqamTeacherContext(
                        teacher.getUserId(), teacher.getUsername(), teacher.getName()),
                null,
                new MaqamAuditService.MaqamListenContext(clampedAdd, positionSeconds),
                sessionKey);

        return mapper.toSessionDTO(session, hasRole(auth, "ADMIN"));
    }

    @Transactional(readOnly = true)
    public Page<MaqamListenSessionDTO> listSessionsForRecord(String maqamCode,
                                                             Long teacherUserId,
                                                             Pageable pageable,
                                                             Authentication auth) {
        ListOfMaqam record = mustFindActive(maqamCode);
        boolean admin = hasRole(auth, "ADMIN");
        Page<MaqamAudioListenSession> page = (teacherUserId == null)
                ? sessionRepository.findAllByListOfMaqamIdOrderByStartedAtDesc(record.getId(), pageable)
                : sessionRepository.findAllByListOfMaqamIdAndTeacherUserIdOrderByStartedAtDesc(
                        record.getId(), teacherUserId, pageable);
        return page.map(s -> mapper.toSessionDTO(s, admin));
    }

    @Transactional(readOnly = true)
    public List<MaqamListenSummaryDTO> listenSummary(String maqamCode, Authentication auth) {
        ListOfMaqam record = mustFindActive(maqamCode);
        List<MaqamTeacherVote> votes = voteRepository.findAllByListOfMaqamOrderByVotedAtAsc(record);
        Long duration = record.getAudioDurationSeconds();
        return votes.stream().map(v -> {
            long sessions = sessionRepository
                    .findAllByListOfMaqamIdAndTeacherUserIdOrderByStartedAtDesc(
                            record.getId(), v.getTeacherUserId(), Pageable.unpaged())
                    .getTotalElements();
            return MaqamListenSummaryDTO.builder()
                    .teacherUserId(v.getTeacherUserId())
                    .teacherUsername(v.getTeacherUsername())
                    .teacherDisplayName(v.getTeacherDisplayName())
                    .totalSeconds(v.getTotalListenSeconds() == null ? 0L : v.getTotalListenSeconds())
                    .maxPositionSeconds(v.getMaxPositionSeconds() == null ? 0L : v.getMaxPositionSeconds())
                    .sessionCount(sessions)
                    .firstListenAt(v.getVotedAt())
                    .lastListenAt(v.getLastListenAt())
                    .coverageRatio(duration == null || duration <= 0 || v.getTotalListenSeconds() == null
                            ? null
                            : Math.min(1.0, v.getTotalListenSeconds().doubleValue() / duration.doubleValue()))
                    .build();
        }).sorted(Comparator.comparing(MaqamListenSummaryDTO::getTeacherUsername))
          .toList();
    }

    // ─── Streaming support ──────────────────────────────────────────────────

    /** Returns the entity (active only) so the streaming controller can read
     *  the S3 URL + content type. Records a STREAM audit entry. */
    @Transactional
    public ListOfMaqam loadForStreaming(String maqamCode, Authentication auth, HttpServletRequest request) {
        ListOfMaqam record = mustFindActive(maqamCode);
        ensureCallerMaySeeRecord(record, auth);
        auditService.record(record, MaqamAuditAction.STREAM, auth, request,
                "range=" + safeHeader(request, "Range"));
        return record;
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    /**
     * The pageable to hand the DB fast path: the incoming page/size, with the
     * sort replaced by the {@code sortBy}-resolved order when present. Falls
     * back to the incoming pageable when no {@code sortBy} was supplied.
     */
    private static Pageable effectivePageable(Pageable pageable, MaqamFilterParams p) {
        Sort dbSort = MaqamFilterSupport.resolveDbSort(p.getSortBy(), p.getSortDirection());
        return dbSort.isSorted()
                ? PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), dbSort)
                : pageable;
    }

    /** Audit suffix: nothing when empty; {@code filtered=true} when real
     *  filters ran; else {@code sorted=in-memory} / {@code sorted=db}. */
    private static String auditSuffix(MaqamFilterParams p, boolean inMemory) {
        if (p.isEmpty()) return "";
        if (p.hasActiveFilters()) return " filtered=true";
        return inMemory ? " sorted=in-memory" : " sorted=db";
    }

    private ListOfMaqam mustFindActive(String maqamCode) {
        return maqamRepository.findByMaqamCodeAndRemovedAtIsNull(maqamCode)
                .orElseThrow(() -> new MaqamNotFoundException("Maqam record not found: " + maqamCode));
    }

    private void ensureCallerMaySeeRecord(ListOfMaqam record, Authentication auth) {
        if (!isTeacher(auth)) return; // admins/employees see everything
        Long me = currentUserId(auth);
        boolean assigned = record.getTeacherVotes() != null && record.getTeacherVotes().stream()
                .anyMatch(v -> Objects.equals(v.getTeacherUserId(), me));
        if (!assigned) {
            throw new MaqamAccessDeniedException(
                    "You are not on the teacher panel for maqam " + record.getMaqamCode());
        }
    }

    private User ensureTeacherAssignedTo(ListOfMaqam record, Authentication auth) {
        User teacher = mustGetCurrentUser(auth);
        if (teacher.getRole() != Role.TEACHER) {
            throw new MaqamAccessDeniedException("Only teachers may track audio listening sessions");
        }
        boolean assigned = record.getTeacherVotes() != null && record.getTeacherVotes().stream()
                .anyMatch(v -> Objects.equals(v.getTeacherUserId(), teacher.getUserId()));
        if (!assigned) {
            throw new MaqamAccessDeniedException(
                    "You are not on the teacher panel for maqam " + record.getMaqamCode());
        }
        return teacher;
    }

    private boolean isTeacher(Authentication auth) {
        return hasRole(auth, "TEACHER");
    }

    private boolean hasRole(Authentication auth, String role) {
        if (auth == null) return false;
        String target = "ROLE_" + role;
        for (GrantedAuthority ga : auth.getAuthorities()) {
            if (target.equals(ga.getAuthority())) return true;
        }
        return false;
    }

    private Long currentUserId(Authentication auth) {
        User u = currentUser(auth).orElse(null);
        return u == null ? null : u.getUserId();
    }

    private User mustGetCurrentUser(Authentication auth) {
        return currentUser(auth).orElseThrow(() ->
                new MaqamAccessDeniedException("Authentication is required"));
    }

    private Optional<User> currentUser(Authentication auth) {
        if (auth == null) return Optional.empty();
        Object principal = auth.getPrincipal();
        if (principal instanceof User u) return Optional.of(u);
        return userRepository.findByUsername(auth.getName());
    }

    private String actorName(Authentication auth) {
        return auth == null ? "system" : auth.getName();
    }

    private void validateAudio(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new MaqamValidationException("Audio file is required");
        }
        String ct = file.getContentType();
        if (ct != null && !ct.toLowerCase(Locale.ROOT).startsWith("audio/")) {
            throw new MaqamValidationException("File must be an audio/* MIME type (got " + ct + ")");
        }
    }

    private void validateCreateBasics(MaqamCreateRequestDTO dto) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (dto.getSongName() == null || dto.getSongName().isBlank()) errors.put("songName", "must not be blank");
        if (dto.getProducer() == null || dto.getProducer().isBlank()) errors.put("producer", "must not be blank");
        if (!errors.isEmpty()) throw new MaqamValidationException("Validation failed", errors);
    }

    private static String trimOrNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String safeHeader(HttpServletRequest request, String name) {
        if (request == null) return null;
        String v = request.getHeader(name);
        return v == null ? null : v.substring(0, Math.min(v.length(), 500));
    }

    /** Builds the absolute stream URL for a record, scheme-aware so it works
     *  behind the railway proxy without leaking the S3 link. */
    private String buildStreamUrl(HttpServletRequest request, String maqamCode) {
        if (request == null) return "/api/maqam/" + maqamCode + "/stream";
        String scheme = Optional.ofNullable(request.getHeader("X-Forwarded-Proto")).orElse(request.getScheme());
        String host = Optional.ofNullable(request.getHeader("X-Forwarded-Host")).orElse(request.getHeader("Host"));
        if (host == null || host.isBlank()) {
            return "/api/maqam/" + maqamCode + "/stream";
        }
        return scheme + "://" + host + "/api/maqam/" + maqamCode + "/stream";
    }

    /** Convenience for the controller — returns a Page of session DTOs for
     *  a given teacher across every record (admin self-service). */
    @Transactional(readOnly = true)
    public Page<MaqamListenSessionDTO> listSessionsForTeacher(Long teacherUserId,
                                                              Pageable pageable,
                                                              Authentication auth) {
        boolean admin = hasRole(auth, "ADMIN");
        return sessionRepository.findAllByTeacherUserIdOrderByStartedAtDesc(teacherUserId, pageable)
                .map(s -> mapper.toSessionDTO(s, admin));
    }

    // ─── Teacher recent-activity feed ───────────────────────────────────────

    /**
     * "Where was I?" feed for the signed-in TEACHER. Returns every active
     * {@link ListOfMaqam} record they're assigned to, sorted by the most
     * recent thing they did on that record — latest of
     * {@code lastListenAt}, vote {@code updatedAt}, {@code votedAt},
     * {@code assignedAt}. Newest first.
     *
     * <p>Designed for elderly teachers who may forget which song they were
     * working on: every row carries enough state ({@code maqamType}-or-null,
     * {@code totalListenSeconds}, {@code maxPositionSeconds}, {@code coverageRatio},
     * {@code streamUrl}) for the UI to render "Resume from 3m20s" or
     * "Vote pending" without a follow-up call.
     *
     * <h4>Algorithm</h4>
     * One JPQL fetch ({@code findAllAssignedActiveByTeacher}) pulls every
     * vote-row for this teacher with the parent record join-fetched — no
     * N+1. The set is small in practice (a teacher is on at most a few
     * dozen records), so optional {@code q} filtering, the composite-max
     * sort, and pagination all run in-memory via {@code PaginationSupport}.
     * Microseconds; no extra DB round-trips.
     *
     * @param q optional case-insensitive substring matched against song name,
     *          producer, or maqam code
     */
    @Transactional(readOnly = true)
    public Page<MaqamTeacherRecentDTO> getMyRecentActivity(String q,
                                                           Pageable pageable,
                                                           Authentication auth,
                                                           HttpServletRequest request) {
        User teacher = mustGetCurrentUser(auth);
        if (teacher.getRole() != Role.TEACHER) {
            throw new MaqamAccessDeniedException("Only teachers may view their recent-activity feed");
        }

        String needle = q == null ? null : q.trim().toLowerCase(Locale.ROOT);
        if (needle != null && needle.isEmpty()) needle = null;

        List<MaqamTeacherVote> votes = voteRepository.findAllAssignedActiveByTeacher(teacher.getUserId());

        List<MaqamTeacherRecentDTO> rows = new ArrayList<>(votes.size());
        for (MaqamTeacherVote v : votes) {
            ListOfMaqam record = v.getListOfMaqam();
            if (record == null) continue; // defensive — JOIN FETCH guarantees non-null
            if (needle != null
                    && !containsIgnoreCase(record.getSongName(), needle)
                    && !containsIgnoreCase(record.getProducer(), needle)
                    && !containsIgnoreCase(record.getMaqamCode(), needle)) {
                continue;
            }
            rows.add(mapper.toRecent(v, record, buildStreamUrl(request, record.getMaqamCode())));
        }

        // Sort newest activity first; rows with no activity at all (no vote,
        // no listen, no assignedAt) fall to the bottom rather than the top.
        rows.sort((a, b) -> {
            Instant ta = a.getLastActivityAt();
            Instant tb = b.getLastActivityAt();
            if (ta == null && tb == null) return 0;
            if (ta == null) return 1;
            if (tb == null) return -1;
            return tb.compareTo(ta);
        });

        Page<MaqamTeacherRecentDTO> page = PaginationSupport.sliceList(rows, pageable);

        auditService.record(null, MaqamAuditAction.LIST, auth, request,
                "teacher recent activity"
                        + (needle == null ? "" : " q=" + needle)
                        + " size=" + page.getNumberOfElements()
                        + " total=" + page.getTotalElements());
        return page;
    }

    private static boolean containsIgnoreCase(String haystack, String needleLower) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needleLower);
    }

    /** Used by the streaming controller (which is outside this service) to
     *  validate that the active call is a teacher assigned to the record
     *  before it serves bytes from S3. Returns the record entity. */
    public ListOfMaqam loadActiveOrThrow(String maqamCode) {
        return mustFindActive(maqamCode);
    }

    /** Public read-page used by the empty-state UI (e.g. dashboard widget). */
    @Transactional(readOnly = true)
    public Page<MaqamResponseDTO> listAllForAdmin(Pageable pageable, HttpServletRequest request) {
        return maqamRepository.findAll(pageable)
                .map(m -> mapper.toResponse(m, buildStreamUrl(request, m.getMaqamCode()), true));
    }
}
