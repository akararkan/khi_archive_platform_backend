package ak.dev.khi_archive_platform.platform.api.tag;

import ak.dev.khi_archive_platform.platform.dto.tag.TagSuggestionDTO;
import ak.dev.khi_archive_platform.platform.service.tag.TagSuggestService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Tag autocomplete.
 *
 * <p>Typing {@code sula} in the tag picker fans out one cheap call to
 * {@code GET /api/tags/suggest?q=sula} which returns the union of distinct
 * tags across audio + video + image + text + project ranked by relevance
 * and usage count.</p>
 *
 * <p>Sits under {@code /api/tags} (authenticated — see {@code SecurityConfig});
 * if you want the guest UI to autocomplete tags too, mirror this on
 * {@code /api/guest/tags/suggest}.</p>
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/tags")
public class TagAPI {

    private final TagSuggestService tagSuggestService;

    /**
     * Ranked tag autocomplete.
     *
     * @param q     the user's input — anything typed in the tag picker.
     *              Canonicalised (trim, NFKC, lower-case) server-side before
     *              matching, so the client need not pre-process.
     * @param limit max rows to return. Defaults to
     *              {@link TagSuggestService#DEFAULT_LIMIT}; clamped to
     *              {@link TagSuggestService#MAX_LIMIT}.
     * @return at most {@code limit} rows ordered by:
     *         exact &gt; prefix &gt; substring, ties broken by descending
     *         usage count then alphabetical.
     */
    @GetMapping("/suggest")
    public ResponseEntity<List<TagSuggestionDTO>> suggest(
            @RequestParam("q") String q,
            @RequestParam(value = "limit", required = false) Integer limit
    ) {
        return ResponseEntity.ok(tagSuggestService.suggest(q, limit));
    }
}
