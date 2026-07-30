package ak.dev.khi_archive_platform.platform.api.tag;

import ak.dev.khi_archive_platform.platform.dto.vocabulary.VocabularyDeleteResult;
import ak.dev.khi_archive_platform.platform.dto.vocabulary.VocabularyItemDTO;
import ak.dev.khi_archive_platform.platform.dto.vocabulary.VocabularyRenameRequest;
import ak.dev.khi_archive_platform.platform.dto.vocabulary.VocabularyRenameResult;
import ak.dev.khi_archive_platform.platform.service.tag.TagVocabularyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin management of the tag vocabulary (ADMIN only). The autocomplete lives on
 * {@code /api/tags/suggest}; this is the CRUD-of-the-vocabulary surface.
 *
 * <ul>
 *   <li>{@code GET  /api/admin/tags?q=&limit=&offset=} — distinct tags with
 *       live usage counts, most-used first. {@code q} is an optional substring.</li>
 *   <li>{@code PATCH /api/admin/tags} — body {@code {"from","to"}}: rename a tag
 *       everywhere it appears (across audio/video/image/text/project), merging
 *       into the target if it already exists.</li>
 *   <li>{@code DELETE /api/admin/tags?value=} — remove a tag everywhere.</li>
 * </ul>
 *
 * <p>Rename/delete are single set-based SQL statements per table (no entity
 * loading) and evict the tag read-caches + {@code tags:suggest} afterwards.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/tags")
@PreAuthorize("hasRole('ADMIN')")
public class AdminTagAPI {

    private final TagVocabularyService service;

    @GetMapping
    public ResponseEntity<List<VocabularyItemDTO>> list(
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "offset", required = false) Integer offset) {
        return ResponseEntity.ok(service.list(q, limit, offset));
    }

    @PatchMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<VocabularyRenameResult> rename(@Valid @RequestBody VocabularyRenameRequest body) {
        return ResponseEntity.ok(service.rename(body.from(), body.to()));
    }

    @DeleteMapping
    public ResponseEntity<VocabularyDeleteResult> delete(@RequestParam("value") String value) {
        return ResponseEntity.ok(service.delete(value));
    }
}
