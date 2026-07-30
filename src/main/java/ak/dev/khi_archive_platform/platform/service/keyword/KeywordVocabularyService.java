package ak.dev.khi_archive_platform.platform.service.keyword;

import ak.dev.khi_archive_platform.platform.dto.vocabulary.VocabularyDeleteResult;
import ak.dev.khi_archive_platform.platform.dto.vocabulary.VocabularyItemDTO;
import ak.dev.khi_archive_platform.platform.dto.vocabulary.VocabularyRenameResult;
import ak.dev.khi_archive_platform.platform.repo.vocabulary.CollectionTableRef;
import ak.dev.khi_archive_platform.platform.repo.vocabulary.VocabularyBulkRepository;
import ak.dev.khi_archive_platform.platform.service.audio.AudioReadCache;
import ak.dev.khi_archive_platform.platform.service.category.CategoryReadCache;
import ak.dev.khi_archive_platform.platform.service.common.Keywords;
import ak.dev.khi_archive_platform.platform.service.image.ImageReadCache;
import ak.dev.khi_archive_platform.platform.service.project.ProjectReadCache;
import ak.dev.khi_archive_platform.platform.service.text.TextReadCache;
import ak.dev.khi_archive_platform.platform.service.video.VideoReadCache;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Admin management of the keyword vocabulary: list, globally rename, and
 * globally delete a keyword across the six keyword collection tables
 * ({@code audio_keywords}, {@code video_keywords}, {@code image_keywords},
 * {@code text_keywords}, {@code project_keywords}, {@code category_keywords}) —
 * the universe {@code /api/keywords/suggest} draws from.
 *
 * <p>Sibling of {@code TagVocabularyService}: identical algorithm, one extra
 * table (Category has keywords but no tags), the higher keyword length cap
 * ({@link Keywords#MAX_KEYWORD_LENGTH}), and the sixth read-cache
 * ({@link CategoryReadCache}) in the eviction set. Each {@code evictAll()} also
 * clears the {@code keywords:suggest} region.
 */
@Service
@RequiredArgsConstructor
public class KeywordVocabularyService {

    private static final List<CollectionTableRef> TABLES = List.of(
            new CollectionTableRef("audio_keywords", "keyword", "audio_id", "audios"),
            new CollectionTableRef("video_keywords", "keyword", "video_id", "videos"),
            new CollectionTableRef("image_keywords", "keyword", "image_id", "images"),
            new CollectionTableRef("text_keywords", "keyword", "text_id", "texts"),
            new CollectionTableRef("project_keywords", "keyword", "project_id", "projects"),
            new CollectionTableRef("category_keywords", "keyword", "category_id", "categories"));

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 2000;

    private final VocabularyBulkRepository repository;
    private final AudioReadCache audioReadCache;
    private final VideoReadCache videoReadCache;
    private final ImageReadCache imageReadCache;
    private final TextReadCache textReadCache;
    private final ProjectReadCache projectReadCache;
    private final CategoryReadCache categoryReadCache;

    @Transactional(readOnly = true)
    public List<VocabularyItemDTO> list(String q, Integer limit, Integer offset) {
        String canonicalQ = null;
        if (q != null && !q.isBlank()) {
            canonicalQ = Keywords.canonicalOne(q);
            if (canonicalQ == null) return List.of(); // longer than the keyword cap → no matches
        }
        return repository.listUsage(TABLES, canonicalQ, clampLimit(limit), clampOffset(offset));
    }

    @Transactional
    public VocabularyRenameResult rename(String from, String to) {
        String canonicalFrom = Keywords.canonicalOne(from);
        String canonicalTo = Keywords.canonicalOne(to);
        if (canonicalFrom == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Source keyword is blank.");
        }
        if (canonicalTo == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Target keyword is blank or exceeds " + Keywords.MAX_KEYWORD_LENGTH + " characters.");
        }
        if (canonicalFrom.equals(canonicalTo)) {
            return new VocabularyRenameResult(canonicalFrom, canonicalTo, 0, 0);
        }
        VocabularyBulkRepository.RenameCounts counts = repository.rename(TABLES, canonicalFrom, canonicalTo);
        evictAll();
        return new VocabularyRenameResult(canonicalFrom, canonicalTo, counts.renamed(), counts.merged());
    }

    @Transactional
    public VocabularyDeleteResult delete(String value) {
        String canonical = Keywords.canonicalOne(value);
        if (canonical == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Keyword is blank.");
        }
        long deleted = repository.delete(TABLES, canonical);
        evictAll();
        return new VocabularyDeleteResult(canonical, deleted);
    }

    /** Each evictAll() also clears the shared {@code keywords:suggest} region. */
    private void evictAll() {
        audioReadCache.evictAll();
        videoReadCache.evictAll();
        imageReadCache.evictAll();
        textReadCache.evictAll();
        projectReadCache.evictAll();
        categoryReadCache.evictAll();
    }

    private static int clampLimit(Integer limit) {
        if (limit == null || limit <= 0) return DEFAULT_LIMIT;
        return Math.min(limit, MAX_LIMIT);
    }

    private static int clampOffset(Integer offset) {
        return (offset == null || offset < 0) ? 0 : offset;
    }
}
