package ak.dev.khi_archive_platform.platform.api.category;

import ak.dev.khi_archive_platform.platform.dto.category.CategoryCreateRequestDTO;
import ak.dev.khi_archive_platform.platform.dto.category.CategoryFilterParams;
import ak.dev.khi_archive_platform.platform.dto.category.CategoryResponseDTO;
import ak.dev.khi_archive_platform.platform.dto.category.CategoryUpdateRequestDTO;
import ak.dev.khi_archive_platform.platform.service.category.CategoryService;
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
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/category")
public class CategoryAPI {

    private final CategoryService categoryService;

    /**
     * List active categories with optional filter + sort.
     *
     * Sort:
     *   sortBy        — name | createdAt | updatedAt
     *                   (synonyms accepted: alpha/alphabetical, added/dateAdded, modified/dateModified)
     *   sortDirection — asc | desc (default asc)
     *
     * Filter:
     *   createdFrom / createdTo — ISO-8601 instants, inclusive range over createdAt
     *   updatedFrom / updatedTo — ISO-8601 instants, inclusive range over updatedAt
     *   tags                    — repeat the param or comma-separate; matches Category.keywords
     *   tagMatch                — any (default) | all
     *
     * With no filter params this returns the cached active list directly (fastest path);
     * with filter params it applies them in-memory over the same cached list.
     */
    @GetMapping
    @PreAuthorize("hasAuthority('category:read')")
    public ResponseEntity<Page<CategoryResponseDTO>> getAll(
            @PageableDefault(size = 100) Pageable pageable,
            @RequestParam(value = "sortBy", required = false) String sortBy,
            @RequestParam(value = "sortDirection", required = false) String sortDirection,
            @RequestParam(value = "createdFrom", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdFrom,
            @RequestParam(value = "createdTo", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdTo,
            @RequestParam(value = "updatedFrom", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate updatedFrom,
            @RequestParam(value = "updatedTo", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate updatedTo,
            @RequestParam(value = "tags", required = false) List<String> tags,
            @RequestParam(value = "tagMatch", required = false) String tagMatch,
            Authentication auth,
            HttpServletRequest request
    ) {
        CategoryFilterParams params = new CategoryFilterParams(
                sortBy, sortDirection,
                createdFrom, createdTo,
                updatedFrom, updatedTo,
                tags, tagMatch);
        return ResponseEntity.ok(categoryService.getAll(pageable, params, auth, request));
    }

    /**
     * Fuzzy, typo-tolerant search across category name, description, code, and keywords.
     * Backed by PostgreSQL pg_trgm GIN indexes — multilingual, fast, no external infra.
     */
    @GetMapping("/search")
    @PreAuthorize("hasAuthority('category:read')")
    public ResponseEntity<List<CategoryResponseDTO>> search(
            @RequestParam("q") String q,
            @RequestParam(value = "limit", required = false) Integer limit,
            Authentication auth,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(categoryService.search(q, limit, auth, request));
    }

    @GetMapping("/{categoryCode}")
    @PreAuthorize("hasAuthority('category:read')")
    public ResponseEntity<CategoryResponseDTO> getByCategoryCode(
            @PathVariable String categoryCode,
            Authentication auth,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(categoryService.getByCategoryCode(categoryCode, auth, request));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('category:create')")
    public ResponseEntity<CategoryResponseDTO> create(
            @Valid @RequestBody CategoryCreateRequestDTO dto,
            Authentication auth,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(categoryService.create(dto, auth, request));
    }

    /**
     * Bulk-create categories. Accepts the JSON array from `test-categories-1000.json`.
     * Skips rows whose categoryCode already exists. One transaction, one cache eviction.
     */
    @PostMapping("/bulk")
    @PreAuthorize("hasAuthority('category:create')")
    public ResponseEntity<CategoryService.BulkCreateResult> createAll(
            @Valid @RequestBody List<CategoryCreateRequestDTO> dtos,
            Authentication auth,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(categoryService.createAll(dtos, auth, request));
    }

    @PatchMapping("/{categoryCode}")
    @PreAuthorize("hasAuthority('category:update')")
    public ResponseEntity<CategoryResponseDTO> update(
            @PathVariable String categoryCode,
            @RequestBody CategoryUpdateRequestDTO dto,
            Authentication auth,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(categoryService.update(categoryCode, dto, auth, request));
    }

    /**
     * Soft delete — sends the category to the trash. Admin-only.
     * Blocked if any active project still references the category.
     */
    @DeleteMapping("/{categoryCode}")
    @PreAuthorize("hasAuthority('category:delete')")
    public ResponseEntity<Void> delete(
            @PathVariable String categoryCode,
            Authentication auth,
            HttpServletRequest request
    ) {
        categoryService.delete(categoryCode, auth, request);
        return ResponseEntity.noContent().build();
    }

    /**
     * Restore a category from trash. Admin-only.
     */
    @PostMapping("/{categoryCode}/restore")
    @PreAuthorize("hasAuthority('category:delete')")
    public ResponseEntity<CategoryResponseDTO> restore(
            @PathVariable String categoryCode,
            Authentication auth,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(categoryService.restore(categoryCode, auth, request));
    }

    /**
     * List trashed categories. Admin-only.
     */
    @GetMapping("/trash")
    @PreAuthorize("hasAuthority('category:delete')")
    public ResponseEntity<Page<CategoryResponseDTO>> getTrash(
            @PageableDefault(size = 100) Pageable pageable,
            Authentication auth,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(categoryService.getTrash(pageable, auth, request));
    }

    /**
     * Permanently delete a category from trash. Admin-only. The category must
     * already be in trash, and no project (active or trashed) may reference it.
     */
    @DeleteMapping("/{categoryCode}/purge")
    @PreAuthorize("hasAuthority('category:delete')")
    public ResponseEntity<Void> purge(
            @PathVariable String categoryCode,
            Authentication auth,
            HttpServletRequest request
    ) {
        categoryService.purge(categoryCode, auth, request);
        return ResponseEntity.noContent().build();
    }
}
