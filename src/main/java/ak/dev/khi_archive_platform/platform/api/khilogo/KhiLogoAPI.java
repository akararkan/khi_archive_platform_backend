package ak.dev.khi_archive_platform.platform.api.khilogo;

import ak.dev.khi_archive_platform.platform.dto.khilogo.KhiLogoResponseDTO;
import ak.dev.khi_archive_platform.platform.service.khilogo.KhiLogoService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/khi-logo")
public class KhiLogoAPI {

    private final KhiLogoService khiLogoService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('khi_logo:create')")
    public ResponseEntity<KhiLogoResponseDTO> create(@RequestPart("file") MultipartFile file) {
        return ResponseEntity.ok(khiLogoService.create(file));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('khi_logo:read')")
    public ResponseEntity<KhiLogoResponseDTO> getById(@PathVariable Long id) {
        return ResponseEntity.ok(khiLogoService.getById(id));
    }

    @PatchMapping(value = "/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('khi_logo:update')")
    public ResponseEntity<KhiLogoResponseDTO> update(@PathVariable Long id, @RequestPart("file") MultipartFile file) {
        return ResponseEntity.ok(khiLogoService.update(id, file));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('khi_logo:delete')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        khiLogoService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
