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
     * Soft remove — marks the project as removed but keeps data in the database.
     */
    @PatchMapping("/{projectCode}/remove")
    @PreAuthorize("hasAuthority('project:remove')")
    public ResponseEntity<Void> remove(
            @PathVariable String projectCode,
            Authentication auth,
            HttpServletRequest request
    ) {
        projectService.remove(projectCode, auth, request);
        return ResponseEntity.noContent().build();
    }

    /**
     * Hard delete — permanently removes the row from the database.
     * Restricted to ADMIN only.
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
}
