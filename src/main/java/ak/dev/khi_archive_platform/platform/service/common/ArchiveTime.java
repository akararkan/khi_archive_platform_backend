package ak.dev.khi_archive_platform.platform.service.common;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * The archive's own time zone, and the single place that turns a calendar date
 * into an absolute instant.
 *
 * <p>Audit-date range filters ({@code createdFrom/To}, {@code updatedFrom/To},
 * {@code removedFrom/To}) accept a bare {@code YYYY-MM-DD} and the backend
 * resolves the day's bounds <em>here</em>, so every client agrees on what
 * "created on the 29th" means without having to send a UTC offset of its own.
 *
 * <p>The team works in {@code Asia/Baghdad} — UTC+3, and Iraq has observed no
 * DST since 2007, so the offset is stable year-round. "From the 29th" is
 * {@code 2026-07-29T00:00:00+03:00} = {@code 2026-07-28T21:00:00Z}; "to the
 * 29th" is inclusive through {@code 2026-07-29T23:59:59.999999999+03:00}.
 */
public final class ArchiveTime {

    private ArchiveTime() {}

    /** UTC+3, no DST. Change here if the archive ever relocates. */
    public static final ZoneId ARCHIVE_ZONE = ZoneId.of("Asia/Baghdad");

    /**
     * Start of the given calendar day (00:00:00.000000000) in the archive zone,
     * as an {@link Instant}. Use for an inclusive <b>lower</b> bound. Null-safe:
     * {@code null} in → {@code null} out.
     */
    public static Instant startOfDay(LocalDate date) {
        return date == null ? null : date.atStartOfDay(ARCHIVE_ZONE).toInstant();
    }

    /**
     * End of the given calendar day (23:59:59.999999999) in the archive zone,
     * as an {@link Instant}. Use for an inclusive <b>upper</b> bound. Null-safe.
     */
    public static Instant endOfDay(LocalDate date) {
        return date == null ? null : date.atTime(LocalTime.MAX).atZone(ARCHIVE_ZONE).toInstant();
    }
}
