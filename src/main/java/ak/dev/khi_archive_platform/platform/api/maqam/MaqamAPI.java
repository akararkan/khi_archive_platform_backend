package ak.dev.khi_archive_platform.platform.api.maqam;

import ak.dev.khi_archive_platform.platform.dto.maqam.MaqamCreateRequestDTO;
import ak.dev.khi_archive_platform.platform.dto.maqam.MaqamFilterParams;
import ak.dev.khi_archive_platform.platform.dto.maqam.MaqamListenEndRequestDTO;
import ak.dev.khi_archive_platform.platform.dto.maqam.MaqamListenProgressRequestDTO;
import ak.dev.khi_archive_platform.platform.dto.maqam.MaqamListenSessionDTO;
import ak.dev.khi_archive_platform.platform.dto.maqam.MaqamListenStartRequestDTO;
import ak.dev.khi_archive_platform.platform.dto.maqam.MaqamResponseDTO;
import ak.dev.khi_archive_platform.platform.dto.maqam.MaqamTeacherRecentDTO;
import ak.dev.khi_archive_platform.platform.dto.maqam.MaqamUpdateRequestDTO;
import ak.dev.khi_archive_platform.platform.dto.maqam.MaqamVoteRequestDTO;
import ak.dev.khi_archive_platform.platform.exceptions.MaqamValidationException;
import ak.dev.khi_archive_platform.platform.service.maqam.MaqamService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Public maqam endpoints. Read paths are open to anyone holding
 * {@code maqam:read} — the service further filters teachers to records they
 * are assigned to. Write paths split between record CRUD ({@code maqam:create},
 * {@code maqam:update}, {@code maqam:delete}) and teacher voting ({@code
 * maqam:vote}).
 *
 * <p>The audio file itself is served by a dedicated streaming endpoint that
 * issues partial-content responses with {@code Content-Disposition: inline}
 * so it plays in the {@code <audio>} element but never triggers a download.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/maqam")
public class MaqamAPI {

    private final MaqamService maqamService;
    private final ObjectMapper objectMapper;
    private final Validator validator;

    // ─── Reading ─────────────────────────────────────────────────────────────

    /**
     * Active records the caller can see, with optional filter + sort. Teachers
     * get only their assigned records; admins/employees see all active records —
     * that visibility split holds whether or not filters are applied.
     *
     * <p>Filter params bind from the query string into {@link MaqamFilterParams}
     * — the same {@code @ModelAttribute} style as {@code /api/audio}. With no
     * params this is the original fast DB-paged listing.
     *
     * <p>Examples:
     * <pre>
     *   GET /api/maqam?songName=layla&amp;sortBy=createdAt&amp;sortDirection=desc
     *   GET /api/maqam?voteStatus=none&amp;assignmentStatus=assigned
     *   GET /api/maqam?maqamType=Rast&amp;sortBy=songName
     *   GET /api/maqam?teacherUserId=42&amp;durationSecondsMin=120
     * </pre>
     */
    @GetMapping
    @PreAuthorize("hasAuthority('maqam:read')")
    public ResponseEntity<Page<MaqamResponseDTO>> listActive(
            @PageableDefault(size = 50) Pageable pageable,
            @ModelAttribute MaqamFilterParams filter,
            Authentication auth,
            HttpServletRequest request) {
        return ResponseEntity.ok(maqamService.listActive(pageable, filter, auth, request));
    }

    /** Free-text search across song name / producer / maqam code. */
    @GetMapping("/search")
    @PreAuthorize("hasAuthority('maqam:read')")
    public ResponseEntity<List<MaqamResponseDTO>> search(
            @RequestParam("q") String q,
            @RequestParam(value = "limit", required = false, defaultValue = "20") Integer limit,
            Authentication auth,
            HttpServletRequest request) {
        return ResponseEntity.ok(maqamService.search(q, limit == null ? 20 : limit, auth, request));
    }

    /**
     * Distinct maqam types voted across active records, most-common first —
     * backs a real dropdown for the {@code maqamType} filter so the exact-match
     * filter targets values that actually exist (avoids silent misses from
     * free-text spelling drift in Kurdish).
     */
    @GetMapping("/maqam-types")
    @PreAuthorize("hasAuthority('maqam:read')")
    public ResponseEntity<List<String>> maqamTypes() {
        return ResponseEntity.ok(maqamService.listDistinctMaqamTypes());
    }

    @GetMapping("/{maqamCode}")
    @PreAuthorize("hasAuthority('maqam:read')")
    public ResponseEntity<MaqamResponseDTO> getOne(
            @PathVariable String maqamCode,
            Authentication auth,
            HttpServletRequest request) {
        return ResponseEntity.ok(maqamService.getByCode(maqamCode, auth, request));
    }

    /** Per-teacher engagement summary on a single record. Visible to anyone
     *  with {@code maqam:read} so peer teachers can see how each teammate
     *  has engaged before voting. */
    @GetMapping("/{maqamCode}/listen-summary")
    @PreAuthorize("hasAuthority('maqam:read')")
    public ResponseEntity<?> listenSummary(@PathVariable String maqamCode,
                                           Authentication auth) {
        return ResponseEntity.ok(maqamService.listenSummary(maqamCode, auth));
    }

    /** Per-session listen log on a single record. ADMIN sees IP/UA columns;
     *  teachers/employees see only what they need to render a timeline. */
    @GetMapping("/{maqamCode}/sessions")
    @PreAuthorize("hasAuthority('maqam:read')")
    public ResponseEntity<Page<MaqamListenSessionDTO>> recordSessions(
            @PathVariable String maqamCode,
            @RequestParam(value = "teacherUserId", required = false) Long teacherUserId,
            @PageableDefault(size = 100) Pageable pageable,
            Authentication auth) {
        return ResponseEntity.ok(maqamService.listSessionsForRecord(maqamCode, teacherUserId, pageable, auth));
    }

    // ─── Create / Update / Delete ────────────────────────────────────────────

    /** Admins and employees with {@code maqam:create} upload a new song
     *  record. {@code data} is the JSON payload, {@code file} the audio. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('maqam:create')")
    public ResponseEntity<MaqamResponseDTO> create(
            @RequestPart("data") String dataJson,
            @RequestPart("file") MultipartFile file,
            Authentication auth,
            HttpServletRequest request) {
        MaqamCreateRequestDTO dto = parseAndValidate(dataJson, MaqamCreateRequestDTO.class);
        return ResponseEntity.ok(maqamService.create(dto, file, auth, request));
    }

    @PatchMapping(value = "/{maqamCode}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('maqam:update')")
    public ResponseEntity<MaqamResponseDTO> update(
            @PathVariable String maqamCode,
            @RequestPart("data") String dataJson,
            @RequestPart(value = "file", required = false) MultipartFile file,
            Authentication auth,
            HttpServletRequest request) {
        MaqamUpdateRequestDTO dto = parseAndValidate(dataJson, MaqamUpdateRequestDTO.class);
        return ResponseEntity.ok(maqamService.update(maqamCode, dto, file, auth, request));
    }

    /** Soft-trash. Admin only. */
    @DeleteMapping("/{maqamCode}")
    @PreAuthorize("hasAuthority('maqam:delete')")
    public ResponseEntity<Void> softDelete(
            @PathVariable String maqamCode,
            Authentication auth,
            HttpServletRequest request) {
        maqamService.delete(maqamCode, auth, request);
        return ResponseEntity.noContent().build();
    }

    // ─── Teacher recent-activity feed (TEACHER role only) ────────────────────

    /**
     * Returns the signed-in teacher's "where was I?" list — every active
     * maqam record they're assigned to, newest activity first. Each row
     * includes their own vote state (or null if not yet voted), their
     * listen progress, the audio duration, and a {@code streamUrl} for
     * inline playback. Optional {@code q} substring-matches song name,
     * producer, or maqam code.
     *
     * <p>The endpoint is guarded by {@code maqam:vote} (the TEACHER-only
     * permission) so admins/employees can't accidentally hit it — they have
     * the global {@link #listActive(Pageable, Authentication, HttpServletRequest)}
     * endpoint instead.
     */
    @GetMapping("/teacher/my-recent")
    @PreAuthorize("hasAuthority('maqam:vote')")
    public ResponseEntity<Page<MaqamTeacherRecentDTO>> myRecent(
            @RequestParam(value = "q", required = false) String q,
            @PageableDefault(size = 50) Pageable pageable,
            Authentication auth,
            HttpServletRequest request) {
        return ResponseEntity.ok(maqamService.getMyRecentActivity(q, pageable, auth, request));
    }

    // ─── Voting (TEACHER role only) ──────────────────────────────────────────

    /** Cast or update the current teacher's vote + note. Idempotent: a
     *  subsequent call with the same payload bumps {@code updatedAt} only. */
    @PostMapping(value = "/{maqamCode}/vote", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('maqam:vote')")
    public ResponseEntity<MaqamResponseDTO> vote(
            @PathVariable String maqamCode,
            @Valid @RequestBody MaqamVoteRequestDTO dto,
            Authentication auth,
            HttpServletRequest request) {
        return ResponseEntity.ok(maqamService.upsertVote(maqamCode, dto.getMaqamType(), dto.getTeacherNote(), auth, request));
    }

    // ─── Listen tracking (TEACHER role only) ─────────────────────────────────

    @PostMapping(value = "/{maqamCode}/listen/start", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('maqam:vote')")
    public ResponseEntity<MaqamListenSessionDTO> startListen(
            @PathVariable String maqamCode,
            @Valid @RequestBody MaqamListenStartRequestDTO dto,
            Authentication auth,
            HttpServletRequest request) {
        return ResponseEntity.ok(maqamService.startListenSession(
                maqamCode, dto.getSessionKey(), dto.getStartPositionSeconds(), auth, request));
    }

    @PostMapping(value = "/{maqamCode}/listen/progress", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('maqam:vote')")
    public ResponseEntity<MaqamListenSessionDTO> progressListen(
            @PathVariable String maqamCode,
            @Valid @RequestBody MaqamListenProgressRequestDTO dto,
            Authentication auth,
            HttpServletRequest request) {
        return ResponseEntity.ok(maqamService.recordProgress(maqamCode, dto.getSessionKey(),
                dto.getAddSeconds(), dto.getPositionSeconds(), auth, request, false));
    }

    @PostMapping(value = "/{maqamCode}/listen/end", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('maqam:vote')")
    public ResponseEntity<MaqamListenSessionDTO> endListen(
            @PathVariable String maqamCode,
            @Valid @RequestBody MaqamListenEndRequestDTO dto,
            Authentication auth,
            HttpServletRequest request) {
        long add = dto.getAddSeconds() == null ? 0L : dto.getAddSeconds();
        long pos = dto.getPositionSeconds() == null ? 0L : dto.getPositionSeconds();
        return ResponseEntity.ok(maqamService.recordProgress(maqamCode, dto.getSessionKey(),
                add, pos, auth, request, true));
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private <T> T parseAndValidate(String dataJson, Class<T> clazz) {
        if (dataJson == null || dataJson.isBlank()) {
            throw new MaqamValidationException("Missing 'data' part in the request.");
        }

        T dto;
        try {
            dto = objectMapper.readValue(dataJson, clazz);
        } catch (Exception e) {
            throw new MaqamValidationException("Failed to parse maqam data: " + e.getMessage());
        }

        var violations = validator.validate(dto);
        if (!violations.isEmpty()) {
            Map<String, String> fieldErrors = new LinkedHashMap<>();
            for (ConstraintViolation<T> v : violations) {
                fieldErrors.put(v.getPropertyPath().toString(), v.getMessage());
            }
            throw new MaqamValidationException("Validation failed for maqam data.", fieldErrors);
        }
        return dto;
    }
}
