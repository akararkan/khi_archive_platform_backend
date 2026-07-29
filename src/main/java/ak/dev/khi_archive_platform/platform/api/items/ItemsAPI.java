package ak.dev.khi_archive_platform.platform.api.items;

import ak.dev.khi_archive_platform.platform.dto.items.ItemDTO;
import ak.dev.khi_archive_platform.platform.dto.items.ItemFilterParams;
import ak.dev.khi_archive_platform.platform.dto.items.ItemType;
import ak.dev.khi_archive_platform.platform.dto.items.VisibilityUpdateRequest;
import ak.dev.khi_archive_platform.platform.service.items.ItemVisibilityService;
import ak.dev.khi_archive_platform.platform.service.items.ItemsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Unified read-only endpoint that merges Audio + Video + Image + Text into
 * a single list, each carrying its project (collection) and person info plus
 * the full original DTO under the matching slot.
 *
 * <p>Auth: same {@code *:read} permissions as the per-type endpoints. We
 * require <b>all four</b> read permissions because the response can include
 * rows from any of the four media types — there's no per-type filtering at
 * the security layer.</p>
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/items")
public class ItemsAPI {

    private final ItemsService itemsService;
    private final ItemVisibilityService itemVisibilityService;

    /**
     * List every active media row (audio/video/image/text) across the platform.
     * Pagination, search, filter, and sort are all driven from this single call.
     *
     * <h3>Query parameters</h3>
     * <ul>
     *   <li>{@code q} — free-text search across code, title, project, person,
     *       creator, tags, keywords, etc. Case-insensitive substring.</li>
     *   <li>{@code types} — repeat: {@code types=AUDIO&types=VIDEO}. Empty/omitted = all four.</li>
     *   <li>{@code projectCodes} — repeat. Restrict to projects.</li>
     *   <li>{@code personCodes} — repeat. Use {@code UNTITLED} to include
     *       projects with no linked person.</li>
     *   <li>{@code categoryCodes} — repeat. Row's project must join any of these categories.</li>
     *   <li>{@code languages} — repeat. Case-insensitive equality.</li>
     *   <li>{@code isPublic} — true/false. Row-level public flag.</li>
     *   <li>{@code projectVisibleToPublic} — true/false. Project-level public flag.</li>
     *   <li>{@code createdFrom}, {@code createdTo} — YYYY-MM-DD, resolved to day
     *       bounds in the archive zone (Asia/Baghdad).</li>
     *   <li>{@code updatedFrom}, {@code updatedTo} — ISO-8601 instants.</li>
     *   <li>{@code sortBy} — one of: createdAt, updatedAt, title, code,
     *       projectName, personName, type. Default: updatedAt.</li>
     *   <li>{@code sortDirection} — asc or desc.</li>
     *   <li>{@code page}, {@code size} — Spring pagination, default size 50.</li>
     * </ul>
     */
    @GetMapping
    @PreAuthorize("hasAuthority('audio:read') and hasAuthority('video:read') "
            + "and hasAuthority('image:read') and hasAuthority('text:read')")
    public ResponseEntity<Page<ItemDTO>> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) List<ItemType> types,
            @RequestParam(required = false) List<String> projectCodes,
            @RequestParam(required = false) List<String> personCodes,
            @RequestParam(required = false) List<String> categoryCodes,
            @RequestParam(required = false) List<String> languages,
            @RequestParam(required = false) Boolean isPublic,
            @RequestParam(required = false) Boolean projectVisibleToPublic,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdTo,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate updatedFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate updatedTo,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDirection,
            @PageableDefault(size = 50) Pageable pageable
    ) {
        ItemFilterParams params = new ItemFilterParams();
        params.setQ(q);
        params.setTypes(types);
        params.setProjectCodes(projectCodes);
        params.setPersonCodes(personCodes);
        params.setCategoryCodes(categoryCodes);
        params.setLanguages(languages);
        params.setIsPublic(isPublic);
        params.setProjectVisibleToPublic(projectVisibleToPublic);
        params.setCreatedFrom(createdFrom);
        params.setCreatedTo(createdTo);
        params.setUpdatedFrom(updatedFrom);
        params.setUpdatedTo(updatedTo);
        params.setSortBy(sortBy);
        params.setSortDirection(sortDirection);
        return ResponseEntity.ok(itemsService.list(params, pageable));
    }

    /**
     * Lightweight visibility toggle for a single media row. Maps 1:1 to the
     * frontend's {@code setItemVisibility(item, isPublic)} — the row already
     * carries {@code item.type} and {@code item.code}, so the UI can flip the
     * flag without re-sending the full 50-field edit payload.
     *
     * <p>Authority {@code {type}:update} is checked inside the dispatcher
     * (data-dependent, so it can't be expressed declaratively here). Trashed
     * records are not silently resurrected — they 404 the same as every other
     * write path.</p>
     */
    @PatchMapping("/{type}/{code}/visibility")
    public ResponseEntity<Object> setVisibility(
            @PathVariable String type,
            @PathVariable String code,
            @Valid @RequestBody VisibilityUpdateRequest body,
            Authentication auth,
            HttpServletRequest request
    ) {
        Object updated = itemVisibilityService.setVisibility(type, code, body.isPublic(), auth, request);
        return ResponseEntity.ok(updated);
    }
}
