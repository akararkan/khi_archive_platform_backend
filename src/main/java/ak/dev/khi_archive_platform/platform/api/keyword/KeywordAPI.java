package ak.dev.khi_archive_platform.platform.api.keyword;

import ak.dev.khi_archive_platform.platform.dto.keyword.KeywordSuggestionDTO;
import ak.dev.khi_archive_platform.platform.service.keyword.KeywordSuggestService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Keyword autocomplete — sibling of {@code /api/tags/suggest}.
 *
 * <p>Pulls the union of distinct keywords across audio + video + image + text
 * + project + category, ranked by relevance and usage count. Lives under
 * {@code /api/keywords} so the frontend can use a different picker component
 * for keywords (longer phrases) vs. tags (short labels) without paying for
 * the heavier cross-entity query when only one is needed.</p>
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/keywords")
public class KeywordAPI {

    private final KeywordSuggestService keywordSuggestService;

    /**
     * Ranked keyword autocomplete.
     *
     * @param q     the user's input — anything typed in the keyword picker.
     *              Canonicalised (NFKC, trim, collapse-spaces, lower-case)
     *              server-side before matching, so the client need not
     *              pre-process.
     * @param limit max rows to return. Defaults to
     *              {@link KeywordSuggestService#DEFAULT_LIMIT}; clamped to
     *              {@link KeywordSuggestService#MAX_LIMIT}.
     * @return at most {@code limit} rows ordered by:
     *         exact &gt; prefix &gt; substring, ties broken by descending
     *         usage count then alphabetical.
     */
    @GetMapping("/suggest")
    public ResponseEntity<List<KeywordSuggestionDTO>> suggest(
            @RequestParam("q") String q,
            @RequestParam(value = "limit", required = false) Integer limit
    ) {
        return ResponseEntity.ok(keywordSuggestService.suggest(q, limit));
    }
}
