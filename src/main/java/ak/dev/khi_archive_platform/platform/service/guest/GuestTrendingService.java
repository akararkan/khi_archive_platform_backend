package ak.dev.khi_archive_platform.platform.service.guest;

import ak.dev.khi_archive_platform.platform.dto.guest.*;
import ak.dev.khi_archive_platform.platform.model.audio.Audio;
import ak.dev.khi_archive_platform.platform.model.image.Image;
import ak.dev.khi_archive_platform.platform.model.person.Person;
import ak.dev.khi_archive_platform.platform.model.project.Project;
import ak.dev.khi_archive_platform.platform.model.text.Text;
import ak.dev.khi_archive_platform.platform.model.trending.GuestInteractionLog;
import ak.dev.khi_archive_platform.platform.model.trending.GuestSearchLog;
import ak.dev.khi_archive_platform.platform.model.video.Video;
import ak.dev.khi_archive_platform.platform.repo.audio.AudioRepository;
import ak.dev.khi_archive_platform.platform.repo.image.ImageRepository;
import ak.dev.khi_archive_platform.platform.repo.person.PersonRepository;
import ak.dev.khi_archive_platform.platform.repo.project.ProjectRepository;
import ak.dev.khi_archive_platform.platform.repo.text.TextRepository;
import ak.dev.khi_archive_platform.platform.repo.trending.GuestInteractionLogRepository;
import ak.dev.khi_archive_platform.platform.repo.trending.GuestSearchLogRepository;
import ak.dev.khi_archive_platform.platform.repo.video.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Tracks guest interactions (views + searches) and computes trending scores.
 *
 * Algorithm — time-decay weighted count:
 *   viewed in last  1 hour  → 3 points
 *   viewed in last 24 hours → 2 points
 *   viewed in last  7 days  → 1 point
 *
 * Trending is computed once and cached for 5 minutes (see CacheConfig).
 * All writes are fire-and-forget via @Async so they never block responses.
 * Old records (> 30 days) are purged nightly by the scheduler.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GuestTrendingService {

    private final GuestInteractionLogRepository interactionRepo;
    private final GuestSearchLogRepository      searchLogRepo;

    private final AudioRepository   audioRepository;
    private final VideoRepository   videoRepository;
    private final TextRepository    textRepository;
    private final ImageRepository   imageRepository;
    private final ProjectRepository projectRepository;
    private final PersonRepository  personRepository;

    static final int TRENDING_POOL    = 100; // raw candidates from DB
    static final int TRENDING_TOP     =  20; // items returned in trendingItems
    static final int TRENDING_PER_TYPE=   5; // items per type in trendingByType
    static final int TOP_SEARCHES     =  10; // search queries returned

    // ─── Async fire-and-forget logging ────────────────────────────────────────

    @Async("trendingLogExecutor")
    @Transactional
    public void logView(String entityType, String entityCode) {
        try {
            interactionRepo.save(GuestInteractionLog.builder()
                    .entityType(entityType)
                    .entityCode(entityCode)
                    .interactedAt(Instant.now())
                    .build());
        } catch (Exception ex) {
            log.debug("Trending log write failed (non-critical): {}", ex.getMessage());
        }
    }

    @Async("trendingLogExecutor")
    @Transactional
    public void logSearch(String query) {
        if (query == null || query.isBlank()) return;
        try {
            searchLogRepo.save(GuestSearchLog.builder()
                    .query(query.trim().toLowerCase())
                    .searchedAt(Instant.now())
                    .build());
        } catch (Exception ex) {
            log.debug("Search log write failed (non-critical): {}", ex.getMessage());
        }
    }

    // ─── Snapshot for inline enrichment of list responses ──────────────────────

    /**
     * Lightweight cached snapshot used to stamp trending metadata onto every
     * item in the listing endpoints without a separate API call.
     *
     * Key   = "type:code"  e.g. "audio:AUD-042"
     * Value = TrendingMark carrying rank (1-based) and score
     *
     * Cached for 5 minutes — same TTL as the full trending response.
     */
    @Cacheable("trending:snapshot")
    @Transactional(readOnly = true)
    public java.util.Map<String, TrendingMark> getSnapshot() {
        Instant now          = Instant.now();
        Instant sevenDaysAgo = now.minus(7,  ChronoUnit.DAYS);
        Instant oneDayAgo    = now.minus(24, ChronoUnit.HOURS);
        Instant oneHourAgo   = now.minus(1,  ChronoUnit.HOURS);

        List<Object[]> raw = interactionRepo.findTrendingRaw(
                sevenDaysAgo, oneDayAgo, oneHourAgo, TRENDING_TOP);

        java.util.Map<String, TrendingMark> map = new java.util.LinkedHashMap<>(raw.size());
        int rank = 0;
        for (Object[] row : raw) {
            String key   = row[0] + ":" + row[1];
            double score = ((Number) row[2]).doubleValue();
            map.put(key, new TrendingMark(++rank, score));
        }
        return map;
    }

    public record TrendingMark(int rank, double score) {}

    // ─── Trending computation (cached 5 minutes) ───────────────────────────────

    @Cacheable("trending:results")
    @Transactional(readOnly = true)
    public GuestTrendingDTO getTrending() {
        Instant now         = Instant.now();
        Instant sevenDaysAgo = now.minus(7,  ChronoUnit.DAYS);
        Instant oneDayAgo   = now.minus(24, ChronoUnit.HOURS);
        Instant oneHourAgo  = now.minus(1,  ChronoUnit.HOURS);

        // 1. Raw trending rows from DB (entityType, entityCode, score)
        List<Object[]> raw = interactionRepo.findTrendingRaw(
                sevenDaysAgo, oneDayAgo, oneHourAgo, TRENDING_POOL);

        // 2. Build ranked items, resolving entity → DTO
        List<GuestTrendingDTO.TrendingItem> all = new ArrayList<>(raw.size());
        int rank = 0;
        for (Object[] row : raw) {
            String type  = (String) row[0];
            String code  = (String) row[1];
            double score = ((Number) row[2]).doubleValue();

            GuestTrendingDTO.TrendingItem item = resolveItem(type, code, ++rank, score);
            if (item != null) all.add(item);
        }

        // 3. Top searches (last 24 hours)
        List<Object[]> searchRows = searchLogRepo.findTopSearches(oneDayAgo, TOP_SEARCHES);
        List<GuestTrendingDTO.TopSearch> topSearches = new ArrayList<>(searchRows.size());
        for (Object[] sr : searchRows) {
            topSearches.add(GuestTrendingDTO.TopSearch.builder()
                    .query((String) sr[0])
                    .count(((Number) sr[1]).longValue())
                    .build());
        }

        // 4. Slice by type for per-type rows
        List<GuestTrendingDTO.TrendingItem> audioItems   = byType(all, "audio");
        List<GuestTrendingDTO.TrendingItem> videoItems   = byType(all, "video");
        List<GuestTrendingDTO.TrendingItem> textItems    = byType(all, "text");
        List<GuestTrendingDTO.TrendingItem> imageItems   = byType(all, "image");

        return GuestTrendingDTO.builder()
                .generatedAt(now)
                .trendingItems(all.size() > TRENDING_TOP ? all.subList(0, TRENDING_TOP) : all)
                .topSearches(topSearches)
                .trendingByType(GuestTrendingDTO.ByType.builder()
                        .audio(audioItems)
                        .video(videoItems)
                        .text(textItems)
                        .image(imageItems)
                        .build())
                .build();
    }

    // ─── Scheduler: nightly cleanup of old records ─────────────────────────────

    @Scheduled(cron = "0 0 3 * * *") // 3 AM every day
    @Transactional
    @org.springframework.cache.annotation.Caching(evict = {
        @CacheEvict(value = "trending:results",  allEntries = true),
        @CacheEvict(value = "trending:snapshot", allEntries = true)
    })
    public void purgeOldLogs() {
        Instant cutoff = Instant.now().minus(30, ChronoUnit.DAYS);
        interactionRepo.deleteOlderThan(cutoff);
        searchLogRepo.deleteOlderThan(cutoff);
        log.info("Purged guest interaction + search logs older than 30 days");
    }

    // ─── Internals ─────────────────────────────────────────────────────────────

    private GuestTrendingDTO.TrendingItem resolveItem(String type, String code,
                                                      int rank, double score) {
        return switch (type) {
            case "audio"    -> resolveAudio(code, rank, score);
            case "video"    -> resolveVideo(code, rank, score);
            case "text"     -> resolveText(code, rank, score);
            case "image"    -> resolveImage(code, rank, score);
            case "project"  -> resolveProject(code, rank, score);
            case "person"   -> resolvePerson(code, rank, score);
            default         -> null;
        };
    }

    private GuestTrendingDTO.TrendingItem resolveAudio(String code, int rank, double score) {
        return audioRepository.findByAudioCodeAndRemovedAtIsNull(code)
                .filter(a -> Boolean.TRUE.equals(a.getIsPublic()))
                .map(a -> {
                    GuestAudioDTO dto = GuestMapper.toAudio(a);
                    Project p = a.getProject();
                    return GuestTrendingDTO.TrendingItem.builder()
                            .rank(rank).score(score).kind("audio").code(code)
                            .title(firstTitle(a.getOriginTitle(), a.getAlterTitle(),
                                    a.getCentral_kurdish_title(), a.getRomanized_title(), code))
                            .thumbnail(null)
                            .projectCode(p == null ? null : p.getProjectCode())
                            .projectName(p == null ? null : p.getProjectName())
                            .personCode(p == null || p.getPerson() == null ? null : p.getPerson().getPersonCode())
                            .personName(p == null ? null : personName(p.getPerson()))
                            .audio(dto)
                            .build();
                }).orElse(null);
    }

    private GuestTrendingDTO.TrendingItem resolveVideo(String code, int rank, double score) {
        return videoRepository.findByVideoCodeAndRemovedAtIsNull(code)
                .filter(v -> Boolean.TRUE.equals(v.getIsPublic()))
                .map(v -> {
                    GuestVideoDTO dto = GuestMapper.toVideo(v);
                    Project p = v.getProject();
                    return GuestTrendingDTO.TrendingItem.builder()
                            .rank(rank).score(score).kind("video").code(code)
                            .title(firstTitle(v.getOriginalTitle(), v.getAlternativeTitle(),
                                    v.getTitleInCentralKurdish(), v.getRomanizedTitle(), code))
                            .thumbnail(null)
                            .projectCode(p == null ? null : p.getProjectCode())
                            .projectName(p == null ? null : p.getProjectName())
                            .personCode(p == null || p.getPerson() == null ? null : p.getPerson().getPersonCode())
                            .personName(p == null ? null : personName(p.getPerson()))
                            .video(dto)
                            .build();
                }).orElse(null);
    }

    private GuestTrendingDTO.TrendingItem resolveText(String code, int rank, double score) {
        return textRepository.findByTextCodeAndRemovedAtIsNull(code)
                .filter(t -> Boolean.TRUE.equals(t.getIsPublic()))
                .map(t -> {
                    GuestTextDTO dto = GuestMapper.toText(t);
                    Project p = t.getProject();
                    return GuestTrendingDTO.TrendingItem.builder()
                            .rank(rank).score(score).kind("text").code(code)
                            .title(firstTitle(t.getOriginalTitle(), t.getAlternativeTitle(),
                                    t.getTitleInCentralKurdish(), t.getRomanizedTitle(), code))
                            .thumbnail(null)
                            .projectCode(p == null ? null : p.getProjectCode())
                            .projectName(p == null ? null : p.getProjectName())
                            .personCode(p == null || p.getPerson() == null ? null : p.getPerson().getPersonCode())
                            .personName(p == null ? null : personName(p.getPerson()))
                            .text(dto)
                            .build();
                }).orElse(null);
    }

    private GuestTrendingDTO.TrendingItem resolveImage(String code, int rank, double score) {
        return imageRepository.findByImageCodeAndRemovedAtIsNull(code)
                .filter(i -> Boolean.TRUE.equals(i.getIsPublic()))
                .map(i -> {
                    GuestImageDTO dto = GuestMapper.toImage(i);
                    Project p = i.getProject();
                    return GuestTrendingDTO.TrendingItem.builder()
                            .rank(rank).score(score).kind("image").code(code)
                            .title(firstTitle(i.getOriginalTitle(), i.getAlternativeTitle(),
                                    i.getTitleInCentralKurdish(), i.getRomanizedTitle(), code))
                            .thumbnail(null)
                            .projectCode(p == null ? null : p.getProjectCode())
                            .projectName(p == null ? null : p.getProjectName())
                            .personCode(p == null || p.getPerson() == null ? null : p.getPerson().getPersonCode())
                            .personName(p == null ? null : personName(p.getPerson()))
                            .image(dto)
                            .build();
                }).orElse(null);
    }

    private GuestTrendingDTO.TrendingItem resolveProject(String code, int rank, double score) {
        return projectRepository.findByProjectCodeAndRemovedAtIsNull(code)
                .map(p -> {
                    long audios   = audioRepository.countByProjectAndRemovedAtIsNull(p);
                    long videos   = videoRepository.countByProjectAndRemovedAtIsNull(p);
                    long texts    = textRepository.countByProjectAndRemovedAtIsNull(p);
                    long images   = imageRepository.countByProjectAndRemovedAtIsNull(p);
                    GuestProjectDTO dto = GuestMapper.toProject(p,
                            GuestProjectDTO.MediaCounts.builder()
                                    .audios(audios).videos(videos).texts(texts).images(images).build());
                    return GuestTrendingDTO.TrendingItem.builder()
                            .rank(rank).score(score).kind("project").code(code)
                            .title(p.getProjectName())
                            .thumbnail(null)
                            .personCode(p.getPerson() == null ? null : p.getPerson().getPersonCode())
                            .personName(p.getPerson() == null ? null : personName(p.getPerson()))
                            .project(dto)
                            .build();
                }).orElse(null);
    }

    private GuestTrendingDTO.TrendingItem resolvePerson(String code, int rank, double score) {
        return personRepository.findByPersonCodeAndRemovedAtIsNull(code)
                .map(p -> {
                    long projectCount = projectRepository.countByPersonAndRemovedAtIsNull(p);
                    GuestPersonDTO dto = GuestMapper.toPerson(p, projectCount);
                    return GuestTrendingDTO.TrendingItem.builder()
                            .rank(rank).score(score).kind("person").code(code)
                            .title(personName(p))
                            .thumbnail(p.getMediaPortrait())
                            .person(dto)
                            .build();
                }).orElse(null);
    }

    private static List<GuestTrendingDTO.TrendingItem> byType(
            List<GuestTrendingDTO.TrendingItem> all, String type) {
        List<GuestTrendingDTO.TrendingItem> out = new ArrayList<>();
        for (GuestTrendingDTO.TrendingItem item : all) {
            if (type.equals(item.getKind())) {
                out.add(item);
                if (out.size() == TRENDING_PER_TYPE) break;
            }
        }
        return out;
    }

    private static String firstTitle(String... candidates) {
        for (String s : candidates) {
            if (s != null && !s.isBlank()) return s;
        }
        return null;
    }

    private static String personName(Person p) {
        if (p == null) return null;
        return firstTitle(p.getFullName(), p.getRomanizedName(), p.getNickname(), p.getPersonCode());
    }
}
