package ak.dev.khi_archive_platform.platform.api.video;

import ak.dev.khi_archive_platform.S3Service;
import ak.dev.khi_archive_platform.platform.model.video.Video;
import ak.dev.khi_archive_platform.platform.repo.video.VideoRepository;
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
 * Proxies video bytes through the backend — the S3 URL is NEVER sent to the
 * browser.  Two mappings share the same streaming logic:
 *
 * <ul>
 *   <li>{@code GET /api/guest/video/{videoCode}/stream} — public, no auth.</li>
 *   <li>{@code GET /api/video/{videoCode}/stream} — requires a valid JWT.</li>
 * </ul>
 *
 * <h3>Range-only design for video</h3>
 * <p>Video files can be gigabytes. This controller ALWAYS uses S3 byte-range
 * GETs — even on the first (non-Range) request it fetches only a default
 * initial window ({@value #DEFAULT_CHUNK_BYTES} bytes) and returns a
 * {@code 206 Partial Content}. The browser then issues its own Range requests
 * for subsequent segments as the user watches. The full file is never loaded
 * into JVM heap.</p>
 *
 * <h3>Cache-Control</h3>
 * <ul>
 *   <li>Guest: {@code public, max-age=300} — CDN/browser can cache 5 min.</li>
 *   <li>Admin: {@code no-store, private} — never cache admin previews.</li>
 * </ul>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class VideoStreamAPI {

    /** Default initial chunk for non-Range requests (2 MB). */
    private static final long DEFAULT_CHUNK_BYTES = 2 * 1024 * 1024L;

    private final VideoRepository videoRepository;
    private final S3Service s3Service;

    // ── Public guest endpoint ─────────────────────────────────────────────────

    @GetMapping("/api/guest/video/{videoCode}/stream")
    public ResponseEntity<byte[]> streamPublic(
            @PathVariable String videoCode,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader) {

        Video video = videoRepository.findByVideoCodeAndRemovedAtIsNull(videoCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Video not found"));

        return buildStreamResponse(video, rangeHeader, true);
    }

    // ── Authenticated admin/user endpoint ─────────────────────────────────────

    @GetMapping("/api/video/{videoCode}/stream")
    public ResponseEntity<byte[]> streamAuthenticated(
            @PathVariable String videoCode,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader) {

        Video video = videoRepository.findByVideoCode(videoCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Video not found"));

        return buildStreamResponse(video, rangeHeader, false);
    }

    // ── Shared streaming logic ────────────────────────────────────────────────

    private ResponseEntity<byte[]> buildStreamResponse(Video video, String rangeHeader, boolean isPublic) {
        String fileUrl = video.getVideoFileUrl();
        if (fileUrl == null || fileUrl.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Video file not available");
        }

        String key = s3Service.extractKeyFromUrl(fileUrl);
        if (key == null || key.isBlank()) {
            log.error("Could not extract S3 key from videoFileUrl for videoCode={}", video.getVideoCode());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Video file not available");
        }

        MediaType contentType = resolveContentType(fileUrl);
        long total = s3Service.getObjectSize(key);

        long[] range = parseRange(rangeHeader, total);
        long start = range[0];
        long end = range[1];
        long len = end - start + 1;

        byte[] slice = downloadRange(key, start, end);

        HttpHeaders headers = buildHeaders(video, contentType, total, isPublic, start, end);
        headers.setContentLength(len);

        // Always 206 — video players expect partial content to enable seeking.
        return new ResponseEntity<>(slice, headers, HttpStatus.PARTIAL_CONTENT);
    }

    private byte[] downloadRange(String key, long start, long end) {
        try (ResponseInputStream<GetObjectResponse> stream = s3Service.openStreamRange(key, start, end)) {
            return stream.readAllBytes();
        } catch (IOException e) {
            log.error("Failed to read video range for key={} start={} end={}", key, start, end, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to stream video");
        }
    }

    private HttpHeaders buildHeaders(Video video, MediaType contentType, long total,
                                     boolean isPublic, long start, long end) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(contentType);
        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        headers.set(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + total);
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                "inline; filename=\"" + safeFilename(video.getFileName(), video.getVideoCode(), contentType) + "\"");
        headers.setCacheControl(isPublic ? "public, max-age=300" : "no-store, private");
        headers.set("X-Content-Type-Options", "nosniff");
        return headers;
    }

    private String safeFilename(String fileName, String fallbackCode, MediaType contentType) {
        if (fileName != null && !fileName.isBlank()) {
            return fileName.replaceAll("[^a-zA-Z0-9._\\-() ]", "_");
        }
        return "video-" + fallbackCode + "." + contentType.getSubtype();
    }

    private MediaType resolveContentType(String url) {
        String lower = url.toLowerCase();
        if (lower.contains(".mp4"))  return MediaType.valueOf("video/mp4");
        if (lower.contains(".webm")) return MediaType.valueOf("video/webm");
        if (lower.contains(".ogg"))  return MediaType.valueOf("video/ogg");
        if (lower.contains(".mov"))  return MediaType.valueOf("video/quicktime");
        if (lower.contains(".avi"))  return MediaType.valueOf("video/x-msvideo");
        if (lower.contains(".mkv"))  return MediaType.valueOf("video/x-matroska");
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    /** Parses {@code Range: bytes=start-end}. Falls back to first chunk on any error. */
    private long[] parseRange(String header, long total) {
        if (header == null || header.isBlank()) {
            // No Range header: serve first DEFAULT_CHUNK_BYTES so the browser
            // can start rendering without fetching the whole file.
            long end = Math.min(DEFAULT_CHUNK_BYTES - 1, total - 1);
            return new long[]{0, end};
        }
        String prefix = "bytes=";
        if (!header.startsWith(prefix)) return new long[]{0, Math.min(DEFAULT_CHUNK_BYTES - 1, total - 1)};
        String spec = header.substring(prefix.length());
        int dash = spec.indexOf('-');
        if (dash < 0) return new long[]{0, Math.min(DEFAULT_CHUNK_BYTES - 1, total - 1)};
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
            return new long[]{0, Math.min(DEFAULT_CHUNK_BYTES - 1, total - 1)};
        }
    }
}
