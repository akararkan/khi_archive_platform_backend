package ak.dev.khi_archive_platform.platform.api.analytics;

import ak.dev.khi_archive_platform.platform.dto.analytics.MaqamTeacherOverviewDTO;
import ak.dev.khi_archive_platform.platform.dto.analytics.TeacherActivityDTO;
import ak.dev.khi_archive_platform.platform.enums.AnalyticsAuditAction;
import ak.dev.khi_archive_platform.platform.service.analytics.AnalyticsAuditService;
import ak.dev.khi_archive_platform.platform.service.analytics.MaqamAnalyticsService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Maqam teacher-classification analytics — aggregate reports over the teacher
 * voting + audio-listening workflow, sourced from {@code maqam_teacher_votes},
 * {@code maqam_audio_listen_sessions} and {@code list_of_maqam} (not the
 * audit-log union, which drops the teacher/vote/listen dimensions). Admin-only;
 * each view is written to {@code analytics_audit_logs}.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/analytics/maqam")
@PreAuthorize("hasRole('ADMIN')")
public class MaqamAnalyticsAPI {

    private final MaqamAnalyticsService maqamAnalyticsService;
    private final AnalyticsAuditService auditService;

    /** Team-level classification progress plus the per-teacher leaderboard. */
    @GetMapping("/overview")
    public ResponseEntity<MaqamTeacherOverviewDTO> overview(Authentication auth, HttpServletRequest request) {
        MaqamTeacherOverviewDTO body = maqamAnalyticsService.getOverview();
        auditService.record(AnalyticsAuditAction.VIEW_MAQAM_OVERVIEW, "maqam/overview", auth, request,
                "Maqam overview (teachers=" + body.getTotalTeachers()
                        + " records=" + body.getTotalActiveRecords() + ")");
        return ResponseEntity.ok(body);
    }

    /** The per-teacher leaderboard on its own, ordered by votes then listening. */
    @GetMapping("/teachers")
    public ResponseEntity<List<TeacherActivityDTO>> teachers(Authentication auth, HttpServletRequest request) {
        List<TeacherActivityDTO> body = maqamAnalyticsService.getTeachers();
        auditService.record(AnalyticsAuditAction.VIEW_MAQAM_TEACHERS, "maqam/teachers", auth, request,
                "Maqam teacher leaderboard (teachers=" + body.size() + ")");
        return ResponseEntity.ok(body);
    }

    /** One teacher's maqam activity, matched on username. 404 when the user is
     *  not on any active-record panel. */
    @GetMapping("/teachers/{username}")
    public ResponseEntity<TeacherActivityDTO> teacher(@PathVariable String username,
                                                      Authentication auth,
                                                      HttpServletRequest request) {
        TeacherActivityDTO body = maqamAnalyticsService.getTeacher(username);
        auditService.record(AnalyticsAuditAction.VIEW_MAQAM_TEACHER, "maqam/teacher:" + username, auth, request,
                "Maqam teacher activity for " + username + (body == null ? " (not found)" : ""));
        return body == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(body);
    }
}
