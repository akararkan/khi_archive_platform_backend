package ak.dev.khi_archive_platform.platform.api.text;

import ak.dev.khi_archive_platform.platform.dto.text.TextBulkCreateRequestDTO;
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
@RequestMapping("/api/text")
public class TextAPI {

    private final TextService textService;
    private final ObjectMapper objectMapper;
    private final Validator validator;

    @GetMapping
    @PreAuthorize("hasAuthority('text:read')")
    public ResponseEntity<Page<TextResponseDTO>> getAll(
            @PageableDefault(size = 100) Pageable pageable,
            Authentication auth,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(textService.getAll(pageable, auth, request));
    }

    /**
     * Two-phase fuzzy search across Text fields and child collections
     * (subjects, genres, tags, keywords). Backed by pg_trgm GIN indexes.
     */
    @GetMapping("/search")
    @PreAuthorize("hasAuthority('text:read')")
    public ResponseEntity<List<TextResponseDTO>> search(
            @RequestParam("q") String q,
            @RequestParam(value = "limit", required = false) Integer limit,
            Authentication auth,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(textService.search(q, limit, auth, request));
    }

    @GetMapping("/{textCode}")
    @PreAuthorize("hasAuthority('text:read')")
    public ResponseEntity<TextResponseDTO> getByTextCode(
            @PathVariable String textCode,
            Authentication auth,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(textService.getByTextCode(textCode, auth, request));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('text:create')")
    public ResponseEntity<TextResponseDTO> create(
            @RequestPart("data") String dataJson,
            @RequestPart("file") MultipartFile textFile,
            Authentication auth,
            HttpServletRequest request
    ) {
        TextCreateRequestDTO dto = parseAndValidate(dataJson, TextCreateRequestDTO.class);
        return ResponseEntity.ok(textService.create(dto, textFile, auth, request));
    }

    /**
     * Bulk-create text records. Accepts a JSON array; each entry carries its
     * own pre-uploaded {@code textFileUrl} (no multipart). Text codes are
     * auto-generated. One transaction, one audit summary.
     */
    @PostMapping(value = "/bulk", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('text:create')")
    public ResponseEntity<TextService.BulkCreateResult> createAll(
            @RequestBody List<TextBulkCreateRequestDTO> dtos,
            Authentication auth,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(textService.createAll(dtos, auth, request));
    }

    @PatchMapping(value = "/{textCode}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('text:update')")
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
     * Soft delete — sends the text record to the trash. Admin-only.
     * The S3 file is preserved so the record can be restored later.
     */
    @DeleteMapping("/{textCode}")
    @PreAuthorize("hasAuthority('text:delete')")
    public ResponseEntity<Void> delete(
            @PathVariable String textCode,
            Authentication auth,
            HttpServletRequest request
    ) {
        textService.delete(textCode, auth, request);
        return ResponseEntity.noContent().build();
    }

    /**
     * Restore a text record from trash. Admin-only.
     */
    @PostMapping("/{textCode}/restore")
    @PreAuthorize("hasAuthority('text:delete')")
    public ResponseEntity<TextResponseDTO> restore(
            @PathVariable String textCode,
            Authentication auth,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(textService.restore(textCode, auth, request));
    }

    /**
     * List trashed text records. Admin-only.
     */
    @GetMapping("/trash")
    @PreAuthorize("hasAuthority('text:delete')")
    public ResponseEntity<Page<TextResponseDTO>> getTrash(
            @PageableDefault(size = 100) Pageable pageable,
            Authentication auth,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(textService.getTrash(pageable, auth, request));
    }

    /**
     * Permanently delete a text record from trash, including its S3 file.
     * Admin-only. The record must already be in trash.
     */
    @DeleteMapping("/{textCode}/purge")
    @PreAuthorize("hasAuthority('text:delete')")
    public ResponseEntity<Void> purge(
            @PathVariable String textCode,
            Authentication auth,
            HttpServletRequest request
    ) {
        textService.purge(textCode, auth, request);
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
