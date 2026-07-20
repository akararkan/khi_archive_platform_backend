package ak.dev.khi_archive_platform.platform.api.text;

import ak.dev.khi_archive_platform.S3Service;
import ak.dev.khi_archive_platform.platform.model.text.Text;
import ak.dev.khi_archive_platform.platform.repo.text.TextRepository;
import ak.dev.khi_archive_platform.user.exceptions.UserStorageException;
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
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Proxies text/book file bytes (PDF, EPUB, DOCX, etc.) through the backend.
 * The S3 URL is NEVER sent to the browser — solving the problem where the
 * frontend was sending a JWT Authorization header directly to S3 (which S3
 * rejects with 400 Bad Request).
 *
 * <ul>
 *   <li>{@code GET /api/guest/text/{textCode}/read} — public, no auth required.</li>
 *   <li>{@code GET /api/text/{textCode}/read} — requires a valid JWT token.</li>
 * </ul>
 *
 * <p>Also serves the cover image separately:</p>
 * <ul>
 *   <li>{@code GET /api/guest/text/{textCode}/cover} — public cover image.</li>
 *   <li>{@code GET /api/text/{textCode}/cover} — authenticated cover image.</li>
 * </ul>
 *
 * <h3>Range support</h3>
 * <p>PDF files can be large. HTTP Range requests are supported so PDF.js and
 * browser PDF viewers can load individual pages without downloading the whole
 * file first.</p>
 *
 * <h3>Cache-Control</h3>
 * <ul>
 *   <li>Guest: {@code public, max-age=3600} — books/covers rarely change.</li>
 *   <li>Admin: {@code no-store, private}.</li>
 * </ul>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class TextStreamAPI {

    private final TextRepository textRepository;
    private final S3Service s3Service;

    // ═══════════════════════════════════════════════════════════════════════
    // TEXT / BOOK FILE
    // ═══════════════════════════════════════════════════════════════════════

    @GetMapping("/api/guest/text/{textCode}/read")
    public ResponseEntity<byte[]> readPublic(
            @PathVariable String textCode,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader) {

        Text text = textRepository.findByTextCodeAndRemovedAtIsNull(textCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Text not found"));

        return buildFileResponse(text, rangeHeader, true);
    }

    @GetMapping("/api/text/{textCode}/read")
    public ResponseEntity<byte[]> readAuthenticated(
            @PathVariable String textCode,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader) {

        Text text = textRepository.findByTextCode(textCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Text not found"));

        return buildFileResponse(text, rangeHeader, false);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // COVER IMAGE
    // ═══════════════════════════════════════════════════════════════════════

    @GetMapping("/api/guest/text/{textCode}/cover")
    public ResponseEntity<byte[]> coverPublic(
            @PathVariable String textCode,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {

        Text text = textRepository.findByTextCodeAndRemovedAtIsNull(textCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Text not found"));

        return buildCoverResponse(text, ifNoneMatch, true);
    }

    @GetMapping("/api/text/{textCode}/cover")
    public ResponseEntity<byte[]> coverAuthenticated(
            @PathVariable String textCode,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {

        Text text = textRepository.findByTextCode(textCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Text not found"));

        return buildCoverResponse(text, ifNoneMatch, false);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // SHARED LOGIC
    // ═══════════════════════════════════════════════════════════════════════

    private ResponseEntity<byte[]> buildFileResponse(Text text, String rangeHeader, boolean isPublic) {
        String fileUrl = text.getTextFileUrl();
        if (fileUrl == null || fileUrl.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Book file not available");
        }

        String key = s3Service.extractKeyFromUrl(fileUrl);
        if (key == null || key.isBlank()) {
            log.error("Could not extract S3 key from textFileUrl for textCode={}", text.getTextCode());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Book file not available");
        }

        MediaType contentType = resolveFileContentType(fileUrl);
        long total = fetchSize(key, text.getTextCode(), "Book file not available");

        long[] range = parseRange(rangeHeader, total);
        long start = range[0];
        long end   = range[1];
        long len   = end - start + 1;

        byte[] slice = downloadRange(key, start, end, text.getTextCode(), "Book file not available", "Failed to stream book file");

        boolean isRangeRequest = rangeHeader != null && !rangeHeader.isBlank();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(contentType);
        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                contentDisposition(safeFilename(text.getFileName(), text.getTextCode(), contentType),
                        "book-" + text.getTextCode() + "." + contentType.getSubtype()));
        if (isRangeRequest) {
            headers.set(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + total);
        }
        headers.setCacheControl(isPublic ? "public, max-age=3600" : "no-store, private");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.setContentLength(len);

        return new ResponseEntity<>(slice, headers, isRangeRequest ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK);
    }

    private ResponseEntity<byte[]> buildCoverResponse(Text text, String ifNoneMatch, boolean isPublic) {
        String coverUrl = text.getCoverImageUrl();
        if (coverUrl == null || coverUrl.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cover image not available");
        }

        String etag = "\"" + sha1Short(text.getTextCode() + "-cover") + "\"";
        if (etag.equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }

        String key = s3Service.extractKeyFromUrl(coverUrl);
        if (key == null || key.isBlank()) {
            log.error("Could not extract S3 key from coverImageUrl for textCode={}", text.getTextCode());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Cover image not available");
        }

        byte[] bytes = downloadFull(key, text.getTextCode(), "Cover image not available", "Failed to serve cover image");
        MediaType contentType = resolveCoverContentType(coverUrl);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(contentType);
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "inline");
        headers.setETag(etag);
        headers.setCacheControl(isPublic ? "public, max-age=3600" : "no-store, private");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.setContentLength(bytes.length);

        return new ResponseEntity<>(bytes, headers, HttpStatus.OK);
    }

    private long fetchSize(String key, String textCode, String notFoundMessage) {
        try {
            return s3Service.getObjectSize(key);
        } catch (UserStorageException e) {
            throw mapStorageError(e, notFoundMessage + " for " + textCode, notFoundMessage);
        }
    }

    private byte[] downloadRange(String key, long start, long end, String textCode,
                                 String notFoundMessage, String failureMessage) {
        try (ResponseInputStream<GetObjectResponse> stream = s3Service.openStreamRange(key, start, end)) {
            return stream.readAllBytes();
        } catch (IOException e) {
            log.error("Failed to read text range for key={} start={} end={}", key, start, end, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, failureMessage);
        } catch (UserStorageException e) {
            throw mapStorageError(e, notFoundMessage + " for " + textCode, failureMessage);
        }
    }

    private byte[] downloadFull(String key, String textCode, String notFoundMessage, String failureMessage) {
        try (ResponseInputStream<GetObjectResponse> stream = s3Service.openStream(key)) {
            return stream.readAllBytes();
        } catch (IOException e) {
            log.error("Failed to read cover image for key={}", key, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, failureMessage);
        } catch (UserStorageException e) {
            throw mapStorageError(e, notFoundMessage + " for " + textCode, failureMessage);
        }
    }

    /**
     * Distinguishes "the S3 object is missing/corrupted" (404) from every
     * other S3 failure (network, permissions, throttling — 500). Without this
     * every missing object surfaced as an opaque generic 500.
     */
    private ResponseStatusException mapStorageError(UserStorageException e, String notFoundMessage, String failureMessage) {
        if (e.getCause() instanceof S3Exception s3Exception && s3Exception.statusCode() == 404) {
            log.warn("S3 object missing: {}", e.getMessage());
            return new ResponseStatusException(HttpStatus.NOT_FOUND, notFoundMessage);
        }
        log.error("S3 storage failure serving text/book content", e);
        return new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, failureMessage);
    }

    /**
     * Builds an RFC 5987 {@code Content-Disposition} value that preserves
     * non-ASCII filenames (Kurdish/Arabic titles are common in this archive)
     * instead of collapsing them to underscores. Includes a sanitized ASCII
     * {@code filename=} fallback for clients that ignore {@code filename*}.
     */
    private String contentDisposition(String rawFilename, String asciiFallbackName) {
        String asciiFallback = rawFilename.replaceAll("[^a-zA-Z0-9._\\-() ]", "_");
        if (asciiFallback.replaceAll("[_\\s]", "").isEmpty()) {
            asciiFallback = asciiFallbackName;
        }
        String encoded = URLEncoder.encode(rawFilename, StandardCharsets.UTF_8).replace("+", "%20");
        return "inline; filename=\"" + asciiFallback + "\"; filename*=UTF-8''" + encoded;
    }

    private String safeFilename(String fileName, String fallbackCode, MediaType contentType) {
        if (fileName != null && !fileName.isBlank()) {
            return fileName;
        }
        return "book-" + fallbackCode + "." + contentType.getSubtype();
    }

    private MediaType resolveFileContentType(String url) {
        String lower = url.toLowerCase();
        if (lower.contains(".pdf"))  return MediaType.APPLICATION_PDF;
        if (lower.contains(".epub")) return MediaType.valueOf("application/epub+zip");
        if (lower.contains(".docx")) return MediaType.valueOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        if (lower.contains(".doc"))  return MediaType.valueOf("application/msword");
        if (lower.contains(".txt"))  return MediaType.TEXT_PLAIN;
        if (lower.contains(".html") || lower.contains(".htm")) return MediaType.TEXT_HTML;
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    private MediaType resolveCoverContentType(String url) {
        String lower = url.toLowerCase();
        if (lower.contains(".jpg") || lower.contains(".jpeg")) return MediaType.IMAGE_JPEG;
        if (lower.contains(".png"))  return MediaType.IMAGE_PNG;
        if (lower.contains(".webp")) return MediaType.valueOf("image/webp");
        return MediaType.IMAGE_JPEG;
    }

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

    private String sha1Short(String input) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-1")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(12);
            for (int i = 0; i < 6; i++) sb.append(String.format("%02x", hash[i]));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(input.hashCode());
        }
    }
}
