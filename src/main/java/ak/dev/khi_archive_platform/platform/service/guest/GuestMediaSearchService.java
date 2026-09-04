package ak.dev.khi_archive_platform.platform.service.guest;

import ak.dev.khi_archive_platform.platform.dto.guest.GuestAudioDTO;
import ak.dev.khi_archive_platform.platform.dto.guest.GuestFacetsDTO;
import ak.dev.khi_archive_platform.platform.dto.guest.GuestImageDTO;
import ak.dev.khi_archive_platform.platform.dto.guest.GuestMediaHitDTO;
import ak.dev.khi_archive_platform.platform.dto.guest.GuestMediaItemDTO;
import ak.dev.khi_archive_platform.platform.dto.guest.GuestMediaSearchDTO;
import ak.dev.khi_archive_platform.platform.dto.guest.GuestMediaSearchParams;
import ak.dev.khi_archive_platform.platform.dto.guest.GuestPersonSummaryDTO;
import ak.dev.khi_archive_platform.platform.dto.guest.GuestTextDTO;
import ak.dev.khi_archive_platform.platform.dto.guest.GuestVideoDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static ak.dev.khi_archive_platform.platform.service.guest.GuestMediaHitMapper.TYPE_AUDIO;
import static ak.dev.khi_archive_platform.platform.service.guest.GuestMediaHitMapper.TYPE_IMAGE;
import static ak.dev.khi_archive_platform.platform.service.guest.GuestMediaHitMapper.TYPE_ORDER;
import static ak.dev.khi_archive_platform.platform.service.guest.GuestMediaHitMapper.TYPE_TEXT;
import static ak.dev.khi_archive_platform.platform.service.guest.GuestMediaHitMapper.TYPE_VIDEO;

/**
 * The website's search source: one keyword in, one ranked list of media out.
 *
 * <p>The public site lets a visitor pick where to search; when they pick the
 * platform, that choice resolves to this service. It answers the question the
 * search box actually asks — "what media do you hold about <em>Hasan
 * Zirak</em>?" — rather than the question the per-kind endpoints answer, which
 * is "which audios match, and separately which videos, and separately …".
 *
 * <p><b>How a request is served.</b>
 * <ol>
 *   <li>The keyword and the shared filters are run against all four kinds
 *       through {@link GuestSearchService}, so public-visibility rules, trash
 *       rules, fuzzy matching and trending stamps are exactly the ones the
 *       rest of the guest API already applies — this service adds no new way
 *       to reach a row.</li>
 *   <li>Each kind's rows are flattened onto the shared card shape and
 *       re-scored on one scale by {@link GuestMediaRelevanceScorer}, which is
 *       what makes a mixed audio/video/image/file list orderable at all.</li>
 *   <li>The merged list is sorted, sliced into a page, and returned alongside
 *       per-kind counts for the tab bar and — on request — refine facets
 *       computed over the matched set rather than over the whole archive.</li>
 * </ol>
 *
 * <p><b>Bounds.</b> Each kind contributes at most {@link #SCAN_WINDOW} rows,
 * which is the same cap {@code GuestSearchService} already applies to a
 * keyword search. When a kind reaches it, {@code truncated} is set on the
 * response: the counts are then a floor, and the caller should narrow the
 * query or add a filter.
 */
@Service
@RequiredArgsConstructor
public class GuestMediaSearchService {

    private final GuestSearchService guestSearchService;
    private final GuestTrendingService trendingService;

    /** Page size when the caller does not ask for one. */
    static final int DEFAULT_PAGE_SIZE = 24;

    /** Hard ceiling on {@code size}, so one request cannot pull the archive. */
    static final int MAX_PAGE_SIZE = 100;

    /**
     * Rows loaded per kind before merging. Matches {@code GuestSearchService.MAX_LIMIT},
     * the cap its keyword search already applies, so a keyword request loses
     * nothing here that it would not have lost anyway.
     */
    static final int SCAN_WINDOW = GuestSearchService.MAX_LIMIT;

    /** Buckets kept per facet, ordered by count desc then label asc. */
    static final int FACET_BUCKET_LIMIT = 30;

    /** Items in the "more from this collection" rail on a detail response. */
    static final int RELATED_LIMIT = 12;

    // ─── Search ───────────────────────────────────────────────────────────────

    /**
     * Runs one search across audio, video, image and text.
     *
     * @param params  the query string contract — see {@link GuestMediaSearchParams}
     * @param page    zero-based page index of the merged result list
     * @param size    page size, clamped to {@link #MAX_PAGE_SIZE}
     */
    @Transactional(readOnly = true)
    public GuestMediaSearchDTO search(GuestMediaSearchParams params, Integer page, Integer size) {
        GuestMediaSearchParams p = params == null ? new GuestMediaSearchParams() : params;

        String query = trimToNull(p.getQ());
        if (query != null) trendingService.logSearch(query);

        Set<String> selected = parseTypes(p.getType());
        int pageIndex = Math.max(0, page == null ? 0 : page);
        int pageSize = clampSize(size);

        Instant dateFrom = parseStart(p.getDateFrom());
        Instant dateTo = parseEnd(p.getDateTo());
        int[] decade = parseDecade(p.getDecade());

        Pageable window = PageRequest.of(0, SCAN_WINDOW);

        // Every kind is always searched: the tab bar needs all four counts even
        // when only one tab is on screen.
        Page<GuestAudioDTO> audioPage = guestSearchService.searchAudios(
                query, p.getProjectCode(), p.getCategoryCode(), p.getPersonCode(),
                p.getLanguage(), p.getDialect(),
                null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, p.getRegion(), null, null,
                p.getSubject(), p.getGenre(), p.getTag(), p.getKeyword(),
                dateFrom, dateTo, null, null,
                null, null, window);

        Page<GuestVideoDTO> videoPage = guestSearchService.searchVideos(
                query, p.getProjectCode(), p.getCategoryCode(), p.getPersonCode(),
                p.getLanguage(), p.getDialect(), p.getRegion(),
                null, null, null, null, null, null, null, null, null, null, null,
                p.getSubject(), p.getGenre(), null, null, p.getTag(), p.getKeyword(),
                dateFrom, dateTo, null, null,
                null, null, window);

        Page<GuestImageDTO> imagePage = guestSearchService.searchImages(
                query, p.getProjectCode(), p.getCategoryCode(), p.getPersonCode(),
                p.getLanguage(), p.getDialect(), p.getRegion(),
                null, null, null, null, null, null, null, null, null,
                p.getSubject(), p.getGenre(), null, null, p.getTag(), p.getKeyword(),
                dateFrom, dateTo, null, null,
                null, null, window);

        Page<GuestTextDTO> textPage = guestSearchService.searchTexts(
                query, p.getProjectCode(), p.getCategoryCode(), p.getPersonCode(),
                p.getLanguage(), p.getDialect(), p.getRegion(),
                null, null, null, null, null, null, null, null, null, null, null, null,
                p.getSubject(), p.getGenre(), p.getTag(), p.getKeyword(),
                dateFrom, dateTo, null, null, null, null,
                null, null, window);

        List<String> tokens = GuestMediaRelevanceScorer.tokenize(query);
        String phrase = query == null ? null : query.trim().toLowerCase(Locale.ROOT);

        Map<String, List<GuestMediaHitDTO>> byType = new LinkedHashMap<>();
        byType.put(TYPE_AUDIO, rank(audioPage.getContent(), (GuestAudioDTO a) -> GuestMediaHitMapper.toHit(a), tokens, phrase, decade));
        byType.put(TYPE_VIDEO, rank(videoPage.getContent(), (GuestVideoDTO v) -> GuestMediaHitMapper.toHit(v), tokens, phrase, decade));
        byType.put(TYPE_IMAGE, rank(imagePage.getContent(), (GuestImageDTO i) -> GuestMediaHitMapper.toHit(i), tokens, phrase, decade));
        byType.put(TYPE_TEXT,  rank(textPage.getContent(),  (GuestTextDTO t) -> GuestMediaHitMapper.toHit(t), tokens, phrase, decade));

        boolean truncated = audioPage.getTotalElements() >= SCAN_WINDOW
                || videoPage.getTotalElements() >= SCAN_WINDOW
                || imagePage.getTotalElements() >= SCAN_WINDOW
                || textPage.getTotalElements() >= SCAN_WINDOW;

        String sort = effectiveSort(p.getSort(), query);
        Comparator<GuestMediaHitDTO> comparator = comparator(sort);
        byType.values().forEach(list -> list.sort(comparator));

        List<GuestMediaHitDTO> merged = new ArrayList<>();
        for (String kind : TYPE_ORDER) {
            if (selected.contains(kind)) merged.addAll(byType.get(kind));
        }
        merged.sort(comparator);

        GuestMediaSearchDTO.Counts counts = GuestMediaSearchDTO.Counts.builder()
                .audio(byType.get(TYPE_AUDIO).size())
                .video(byType.get(TYPE_VIDEO).size())
                .image(byType.get(TYPE_IMAGE).size())
                .text(byType.get(TYPE_TEXT).size())
                .total(byType.values().stream().mapToLong(List::size).sum())
                .build();

        GuestMediaSearchDTO.Facets facets = Boolean.TRUE.equals(p.getFacets())
                ? facets(merged)
                : null;

        GuestMediaSearchDTO.Groups groups = "type".equalsIgnoreCase(trimToEmpty(p.getGroupBy()))
                ? GuestMediaSearchDTO.Groups.builder()
                    .audio(section(TYPE_AUDIO, byType.get(TYPE_AUDIO), selected, pageIndex, pageSize))
                    .video(section(TYPE_VIDEO, byType.get(TYPE_VIDEO), selected, pageIndex, pageSize))
                    .image(section(TYPE_IMAGE, byType.get(TYPE_IMAGE), selected, pageIndex, pageSize))
                    .text(section(TYPE_TEXT,  byType.get(TYPE_TEXT),  selected, pageIndex, pageSize))
                    .build()
                : null;

        List<GuestMediaHitDTO> content = slice(merged, pageIndex, pageSize);
        int totalPages = totalPages(merged.size(), pageSize);

        boolean full = "full".equalsIgnoreCase(trimToEmpty(p.getInclude()));
        if (!full) {
            // Everything shares hit instances with `merged`/`groups`, so strip
            // after slicing and only on what is actually being returned.
            stripFullPayload(merged);
        }

        return GuestMediaSearchDTO.builder()
                .query(query == null ? "" : query)
                .type(describeSelection(selected))
                .sort(sort)
                .order(TYPE_ORDER)
                .counts(counts)
                .content(content)
                .page(pageIndex)
                .size(pageSize)
                .totalElements(merged.size())
                .totalPages(totalPages)
                .numberOfElements(content.size())
                .first(pageIndex == 0)
                .last(pageIndex >= totalPages - 1)
                .empty(content.isEmpty())
                .hasNext(pageIndex < totalPages - 1)
                .hasPrevious(pageIndex > 0)
                .groups(groups)
                .facets(facets)
                .truncated(truncated)
                .build();
    }

    // ─── Detail ───────────────────────────────────────────────────────────────

    /**
     * Fetches one media item by the {@code type} + {@code code} pair a search
     * result carries. Delegates to the per-kind guest lookups, so the same
     * visibility rules apply and the view is logged for trending exactly once.
     *
     * @return empty when the type is unknown, or the item does not exist, is
     *         trashed, or is not public
     */
    @Transactional(readOnly = true)
    public Optional<GuestMediaItemDTO> getItem(String type, String code, boolean withRelated) {
        String kind = normalizeType(type);
        if (kind == null || trimToNull(code) == null) return Optional.empty();

        GuestMediaItemDTO.GuestMediaItemDTOBuilder out = GuestMediaItemDTO.builder()
                .type(kind)
                .code(code);

        GuestMediaHitDTO hit = null;
        String projectCode = null;

        if (TYPE_AUDIO.equals(kind)) {
            GuestAudioDTO dto = guestSearchService.getAudioByCode(code).orElse(null);
            if (dto == null) return Optional.empty();
            hit = GuestMediaHitMapper.toHit(dto);
            projectCode = dto.getProjectCode();
            out.audio(dto);
        } else if (TYPE_VIDEO.equals(kind)) {
            GuestVideoDTO dto = guestSearchService.getVideoByCode(code).orElse(null);
            if (dto == null) return Optional.empty();
            hit = GuestMediaHitMapper.toHit(dto);
            projectCode = dto.getProjectCode();
            out.video(dto);
        } else if (TYPE_IMAGE.equals(kind)) {
            GuestImageDTO dto = guestSearchService.getImageByCode(code).orElse(null);
            if (dto == null) return Optional.empty();
            hit = GuestMediaHitMapper.toHit(dto);
            projectCode = dto.getProjectCode();
            out.image(dto);
        } else if (TYPE_TEXT.equals(kind)) {
            GuestTextDTO dto = guestSearchService.getTextByCode(code).orElse(null);
            if (dto == null) return Optional.empty();
            hit = GuestMediaHitMapper.toHit(dto);
            projectCode = dto.getProjectCode();
            out.text(dto);
        }
        if (hit == null) return Optional.empty();

        // The card repeats the payload that is already on the response.
        stripFullPayload(List.of(hit));
        out.item(hit);

        if (withRelated) {
            out.related(related(projectCode, kind, code));
        }
        return Optional.of(out.build());
    }

    /**
     * Other public media from the same project, interleaved kind by kind so the
     * rail never fills up with one kind, and capped at {@link #RELATED_LIMIT}.
     */
    private List<GuestMediaHitDTO> related(String projectCode, String excludeType, String excludeCode) {
        if (trimToNull(projectCode) == null) return List.of();
        Map<String, Object> media = guestSearchService.getProjectMedia(projectCode, null);
        if (media == null) return List.of();

        Map<String, List<GuestMediaHitDTO>> pools = new LinkedHashMap<>();
        pools.put(TYPE_AUDIO, hits(media.get("audios"), GuestAudioDTO.class, (GuestAudioDTO a) -> GuestMediaHitMapper.toHit(a)));
        pools.put(TYPE_VIDEO, hits(media.get("videos"), GuestVideoDTO.class, (GuestVideoDTO v) -> GuestMediaHitMapper.toHit(v)));
        pools.put(TYPE_IMAGE, hits(media.get("images"), GuestImageDTO.class, (GuestImageDTO i) -> GuestMediaHitMapper.toHit(i)));
        pools.put(TYPE_TEXT,  hits(media.get("texts"),  GuestTextDTO.class,  (GuestTextDTO t) -> GuestMediaHitMapper.toHit(t)));

        List<GuestMediaHitDTO> out = new ArrayList<>(RELATED_LIMIT);
        for (int round = 0; out.size() < RELATED_LIMIT; round++) {
            boolean added = false;
            for (String kind : TYPE_ORDER) {
                List<GuestMediaHitDTO> pool = pools.get(kind);
                if (round >= pool.size()) continue;
                added = true;
                GuestMediaHitDTO candidate = pool.get(round);
                if (kind.equals(excludeType) && excludeCode.equals(candidate.getCode())) continue;
                out.add(candidate);
                if (out.size() == RELATED_LIMIT) break;
            }
            if (!added) break;
        }
        stripFullPayload(out);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static <T> List<GuestMediaHitDTO> hits(Object raw, Class<T> type,
                                                   java.util.function.Function<T, GuestMediaHitDTO> mapper) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) return List.of();
        List<GuestMediaHitDTO> out = new ArrayList<>(list.size());
        for (Object o : list) {
            if (type.isInstance(o)) out.add(mapper.apply((T) o));
        }
        return out;
    }

    // ─── Ranking ──────────────────────────────────────────────────────────────

    /**
     * Flattens one kind's page onto cards, scores each against the query, and
     * applies the decade filter. The SQL order is folded into the score as a
     * small decreasing nudge, so items the database ranked first stay ahead of
     * their exact ties instead of being reordered arbitrarily.
     */
    private static <T> List<GuestMediaHitDTO> rank(List<T> rows,
                                                   java.util.function.Function<T, GuestMediaHitDTO> mapper,
                                                   List<String> tokens,
                                                   String phrase,
                                                   int[] decade) {
        List<GuestMediaHitDTO> out = new ArrayList<>(rows.size());
        int n = rows.size();
        for (int i = 0; i < n; i++) {
            GuestMediaHitDTO hit = mapper.apply(rows.get(i));
            if (hit == null) continue;
            if (!withinDecade(hit, decade)) continue;
            GuestMediaRelevanceScorer.Scored scored =
                    GuestMediaRelevanceScorer.score(hit, tokens, phrase);
            double sqlNudge = n <= 1 ? 0.0 : (1.0 - (double) i / (n - 1)) * 1.5;
            hit.setScore(Math.round((scored.score() + sqlNudge) * 1000.0) / 1000.0);
            hit.setMatchedIn(scored.matchedIn());
            out.add(hit);
        }
        return out;
    }

    private static boolean withinDecade(GuestMediaHitDTO hit, int[] decade) {
        if (decade == null) return true;
        Instant when = hit.getDateCreated() != null ? hit.getDateCreated() : hit.getDatePublished();
        if (when == null) return false;
        int year = when.atZone(ZoneOffset.UTC).getYear();
        return year >= decade[0] && year <= decade[1];
    }

    /** Resolves the requested sort, defaulting on whether a keyword was sent. */
    static String effectiveSort(String requested, String query) {
        String wanted = trimToEmpty(requested).toLowerCase(Locale.ROOT);
        return switch (wanted) {
            case "relevance", "newest", "oldest", "title", "trending" -> wanted;
            default -> query == null ? "newest" : "relevance";
        };
    }

    private static Comparator<GuestMediaHitDTO> comparator(String sort) {
        Comparator<GuestMediaHitDTO> byCode = Comparator.comparing(
                GuestMediaHitDTO::getCode, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));

        Comparator<GuestMediaHitDTO> newestFirst = Comparator.comparing(
                GuestMediaSearchService::when, Comparator.nullsLast(Comparator.<Instant>reverseOrder()));
        Comparator<GuestMediaHitDTO> oldestFirst = Comparator.comparing(
                GuestMediaSearchService::when, Comparator.nullsLast(Comparator.<Instant>naturalOrder()));
        Comparator<GuestMediaHitDTO> bestScoreFirst =
                Comparator.<GuestMediaHitDTO>comparingDouble(GuestMediaHitDTO::getScore).reversed();

        return switch (sort) {
            case "newest" -> newestFirst.thenComparing(byCode);
            case "oldest" -> oldestFirst.thenComparing(byCode);
            case "title"  -> Comparator.comparing(GuestMediaHitDTO::getTitle,
                            Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                    .thenComparing(byCode);
            case "trending" -> Comparator.comparing(GuestMediaHitDTO::getTrendingScore,
                            Comparator.nullsLast(Comparator.<Double>reverseOrder()))
                    .thenComparing(bestScoreFirst)
                    .thenComparing(byCode);
            // relevance
            default -> bestScoreFirst.thenComparing(newestFirst).thenComparing(byCode);
        };
    }

    private static Instant when(GuestMediaHitDTO hit) {
        return hit.getDateCreated() != null ? hit.getDateCreated() : hit.getDatePublished();
    }

    // ─── Facets over the matched set ──────────────────────────────────────────

    private static GuestMediaSearchDTO.Facets facets(List<GuestMediaHitDTO> hits) {
        Map<String, Long> languages = new LinkedHashMap<>();
        Map<String, Long> dialects = new LinkedHashMap<>();
        Map<String, Long> regions = new LinkedHashMap<>();
        Map<String, Long> subjects = new LinkedHashMap<>();
        Map<String, Long> genres = new LinkedHashMap<>();
        Map<String, Long> tags = new LinkedHashMap<>();
        Map<String, Long> keywords = new LinkedHashMap<>();
        Map<String, Long> decades = new LinkedHashMap<>();
        Map<String, Coded> persons = new LinkedHashMap<>();
        Map<String, Coded> projects = new LinkedHashMap<>();

        for (GuestMediaHitDTO hit : hits) {
            bump(languages, hit.getLanguage());
            bump(dialects, hit.getDialect());
            bump(regions, hit.getRegion());
            bumpAll(subjects, hit.getSubject());
            bumpAll(genres, hit.getGenre());
            bumpAll(tags, hit.getTags());
            bumpAll(keywords, hit.getKeywords());

            Instant when = when(hit);
            if (when != null) {
                int year = when.atZone(ZoneOffset.UTC).getYear();
                bump(decades, (year / 10 * 10) + "s");
            }

            GuestPersonSummaryDTO person = hit.getPerson();
            if (person != null && person.getPersonCode() != null) {
                String label = GuestMediaHitMapper.firstNonBlank(
                        person.getFullName(), person.getNickname(),
                        person.getRomanizedName(), person.getPersonCode());
                persons.computeIfAbsent(person.getPersonCode(),
                        c -> new Coded(c, label)).count++;
            }
            if (hit.getProjectCode() != null) {
                String label = GuestMediaHitMapper.firstNonBlank(hit.getProjectName(), hit.getProjectCode());
                projects.computeIfAbsent(hit.getProjectCode(), c -> new Coded(c, label)).count++;
            }
        }

        return GuestMediaSearchDTO.Facets.builder()
                .languages(buckets(languages))
                .dialects(buckets(dialects))
                .regions(buckets(regions))
                .subjects(buckets(subjects))
                .genres(buckets(genres))
                .tags(buckets(tags))
                .keywords(buckets(keywords))
                .decades(sortedDecades(decades))
                .persons(codedBuckets(persons))
                .projects(codedBuckets(projects))
                .build();
    }

    /** A facet bucket that keeps a stable filter code next to its display label. */
    private static final class Coded {
        private final String code;
        private final String label;
        private long count;

        private Coded(String code, String label) {
            this.code = code;
            this.label = label;
        }
    }

    private static void bump(Map<String, Long> map, String value) {
        String v = GuestMediaHitMapper.blankToNull(value);
        if (v != null) map.merge(v, 1L, Long::sum);
    }

    private static void bumpAll(Map<String, Long> map, List<String> values) {
        if (values == null) return;
        for (String v : values) bump(map, v);
    }

    private static List<GuestFacetsDTO.Bucket> buckets(Map<String, Long> map) {
        return map.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, Long>>comparingLong(e -> -e.getValue())
                        .thenComparing(Map.Entry::getKey, String.CASE_INSENSITIVE_ORDER))
                .limit(FACET_BUCKET_LIMIT)
                .map(e -> GuestFacetsDTO.Bucket.builder().label(e.getKey()).count(e.getValue()).build())
                .toList();
    }

    private static List<GuestFacetsDTO.Bucket> codedBuckets(Map<String, Coded> map) {
        return map.values().stream()
                .sorted(Comparator.<Coded>comparingLong(c -> -c.count)
                        .thenComparing(c -> c.label, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .limit(FACET_BUCKET_LIMIT)
                .map(c -> GuestFacetsDTO.Bucket.builder()
                        .code(c.code).label(c.label).count(c.count).build())
                .toList();
    }

    /** Decades read as a timeline, so they are ordered oldest → newest, not by count. */
    private static List<GuestFacetsDTO.Bucket> sortedDecades(Map<String, Long> map) {
        return map.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .limit(FACET_BUCKET_LIMIT)
                .map(e -> GuestFacetsDTO.Bucket.builder().label(e.getKey()).count(e.getValue()).build())
                .toList();
    }

    // ─── Paging + shaping helpers ─────────────────────────────────────────────

    private static GuestMediaSearchDTO.Section section(String kind,
                                                       List<GuestMediaHitDTO> all,
                                                       Set<String> selected,
                                                       int page,
                                                       int size) {
        List<GuestMediaHitDTO> source = selected.contains(kind) ? all : List.of();
        List<GuestMediaHitDTO> content = slice(source, page, size);
        int totalPages = totalPages(source.size(), size);
        return GuestMediaSearchDTO.Section.builder()
                .kind(kind)
                .content(content)
                .page(page)
                .size(size)
                .totalElements(source.size())
                .totalPages(totalPages)
                .numberOfElements(content.size())
                .first(page == 0)
                .last(page >= totalPages - 1)
                .empty(content.isEmpty())
                .build();
    }

    private static List<GuestMediaHitDTO> slice(List<GuestMediaHitDTO> source, int page, int size) {
        int from = (int) Math.min((long) page * size, source.size());
        int to = Math.min(from + size, source.size());
        return List.copyOf(source.subList(from, to));
    }

    private static int totalPages(int total, int size) {
        return total == 0 ? 0 : (total + size - 1) / size;
    }

    /**
     * Drops the attached kind-specific DTOs. They are always populated while
     * scoring — the scorer reads fields the card does not carry — and removed
     * again unless the caller asked for {@code include=full}.
     */
    static void stripFullPayload(List<GuestMediaHitDTO> hits) {
        for (GuestMediaHitDTO hit : hits) {
            hit.setAudio(null);
            hit.setVideo(null);
            hit.setImage(null);
            hit.setText(null);
        }
    }

    // ─── Parameter parsing ────────────────────────────────────────────────────

    private static int clampSize(Integer size) {
        if (size == null || size <= 0) return DEFAULT_PAGE_SIZE;
        return Math.min(size, MAX_PAGE_SIZE);
    }

    /**
     * Reads the {@code type} selection, accepting repeats, comma lists and the
     * public aliases. Unknown or absent values mean "all four kinds" rather
     * than "nothing", so a typo degrades to a wider search, never to an empty
     * page.
     */
    static Set<String> parseTypes(List<String> in) {
        if (in == null || in.isEmpty()) return Set.copyOf(TYPE_ORDER);
        Set<String> out = new LinkedHashSet<>();
        for (String raw : in) {
            if (raw == null) continue;
            for (String value : raw.split(",")) {
                switch (value.trim().toLowerCase(Locale.ROOT)) {
                    case "audio", "audios", "sound", "sounds" -> out.add(TYPE_AUDIO);
                    case "video", "videos"                    -> out.add(TYPE_VIDEO);
                    case "image", "images", "photo", "photos" -> out.add(TYPE_IMAGE);
                    case "text", "texts", "file", "files",
                         "document", "documents"              -> out.add(TYPE_TEXT);
                    case "all", ""                            -> out.addAll(TYPE_ORDER);
                    default -> { /* unknown kind — ignored */ }
                }
            }
        }
        return out.isEmpty() ? Set.copyOf(TYPE_ORDER) : out;
    }

    /** Normalizes a single path/segment type, including the public aliases. */
    static String normalizeType(String type) {
        if (type == null) return null;
        return switch (type.trim().toLowerCase(Locale.ROOT)) {
            case "audio", "audios", "sound", "sounds" -> TYPE_AUDIO;
            case "video", "videos"                    -> TYPE_VIDEO;
            case "image", "images", "photo", "photos" -> TYPE_IMAGE;
            case "text", "texts", "file", "files",
                 "document", "documents"              -> TYPE_TEXT;
            default -> null;
        };
    }

    /** Echoes the selection back as {@code all} or a comma list, in public order. */
    private static String describeSelection(Set<String> selected) {
        if (selected.size() == TYPE_ORDER.size()) return "all";
        return TYPE_ORDER.stream().filter(selected::contains).reduce((a, b) -> a + "," + b).orElse("all");
    }

    /**
     * Parses {@code 1970} or {@code 1970s} into an inclusive year range.
     * Returns null for anything else, so a bad value widens rather than empties.
     */
    static int[] parseDecade(String decade) {
        String v = trimToNull(decade);
        if (v == null) return null;
        String digits = v.toLowerCase(Locale.ROOT).endsWith("s") ? v.substring(0, v.length() - 1) : v;
        try {
            int year = Integer.parseInt(digits.trim());
            if (year < 1 || year > 9999) return null;
            int start = year / 10 * 10;
            return new int[]{start, start + 9};
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * Parses a "from" date. Accepts an ISO instant, an ISO local date-time
     * (read as UTC), or a plain ISO date (start of that day, UTC). Invalid
     * input yields null so a malformed param widens the search instead of
     * failing the request — the same lenience {@code GuestSearchAPI} applies.
     */
    static Instant parseStart(String s) {
        String t = trimToNull(s);
        if (t == null) return null;
        try { return Instant.parse(t); } catch (DateTimeParseException ignored) { /* try next */ }
        try { return LocalDateTime.parse(t).toInstant(ZoneOffset.UTC); } catch (DateTimeParseException ignored) { /* try next */ }
        try { return LocalDate.parse(t).atStartOfDay(ZoneOffset.UTC).toInstant(); } catch (DateTimeParseException ignored) { /* give up */ }
        return null;
    }

    /** As {@link #parseStart}, but a plain date covers the whole day. */
    static Instant parseEnd(String s) {
        String t = trimToNull(s);
        if (t == null) return null;
        try { return Instant.parse(t); } catch (DateTimeParseException ignored) { /* try next */ }
        try { return LocalDateTime.parse(t).toInstant(ZoneOffset.UTC); } catch (DateTimeParseException ignored) { /* try next */ }
        try {
            return LocalDate.parse(t).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().minusNanos(1);
        } catch (DateTimeParseException ignored) { /* give up */ }
        return null;
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String trimToEmpty(String s) {
        return s == null ? "" : s.trim();
    }
}
