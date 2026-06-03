package ak.dev.khi_archive_platform.user.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Every permission the system recognises. Pattern: {@code <resource>:<action>}.
 *
 * Actions:
 *   read   — list / get / search
 *   create — add (single or bulk)
 *   update — partial or full update
 *   remove — soft remove (record stays in DB, flagged removed)
 *   delete — hard delete (row physically removed) — ADMIN only
 *
 * Resources include the seven content types (audio/video/image/text/category/
 * person/project) PLUS the {@code user} resource, used by the admin
 * user-management endpoints under {@code /api/admin/users}.
 */
@Getter
@RequiredArgsConstructor
public enum Permission {

    // ── Audio ───────────────────────────────────────────────
    AUDIO_READ("audio:read"),
    AUDIO_CREATE("audio:create"),
    AUDIO_UPDATE("audio:update"),
    AUDIO_REMOVE("audio:remove"),
    AUDIO_DELETE("audio:delete"),

    // ── Video ───────────────────────────────────────────────
    VIDEO_READ("video:read"),
    VIDEO_CREATE("video:create"),
    VIDEO_UPDATE("video:update"),
    VIDEO_REMOVE("video:remove"),
    VIDEO_DELETE("video:delete"),

    // ── Image ───────────────────────────────────────────────
    IMAGE_READ("image:read"),
    IMAGE_CREATE("image:create"),
    IMAGE_UPDATE("image:update"),
    IMAGE_REMOVE("image:remove"),
    IMAGE_DELETE("image:delete"),

    // ── Text ────────────────────────────────────────────────
    TEXT_READ("text:read"),
    TEXT_CREATE("text:create"),
    TEXT_UPDATE("text:update"),
    TEXT_REMOVE("text:remove"),
    TEXT_DELETE("text:delete"),

    // ── Category ────────────────────────────────────────────
    CATEGORY_READ("category:read"),
    CATEGORY_CREATE("category:create"),
    CATEGORY_UPDATE("category:update"),
    CATEGORY_REMOVE("category:remove"),
    CATEGORY_DELETE("category:delete"),

    // ── Person ──────────────────────────────────────────────
    PERSON_READ("person:read"),
    PERSON_CREATE("person:create"),
    PERSON_UPDATE("person:update"),
    PERSON_REMOVE("person:remove"),
    PERSON_DELETE("person:delete"),

    // ── Project ─────────────────────────────────────────────
    PROJECT_READ("project:read"),
    PROJECT_CREATE("project:create"),
    PROJECT_UPDATE("project:update"),
    PROJECT_REMOVE("project:remove"),
    PROJECT_DELETE("project:delete"),

    // ── User (admin-only by default) ────────────────────────
    // Used by the /api/admin/users endpoints. ADMIN gets all five via the
    // Role definition; admins can also grant individual user:* authorities
    // to non-ADMINs to delegate, e.g., user-listing without full ADMIN power.
    USER_READ("user:read"),
    USER_CREATE("user:create"),
    USER_UPDATE("user:update"),
    USER_REMOVE("user:remove"),
    USER_DELETE("user:delete"),

    // ── Warning (admin-only) ────────────────────────────────
    // Drives /api/admin/warnings endpoints. ADMIN holds all of these via
    // the role; non-admins never see them unless an admin grants the
    // specific authority. The recipient does NOT need any warning:* perm
    // to view or acknowledge their own warnings — that's handled by the
    // /api/warnings endpoints which are gated on authentication alone.
    WARNING_READ("warning:read"),
    WARNING_CREATE("warning:create"),
    WARNING_UPDATE("warning:update"),
    WARNING_REMOVE("warning:remove"),
    WARNING_DELETE("warning:delete"),

    // ── Correction (admin management of guest correction suggestions) ──
    // Drives /api/admin/corrections endpoints. ADMIN gets all three via
    // the role. Submission by guests uses isAuthenticated() only — no
    // correction:create permission is required to submit.
    CORRECTION_READ("correction:read"),
    CORRECTION_UPDATE("correction:update"),
    CORRECTION_REMOVE("correction:remove"),

    // ── Maqam (List-of-Maqam song records) ─────────────────────
    // Five-action CRUD parallels the other media entities. {@code MAQAM_READ}
    // is held by ADMIN, EMPLOYEE seed, and the TEACHER role baseline. CREATE
    // and UPDATE are admin/employee only — teachers never edit the song
    // metadata even on records they vote on. REMOVE/DELETE are admin only
    // (trash + purge), matching the soft-delete pattern used by audio/video.
    MAQAM_READ("maqam:read"),
    MAQAM_CREATE("maqam:create"),
    MAQAM_UPDATE("maqam:update"),
    MAQAM_REMOVE("maqam:remove"),
    MAQAM_DELETE("maqam:delete"),

    // Cast a vote / save a note on a List-of-Maqam record. Held by the
    // TEACHER role baseline only — employees and admins do not vote (admins
    // can still manage the teacher roster via {@link #MAQAM_TEACHER_MANAGE}).
    MAQAM_VOTE("maqam:vote"),

    // Assign / unassign which teachers are on a List-of-Maqam record
    // (min 1, max 3). Held by ADMIN and seeded into the EMPLOYEE default set
    // so the employee who prepared the record can also pick its teacher panel.
    MAQAM_TEACHER_MANAGE("maqam:teacher_manage"),

    // ── Physical Media (inventory of cassettes, reels, DVDs, …) ────────
    // Five-action CRUD parallels the other content entities, plus an explicit
    // {@code physical_media:import} for the .xlsx ingest endpoint. Seeded
    // into EMPLOYEE_DEFAULT_PERMISSIONS: READ + CREATE + UPDATE + IMPORT —
    // employees can fill the inventory the same way they prepare audio,
    // including running an Excel import. REMOVE/DELETE stay admin-only
    // (soft-trash + purge), matching the pattern used by audio/video.
    PHYSICAL_MEDIA_READ("physical_media:read"),
    PHYSICAL_MEDIA_CREATE("physical_media:create"),
    PHYSICAL_MEDIA_UPDATE("physical_media:update"),
    PHYSICAL_MEDIA_REMOVE("physical_media:remove"),
    PHYSICAL_MEDIA_DELETE("physical_media:delete"),
    PHYSICAL_MEDIA_IMPORT("physical_media:import"),

    // Manage the {@code physical_media_types} catalog: add a new type,
    // edit a type's nine technical defaults, delete an unused type. Held
    // by ADMIN; not seeded into the EMPLOYEE default set — the catalog is
    // system configuration, not day-to-day work.
    PHYSICAL_MEDIA_TYPE_MANAGE("physical_media:type_manage");

    private final String permission;
}
