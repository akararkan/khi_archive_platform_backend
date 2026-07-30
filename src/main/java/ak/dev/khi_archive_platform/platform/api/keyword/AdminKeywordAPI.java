package ak.dev.khi_archive_platform.platform.api.keyword;

import ak.dev.khi_archive_platform.platform.dto.vocabulary.VocabularyDeleteResult;
import ak.dev.khi_archive_platform.platform.dto.vocabulary.VocabularyItemDTO;
import ak.dev.khi_archive_platform.platform.dto.vocabulary.VocabularyRenameRequest;
import ak.dev.khi_archive_platform.platform.dto.vocabulary.VocabularyRenameResult;
import ak.dev.khi_archive_platform.platform.service.keyword.KeywordVocabularyService;
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
 * Admin management of the keyword vocabulary (ADMIN only) — sibling of
 * {@code AdminTagAPI}, over the six keyword collection tables (adds Category).
 *
 * <ul>
 *   <li>{@code GET  /api/admin/keywords?q=&limit=&offset=} — distinct keywords
 *       with live usage counts.</li>
 *   <li>{@code PATCH /api/admin/keywords} — body {@code {"from","to"}}: rename.</li>
 *   <li>{@code DELETE /api/admin/keywords?value=} — remove a keyword everywhere.</li>
 * </ul>
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/keywords")
@PreAuthorize("hasRole('ADMIN')")
public class AdminKeywordAPI {

    private final KeywordVocabularyService service;

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
