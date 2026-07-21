package ak.dev.khi_archive_platform.platform.service.common;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.mov.QuickTimeDirectory;
import com.drew.metadata.mp4.Mp4Directory;
import com.drew.metadata.wav.WavDirectory;
import com.mpatric.mp3agic.Mp3File;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.Locale;
import java.util.Optional;

/**
 * Best-effort audio/video duration extraction straight from the uploaded file bytes.
 * <p>
 * The primary duration source is the browser-side probe (see media-metadata.js on the
 * frontend, which reads it via a hidden &lt;audio&gt;/&lt;video&gt; element's `loadedmetadata`
 * event and sends it as part of the create/update payload). This class is the server-side
 * fallback used only when the client didn't supply a duration — e.g. a non-browser API
 * client, or a codec the browser couldn't probe.
 * <p>
 * Pure-Java, no ffprobe: {@code metadata-extractor} reads the container-level duration
 * atom for MP4/QuickTime/WAV, and {@code mp3agic} decodes MP3 frame headers (metadata-extractor
 * has no duration tag for MP3). Formats/codecs neither library understands (ogg, flac, wma, avi…)
 * fall back to empty — callers should leave duration untouched rather than fail the upload.
 */
public final class MediaDurationExtractor {

    private static final Logger log = LoggerFactory.getLogger(MediaDurationExtractor.class);

    private MediaDurationExtractor() {
    }

    /** Returns the duration formatted as {@code M:SS} or {@code H:MM:SS}, if it could be determined. */
    public static Optional<String> extractDuration(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return Optional.empty();
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (Exception e) {
            log.warn("Could not read uploaded file bytes for duration extraction: {}", e.getMessage());
            return Optional.empty();
        }

        return extractDurationSeconds(bytes).map(MediaDurationExtractor::format);
    }

    private static Optional<Double> extractDurationSeconds(byte[] bytes) {
        Optional<Double> viaContainer = tryContainerMetadata(bytes);
        if (viaContainer.isPresent()) {
            return viaContainer;
        }
        return tryMp3(bytes);
    }

    private static Optional<Double> tryContainerMetadata(byte[] bytes) {
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(bytes), bytes.length);

            for (Mp4Directory dir : metadata.getDirectoriesOfType(Mp4Directory.class)) {
                Double seconds = readRationalSeconds(dir, Mp4Directory.TAG_DURATION_SECONDS);
                if (seconds != null) return Optional.of(seconds);
            }
            for (QuickTimeDirectory dir : metadata.getDirectoriesOfType(QuickTimeDirectory.class)) {
                Double seconds = readRationalSeconds(dir, QuickTimeDirectory.TAG_DURATION_SECONDS);
                if (seconds != null) return Optional.of(seconds);
            }
            for (WavDirectory dir : metadata.getDirectoriesOfType(WavDirectory.class)) {
                if (dir.containsTag(WavDirectory.TAG_DURATION)) {
                    Double seconds = readRationalSeconds(dir, WavDirectory.TAG_DURATION);
                    if (seconds != null) return Optional.of(seconds);
                }
            }
        } catch (Exception e) {
            log.debug("No container duration metadata found: {}", e.getMessage());
        }
        return Optional.empty();
    }

    private static Double readRationalSeconds(Directory dir, int tag) {
        try {
            if (!dir.containsTag(tag)) return null;
            double value = dir.getDoubleObject(tag);
            return value > 0 ? value : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static Optional<Double> tryMp3(byte[] bytes) {
        // mp3agic 0.9.1 has no InputStream constructor — it needs a real file to seek within.
        File temp = null;
        try {
            temp = File.createTempFile("khi-duration-", ".mp3");
            Files.write(temp.toPath(), bytes);
            Mp3File mp3 = new Mp3File(temp);
            long seconds = mp3.getLengthInSeconds();
            return seconds > 0 ? Optional.of((double) seconds) : Optional.empty();
        } catch (Exception e) {
            log.debug("Not a readable MP3, skipping mp3agic duration: {}", e.getMessage());
            return Optional.empty();
        } finally {
            if (temp != null) {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            }
        }
    }

    private static String format(double totalSecondsRaw) {
        long totalSeconds = Math.round(totalSecondsRaw);
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        return hours > 0
                ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
                : String.format(Locale.ROOT, "%d:%02d", minutes, seconds);
    }
}
