package ak.dev.khi_archive_platform.platform.api.text;

import ak.dev.khi_archive_platform.platform.dto.text.TextCreateRequestDTO;
import ak.dev.khi_archive_platform.platform.dto.text.TextResponseDTO;
import ak.dev.khi_archive_platform.platform.dto.text.TextUpdateRequestDTO;
import ak.dev.khi_archive_platform.platform.exceptions.TextValidationException;
import ak.dev.khi_archive_platform.platform.service.text.TextService;
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
@RequestMapping("/api/text")
public class TextAPI {

    private final TextService textService;
    private final ObjectMapper objectMapper;
    private final Validator validator;

    @GetMapping
    public ResponseEntity<List<TextResponseDTO>> getAll(Authentication auth, HttpServletRequest request) {
        return ResponseEntity.ok(textService.getAll(auth, request));
    }

    @GetMapping("/{textCode}")
    public ResponseEntity<TextResponseDTO> getByTextCode(
            @PathVariable String textCode,
            Authentication auth,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(textService.getByTextCode(textCode, auth, request));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TextResponseDTO> create(
            @RequestPart("data") String dataJson,
            @RequestPart("file") MultipartFile textFile,
            Authentication auth,
            HttpServletRequest request
    ) {
        TextCreateRequestDTO dto = parseAndValidate(dataJson, TextCreateRequestDTO.class);
        return ResponseEntity.ok(textService.create(dto, textFile, auth, request));
    }

    @PatchMapping(value = "/{textCode}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TextResponseDTO> update(
            @PathVariable String textCode,
            @RequestPart("data") String dataJson,
            @RequestPart(value = "file", required = false) MultipartFile textFile,
            Authentication auth,
            HttpServletRequest request
    ) {
        TextUpdateRequestDTO dto = parseAndValidate(dataJson, TextUpdateRequestDTO.class);
        return ResponseEntity.ok(textService.update(textCode, dto, textFile, auth, request));
    }

    /**
     * Soft remove — marks the text as removed but keeps data in the database.
     */
    @PatchMapping("/{textCode}/remove")
    public ResponseEntity<Void> remove(
            @PathVariable String textCode,
            Authentication auth,
            HttpServletRequest request
    ) {
        textService.remove(textCode, auth, request);
        return ResponseEntity.noContent().build();
    }

    /**
     * Hard delete — permanently removes the row from the database.
     * Restricted to ADMIN and SUPER_ADMIN only.
     */
    @DeleteMapping("/{textCode}")
    public ResponseEntity<Void> delete(
            @PathVariable String textCode,
            Authentication auth,
            HttpServletRequest request
    ) {
        textService.delete(textCode, auth, request);
        return ResponseEntity.noContent().build();
    }

    private <T> T parseAndValidate(String dataJson, Class<T> clazz) {
        if (dataJson == null || dataJson.isBlank()) {
            throw new TextValidationException("Missing 'data' part in the request.");
        }

        T dto;
        try {
            dto = objectMapper.readValue(dataJson, clazz);
        } catch (Exception e) {
            throw new TextValidationException("Failed to parse text data: " + e.getMessage());
        }

        var violations = validator.validate(dto);
        if (!violations.isEmpty()) {
            Map<String, String> fieldErrors = new LinkedHashMap<>();
            for (ConstraintViolation<T> violation : violations) {
                fieldErrors.put(violation.getPropertyPath().toString(), violation.getMessage());
            }
            throw new TextValidationException("Validation failed for text data.", fieldErrors);
        }

        return dto;
    }
}
