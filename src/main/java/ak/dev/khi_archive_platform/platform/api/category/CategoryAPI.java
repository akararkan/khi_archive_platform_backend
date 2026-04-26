package ak.dev.khi_archive_platform.platform.api.category;

import ak.dev.khi_archive_platform.platform.dto.category.CategoryCreateRequestDTO;
import ak.dev.khi_archive_platform.platform.dto.category.CategoryResponseDTO;
import ak.dev.khi_archive_platform.platform.dto.category.CategoryUpdateRequestDTO;
import ak.dev.khi_archive_platform.platform.service.category.CategoryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/category")
public class CategoryAPI {

    private final CategoryService categoryService;

    @GetMapping
    public ResponseEntity<List<CategoryResponseDTO>> getAll(Authentication auth, HttpServletRequest request) {
        return ResponseEntity.ok(categoryService.getAll(auth, request));
    }

    @GetMapping("/{categoryCode}")
    public ResponseEntity<CategoryResponseDTO> getByCategoryCode(
            @PathVariable String categoryCode,
            Authentication auth,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(categoryService.getByCategoryCode(categoryCode, auth, request));
    }

    @PostMapping
    public ResponseEntity<CategoryResponseDTO> create(
            @Valid @RequestBody CategoryCreateRequestDTO dto,
            Authentication auth,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(categoryService.create(dto, auth, request));
    }

    @PatchMapping("/{categoryCode}")
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
     * Restricted to ADMIN and SUPER_ADMIN only.
     */
    @DeleteMapping("/{categoryCode}")
    public ResponseEntity<Void> delete(
            @PathVariable String categoryCode,
            Authentication auth,
            HttpServletRequest request
    ) {
        categoryService.delete(categoryCode, auth, request);
        return ResponseEntity.noContent().build();
    }
}
