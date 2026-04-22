package ak.dev.khi_archive_platform.platform.api.audio;

import ak.dev.khi_archive_platform.platform.dto.audio.AudioCreateRequestDTO;
import ak.dev.khi_archive_platform.platform.dto.audio.AudioResponseDTO;
import ak.dev.khi_archive_platform.platform.dto.audio.AudioUpdateRequestDTO;
import ak.dev.khi_archive_platform.platform.service.audio.AudioService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
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
            @Valid @RequestPart("data") AudioCreateRequestDTO dto,
            @RequestPart("file") MultipartFile audioFile,
            Authentication auth,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(audioService.create(dto, audioFile, auth, request));
    }

    @PatchMapping(value = "/{audioCode}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AudioResponseDTO> update(
            @PathVariable String audioCode,
            @Valid @RequestPart("data") AudioUpdateRequestDTO dto,
            @RequestPart(value = "file", required = false) MultipartFile audioFile,
            Authentication auth,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(audioService.update(audioCode, dto, audioFile, auth, request));
    }

    @DeleteMapping("/{audioCode}")
    public ResponseEntity<Void> delete(
            @PathVariable String audioCode,
            Authentication auth,
            HttpServletRequest request
    ) {
        audioService.delete(audioCode, auth, request);
        return ResponseEntity.noContent().build();
    }
}

