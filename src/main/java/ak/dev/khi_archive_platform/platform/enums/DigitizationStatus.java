package ak.dev.khi_archive_platform.platform.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Encodes the {@code Digitization} column from the physical-media
 * inventory sheet. The Excel uses integer codes (0/1/2); we store them as a
 * Postgres enum so analytics queries can read a stable label without having
 * to remember what each integer meant.
 *
 * <ul>
 *   <li>{@code 0 → NOT_DIGITIZED} — physical only, no digital copy yet.</li>
 *   <li>{@code 1 → DIGITIZED}     — captured at least once into the archive.</li>
 *   <li>{@code 2 → DUPLICATED}    — captured more than once (intentionally re-ingested).</li>
 * </ul>
 *
 * The companion {@link #fromCode(Integer)} accepts either the Excel integer
 * or {@code null} (blank cell → null status) so the importer doesn't need to
 * sprinkle null-checks of its own.
 */
@Getter
@RequiredArgsConstructor
public enum DigitizationStatus {

    NOT_DIGITIZED(0),
    DIGITIZED(1),
    DUPLICATED(2);

    private final int code;

    /** Parses the 0/1/2 integer from the source sheet. Returns {@code null}
     *  for {@code null} input so callers can store an unknown status. Any
     *  unrecognised code triggers {@link IllegalArgumentException}. */
    public static DigitizationStatus fromCode(Integer code) {
        if (code == null) return null;
        for (DigitizationStatus s : values()) {
            if (s.code == code) return s;
        }
        throw new IllegalArgumentException("Unknown DigitizationStatus code: " + code);
    }
}
