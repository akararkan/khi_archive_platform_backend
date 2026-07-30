package ak.dev.khi_archive_platform.platform.dto.vocabulary;

import jakarta.validation.constraints.NotBlank;

/**
 * Body for {@code PATCH /api/admin/tags} and {@code PATCH /api/admin/keywords}:
 * rename every occurrence of {@code from} to {@code to}. Both are canonicalised
 * server-side (NFKC, trim, collapse whitespace, lower-case) before matching, so
 * the client need not pre-process.
 */
public record VocabularyRenameRequest(@NotBlank String from, @NotBlank String to) {
}
