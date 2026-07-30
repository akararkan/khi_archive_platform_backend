package ak.dev.khi_archive_platform.platform.dto.vocabulary;

/**
 * Outcome of a delete: the canonical value removed and how many collection rows
 * were deleted across all tables (active + trashed).
 */
public record VocabularyDeleteResult(String value, long deleted) {
}
