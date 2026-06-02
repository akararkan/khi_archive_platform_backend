package ak.dev.khi_archive_platform.platform.enums;

/**
 * Actions worth auditing on a List-of-Maqam record. Three families:
 *
 * <ul>
 *   <li><b>Record CRUD</b> ({@code CREATE … PURGE}) — performed by employees
 *       and admins on the song fields (id → audio file). Matches the action
 *       set used by audio/video/image/text so the cross-entity analytics
 *       union stays aligned.</li>
 *   <li><b>Teacher roster</b> ({@code TEACHER_ASSIGNED}, {@code TEACHER_REMOVED})
 *       — admin-only management of which 1–3 teachers are attached to the
 *       record.</li>
 *   <li><b>Voting & listening</b> ({@code VOTE_CAST} … {@code LISTEN_ENDED},
 *       {@code STREAM}) — performed by the assigned TEACHERs. Listen events
 *       carry per-session second counts so an admin can see how long each
 *       teacher actually engaged with the audio before voting.</li>
 * </ul>
 */
public enum MaqamAuditAction {
    CREATE,
    READ,
    LIST,
    SEARCH,
    UPDATE,
    REMOVE,
    DELETE,
    RESTORE,
    PURGE,
    TEACHER_ASSIGNED,
    TEACHER_REMOVED,
    VOTE_CAST,
    VOTE_UPDATED,
    VOTE_DELETED,
    STREAM,
    LISTEN_STARTED,
    LISTEN_PROGRESS,
    LISTEN_ENDED
}
