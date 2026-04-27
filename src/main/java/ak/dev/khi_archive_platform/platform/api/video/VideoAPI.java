package ak.dev.khi_archive_platform.platform.api.video;

import ak.dev.khi_archive_platform.platform.dto.video.VideoCreateRequestDTO;
import ak.dev.khi_archive_platform.platform.dto.video.VideoResponseDTO;
import ak.dev.khi_archive_platform.platform.dto.video.VideoUpdateRequestDTO;
import ak.dev.khi_archive_platform.platform.exceptions.VideoValidationException;
import ak.dev.khi_archive_platform.platform.service.video.VideoService;
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
@RequestMapping("/api/video")
public class VideoAPI {

    private final VideoService videoService;
    private final ObjectMapper objectMapper;
    private final Validator validator;

    @GetMapping
    public ResponseEntity<List<VideoResponseDTO>> getAll(Authentication auth, HttpServletRequest request) {
        return ResponseEntity.ok(videoService.getAll(auth, request));
    }

    @GetMapping("/{videoCode}")
    public ResponseEntity<VideoResponseDTO> getByVideoCode(
            @PathVariable String videoCode,
            Authentication auth,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(videoService.getByVideoCode(videoCode, auth, request));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<VideoResponseDTO> create(
            @RequestPart("data") String dataJson,
            @RequestPart("file") MultipartFile videoFile,
            Authentication auth,
            HttpServletRequest request
    ) {
        VideoCreateRequestDTO dto = parseAndValidate(dataJson, VideoCreateRequestDTO.class);
        return ResponseEntity.ok(videoService.create(dto, videoFile, auth, request));
    }

    @PatchMapping(value = "/{videoCode}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<VideoResponseDTO> update(
            @PathVariable String videoCode,
            @RequestPart("data") String dataJson,
            @RequestPart(value = "file", required = false) MultipartFile videoFile,
            Authentication auth,
            HttpServletRequest request
    ) {
        VideoUpdateRequestDTO dto = parseAndValidate(dataJson, VideoUpdateRequestDTO.class);
        return ResponseEntity.ok(videoService.update(videoCode, dto, videoFile, auth, request));
    }

    /**
     * Soft remove — marks the video as removed but keeps data in the database.
     */
    @PatchMapping("/{videoCode}/remove")
    public ResponseEntity<Void> remove(
            @PathVariable String videoCode,
            Authentication auth,
            HttpServletRequest request
    ) {
        videoService.remove(videoCode, auth, request);
        return ResponseEntity.noContent().build();
    }

    /**
     * Hard delete — permanently removes the row from the database.
     * Restricted to ADMIN and SUPER_ADMIN only.
     */
    @DeleteMapping("/{videoCode}")
    public ResponseEntity<Void> delete(
            @PathVariable String videoCode,
            Authentication auth,
            HttpServletRequest request
    ) {
        videoService.delete(videoCode, auth, request);
        return ResponseEntity.noContent().build();
    }

    private <T> T parseAndValidate(String dataJson, Class<T> clazz) {
        if (dataJson == null || dataJson.isBlank()) {
            throw new VideoValidationException("Missing 'data' part in the request.");
        }

        T dto;
        try {
            dto = objectMapper.readValue(dataJson, clazz);
        } catch (Exception e) {
            throw new VideoValidationException("Failed to parse video data: " + e.getMessage());
        }

        var violations = validator.validate(dto);
        if (!violations.isEmpty()) {
            Map<String, String> fieldErrors = new LinkedHashMap<>();
            for (ConstraintViolation<T> violation : violations) {
                fieldErrors.put(violation.getPropertyPath().toString(), violation.getMessage());
            }
            throw new VideoValidationException("Validation failed for video data.", fieldErrors);
        }

        return dto;
    }
}
