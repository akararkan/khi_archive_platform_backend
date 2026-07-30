package ak.dev.khi_archive_platform.platform.dto.vocabulary;

/**
 * Outcome of a rename. {@code renamed} = rows whose value was rewritten;
 * {@code merged} = duplicate rows collapsed because their parent already carried
 * the target value. Net new distinct occurrences of {@code to} = renamed - merged.
 */
public record VocabularyRenameResult(String from, String to, long renamed, long merged) {
}
