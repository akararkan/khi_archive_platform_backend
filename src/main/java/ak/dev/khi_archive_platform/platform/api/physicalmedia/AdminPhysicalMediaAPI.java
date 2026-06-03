package ak.dev.khi_archive_platform.platform.api.physicalmedia;

import ak.dev.khi_archive_platform.platform.dto.physicalmedia.PhysicalMediaResponseDTO;
import ak.dev.khi_archive_platform.platform.service.physicalmedia.PhysicalMediaService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-only trash management for physical media. Soft-trash itself lives
 * on {@link PhysicalMediaAPI} (so an employee can trash a row they own);
 * inspecting the trash, restoring, and the hard delete (purge) stay
 * admin-only because they touch records that may belong to other people.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/physical-media")
public class AdminPhysicalMediaAPI {

    private final PhysicalMediaService service;

    /** Trash listing. Same ascending-by-id default as the active list so the
     *  UI shows "earliest trashed first" without an explicit sort param. */
    @GetMapping("/trash")
    @PreAuthorize("hasAuthority('physical_media:delete')")
    public ResponseEntity<Page<PhysicalMediaResponseDTO>> trash(
            @PageableDefault(size = 100, sort = "id", direction = Sort.Direction.ASC) Pageable pageable,
            Authentication auth,
            HttpServletRequest request) {
        return ResponseEntity.ok(service.listTrash(pageable, auth, request));
    }

    @PostMapping("/{pmCode}/restore")
    @PreAuthorize("hasAuthority('physical_media:delete')")
    public ResponseEntity<PhysicalMediaResponseDTO> restore(
            @PathVariable String pmCode,
            Authentication auth,
            HttpServletRequest request) {
        return ResponseEntity.ok(service.restore(pmCode, auth, request));
    }

    @DeleteMapping("/{pmCode}/purge")
    @PreAuthorize("hasAuthority('physical_media:delete')")
    public ResponseEntity<Void> purge(
            @PathVariable String pmCode,
            Authentication auth,
            HttpServletRequest request) {
        service.purge(pmCode, auth, request);
        return ResponseEntity.noContent().build();
    }
}
