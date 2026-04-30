package ak.dev.khi_archive_platform.platform.api.project;

import ak.dev.khi_archive_platform.platform.dto.project.ProjectCreateRequestDTO;
import ak.dev.khi_archive_platform.platform.dto.project.ProjectResponseDTO;
import ak.dev.khi_archive_platform.platform.dto.project.ProjectUpdateRequestDTO;
import ak.dev.khi_archive_platform.platform.service.project.ProjectService;
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
@RequestMapping("/api/project")
public class ProjectAPI {

    private final ProjectService projectService;

    @GetMapping
    @PreAuthorize("hasAuthority('project:read')")
    public ResponseEntity<Page<ProjectResponseDTO>> getAll(
            @PageableDefault(size = 100) Pageable pageable,
            Authentication auth,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(projectService.getAll(pageable, auth, request));
    }

    @GetMapping("/{projectCode}")
    @PreAuthorize("hasAuthority('project:read')")
    public ResponseEntity<ProjectResponseDTO> getByProjectCode(
            @PathVariable String projectCode,
            Authentication auth,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(projectService.getByProjectCode(projectCode, auth, request));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('project:create')")
    public ResponseEntity<ProjectResponseDTO> create(
            @Valid @RequestBody ProjectCreateRequestDTO dto,
            Authentication auth,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(projectService.create(dto, auth, request));
    }

    /**
     * Bulk-create projects. Accepts a JSON array of project create DTOs.
     * Project codes are auto-generated; rows that fail validation or whose
     * generated code already exists are skipped. One transaction, one summary
     * audit entry.
     */
    @PostMapping("/bulk")
    @PreAuthorize("hasAuthority('project:create')")
    public ResponseEntity<ProjectService.BulkCreateResult> createAll(
            @Valid @RequestBody List<ProjectCreateRequestDTO> dtos,
            Authentication auth,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(projectService.createAll(dtos, auth, request));
    }

    @PatchMapping("/{projectCode}")
    @PreAuthorize("hasAuthority('project:update')")
    public ResponseEntity<ProjectResponseDTO> update(
            @PathVariable String projectCode,
            @RequestBody ProjectUpdateRequestDTO dto,
            Authentication auth,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(projectService.update(projectCode, dto, auth, request));
    }

    /**
     * Soft delete — sends the project (and its media) to the trash. Admin-only.
     * Categories and the linked person are not affected.
     */
    @DeleteMapping("/{projectCode}")
    @PreAuthorize("hasAuthority('project:delete')")
    public ResponseEntity<Void> delete(
            @PathVariable String projectCode,
            Authentication auth,
            HttpServletRequest request
    ) {
        projectService.delete(projectCode, auth, request);
        return ResponseEntity.noContent().build();
    }

    /**
     * Restore a project from trash. Admin-only. Cascades to every trashed
     * media record (audio/video/image/text) belonging to this project — the
     * response body lists how many of each came back, so the UI can confirm.
     */
    @PostMapping("/{projectCode}/restore")
    @PreAuthorize("hasAuthority('project:delete')")
    public ResponseEntity<ProjectService.RestoreResult> restore(
            @PathVariable String projectCode,
            Authentication auth,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(projectService.restore(projectCode, auth, request));
    }

    /**
     * List trashed projects. Admin-only.
     */
    @GetMapping("/trash")
    @PreAuthorize("hasAuthority('project:delete')")
    public ResponseEntity<Page<ProjectResponseDTO>> getTrash(
            @PageableDefault(size = 100) Pageable pageable,
            Authentication auth,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(projectService.getTrash(pageable, auth, request));
    }

    /**
     * Permanently delete a project (and all its media + S3 files) from trash.
     * Admin-only. Project must already be in trash. The linked person and
     * categories are not affected.
     */
    @DeleteMapping("/{projectCode}/purge")
    @PreAuthorize("hasAuthority('project:delete')")
    public ResponseEntity<Void> purge(
            @PathVariable String projectCode,
            Authentication auth,
            HttpServletRequest request
    ) {
        projectService.purge(projectCode, auth, request);
        return ResponseEntity.noContent().build();
    }
}
