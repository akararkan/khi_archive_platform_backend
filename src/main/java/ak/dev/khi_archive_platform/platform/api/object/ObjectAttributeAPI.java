package ak.dev.khi_archive_platform.platform.api.object;

import ak.dev.khi_archive_platform.platform.dto.object.ObjectCreateRequestDTO;
import ak.dev.khi_archive_platform.platform.dto.object.ObjectResponseDTO;
import ak.dev.khi_archive_platform.platform.dto.object.ObjectUpdateRequestDTO;
import ak.dev.khi_archive_platform.platform.service.object.ObjectAttributeService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/object")
public class ObjectAttributeAPI {

    private final ObjectAttributeService objectService;

    @GetMapping
    public ResponseEntity<List<ObjectResponseDTO>> getAll(Authentication auth, HttpServletRequest request) {
        return ResponseEntity.ok(objectService.getAll(auth, request));
    }

    @GetMapping("/{objectCode}")
    public ResponseEntity<ObjectResponseDTO> getByObjectCode(
            @PathVariable String objectCode,
            Authentication auth,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(objectService.getByObjectCode(objectCode, auth, request));
    }

    @PostMapping
    public ResponseEntity<ObjectResponseDTO> create(
            @Valid @RequestBody ObjectCreateRequestDTO dto,
            Authentication auth,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(objectService.create(dto, auth, request));
    }

    @PatchMapping("/{objectCode}")
    public ResponseEntity<ObjectResponseDTO> update(
            @PathVariable String objectCode,
            @RequestBody ObjectUpdateRequestDTO dto,
            Authentication auth,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(objectService.update(objectCode, dto, auth, request));
    }

    @DeleteMapping("/{objectCode}")
    public ResponseEntity<Void> delete(
            @PathVariable String objectCode,
            Authentication auth,
            HttpServletRequest request
    ) {
        objectService.delete(objectCode, auth, request);
        return ResponseEntity.noContent().build();
    }
}

