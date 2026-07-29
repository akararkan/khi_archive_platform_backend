package ak.dev.khi_archive_platform.platform.api.maqam;

import ak.dev.khi_archive_platform.platform.dto.maqam.MaqamFilterParams;
import ak.dev.khi_archive_platform.platform.dto.maqam.MaqamListenSessionDTO;
import ak.dev.khi_archive_platform.platform.dto.maqam.MaqamResponseDTO;
import ak.dev.khi_archive_platform.platform.dto.maqam.MaqamTeacherAssignmentDTO;
import ak.dev.khi_archive_platform.platform.service.maqam.MaqamService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * Admin surface for the maqam workflow:
 *
 * <ul>
 *   <li>Teacher roster management — assign / replace the 1–3 teachers per
 *       record. {@code maqam:teacher_manage}.</li>
 *   <li>Trash listing, restore, purge — soft-trash pattern parallel to audio.
 *       {@code maqam:delete}.</li>
 *   <li>Per-record / per-teacher listen log mining for engagement reports.
 *       {@code maqam:read} plus {@code hasRole('ADMIN')} to access PII like
 *       IP + user-agent on each session row.</li>
 *   <li>Clearing a teacher's vote (e.g. after misconduct). {@code maqam:teacher_manage}.</li>
 * </ul>
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/maqam")
public class AdminMaqamAPI {

    private final MaqamService maqamService;

    /** Replaces the entire teacher panel for a record. Validates 1–3 teachers,
     *  TEACHER role, and active status. Gated on {@code maqam:teacher_manage},
     *  which ADMIN holds via the role and employees receive in the seeded
     *  default set so they can pick teachers for the records they prepared. */
    @PutMapping(value = "/{maqamCode}/teachers", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('maqam:teacher_manage')")
    public ResponseEntity<MaqamResponseDTO> assignTeachers(
            @PathVariable String maqamCode,
            @Valid @RequestBody MaqamTeacherAssignmentDTO dto,
            Authentication auth,
            HttpServletRequest request) {
        return ResponseEntity.ok(maqamService.assignTeachers(maqamCode, dto, auth, request));
    }

    /** Soft-trashed records, with the same optional filter + sort as the
     *  active list ({@link MaqamFilterParams}, bound via {@code @ModelAttribute}).
     *  Admin-only, so every trashed record is in scope regardless of role. */
    @GetMapping("/trash")
    @PreAuthorize("hasAuthority('maqam:delete')")
    public ResponseEntity<Page<MaqamResponseDTO>> trash(
            @PageableDefault(size = 100) Pageable pageable,
            @ModelAttribute MaqamFilterParams filter,
            Authentication auth,
            HttpServletRequest request) {
        return ResponseEntity.ok(maqamService.listTrash(pageable, filter, auth, request));
    }

    @PostMapping("/{maqamCode}/restore")
    @PreAuthorize("hasAuthority('maqam:delete')")
    public ResponseEntity<MaqamResponseDTO> restore(
            @PathVariable String maqamCode,
            Authentication auth,
            HttpServletRequest request) {
        return ResponseEntity.ok(maqamService.restore(maqamCode, auth, request));
    }

    @DeleteMapping("/{maqamCode}/purge")
    @PreAuthorize("hasAuthority('maqam:delete')")
    public ResponseEntity<Void> purge(
            @PathVariable String maqamCode,
            Authentication auth,
            HttpServletRequest request) {
        maqamService.purge(maqamCode, auth, request);
        return ResponseEntity.noContent().build();
    }

    /** Clears a specific teacher's vote on a record. Kept the assignment so
     *  the teacher can re-vote — use the assignment endpoint to remove them
     *  from the panel entirely. */
    @DeleteMapping("/{maqamCode}/votes/{teacherUserId}")
    @PreAuthorize("hasAuthority('maqam:teacher_manage')")
    public ResponseEntity<MaqamResponseDTO> clearVote(
            @PathVariable String maqamCode,
            @PathVariable Long teacherUserId,
            Authentication auth,
            HttpServletRequest request) {
        return ResponseEntity.ok(maqamService.deleteVote(maqamCode, teacherUserId, auth, request));
    }

    /** Every listen session a single teacher ever recorded. PII columns
     *  visible only because this endpoint is gated on ROLE_ADMIN. */
    @GetMapping("/teachers/{teacherUserId}/sessions")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Page<MaqamListenSessionDTO>> sessionsForTeacher(
            @PathVariable Long teacherUserId,
            @PageableDefault(size = 100) Pageable pageable,
            Authentication auth) {
        return ResponseEntity.ok(maqamService.listSessionsForTeacher(teacherUserId, pageable, auth));
    }
}
