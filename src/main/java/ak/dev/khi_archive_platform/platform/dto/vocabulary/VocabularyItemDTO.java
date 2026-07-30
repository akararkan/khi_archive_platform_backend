package ak.dev.khi_archive_platform.platform.dto.vocabulary;

/**
 * One distinct tag/keyword and how many live (non-trashed) records use it.
 * Returned by the admin list endpoints ({@code GET /api/admin/tags},
 * {@code GET /api/admin/keywords}).
 */
public record VocabularyItemDTO(String value, long usageCount) {
}
