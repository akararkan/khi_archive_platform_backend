package ak.dev.khi_archive_platform.platform.enums;

/**
 * Actions recorded into {@code physical_media_audit_logs}. The first nine map
 * 1:1 to the other {@code *_audit_logs} tables so the analytics UNION sees a
 * uniform action vocabulary; {@link #IMPORT} is unique to this entity and
 * marks a successful Excel ingestion batch.
 */
public enum PhysicalMediaAuditAction {
    CREATE,
    READ,
    LIST,
    SEARCH,
    UPDATE,
    REMOVE,
    DELETE,
    RESTORE,
    PURGE,
    IMPORT,

    // ── Catalog actions ───────────────────────────────────────────────
    // Operations on the {@code physical_media_types} table. The audit
    // row's {@code physical_media_id} carries the type's catalog id and
    // {@code physical_label} carries its name, so the analytics feed
    // surfaces "<actor> added type 'CD/DVD'" without extra joins.
    TYPE_CREATE,
    TYPE_UPDATE,
    TYPE_DELETE
}
