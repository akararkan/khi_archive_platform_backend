package ak.dev.khi_archive_platform.user.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Application-resource permissions.
 * Pattern: {@code <resource>:<action>}.
 *
 * Actions:
 *   read   — list / get / search
 *   create — add (single or bulk)
 *   update — partial or full update
 *   remove — soft remove (record stays in DB, flagged removed)
 *   delete — hard delete (row physically removed) — ADMIN only
 *
 * User-account permissions live in {@link UserPermission}.
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
    PROJECT_DELETE("project:delete");

    private final String permission;
}
