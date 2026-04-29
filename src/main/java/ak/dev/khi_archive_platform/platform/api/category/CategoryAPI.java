package ak.dev.khi_archive_platform.platform.api.category;

import ak.dev.khi_archive_platform.platform.dto.category.CategoryCreateRequestDTO;
import ak.dev.khi_archive_platform.platform.dto.category.CategoryResponseDTO;
import ak.dev.khi_archive_platform.platform.dto.category.CategoryUpdateRequestDTO;
import ak.dev.khi_archive_platform.platform.service.category.CategoryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/category")
public class CategoryAPI {

    private final CategoryService categoryService;

    @GetMapping
    @PreAuthorize("hasAuthority('category:read')")
    public ResponseEntity<Page<CategoryResponseDTO>> getAll(
            @PageableDefault(size = 100) Pageable pageable,
            Authentication auth,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(categoryService.getAll(pageable, auth, request));
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
     * Soft remove — marks the category as removed but keeps data in the database.
     */
    @PatchMapping("/{categoryCode}/remove")
    @PreAuthorize("hasAuthority('category:remove')")
    public ResponseEntity<Void> remove(
            @PathVariable String categoryCode,
            Authentication auth,
            HttpServletRequest request
    ) {
        categoryService.remove(categoryCode, auth, request);
        return ResponseEntity.noContent().build();
    }

    /**
     * Hard delete — permanently removes the row from the database.
     * Restricted to ADMIN only.
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
}
