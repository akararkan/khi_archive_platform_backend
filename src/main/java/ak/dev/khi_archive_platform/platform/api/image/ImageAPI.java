package ak.dev.khi_archive_platform.platform.api.image;

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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
    public ResponseEntity<List<ImageResponseDTO>> getAll(Authentication auth, HttpServletRequest request) {
        return ResponseEntity.ok(imageService.getAll(auth, request));
    }

    @GetMapping("/{imageCode}")
    public ResponseEntity<ImageResponseDTO> getByImageCode(
            @PathVariable String imageCode,
            Authentication auth,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(imageService.getByImageCode(imageCode, auth, request));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ImageResponseDTO> create(
            @RequestPart("data") String dataJson,
            @RequestPart("file") MultipartFile imageFile,
            Authentication auth,
            HttpServletRequest request
    ) {
        ImageCreateRequestDTO dto = parseAndValidate(dataJson, ImageCreateRequestDTO.class);
        return ResponseEntity.ok(imageService.create(dto, imageFile, auth, request));
    }

    @PatchMapping(value = "/{imageCode}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
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
     * Soft remove — marks the image as removed but keeps data in the database.
     */
    @PatchMapping("/{imageCode}/remove")
    public ResponseEntity<Void> remove(
            @PathVariable String imageCode,
            Authentication auth,
            HttpServletRequest request
    ) {
        imageService.remove(imageCode, auth, request);
        return ResponseEntity.noContent().build();
    }

    /**
     * Hard delete — permanently removes the row from the database.
     * Restricted to ADMIN and SUPER_ADMIN only.
     */
    @DeleteMapping("/{imageCode}")
    public ResponseEntity<Void> delete(
            @PathVariable String imageCode,
            Authentication auth,
            HttpServletRequest request
    ) {
        imageService.delete(imageCode, auth, request);
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
