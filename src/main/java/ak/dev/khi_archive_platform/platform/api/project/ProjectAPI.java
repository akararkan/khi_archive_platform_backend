package ak.dev.khi_archive_platform.platform.api.project;

import ak.dev.khi_archive_platform.platform.dto.project.ProjectCreateRequestDTO;
import ak.dev.khi_archive_platform.platform.dto.project.ProjectResponseDTO;
import ak.dev.khi_archive_platform.platform.dto.project.ProjectUpdateRequestDTO;
import ak.dev.khi_archive_platform.platform.service.project.ProjectService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/project")
public class ProjectAPI {

    private final ProjectService projectService;

    @GetMapping
    public ResponseEntity<List<ProjectResponseDTO>> getAll(Authentication auth, HttpServletRequest request) {
        return ResponseEntity.ok(projectService.getAll(auth, request));
    }

    @GetMapping("/{projectCode}")
    public ResponseEntity<ProjectResponseDTO> getByProjectCode(
            @PathVariable String projectCode,
            Authentication auth,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(projectService.getByProjectCode(projectCode, auth, request));
    }

    @PostMapping
    public ResponseEntity<ProjectResponseDTO> create(
            @Valid @RequestBody ProjectCreateRequestDTO dto,
            Authentication auth,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(projectService.create(dto, auth, request));
    }

    @PatchMapping("/{projectCode}")
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
     * Restricted to ADMIN and SUPER_ADMIN only.
     */
    @DeleteMapping("/{projectCode}")
    public ResponseEntity<Void> delete(
            @PathVariable String projectCode,
            Authentication auth,
            HttpServletRequest request
    ) {
        projectService.delete(projectCode, auth, request);
        return ResponseEntity.noContent().build();
    }
}
