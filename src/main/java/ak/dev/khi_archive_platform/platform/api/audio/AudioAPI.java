package ak.dev.khi_archive_platform.platform.api.audio;

import ak.dev.khi_archive_platform.platform.dto.audio.AudioCreateRequestDTO;
import ak.dev.khi_archive_platform.platform.dto.audio.AudioResponseDTO;
import ak.dev.khi_archive_platform.platform.dto.audio.AudioUpdateRequestDTO;
import ak.dev.khi_archive_platform.platform.service.audio.AudioService;
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

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/audio")
public class AudioAPI {

    private final AudioService audioService;
    private final ObjectMapper objectMapper;
    private final Validator validator;

    @GetMapping
    public ResponseEntity<List<AudioResponseDTO>> getAll(Authentication auth, HttpServletRequest request) {
        return ResponseEntity.ok(audioService.getAll(auth, request));
    }

    @GetMapping("/{audioCode}")
    public ResponseEntity<AudioResponseDTO> getByAudioCode(
            @PathVariable String audioCode,
            Authentication auth,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(audioService.getByAudioCode(audioCode, auth, request));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AudioResponseDTO> create(
            @RequestPart("data") String dataJson,
            @RequestPart("file") MultipartFile audioFile,
            Authentication auth,
            HttpServletRequest request
    ) {
        AudioCreateRequestDTO dto = parseAndValidate(dataJson, AudioCreateRequestDTO.class);
        return ResponseEntity.ok(audioService.create(dto, audioFile, auth, request));
    }

    @PatchMapping(value = "/{audioCode}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
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
     * Soft remove — marks the audio as removed but keeps data in the database.
     */
    @PatchMapping("/{audioCode}/remove")
    public ResponseEntity<Void> remove(
            @PathVariable String audioCode,
            Authentication auth,
            HttpServletRequest request
    ) {
        audioService.remove(audioCode, auth, request);
        return ResponseEntity.noContent().build();
    }

    /**
     * Hard delete — permanently removes the row from the database.
     * Restricted to ADMIN and SUPER_ADMIN only.
     */
    @DeleteMapping("/{audioCode}")
    public ResponseEntity<Void> delete(
            @PathVariable String audioCode,
            Authentication auth,
            HttpServletRequest request
    ) {
        audioService.delete(audioCode, auth, request);
        return ResponseEntity.noContent().build();
    }

    private <T> T parseAndValidate(String dataJson, Class<T> clazz) {
        try {
            if (dataJson == null || dataJson.isBlank()) {
                throw new IllegalArgumentException("Missing 'data' part");
            }

            T dto = objectMapper.readValue(dataJson, clazz);

            var violations = validator.validate(dto);
            if (!violations.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (ConstraintViolation<T> violation : violations) {
                    sb.append(violation.getPropertyPath())
                            .append(": ")
                            .append(violation.getMessage())
                            .append("; ");
                }
                throw new IllegalArgumentException("Validation failed: " + sb);
            }

            return dto;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse 'data' part: " + e.getMessage(), e);
        }
    }
}
