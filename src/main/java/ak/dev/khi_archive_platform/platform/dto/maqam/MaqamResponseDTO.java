package ak.dev.khi_archive_platform.platform.dto.maqam;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.List;

/**
 * Read view of a {@code list_of_maqam} record. Crucially, the raw
 * {@code audioFileUrl} from the entity is never serialised here — only the
 * authenticated streaming URL ({@link #streamUrl}). Honours the "no downloads"
 * rule across roles (admin, employee, teacher).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MaqamResponseDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private String maqamCode;
    private String songName;
    private String producer;

    /** Filename the upload was made under, for display only. */
    private String audioFileName;
    private String audioContentType;
    private Long audioFileSizeBytes;
    private Long audioDurationSeconds;

    /** Stream-through URL ({@code /api/maqam/{code}/stream}). Frontends bind
     *  this to an {@code <audio src>}; the raw S3 link is never exposed. */
    private String streamUrl;

    private String archiveNote;

    /** All assigned teachers' votes/notes, oldest-assigned first. Visible to
     *  every authenticated user with maqam:read, including peers. */
    private List<MaqamTeacherVoteDTO> teacherVotes;

    private Instant createdAt;
    private Instant updatedAt;
    private Instant removedAt;
    private String createdBy;
    private String updatedBy;
    private String removedBy;
}
