package ak.dev.khi_archive_platform.platform.repo.vocabulary;

/**
 * Describes one tag/keyword {@code @ElementCollection} table so the bulk
 * vocabulary operations can drive list / rename / delete generically across
 * all of them.
 *
 * <p>All values here come from a hardcoded, trusted catalog (never user input),
 * so they are safe to concatenate into SQL identifiers; the actual tag/keyword
 * <em>values</em> are always passed as bound parameters.
 *
 * @param table        collection table, e.g. {@code audio_tags} / {@code category_keywords}
 * @param valueColumn  the value column, {@code tag} or {@code keyword}
 * @param fkColumn     FK back to the owning row, e.g. {@code audio_id}
 * @param parentTable  the owning entity table, e.g. {@code audios} — joined on
 *                     {@code id} and filtered by {@code removed_at IS NULL} so
 *                     the usage listing matches the {@code /suggest} vocabulary
 */
public record CollectionTableRef(String table, String valueColumn, String fkColumn, String parentTable) {
}
