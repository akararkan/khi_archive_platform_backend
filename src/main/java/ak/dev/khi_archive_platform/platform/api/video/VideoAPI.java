package ak.dev.khi_archive_platform.platform.api.video;

import ak.dev.khi_archive_platform.platform.dto.video.VideoBulkCreateRequestDTO;
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
@RequestMapping("/api/video")
public class VideoAPI {

    private final VideoService videoService;
    private final ObjectMapper objectMapper;
    private final Validator validator;

    @GetMapping
    @PreAuthorize("hasAuthority('video:read')")
    public ResponseEntity<Page<VideoResponseDTO>> getAll(
            @PageableDefault(size = 100) Pageable pageable,
            Authentication auth,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(videoService.getAll(pageable, auth, request));
    }

    /**
     * Two-phase fuzzy search across Video fields and child collections
     * (subjects, genres, colors, usages, tags, keywords). Backed by pg_trgm
     * GIN indexes — typo-tolerant, multilingual, sub-second on large tables.
     */
    @GetMapping("/search")
    @PreAuthorize("hasAuthority('video:read')")
    public ResponseEntity<List<VideoResponseDTO>> search(
            @RequestParam("q") String q,
            @RequestParam(value = "limit", required = false) Integer limit,
            Authentication auth,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(videoService.search(q, limit, auth, request));
    }

    @GetMapping("/{videoCode}")
    @PreAuthorize("hasAuthority('video:read')")
    public ResponseEntity<VideoResponseDTO> getByVideoCode(
            @PathVariable String videoCode,
            Authentication auth,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(videoService.getByVideoCode(videoCode, auth, request));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('video:create')")
    public ResponseEntity<VideoResponseDTO> create(
            @RequestPart("data") String dataJson,
            @RequestPart("file") MultipartFile videoFile,
            Authentication auth,
            HttpServletRequest request
    ) {
        VideoCreateRequestDTO dto = parseAndValidate(dataJson, VideoCreateRequestDTO.class);
        return ResponseEntity.ok(videoService.create(dto, videoFile, auth, request));
    }

    /**
     * Bulk-create video records. Accepts a JSON array; each entry carries its
     * own pre-uploaded {@code videoFileUrl} (no multipart). Video codes are
     * auto-generated. One transaction, one audit summary.
     */
    @PostMapping(value = "/bulk", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('video:create')")
    public ResponseEntity<VideoService.BulkCreateResult> createAll(
            @RequestBody List<VideoBulkCreateRequestDTO> dtos,
            Authentication auth,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(videoService.createAll(dtos, auth, request));
    }

    @PatchMapping(value = "/{videoCode}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('video:update')")
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
     * Soft delete — sends the video record to the trash. Admin-only.
     * The S3 file is preserved so the record can be restored later.
     */
    @DeleteMapping("/{videoCode}")
    @PreAuthorize("hasAuthority('video:delete')")
    public ResponseEntity<Void> delete(
            @PathVariable String videoCode,
            Authentication auth,
            HttpServletRequest request
    ) {
        videoService.delete(videoCode, auth, request);
        return ResponseEntity.noContent().build();
    }

    /**
     * Restore a video record from trash. Admin-only.
     */
    @PostMapping("/{videoCode}/restore")
    @PreAuthorize("hasAuthority('video:delete')")
    public ResponseEntity<VideoResponseDTO> restore(
            @PathVariable String videoCode,
            Authentication auth,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(videoService.restore(videoCode, auth, request));
    }

    /**
     * List trashed video records. Admin-only.
     */
    @GetMapping("/trash")
    @PreAuthorize("hasAuthority('video:delete')")
    public ResponseEntity<Page<VideoResponseDTO>> getTrash(
            @PageableDefault(size = 100) Pageable pageable,
            Authentication auth,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(videoService.getTrash(pageable, auth, request));
    }

    /**
     * Permanently delete a video record from trash, including its S3 file.
     * Admin-only. The record must already be in trash.
     */
    @DeleteMapping("/{videoCode}/purge")
    @PreAuthorize("hasAuthority('video:delete')")
    public ResponseEntity<Void> purge(
            @PathVariable String videoCode,
            Authentication auth,
            HttpServletRequest request
    ) {
        videoService.purge(videoCode, auth, request);
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
