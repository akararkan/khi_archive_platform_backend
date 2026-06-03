package ak.dev.khi_archive_platform.platform.config;

import ak.dev.khi_archive_platform.platform.model.physicalmedia.PhysicalMediaType;
import ak.dev.khi_archive_platform.platform.repo.physicalmedia.PhysicalMediaTypeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Boot-time seeder for the {@code physical_media_types} catalog.
 *
 * <p>Pre-populates the six media types observed in
 * {@code All Final Archive Lists.xlsx → Sheet1} along with the technical
 * defaults the team has standardised on for each. The frontend reads
 * these to autofill the nine capture fields when the user picks a type.
 *
 * <p>Seeder is <b>idempotent and non-destructive</b>:
 * <ul>
 *   <li>Missing types are inserted with their defaults.</li>
 *   <li>Existing types are <em>left alone</em> — an admin who has edited
 *       the playback model after a hardware upgrade keeps their edit.</li>
 * </ul>
 * If the team wants a hard refresh to the values shipped here, they can
 * delete the row from the catalog and the next boot will re-seed it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PhysicalMediaTypeSeeder {

    private final PhysicalMediaTypeRepository repository;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seed() {
        List<PhysicalMediaType> seeds = List.of(
                PhysicalMediaType.builder()
                        .name("Audio Cassette")
                        .description("Compact audio cassette tape; 4-track stereo, ~1.875 ips.")
                        .extension("wav")
                        .bitOrColorDepth("24")
                        .sampleOrFrameRate("48000")
                        .channelsOrResolution("Stereo")
                        .playbackModel("Pioneer Stereo Double Cassette Deck CT-W2O8R")
                        .captureInterface("MOTO 896mk3 hybrid")
                        .signalInterface("RCA")
                        .ingestSoftware("Adobe Audition")
                        .formatCodec("PCM")
                        .build(),
                PhysicalMediaType.builder()
                        .name("Reel")
                        .description("Open-reel magnetic tape, typically 1/4-inch.")
                        .extension("wav")
                        .bitOrColorDepth("24")
                        .sampleOrFrameRate("48000")
                        .channelsOrResolution("Stereo")
                        .playbackModel("AKAI X-201D")
                        .captureInterface("MOTO 896mk3 hybrid")
                        .signalInterface("RCA")
                        .ingestSoftware("Adobe Audition")
                        .formatCodec("PCM")
                        .build(),
                PhysicalMediaType.builder()
                        .name("Vinyl Record")
                        .description("LP / 7-inch / 12-inch vinyl record.")
                        .extension("wav")
                        .bitOrColorDepth("24")
                        .sampleOrFrameRate("48000")
                        .channelsOrResolution("Stereo")
                        .playbackModel("Audio-Technica AT-LP60")
                        .captureInterface("MOTO 896mk3 hybrid")
                        .signalInterface("RCA")
                        .ingestSoftware("Adobe Audition")
                        .formatCodec("PCM")
                        .build(),
                PhysicalMediaType.builder()
                        .name("VHS Cassette")
                        .description("VHS video cassette; PAL 625i.")
                        .extension("avi")
                        .bitOrColorDepth("8")
                        .sampleOrFrameRate("25")
                        .channelsOrResolution("720X576")
                        .playbackModel("Sony DVD Player / Video Cassette Recorder SLV-D985P ME")
                        .captureInterface("Blackmagic Intensity Pro 4K")
                        .signalInterface("Composite")
                        .ingestSoftware("Blackmagic Media Express")
                        .formatCodec("Uncompressed avi 8-bit YUV, 625i50 PAL")
                        .build(),
                PhysicalMediaType.builder()
                        .name("MiniDV")
                        .description("MiniDV digital video cassette captured over FireWire.")
                        .extension("avi")
                        .bitOrColorDepth("8")
                        .sampleOrFrameRate("25")
                        .channelsOrResolution("720x576")
                        .playbackModel("Sony HVR M10")
                        .captureInterface("FireWire 400")
                        .signalInterface("FireWire IEEE 1394")
                        .ingestSoftware("Adobe Premiere")
                        .formatCodec("DV(Native)")
                        .build(),
                PhysicalMediaType.builder()
                        .name("CD/DVD")
                        .description("Compact disc / DVD optical media. Capture defaults"
                                + " to be filled when the team picks the ingest chain.")
                        // intentionally empty defaults — admin fills them in later
                        .build()
        );

        int inserted = 0;
        for (PhysicalMediaType seed : seeds) {
            if (!repository.existsByName(seed.getName())) {
                seed.setCreatedBy("system-seed");
                seed.setUpdatedBy("system-seed");
                repository.save(seed);
                inserted++;
            }
        }
        if (inserted > 0) {
            log.info("Seeded {} physical-media type catalog row(s)", inserted);
        }
    }
}
