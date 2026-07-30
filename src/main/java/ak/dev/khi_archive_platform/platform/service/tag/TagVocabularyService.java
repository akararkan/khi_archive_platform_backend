package ak.dev.khi_archive_platform.platform.service.tag;

import ak.dev.khi_archive_platform.platform.dto.vocabulary.VocabularyDeleteResult;
import ak.dev.khi_archive_platform.platform.dto.vocabulary.VocabularyItemDTO;
import ak.dev.khi_archive_platform.platform.dto.vocabulary.VocabularyRenameResult;
import ak.dev.khi_archive_platform.platform.repo.vocabulary.CollectionTableRef;
import ak.dev.khi_archive_platform.platform.repo.vocabulary.VocabularyBulkRepository;
import ak.dev.khi_archive_platform.platform.service.audio.AudioReadCache;
import ak.dev.khi_archive_platform.platform.service.common.Tags;
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
 * Admin management of the tag vocabulary: list distinct tags with usage counts,
 * globally rename a tag, and globally delete a tag — across the five tag
 * collection tables ({@code audio_tags}, {@code video_tags}, {@code image_tags},
 * {@code text_tags}, {@code project_tags}), the same universe the
 * {@code /api/tags/suggest} autocomplete draws from.
 *
 * <p>Mutations go through {@link VocabularyBulkRepository} as set-based SQL and
 * then evict the read-caches of every tag-owning entity — each
 * {@code evictAll()} also clears the {@code tags:suggest} region — so
 * autocomplete and cached list DTOs reflect the change immediately.
 *
 * <p>Values are canonicalised with {@link Tags#canonicalOne} (the exact rule
 * used on save), so a rename target is stored in the same normal form as every
 * other tag. Person's {@code tag} column is a separate delimited string outside
 * this collection-table system and is intentionally not touched here.
 */
@Service
@RequiredArgsConstructor
public class TagVocabularyService {

    private static final List<CollectionTableRef> TABLES = List.of(
            new CollectionTableRef("audio_tags", "tag", "audio_id", "audios"),
            new CollectionTableRef("video_tags", "tag", "video_id", "videos"),
            new CollectionTableRef("image_tags", "tag", "image_id", "images"),
            new CollectionTableRef("text_tags", "tag", "text_id", "texts"),
            new CollectionTableRef("project_tags", "tag", "project_id", "projects"));

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 2000;

    private final VocabularyBulkRepository repository;
    private final AudioReadCache audioReadCache;
    private final VideoReadCache videoReadCache;
    private final ImageReadCache imageReadCache;
    private final TextReadCache textReadCache;
    private final ProjectReadCache projectReadCache;

    @Transactional(readOnly = true)
    public List<VocabularyItemDTO> list(String q, Integer limit, Integer offset) {
        String canonicalQ = null;
        if (q != null && !q.isBlank()) {
            canonicalQ = Tags.canonicalOne(q);
            if (canonicalQ == null) return List.of(); // longer than the tag cap → no matches
        }
        return repository.listUsage(TABLES, canonicalQ, clampLimit(limit), clampOffset(offset));
    }

    @Transactional
    public VocabularyRenameResult rename(String from, String to) {
        String canonicalFrom = Tags.canonicalOne(from);
        String canonicalTo = Tags.canonicalOne(to);
        if (canonicalFrom == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Source tag is blank.");
        }
        if (canonicalTo == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Target tag is blank or exceeds " + Tags.MAX_TAG_LENGTH + " characters.");
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
        String canonical = Tags.canonicalOne(value);
        if (canonical == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tag is blank.");
        }
        long deleted = repository.delete(TABLES, canonical);
        evictAll();
        return new VocabularyDeleteResult(canonical, deleted);
    }

    /** Each evictAll() also clears the shared {@code tags:suggest} region. */
    private void evictAll() {
        audioReadCache.evictAll();
        videoReadCache.evictAll();
        imageReadCache.evictAll();
        textReadCache.evictAll();
        projectReadCache.evictAll();
    }

    private static int clampLimit(Integer limit) {
        if (limit == null || limit <= 0) return DEFAULT_LIMIT;
        return Math.min(limit, MAX_LIMIT);
    }

    private static int clampOffset(Integer offset) {
        return (offset == null || offset < 0) ? 0 : offset;
    }
}
