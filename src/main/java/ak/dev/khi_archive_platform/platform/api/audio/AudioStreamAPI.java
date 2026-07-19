package ak.dev.khi_archive_platform.platform.api.audio;

import ak.dev.khi_archive_platform.S3Service;
import ak.dev.khi_archive_platform.platform.model.audio.Audio;
import ak.dev.khi_archive_platform.platform.repo.audio.AudioRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.io.IOException;

/**
 * Proxies audio bytes through the backend — the S3 URL is NEVER sent to the
 * browser.  Two mappings share the same streaming logic:
 *
 * <ul>
 *   <li>{@code GET /api/guest/audio/{audioCode}/stream} — public, no auth.
 *       Only non-deleted records ({@code removedAt IS NULL}) are served.</li>
 *   <li>{@code GET /api/audio/{audioCode}/stream} — requires a valid JWT
 *       (enforced by {@code SecurityFilterChain}); also serves soft-deleted
 *       records so admins can preview before restoring.</li>
 * </ul>
 *
 * <h3>HTTP Range support</h3>
 * <p>Both endpoints honour the {@code Range: bytes=start-end} header, which is
 * required for browser {@code <audio>} seek and for resumable downloads. Only
 * the requested byte window is fetched from S3 — the full file is never loaded
 * into memory.</p>
 *
 * <h3>Headers returned</h3>
 * <ul>
 *   <li>{@code Content-Type} — inferred from file extension.</li>
 *   <li>{@code Accept-Ranges: bytes} — tells the browser it can seek.</li>
 *   <li>{@code Content-Disposition: inline; filename="..."} — plays in browser,
 *       shows a human-readable filename in the download dialog if the user saves
 *       via browser DevTools (not a button we expose).</li>
 *   <li>{@code Cache-Control} — public guest streams: {@code public, max-age=300}
 *       (5-minute browser/CDN cache to reduce origin hits). Admin streams:
 *       {@code no-store, private}.</li>
 *   <li>{@code X-Content-Type-Options: nosniff} — prevents MIME-sniffing.</li>
 * </ul>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class AudioStreamAPI {

    private final AudioRepository audioRepository;
    private final S3Service s3Service;

    // ── Public guest endpoint ─────────────────────────────────────────────────

    @GetMapping("/api/guest/audio/{audioCode}/stream")
    public ResponseEntity<byte[]> streamPublic(
            @PathVariable String audioCode,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader) {

        Audio audio = audioRepository.findByAudioCodeAndRemovedAtIsNull(audioCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Audio not found"));

        return buildStreamResponse(audio, rangeHeader, true);
    }

    // ── Authenticated admin/user endpoint ─────────────────────────────────────

    @GetMapping("/api/audio/{audioCode}/stream")
    public ResponseEntity<byte[]> streamAuthenticated(
            @PathVariable String audioCode,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader) {

        Audio audio = audioRepository.findByAudioCode(audioCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Audio not found"));

        return buildStreamResponse(audio, rangeHeader, false);
    }

    // ── Shared streaming logic ────────────────────────────────────────────────

    private ResponseEntity<byte[]> buildStreamResponse(Audio audio, String rangeHeader, boolean isPublic) {
        String fileUrl = audio.getAudioFileUrl();
        if (fileUrl == null || fileUrl.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Audio file not available");
        }

        String key = s3Service.extractKeyFromUrl(fileUrl);
        if (key == null || key.isBlank()) {
            log.error("Could not extract S3 key from audioFileUrl for audioCode={}", audio.getAudioCode());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Audio file not available");
        }

        MediaType contentType = resolveContentType(fileUrl);
        long total = s3Service.getObjectSize(key);

        long[] range = parseRange(rangeHeader, total);
        long start = range[0];
        long end = range[1];
        long len = end - start + 1;

        byte[] slice = downloadRange(key, start, end);

        boolean isRangeRequest = rangeHeader != null && !rangeHeader.isBlank();
        HttpHeaders headers = buildHeaders(audio, contentType, total, isPublic, isRangeRequest, start, end);
        headers.setContentLength(len);

        return new ResponseEntity<>(slice, headers, isRangeRequest ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK);
    }

    private byte[] downloadRange(String key, long start, long end) {
        try (ResponseInputStream<GetObjectResponse> stream = s3Service.openStreamRange(key, start, end)) {
            return stream.readAllBytes();
        } catch (IOException e) {
            log.error("Failed to read audio range for key={} start={} end={}", key, start, end, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to stream audio");
        }
    }

    private HttpHeaders buildHeaders(Audio audio, MediaType contentType, long total,
                                     boolean isPublic, boolean isRangeRequest, long start, long end) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(contentType);
        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                "inline; filename=\"" + safeFilename(audio.getFileName(), audio.getAudioCode(), contentType) + "\"");
        // Public content: allow CDN/browser to cache for 5 minutes.
        // Admin content: never cache — may include soft-deleted records.
        headers.setCacheControl(isPublic ? "public, max-age=300" : "no-store, private");
        headers.set("X-Content-Type-Options", "nosniff");
        if (isRangeRequest) {
            headers.set(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + total);
        }
        return headers;
    }

    private String safeFilename(String fileName, String fallbackCode, MediaType contentType) {
        if (fileName != null && !fileName.isBlank()) {
            return fileName.replaceAll("[^a-zA-Z0-9._\\-() ]", "_");
        }
        String ext = contentType.getSubtype().equals("mpeg") ? "mp3" : contentType.getSubtype();
        return "audio-" + fallbackCode + "." + ext;
    }

    private MediaType resolveContentType(String url) {
        String lower = url.toLowerCase();
        if (lower.contains(".mp3"))  return MediaType.valueOf("audio/mpeg");
        if (lower.contains(".ogg"))  return MediaType.valueOf("audio/ogg");
        if (lower.contains(".wav"))  return MediaType.valueOf("audio/wav");
        if (lower.contains(".flac")) return MediaType.valueOf("audio/flac");
        if (lower.contains(".aac"))  return MediaType.valueOf("audio/aac");
        if (lower.contains(".m4a"))  return MediaType.valueOf("audio/mp4");
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    /** Parses {@code Range: bytes=start-end}. Falls back to full file on any parse error. */
    private long[] parseRange(String header, long total) {
        if (header == null || header.isBlank()) return new long[]{0, total - 1};
        String prefix = "bytes=";
        if (!header.startsWith(prefix)) return new long[]{0, total - 1};
        String spec = header.substring(prefix.length());
        int dash = spec.indexOf('-');
        if (dash < 0) return new long[]{0, total - 1};
        try {
            long start = spec.substring(0, dash).isBlank() ? 0L : Long.parseLong(spec.substring(0, dash).trim());
            long end = (dash == spec.length() - 1 || spec.substring(dash + 1).isBlank())
                    ? total - 1
                    : Long.parseLong(spec.substring(dash + 1).trim());
            if (start < 0) start = 0;
            if (end >= total) end = total - 1;
            if (end < start) end = start;
            return new long[]{start, end};
        } catch (NumberFormatException e) {
            return new long[]{0, total - 1};
        }
    }
}
