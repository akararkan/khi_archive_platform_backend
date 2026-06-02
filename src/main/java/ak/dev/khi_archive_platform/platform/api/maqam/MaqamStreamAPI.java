package ak.dev.khi_archive_platform.platform.api.maqam;

import ak.dev.khi_archive_platform.S3Service;
import ak.dev.khi_archive_platform.platform.model.maqam.ListOfMaqam;
import ak.dev.khi_archive_platform.platform.service.maqam.MaqamService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;

/**
 * Proxies the maqam audio bytes through the backend so:
 *
 * <ul>
 *   <li>Permission to play is re-checked on every request — teachers only get
 *       bytes for records they are on the panel for.</li>
 *   <li>Every range request is logged via {@code MaqamAuditAction.STREAM}.</li>
 *   <li>The S3 link is never sent to the browser, so the frontend cannot
 *       trivially produce a downloadable URL. Combined with {@code
 *       Content-Disposition: inline} and the front-end's {@code
 *       controlsList="nodownload"} this implements the "no downloads"
 *       requirement.</li>
 * </ul>
 *
 * <p>The implementation downloads the full object from S3 on each call and
 * serves the requested byte range from memory. Given the small size of a
 * maqam clip (typically a few MB), and the strict no-download requirement,
 * this trade-off keeps the audit guarantee airtight. Switching to pre-signed
 * GETs with short TTLs would be faster but would also leak a downloadable
 * link — explicitly rejected.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/maqam")
public class MaqamStreamAPI {

    private final MaqamService maqamService;
    private final S3Service s3Service;

    @GetMapping("/{maqamCode}/stream")
    @PreAuthorize("hasAuthority('maqam:read')")
    public ResponseEntity<ByteArrayResource> stream(
            @PathVariable String maqamCode,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader,
            Authentication auth,
            HttpServletRequest request) {

        ListOfMaqam record = maqamService.loadForStreaming(maqamCode, auth, request);
        byte[] full = s3Service.downloadByUrl(record.getAudioFileUrl());
        long total = full.length;

        MediaType contentType = pickContentType(record.getAudioContentType());

        if (rangeHeader == null || rangeHeader.isBlank()) {
            HttpHeaders headers = baseHeaders(record, contentType, total);
            headers.setContentLength(total);
            return new ResponseEntity<>(new ByteArrayResource(full), headers, HttpStatus.OK);
        }

        long[] range = parseRange(rangeHeader, total);
        long start = range[0];
        long end = range[1];
        long len = end - start + 1;
        byte[] slice = Arrays.copyOfRange(full, (int) start, (int) end + 1);

        HttpHeaders headers = baseHeaders(record, contentType, total);
        headers.set(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + total);
        headers.setContentLength(len);
        return new ResponseEntity<>(new ByteArrayResource(slice), headers, HttpStatus.PARTIAL_CONTENT);
    }

    private MediaType pickContentType(String stored) {
        if (stored == null || stored.isBlank()) return MediaType.APPLICATION_OCTET_STREAM;
        try {
            return MediaType.parseMediaType(stored);
        } catch (Exception e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    private HttpHeaders baseHeaders(ListOfMaqam record, MediaType contentType, long total) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(contentType);
        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        // Inline — never attachment. Combined with the front-end's
        // <audio controlsList="nodownload"> this prevents the browser's
        // built-in download affordance.
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "inline");
        headers.setCacheControl("no-store, private");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Maqam-Code", record.getMaqamCode());
        return headers;
    }

    private long[] parseRange(String header, long total) {
        // Accept only the simple "bytes=start-end" / "bytes=start-" forms.
        String prefix = "bytes=";
        if (!header.startsWith(prefix)) return new long[]{0, total - 1};
        String spec = header.substring(prefix.length());
        int dash = spec.indexOf('-');
        if (dash < 0) return new long[]{0, total - 1};
        try {
            long start = spec.substring(0, dash).isBlank() ? 0L : Long.parseLong(spec.substring(0, dash));
            long end = (dash == spec.length() - 1 || spec.substring(dash + 1).isBlank())
                    ? total - 1
                    : Long.parseLong(spec.substring(dash + 1));
            if (start < 0) start = 0;
            if (end >= total) end = total - 1;
            if (end < start) end = start;
            return new long[]{start, end};
        } catch (NumberFormatException e) {
            return new long[]{0, total - 1};
        }
    }
}
