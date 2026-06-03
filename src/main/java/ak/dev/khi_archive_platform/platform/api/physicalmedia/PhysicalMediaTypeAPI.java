package ak.dev.khi_archive_platform.platform.api.physicalmedia;

import ak.dev.khi_archive_platform.platform.dto.physicalmedia.PhysicalMediaTypeCreateRequestDTO;
import ak.dev.khi_archive_platform.platform.dto.physicalmedia.PhysicalMediaTypeResponseDTO;
import ak.dev.khi_archive_platform.platform.dto.physicalmedia.PhysicalMediaTypeUpdateRequestDTO;
import ak.dev.khi_archive_platform.platform.service.physicalmedia.PhysicalMediaTypeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Catalog of physical-media types and their nine technical defaults.
 *
 * <p>The frontend's "type dropdown" pulls from {@code GET /} on this
 * controller. When the user picks a type, the frontend reads the nine
 * default fields off the response and autofills the corresponding
 * inputs on the create / edit form — overridable per row.
 *
 * <p>Adding a new type is an admin-only flow: the "+ Add type" button
 * in the dropdown opens a small form that POSTs to {@code POST /}. The
 * type is then immediately available in everyone's dropdown.
 *
 * <p>Permissions:
 * <ul>
 *   <li>{@code physical_media:read} → list / get (everyone with read).</li>
 *   <li>{@code physical_media:type_manage} → create / update / delete
 *       (admin-only by default).</li>
 * </ul>
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/physical-media/types")
public class PhysicalMediaTypeAPI {

    private final PhysicalMediaTypeService service;

    @GetMapping
    @PreAuthorize("hasAuthority('physical_media:read')")
    public ResponseEntity<List<PhysicalMediaTypeResponseDTO>> list() {
        return ResponseEntity.ok(service.listAll());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('physical_media:read')")
    public ResponseEntity<PhysicalMediaTypeResponseDTO> getOne(@PathVariable Long id) {
        return ResponseEntity.ok(service.getById(id));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('physical_media:type_manage')")
    public ResponseEntity<PhysicalMediaTypeResponseDTO> create(
            @Valid @RequestBody PhysicalMediaTypeCreateRequestDTO dto,
            Authentication auth) {
        return ResponseEntity.ok(service.create(dto, auth));
    }

    @PatchMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('physical_media:type_manage')")
    public ResponseEntity<PhysicalMediaTypeResponseDTO> update(
            @PathVariable Long id,
            @Valid @RequestBody PhysicalMediaTypeUpdateRequestDTO dto,
            Authentication auth) {
        return ResponseEntity.ok(service.update(id, dto, auth));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('physical_media:type_manage')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
