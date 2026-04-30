package ak.dev.khi_archive_platform.platform.api.audio;

import ak.dev.khi_archive_platform.platform.dto.audio.AudioBulkCreateRequestDTO;
import ak.dev.khi_archive_platform.platform.dto.audio.AudioCreateRequestDTO;
import ak.dev.khi_archive_platform.platform.dto.audio.AudioResponseDTO;
import ak.dev.khi_archive_platform.platform.dto.audio.AudioUpdateRequestDTO;
import ak.dev.khi_archive_platform.platform.exceptions.AudioValidationException;
import ak.dev.khi_archive_platform.platform.service.audio.AudioService;
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
@RequestMapping("/api/audio")
public class AudioAPI {

    private final AudioService audioService;
    private final ObjectMapper objectMapper;
    private final Validator validator;

    @GetMapping
    @PreAuthorize("hasAuthority('audio:read')")
    public ResponseEntity<Page<AudioResponseDTO>> getAll(
            @PageableDefault(size = 100) Pageable pageable,
            Authentication auth,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(audioService.getAll(pageable, auth, request));
    }

    /**
     * Two-phase fuzzy search across Audio fields and child collections
     * (genres, contributors, tags, keywords). Backed by pg_trgm GIN indexes.
     */
    @GetMapping("/search")
    @PreAuthorize("hasAuthority('audio:read')")
    public ResponseEntity<List<AudioResponseDTO>> search(
            @RequestParam("q") String q,
            @RequestParam(value = "limit", required = false) Integer limit,
            Authentication auth,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(audioService.search(q, limit, auth, request));
    }

    @GetMapping("/{audioCode}")
    @PreAuthorize("hasAuthority('audio:read')")
    public ResponseEntity<AudioResponseDTO> getByAudioCode(
            @PathVariable String audioCode,
            Authentication auth,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(audioService.getByAudioCode(audioCode, auth, request));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('audio:create')")
    public ResponseEntity<AudioResponseDTO> create(
            @RequestPart("data") String dataJson,
            @RequestPart("file") MultipartFile audioFile,
            Authentication auth,
            HttpServletRequest request
    ) {
        AudioCreateRequestDTO dto = parseAndValidate(dataJson, AudioCreateRequestDTO.class);
        return ResponseEntity.ok(audioService.create(dto, audioFile, auth, request));
    }

    /**
     * Bulk-create audio records. Accepts a JSON array; each entry carries its
     * own pre-uploaded {@code audioFileUrl} (no multipart). Audio codes are
     * auto-generated. One transaction, one audit summary.
     */
    @PostMapping(value = "/bulk", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('audio:create')")
    public ResponseEntity<AudioService.BulkCreateResult> createAll(
            @RequestBody List<AudioBulkCreateRequestDTO> dtos,
            Authentication auth,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(audioService.createAll(dtos, auth, request));
    }

    @PatchMapping(value = "/{audioCode}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('audio:update')")
    public ResponseEntity<AudioResponseDTO> update(
            @PathVariable String audioCode,
            @RequestPart("data") String dataJson,
            @RequestPart(value = "file", required = false) MultipartFile audioFile,
            Authentication auth,
            HttpServletRequest request
    ) {
        AudioUpdateRequestDTO dto = parseAndValidate(dataJson, AudioUpdateRequestDTO.class);
        return ResponseEntity.ok(audioService.update(audioCode, dto, audioFile, auth, request));
    }

    /**
     * Soft delete — sends the audio record to the trash. Admin-only.
     * The S3 file is preserved so the record can be restored later.
     */
    @DeleteMapping("/{audioCode}")
    @PreAuthorize("hasAuthority('audio:delete')")
    public ResponseEntity<Void> delete(
            @PathVariable String audioCode,
            Authentication auth,
            HttpServletRequest request
    ) {
        audioService.delete(audioCode, auth, request);
        return ResponseEntity.noContent().build();
    }

    /**
     * Restore an audio record from trash. Admin-only.
     */
    @PostMapping("/{audioCode}/restore")
    @PreAuthorize("hasAuthority('audio:delete')")
    public ResponseEntity<AudioResponseDTO> restore(
            @PathVariable String audioCode,
            Authentication auth,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(audioService.restore(audioCode, auth, request));
    }

    /**
     * List trashed audio records. Admin-only.
     */
    @GetMapping("/trash")
    @PreAuthorize("hasAuthority('audio:delete')")
    public ResponseEntity<Page<AudioResponseDTO>> getTrash(
            @PageableDefault(size = 100) Pageable pageable,
            Authentication auth,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(audioService.getTrash(pageable, auth, request));
    }

    /**
     * Permanently delete an audio record from trash, including its S3 file.
     * Admin-only. The record must already be in trash.
     */
    @DeleteMapping("/{audioCode}/purge")
    @PreAuthorize("hasAuthority('audio:delete')")
    public ResponseEntity<Void> purge(
            @PathVariable String audioCode,
            Authentication auth,
            HttpServletRequest request
    ) {
        audioService.purge(audioCode, auth, request);
        return ResponseEntity.noContent().build();
    }

    private <T> T parseAndValidate(String dataJson, Class<T> clazz) {
        if (dataJson == null || dataJson.isBlank()) {
            throw new AudioValidationException("Missing 'data' part in the request.");
        }

        T dto;
        try {
            dto = objectMapper.readValue(dataJson, clazz);
        } catch (Exception e) {
            throw new AudioValidationException("Failed to parse audio data: " + e.getMessage());
        }

        var violations = validator.validate(dto);
        if (!violations.isEmpty()) {
            Map<String, String> fieldErrors = new LinkedHashMap<>();
            for (ConstraintViolation<T> violation : violations) {
                fieldErrors.put(violation.getPropertyPath().toString(), violation.getMessage());
            }
            throw new AudioValidationException("Validation failed for audio data.", fieldErrors);
        }

        return dto;
    }
}
