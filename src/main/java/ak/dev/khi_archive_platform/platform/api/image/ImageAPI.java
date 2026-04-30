package ak.dev.khi_archive_platform.platform.api.image;

import ak.dev.khi_archive_platform.platform.dto.image.ImageBulkCreateRequestDTO;
import ak.dev.khi_archive_platform.platform.dto.image.ImageCreateRequestDTO;
import ak.dev.khi_archive_platform.platform.dto.image.ImageResponseDTO;
import ak.dev.khi_archive_platform.platform.dto.image.ImageUpdateRequestDTO;
import ak.dev.khi_archive_platform.platform.exceptions.ImageValidationException;
import ak.dev.khi_archive_platform.platform.service.image.ImageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/image")
public class ImageAPI {

    private final ImageService imageService;
    private final ObjectMapper objectMapper;
    private final Validator validator;

    @GetMapping
    @PreAuthorize("hasAuthority('image:read')")
    public ResponseEntity<Page<ImageResponseDTO>> getAll(
            @PageableDefault(size = 100) Pageable pageable,
            Authentication auth,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(imageService.getAll(pageable, auth, request));
    }

    /**
     * Two-phase fuzzy search across Image text fields and child collections
     * (subjects, genres, colors, usages, tags, keywords). Backed by pg_trgm GIN
     * indexes — typo-tolerant, multilingual, sub-second on large tables.
     */
    @GetMapping("/search")
    @PreAuthorize("hasAuthority('image:read')")
    public ResponseEntity<List<ImageResponseDTO>> search(
            @RequestParam("q") String q,
            @RequestParam(value = "limit", required = false) Integer limit,
            Authentication auth,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(imageService.search(q, limit, auth, request));
    }

    @GetMapping("/{imageCode}")
    @PreAuthorize("hasAuthority('image:read')")
    public ResponseEntity<ImageResponseDTO> getByImageCode(
            @PathVariable String imageCode,
            Authentication auth,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(imageService.getByImageCode(imageCode, auth, request));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('image:create')")
    public ResponseEntity<ImageResponseDTO> create(
            @RequestPart("data") String dataJson,
            @RequestPart("file") MultipartFile imageFile,
            Authentication auth,
            HttpServletRequest request
    ) {
        ImageCreateRequestDTO dto = parseAndValidate(dataJson, ImageCreateRequestDTO.class);
        return ResponseEntity.ok(imageService.create(dto, imageFile, auth, request));
    }

    /**
     * Bulk-create image records. Accepts a JSON array of bulk DTOs; each entry
     * carries its own pre-uploaded {@code imageFileUrl} (no multipart). Image
     * codes are auto-generated. One transaction, one audit summary.
     */
    @PostMapping(value = "/bulk", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('image:create')")
    public ResponseEntity<ImageService.BulkCreateResult> createAll(
            @RequestBody List<ImageBulkCreateRequestDTO> dtos,
            Authentication auth,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(imageService.createAll(dtos, auth, request));
    }

    @PatchMapping(value = "/{imageCode}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('image:update')")
    public ResponseEntity<ImageResponseDTO> update(
            @PathVariable String imageCode,
            @RequestPart("data") String dataJson,
            @RequestPart(value = "file", required = false) MultipartFile imageFile,
            Authentication auth,
            HttpServletRequest request
    ) {
        ImageUpdateRequestDTO dto = parseAndValidate(dataJson, ImageUpdateRequestDTO.class);
        return ResponseEntity.ok(imageService.update(imageCode, dto, imageFile, auth, request));
    }

    /**
     * Soft delete — sends the image record to the trash. Admin-only.
     * The S3 file is preserved so the record can be restored later.
     */
    @DeleteMapping("/{imageCode}")
    @PreAuthorize("hasAuthority('image:delete')")
    public ResponseEntity<Void> delete(
            @PathVariable String imageCode,
            Authentication auth,
            HttpServletRequest request
    ) {
        imageService.delete(imageCode, auth, request);
        return ResponseEntity.noContent().build();
    }

    /**
     * Restore an image record from trash. Admin-only.
     */
    @PostMapping("/{imageCode}/restore")
    @PreAuthorize("hasAuthority('image:delete')")
    public ResponseEntity<ImageResponseDTO> restore(
            @PathVariable String imageCode,
            Authentication auth,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(imageService.restore(imageCode, auth, request));
    }

    /**
     * List trashed image records. Admin-only.
     */
    @GetMapping("/trash")
    @PreAuthorize("hasAuthority('image:delete')")
    public ResponseEntity<Page<ImageResponseDTO>> getTrash(
            @PageableDefault(size = 100) Pageable pageable,
            Authentication auth,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(imageService.getTrash(pageable, auth, request));
    }

    /**
     * Permanently delete an image record from trash, including its S3 file.
     * Admin-only. The record must already be in trash.
     */
    @DeleteMapping("/{imageCode}/purge")
    @PreAuthorize("hasAuthority('image:delete')")
    public ResponseEntity<Void> purge(
            @PathVariable String imageCode,
            Authentication auth,
            HttpServletRequest request
    ) {
        imageService.purge(imageCode, auth, request);
        return ResponseEntity.noContent().build();
    }

    private <T> T parseAndValidate(String dataJson, Class<T> clazz) {
        if (dataJson == null || dataJson.isBlank()) {
            throw new ImageValidationException("Missing 'data' part in the request.");
        }

        T dto;
        try {
            dto = objectMapper.readValue(dataJson, clazz);
        } catch (Exception e) {
            throw new ImageValidationException("Failed to parse image data: " + e.getMessage());
        }

        var violations = validator.validate(dto);
        if (!violations.isEmpty()) {
            Map<String, String> fieldErrors = new LinkedHashMap<>();
            for (ConstraintViolation<T> violation : violations) {
                fieldErrors.put(violation.getPropertyPath().toString(), violation.getMessage());
            }
            throw new ImageValidationException("Validation failed for image data.", fieldErrors);
        }

        return dto;
    }
}
