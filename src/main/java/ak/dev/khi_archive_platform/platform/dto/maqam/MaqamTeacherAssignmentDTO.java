package ak.dev.khi_archive_platform.platform.dto.maqam;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Admin-only payload for {@code PUT /api/admin/maqam/{maqamCode}/teachers}.
 * Replaces the assigned teacher set. The IDs must belong to active users
 * with the TEACHER role; the service rejects the request when the panel
 * would shrink below 1 or exceed 3.
 *
 * <p>Removing a teacher who has already voted is allowed but logged as a
 * {@code TEACHER_REMOVED} action — their vote row is deleted (cascade) so
 * subsequent reads of the record show only the surviving panel.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MaqamTeacherAssignmentDTO {

    @NotNull
    @NotEmpty(message = "teacherUserIds must contain at least one user id")
    @Size(min = 1, max = 3, message = "between 1 and 3 teachers may be assigned to a maqam record")
    private List<Long> teacherUserIds;
}
