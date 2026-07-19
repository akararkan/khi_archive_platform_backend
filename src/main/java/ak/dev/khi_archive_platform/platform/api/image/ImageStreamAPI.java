package ak.dev.khi_archive_platform.platform.api.image;

import ak.dev.khi_archive_platform.S3Service;
import ak.dev.khi_archive_platform.platform.model.image.Image;
import ak.dev.khi_archive_platform.platform.repo.image.ImageRepository;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Proxies image bytes through the backend — the S3 URL is NEVER sent to the
 * browser.  Two mappings share the same logic:
 *
 * <ul>
 *   <li>{@code GET /api/guest/image/{imageCode}/view} — public, no auth.</li>
 *   <li>{@code GET /api/image/{imageCode}/view} — requires a valid JWT.</li>
 * </ul>
 *
 * <h3>ETag / 304 Not Modified</h3>
 * <p>An {@code ETag} derived from the imageCode is returned with every
 * response. If the browser sends {@code If-None-Match} with that value the
 * controller returns {@code 304 Not Modified} immediately — no S3 round-trip,
 * no bytes transferred. This cuts repeated image loads to near zero cost.</p>
 *
 * <h3>Cache-Control</h3>
 * <ul>
 *   <li>Guest: {@code public, max-age=3600} — images rarely change; 1-hour
 *       CDN/browser cache greatly reduces bandwidth.</li>
 *   <li>Admin: {@code no-store, private}.</li>
 * </ul>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ImageStreamAPI {

    private final ImageRepository imageRepository;
    private final S3Service s3Service;

    // ── Public guest endpoint ─────────────────────────────────────────────────

    @GetMapping("/api/guest/image/{imageCode}/view")
    public ResponseEntity<byte[]> viewPublic(
            @PathVariable String imageCode,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {

        Image image = imageRepository.findByImageCodeAndRemovedAtIsNull(imageCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Image not found"));

        return buildViewResponse(image, ifNoneMatch, true);
    }

    // ── Authenticated admin/user endpoint ─────────────────────────────────────

    @GetMapping("/api/image/{imageCode}/view")
    public ResponseEntity<byte[]> viewAuthenticated(
            @PathVariable String imageCode,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {

        Image image = imageRepository.findByImageCode(imageCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Image not found"));

        return buildViewResponse(image, ifNoneMatch, false);
    }

    // ── Shared serving logic ──────────────────────────────────────────────────

    private ResponseEntity<byte[]> buildViewResponse(Image image, String ifNoneMatch, boolean isPublic) {
        String fileUrl = image.getImageFileUrl();
        if (fileUrl == null || fileUrl.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Image file not available");
        }

        // Build ETag from imageCode — stable since image content does not change after upload.
        String etag = "\"" + sha1Short(image.getImageCode()) + "\"";

        // Return 304 if the browser already has this image cached — no S3 hit needed.
        if (etag.equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }

        String key = s3Service.extractKeyFromUrl(fileUrl);
        if (key == null || key.isBlank()) {
            log.error("Could not extract S3 key from imageFileUrl for imageCode={}", image.getImageCode());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Image file not available");
        }

        byte[] bytes = downloadFull(key);
        MediaType contentType = resolveContentType(fileUrl);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(contentType);
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                "inline; filename=\"" + safeFilename(image.getFileName(), image.getImageCode(), contentType) + "\"");
        headers.setETag(etag);
        // Public: 1-hour browser/CDN cache — images are immutable after upload.
        // Admin: never cache, may preview soft-deleted records.
        headers.setCacheControl(isPublic ? "public, max-age=3600" : "no-store, private");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.setContentLength(bytes.length);

        return new ResponseEntity<>(bytes, headers, HttpStatus.OK);
    }

    private byte[] downloadFull(String key) {
        try (ResponseInputStream<GetObjectResponse> stream = s3Service.openStream(key)) {
            return stream.readAllBytes();
        } catch (IOException e) {
            log.error("Failed to read image for key={}", key, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to serve image");
        }
    }

    private String safeFilename(String fileName, String fallbackCode, MediaType contentType) {
        if (fileName != null && !fileName.isBlank()) {
            return fileName.replaceAll("[^a-zA-Z0-9._\\-() ]", "_");
        }
        return "image-" + fallbackCode + "." + contentType.getSubtype();
    }

    private MediaType resolveContentType(String url) {
        String lower = url.toLowerCase();
        if (lower.contains(".jpg") || lower.contains(".jpeg")) return MediaType.IMAGE_JPEG;
        if (lower.contains(".png"))  return MediaType.IMAGE_PNG;
        if (lower.contains(".gif"))  return MediaType.IMAGE_GIF;
        if (lower.contains(".webp")) return MediaType.valueOf("image/webp");
        if (lower.contains(".tif") || lower.contains(".tiff")) return MediaType.valueOf("image/tiff");
        if (lower.contains(".bmp"))  return MediaType.valueOf("image/bmp");
        if (lower.contains(".svg"))  return MediaType.valueOf("image/svg+xml");
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    /** Short stable hash of a string for use as an ETag value. */
    private String sha1Short(String input) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-1")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(12);
            for (int i = 0; i < 6; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(input.hashCode());
        }
    }
}
