package ak.dev.khi_archive_platform.platform.api.guest;

import ak.dev.khi_archive_platform.platform.dto.guest.GuestMediaItemDTO;
import ak.dev.khi_archive_platform.platform.dto.guest.GuestMediaSearchDTO;
import ak.dev.khi_archive_platform.platform.dto.guest.GuestMediaSearchParams;
import ak.dev.khi_archive_platform.platform.service.guest.GuestMediaSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public website's search source.
 *
 * <p>The site's search box lets a visitor choose where to look; picking the
 * platform points at these two endpoints. Together they cover the whole
 * interaction: type a keyword, see how many audios, videos, images and files
 * match, page through the ranked results, and open any one of them — without
 * the frontend needing to know which of the four per-kind endpoints holds it.
 *
 * <table>
 *   <caption>Endpoints</caption>
 *   <tr><th>Method</th><th>Path</th><th>Purpose</th></tr>
 *   <tr><td>GET</td><td>/api/guest/media/search</td>
 *       <td>One keyword, all four media kinds, ranked on one scale</td></tr>
 *   <tr><td>GET</td><td>/api/guest/media/{type}/{code}</td>
 *       <td>Open one result, whatever its kind</td></tr>
 * </table>
 *
 * <p>Both are anonymous: {@code SecurityConfig} matches {@code /api/guest/**}
 * with {@code permitAll()} and the JWT filter skips the prefix entirely, so no
 * token is needed and a stale cookie cannot break them. Responses carry only
 * {@code Guest…DTO} shapes, so the technical columns the archive keeps
 * internally (S3 paths, bit rate, file size, version internals, audit and
 * trash bookkeeping) never reach the public.
 *
 * <p>These endpoints add reach, not access: every row still passes the same
 * public-visibility gate the rest of the guest API applies, and a private or
 * trashed item is invisible here exactly as it is everywhere else.
 *
 * @see ak.dev.khi_archive_platform.platform.service.guest.GuestMediaSearchService
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/guest/media")
public class GuestMediaSearchAPI {

    private final GuestMediaSearchService guestMediaSearchService;

    /**
     * Searches every media kind at once and returns them merged and ranked.
     *
     * <p>Typical call for the site's search page:
     * <pre>
     * GET /api/guest/media/search?q=Hasan%20Zirak&amp;page=0&amp;size=24&amp;facets=true
     * </pre>
     *
     * <p>What comes back:
     * <ul>
     *   <li>{@code counts} — {@code total}, {@code audio}, {@code video},
     *       {@code image}, {@code text}. Always all four, whatever
     *       {@code type} selects, so the tab bar can be drawn from one call.</li>
     *   <li>{@code content} — the ranked page. Every entry has the same fields
     *       regardless of kind, so one card component renders the whole list;
     *       {@code type} + {@code code} address the item, {@code mediaUrl}
     *       streams or views it, {@code thumbnailUrl} illustrates it, and
     *       {@code matchedIn} says why it matched.</li>
     *   <li>{@code groups} — the same results split per kind, when
     *       {@code groupBy=type} is sent.</li>
     *   <li>{@code facets} — refine counts over the matched set, when
     *       {@code facets=true} is sent.</li>
     * </ul>
     *
     * <p>All filters are optional and compose; see {@link GuestMediaSearchParams}
     * for the full query string. Omitting {@code q} turns the call into a
     * browse of the newest public media.
     *
     * <p>Paging applies to the merged list, and independently to each
     * {@code groups} section. {@code size} is clamped to 100.
     *
     * @param filter the query string, bound with {@code @ModelAttribute}
     * @param page   zero-based page index, default 0
     * @param size   page size, default 24, max 100
     */
    @GetMapping("/search")
    public ResponseEntity<GuestMediaSearchDTO> search(
            @ModelAttribute GuestMediaSearchParams filter,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size
    ) {
        return ResponseEntity.ok(guestMediaSearchService.search(filter, page, size));
    }

    /**
     * Opens one media item using the {@code type} + {@code code} pair that
     * every search result carries — no per-kind routing on the frontend.
     *
     * <pre>
     * GET /api/guest/media/audio/AUD-001
     * GET /api/guest/media/text/TXT-014?related=false
     * </pre>
     *
     * <p>{@code type} accepts the public aliases, so {@code sound},
     * {@code photo} and {@code file} route to audio, image and text. The
     * kind-specific payload lands on the matching field ({@code audio},
     * {@code video}, {@code image}, {@code text}) and is identical to what the
     * per-kind endpoint returns; {@code item} repeats it in the flat card
     * shape for headers.
     *
     * <p>Returns 404 when the code is unknown, the type is not one of the four
     * kinds, or the item is trashed or not public — the three cases are
     * deliberately indistinguishable, so this endpoint cannot be used to probe
     * for the existence of non-public records.
     *
     * @param related whether to include the "more from this collection" rail
     *                (default true) — pass {@code false} to skip loading the
     *                rest of the project
     */
    @GetMapping("/{type}/{code}")
    public ResponseEntity<GuestMediaItemDTO> getItem(
            @PathVariable String type,
            @PathVariable String code,
            @RequestParam(value = "related", required = false, defaultValue = "true") boolean related
    ) {
        return guestMediaSearchService.getItem(type, code, related)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
