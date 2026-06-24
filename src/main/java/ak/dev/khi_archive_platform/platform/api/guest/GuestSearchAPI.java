package ak.dev.khi_archive_platform.platform.api.guest;

import ak.dev.khi_archive_platform.platform.dto.guest.GuestAudioDTO;
import ak.dev.khi_archive_platform.platform.dto.guest.GuestCategoryDTO;
import ak.dev.khi_archive_platform.platform.dto.guest.GuestFacetsDTO;
import ak.dev.khi_archive_platform.platform.dto.guest.GuestGlobalSearchDTO;
import ak.dev.khi_archive_platform.platform.dto.guest.GuestImageDTO;
import ak.dev.khi_archive_platform.platform.dto.guest.GuestMediaFeedDTO;
import ak.dev.khi_archive_platform.platform.dto.guest.GuestPersonDTO;
import ak.dev.khi_archive_platform.platform.dto.guest.GuestProjectDTO;
import ak.dev.khi_archive_platform.platform.dto.guest.GuestSuggestionDTO;
import ak.dev.khi_archive_platform.platform.dto.guest.GuestTextDTO;
import ak.dev.khi_archive_platform.platform.dto.guest.GuestTrendingDTO;
import ak.dev.khi_archive_platform.platform.dto.guest.GuestVideoDTO;
import ak.dev.khi_archive_platform.platform.enums.Gender;
import ak.dev.khi_archive_platform.platform.service.guest.GuestSearchService;
import ak.dev.khi_archive_platform.platform.service.guest.GuestTrendingService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * Public read-only API for unauthenticated visitors. Mirrors KCAC's
 * /browse experience: a single global-search entry point, per-entity
 * filterable listings, and faceted aggregates for sidebar checkboxes.
 *
 * <p>Every response goes through {@code Guest…DTO} so technical fields
 * (path/volume/directory, lcc-classification, bit-rate / bit-depth /
 * sample-rate / file-size, version internals, audit/trash bookkeeping)
 * never reach the public.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/guest")
public class GuestSearchAPI {

    private final GuestSearchService  guestSearchService;
    private final GuestTrendingService guestTrendingService;

    // ─── Trending ─────────────────────────────────────────────────────────────────

    /**
     * Returns the current trending items and top searches.
     * Cached server-side for 5 minutes. Call on every page load.
     *
     * Frontend usage:
     *   trendingItems[0..4]        → "Trending Now" hero carousel
     *   trendingItems with rank<=10 → show "🔥 Trending" badge on any card
     *   trendingByType.audio[0..4] → "Trending Audios" horizontal row
     *   topSearches[0..9]          → "Popular Searches" tag cloud / chips
     *   generatedAt                → "Updated X minutes ago" tooltip
     */
    @GetMapping("/trending")
    public ResponseEntity<GuestTrendingDTO> trending() {
        return ResponseEntity.ok(guestTrendingService.getTrending());
    }

    // ─── Universal search ─────────────────────────────────────────────────────────

    /**
     * Single-call cross-entity search. Returns the top-N projects, categories,
     * persons, audios, videos, texts, and images that match {@code q}, plus
     * a per-section count for headline UI.
     */
    @GetMapping("/search")
    public ResponseEntity<GuestGlobalSearchDTO> globalSearch(
            @RequestParam("q") String q,
            @RequestParam(value = "perSection", required = false) Integer perSection
    ) {
        return ResponseEntity.ok(guestSearchService.globalSearch(q, perSection));
    }

    /** Autocomplete — titles, names, codes across every entity. */
    @GetMapping("/suggest")
    public ResponseEntity<List<GuestSuggestionDTO>> suggest(
            @RequestParam("q") String q,
            @RequestParam(value = "limit", required = false) Integer limit
    ) {
        return ResponseEntity.ok(guestSearchService.suggest(q, limit));
    }

    /** Sidebar facet counts — drives KCAC-style filter checkboxes. */
    @GetMapping("/facets")
    public ResponseEntity<GuestFacetsDTO> facets() {
        return ResponseEntity.ok(guestSearchService.facets());
    }

    // ─── Grouped media feed ──────────────────────────────────────────────────────

    /**
     * Grouped media feed across the four public media kinds — the recommended
     * browse-and-search endpoint for the public site.
     *
     * <p>The response is grouped by media kind in the exact public order:
     * <em>photos → sounds → videos → texts</em>. Each section contains the
     * same full DTO shape returned by its own endpoint
     * ({@code /images}, {@code /audios}, {@code /videos}, {@code /texts}),
     * so the frontend does not lose media-specific fields.
     *
     * <p><strong>The feed is media-only.</strong> It returns
     * <em>image, audio, video and text</em> sections — projects and persons
     * are NOT included here (browse those via {@code /projects} and
     * {@code /persons}). Per-type endpoints stay around for pages that need
     * media-specific filters (singer, ISBN, frame rate, …).
     *
     * <p><strong>Pagination.</strong> The shared {@code page}/{@code size}
     * request is applied independently to every selected section. For example,
     * {@code size=12} returns up to 12 images, 12 audios, 12 videos, and
     * 12 texts. This guarantees every media kind can appear on the public
     * page instead of one large kind hiding the others.
     *
     * <p>Filters supported (all optional):
     * <ul>
     *   <li>{@code q} — free text matched against titles, codes,
     *       description, project name, person name, tags, keywords,
     *       subjects, genres on every kind.</li>
     *   <li>{@code types} — which media kinds to include
     *       ({@code image | video | audio | text}; aliases {@code photo},
     *       {@code sound} accepted; repeat or omit for all four). Any
     *       {@code project}/{@code person} value is ignored.</li>
     *   <li>Common scalars: {@code projectCode}, {@code categoryCode},
     *       {@code personCode}, {@code language}, {@code dialect},
     *       {@code region}.</li>
     *   <li>Multi-value collection filters: {@code subject},
     *       {@code genre}, {@code tag}, {@code keyword} (repeatable).</li>
     *   <li>{@code dateFrom} / {@code dateTo} — inclusive ISO date range
     *       on {@code dateCreated}.</li>
     *   <li>{@code sortBy}: {@code relevance} (default when q present)
     *       / {@code date} (default otherwise) / {@code datePublished} /
     *       {@code title}. {@code sortDirection}: {@code asc | desc}.
     *       Applied within each media-kind block.</li>
     * </ul>
     */
    @GetMapping("/feed")
    public ResponseEntity<GuestMediaFeedDTO> feed(
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "types", required = false) List<String> types,
            @RequestParam(value = "projectCode", required = false) String projectCode,
            @RequestParam(value = "categoryCode", required = false) String categoryCode,
            @RequestParam(value = "personCode", required = false) String personCode,
            @RequestParam(value = "language", required = false) String language,
            @RequestParam(value = "dialect", required = false) String dialect,
            @RequestParam(value = "region", required = false) String region,
            @RequestParam(value = "subject", required = false) List<String> subjects,
            @RequestParam(value = "genre", required = false) List<String> genres,
            @RequestParam(value = "tag", required = false) List<String> tags,
            @RequestParam(value = "keyword", required = false) List<String> keywords,
            @RequestParam(value = "dateFrom", required = false) String dateFrom,
            @RequestParam(value = "dateTo", required = false) String dateTo,
            @RequestParam(value = "sortBy", required = false) String sortBy,
            @RequestParam(value = "sortDirection", required = false) String sortDirection,
            @PageableDefault(size = 50) Pageable pageable
    ) {
        return ResponseEntity.ok(guestSearchService.feedAll(
                q, types, projectCode, categoryCode, personCode,
                language, dialect, region,
                subjects, genres, tags, keywords,
                parseStart(dateFrom), parseEnd(dateTo),
                sortBy, sortDirection, pageable));
    }

    // ─── Projects (collections) ───────────────────────────────────────────────────

    @GetMapping("/projects")
    public ResponseEntity<Page<GuestProjectDTO>> projects(
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "categoryCode", required = false) String categoryCode,
            @RequestParam(value = "personCode", required = false) String personCode,
            @RequestParam(value = "tag", required = false) List<String> tags,
            @RequestParam(value = "keyword", required = false) List<String> keywords,
            @RequestParam(value = "sortBy", required = false) String sortBy,
            @RequestParam(value = "sortDirection", required = false) String sortDirection,
            @PageableDefault(size = 50) Pageable pageable
    ) {
        return ResponseEntity.ok(guestSearchService.searchProjects(
                q, categoryCode, personCode, tags, keywords,
                sortBy, sortDirection, pageable));
    }

    @GetMapping("/projects/{projectCode}")
    public ResponseEntity<GuestProjectDTO> getProject(@PathVariable String projectCode) {
        return guestSearchService.getProjectByCode(projectCode)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * All media inside a project, optionally filtered by type
     * ({@code audio | video | text | image | all}).
     */
    @GetMapping("/projects/{projectCode}/media")
    public ResponseEntity<Map<String, Object>> getProjectMedia(
            @PathVariable String projectCode,
            @RequestParam(value = "type", required = false) String type
    ) {
        Map<String, Object> body = guestSearchService.getProjectMedia(projectCode, type);
        return body == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(body);
    }

    // ─── Categories ───────────────────────────────────────────────────────────────

    @GetMapping("/categories")
    public ResponseEntity<Page<GuestCategoryDTO>> categories(
            @RequestParam(value = "q", required = false) String q,
            @PageableDefault(size = 100) Pageable pageable
    ) {
        return ResponseEntity.ok(guestSearchService.searchCategories(q, pageable));
    }

    @GetMapping("/categories/{categoryCode}")
    public ResponseEntity<GuestCategoryDTO> getCategory(@PathVariable String categoryCode) {
        return guestSearchService.getCategoryByCode(categoryCode)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/categories/{categoryCode}/projects")
    public ResponseEntity<Page<GuestProjectDTO>> getCategoryProjects(
            @PathVariable String categoryCode,
            @PageableDefault(size = 50) Pageable pageable
    ) {
        return ResponseEntity.ok(guestSearchService.getCategoryProjects(categoryCode, pageable));
    }

    // ─── Persons ──────────────────────────────────────────────────────────────────

    @GetMapping("/persons")
    public ResponseEntity<Page<GuestPersonDTO>> persons(
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "region", required = false) String region,
            @RequestParam(value = "gender", required = false) Gender gender,
            @RequestParam(value = "personType", required = false) List<String> personTypes,
            @PageableDefault(size = 50) Pageable pageable
    ) {
        return ResponseEntity.ok(guestSearchService.searchPersons(q, region, gender, personTypes, pageable));
    }

    @GetMapping("/persons/{personCode}")
    public ResponseEntity<GuestPersonDTO> getPerson(@PathVariable String personCode) {
        return guestSearchService.getPersonByCode(personCode)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/persons/{personCode}/projects")
    public ResponseEntity<Page<GuestProjectDTO>> getPersonProjects(
            @PathVariable String personCode,
            @PageableDefault(size = 50) Pageable pageable
    ) {
        return ResponseEntity.ok(guestSearchService.getPersonProjects(personCode, pageable));
    }

    // ─── Audios ───────────────────────────────────────────────────────────────────

    @GetMapping("/audios")
    public ResponseEntity<Page<GuestAudioDTO>> audios(
            // ── Common filters (all media) ───────────────────────────────────
            @RequestParam(value = "q",            required = false) String q,
            @RequestParam(value = "projectCode",  required = false) String projectCode,
            @RequestParam(value = "categoryCode", required = false) String categoryCode,
            @RequestParam(value = "personCode",   required = false) String personCode,
            @RequestParam(value = "language",     required = false) String language,
            @RequestParam(value = "dialect",      required = false) String dialect,
            @RequestParam(value = "region",       required = false) String region,
            @RequestParam(value = "subject",      required = false) List<String> subjects,
            @RequestParam(value = "genre",        required = false) List<String> genres,
            @RequestParam(value = "tag",          required = false) List<String> tags,
            @RequestParam(value = "keyword",      required = false) List<String> keywords,
            @RequestParam(value = "dateFrom",     required = false) String dateFrom,
            @RequestParam(value = "dateTo",       required = false) String dateTo,
            // ── Audio-specific filters ───────────────────────────────────────
            @RequestParam(value = "singer",          required = false) String singer,
            @RequestParam(value = "speaker",         required = false) String speaker,
            @RequestParam(value = "poet",            required = false) String poet,
            @RequestParam(value = "composer",        required = false) String composer,
            @RequestParam(value = "producer",        required = false) String producer,
            @RequestParam(value = "contributor",     required = false) List<String> contributors,
            @RequestParam(value = "form",            required = false) String form,
            @RequestParam(value = "lyrics",          required = false) String lyrics,
            @RequestParam(value = "typeOfBasta",     required = false) String typeOfBasta,
            @RequestParam(value = "typeOfMaqam",     required = false) String typeOfMaqam,
            @RequestParam(value = "typeOfComposition",  required = false) String typeOfComposition,
            @RequestParam(value = "typeOfPerformance",  required = false) String typeOfPerformance,
            @RequestParam(value = "recordingVenue",  required = false) String recordingVenue,
            @RequestParam(value = "city",            required = false) String city,
            @RequestParam(value = "audience",        required = false) String audience,
            @RequestParam(value = "publishedFrom",   required = false) String publishedFrom,
            @RequestParam(value = "publishedTo",     required = false) String publishedTo,
            // ── Sorting / paging ────────────────────────────────────────────
            @RequestParam(value = "sortBy",        required = false) String sortBy,
            @RequestParam(value = "sortDirection", required = false) String sortDirection,
            @PageableDefault(size = 50) Pageable pageable
    ) {
        return ResponseEntity.ok(guestSearchService.searchAudios(
                q, projectCode, categoryCode, personCode, language, dialect,
                form, typeOfBasta, typeOfMaqam,
                typeOfComposition, typeOfPerformance,
                composer, producer, speaker, singer, poet, contributors,
                recordingVenue, city, region, audience, lyrics,
                subjects, genres, tags, keywords,
                parseStart(dateFrom), parseEnd(dateTo),
                parseStart(publishedFrom), parseEnd(publishedTo),
                sortBy, sortDirection, pageable));
    }

    @GetMapping("/audios/{audioCode}")
    public ResponseEntity<GuestAudioDTO> getAudio(@PathVariable String audioCode) {
        return guestSearchService.getAudioByCode(audioCode)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ─── Videos ───────────────────────────────────────────────────────────────────

    @GetMapping("/videos")
    public ResponseEntity<Page<GuestVideoDTO>> videos(
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "projectCode", required = false) String projectCode,
            @RequestParam(value = "categoryCode", required = false) String categoryCode,
            @RequestParam(value = "personCode", required = false) String personCode,
            @RequestParam(value = "language", required = false) String language,
            @RequestParam(value = "dialect", required = false) String dialect,
            @RequestParam(value = "region", required = false) String region,
            @RequestParam(value = "event", required = false) String event,
            @RequestParam(value = "location", required = false) String location,
            @RequestParam(value = "creatorArtistDirector", required = false) String creatorArtistDirector,
            @RequestParam(value = "producer", required = false) String producer,
            @RequestParam(value = "contributor", required = false) String contributor,
            @RequestParam(value = "personShownInVideo", required = false) String personShownInVideo,
            @RequestParam(value = "subtitle", required = false) String subtitle,
            @RequestParam(value = "audience", required = false) String audience,
            @RequestParam(value = "provenance", required = false) String provenance,
            @RequestParam(value = "videoStatus", required = false) String videoStatus,
            @RequestParam(value = "publisher", required = false) String publisher,
            @RequestParam(value = "subject", required = false) List<String> subjects,
            @RequestParam(value = "genre", required = false) List<String> genres,
            @RequestParam(value = "color", required = false) List<String> colors,
            @RequestParam(value = "whereUsed", required = false) List<String> whereUsed,
            @RequestParam(value = "tag", required = false) List<String> tags,
            @RequestParam(value = "keyword", required = false) List<String> keywords,
            @RequestParam(value = "dateFrom", required = false) String dateFrom,
            @RequestParam(value = "dateTo", required = false) String dateTo,
            @RequestParam(value = "publishedFrom", required = false) String publishedFrom,
            @RequestParam(value = "publishedTo", required = false) String publishedTo,
            @RequestParam(value = "sortBy", required = false) String sortBy,
            @RequestParam(value = "sortDirection", required = false) String sortDirection,
            @PageableDefault(size = 50) Pageable pageable
    ) {
        return ResponseEntity.ok(guestSearchService.searchVideos(
                q, projectCode, categoryCode, personCode, language, dialect, region,
                event, location,
                creatorArtistDirector, producer, contributor, personShownInVideo, subtitle, audience,
                provenance, videoStatus, publisher,
                subjects, genres, colors, whereUsed, tags, keywords,
                parseStart(dateFrom), parseEnd(dateTo),
                parseStart(publishedFrom), parseEnd(publishedTo),
                sortBy, sortDirection, pageable));
    }

    @GetMapping("/videos/{videoCode}")
    public ResponseEntity<GuestVideoDTO> getVideo(@PathVariable String videoCode) {
        return guestSearchService.getVideoByCode(videoCode)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ─── Texts ────────────────────────────────────────────────────────────────────

    @GetMapping("/texts")
    public ResponseEntity<Page<GuestTextDTO>> texts(
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "projectCode", required = false) String projectCode,
            @RequestParam(value = "categoryCode", required = false) String categoryCode,
            @RequestParam(value = "personCode", required = false) String personCode,
            @RequestParam(value = "language", required = false) String language,
            @RequestParam(value = "dialect", required = false) String dialect,
            @RequestParam(value = "region", required = false) String region,
            @RequestParam(value = "documentType", required = false) String documentType,
            @RequestParam(value = "isbn", required = false) String isbn,
            @RequestParam(value = "author", required = false) String author,
            @RequestParam(value = "contributors", required = false) String contributors,
            @RequestParam(value = "script", required = false) String script,
            @RequestParam(value = "series", required = false) String series,
            @RequestParam(value = "edition", required = false) String edition,
            @RequestParam(value = "volume", required = false) String volume,
            @RequestParam(value = "printingHouse", required = false) String printingHouse,
            @RequestParam(value = "audience", required = false) String audience,
            @RequestParam(value = "provenance", required = false) String provenance,
            @RequestParam(value = "publisher", required = false) String publisher,
            @RequestParam(value = "subject", required = false) List<String> subjects,
            @RequestParam(value = "genre", required = false) List<String> genres,
            @RequestParam(value = "tag", required = false) List<String> tags,
            @RequestParam(value = "keyword", required = false) List<String> keywords,
            @RequestParam(value = "dateFrom", required = false) String dateFrom,
            @RequestParam(value = "dateTo", required = false) String dateTo,
            @RequestParam(value = "publishedFrom", required = false) String publishedFrom,
            @RequestParam(value = "publishedTo", required = false) String publishedTo,
            @RequestParam(value = "printDateFrom", required = false) String printDateFrom,
            @RequestParam(value = "printDateTo", required = false) String printDateTo,
            @RequestParam(value = "sortBy", required = false) String sortBy,
            @RequestParam(value = "sortDirection", required = false) String sortDirection,
            @PageableDefault(size = 50) Pageable pageable
    ) {
        return ResponseEntity.ok(guestSearchService.searchTexts(
                q, projectCode, categoryCode, personCode, language, dialect, region,
                documentType, isbn, author,
                contributors, script, series, edition, volume, printingHouse, audience,
                provenance, publisher,
                subjects, genres, tags, keywords,
                parseStart(dateFrom), parseEnd(dateTo),
                parseStart(publishedFrom), parseEnd(publishedTo),
                parseStart(printDateFrom), parseEnd(printDateTo),
                sortBy, sortDirection, pageable));
    }

    @GetMapping("/texts/{textCode}")
    public ResponseEntity<GuestTextDTO> getText(@PathVariable String textCode) {
        return guestSearchService.getTextByCode(textCode)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ─── Images ───────────────────────────────────────────────────────────────────

    @GetMapping("/images")
    public ResponseEntity<Page<GuestImageDTO>> images(
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "projectCode", required = false) String projectCode,
            @RequestParam(value = "categoryCode", required = false) String categoryCode,
            @RequestParam(value = "personCode", required = false) String personCode,
            @RequestParam(value = "language", required = false) String language,
            @RequestParam(value = "dialect", required = false) String dialect,
            @RequestParam(value = "region", required = false) String region,
            @RequestParam(value = "event", required = false) String event,
            @RequestParam(value = "location", required = false) String location,
            @RequestParam(value = "creatorArtistPhotographer", required = false) String creatorArtistPhotographer,
            @RequestParam(value = "contributor", required = false) String contributor,
            @RequestParam(value = "personShownInImage", required = false) String personShownInImage,
            @RequestParam(value = "audience", required = false) String audience,
            @RequestParam(value = "provenance", required = false) String provenance,
            @RequestParam(value = "photostory", required = false) String photostory,
            @RequestParam(value = "imageStatus", required = false) String imageStatus,
            @RequestParam(value = "subject", required = false) List<String> subjects,
            @RequestParam(value = "genre", required = false) List<String> genres,
            @RequestParam(value = "color", required = false) List<String> colors,
            @RequestParam(value = "whereUsed", required = false) List<String> whereUsed,
            @RequestParam(value = "tag", required = false) List<String> tags,
            @RequestParam(value = "keyword", required = false) List<String> keywords,
            @RequestParam(value = "dateFrom", required = false) String dateFrom,
            @RequestParam(value = "dateTo", required = false) String dateTo,
            @RequestParam(value = "publishedFrom", required = false) String publishedFrom,
            @RequestParam(value = "publishedTo", required = false) String publishedTo,
            @RequestParam(value = "sortBy", required = false) String sortBy,
            @RequestParam(value = "sortDirection", required = false) String sortDirection,
            @PageableDefault(size = 50) Pageable pageable
    ) {
        return ResponseEntity.ok(guestSearchService.searchImages(
                q, projectCode, categoryCode, personCode,
                language, dialect, region,
                event, location,
                creatorArtistPhotographer, contributor, personShownInImage,
                audience, provenance, photostory, imageStatus,
                subjects, genres, colors, whereUsed, tags, keywords,
                parseStart(dateFrom), parseEnd(dateTo),
                parseStart(publishedFrom), parseEnd(publishedTo),
                sortBy, sortDirection, pageable));
    }

    @GetMapping("/images/{imageCode}")
    public ResponseEntity<GuestImageDTO> getImage(@PathVariable String imageCode) {
        return guestSearchService.getImageByCode(imageCode)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ─── Lenient date parsing ─────────────────────────────────────────────────────

    /**
     * Parses a "from" date param. Accepts either an ISO instant
     * ({@code 2020-01-01T00:00:00Z}), an ISO local date-time
     * ({@code 2020-01-01T00:00:00}, treated as UTC), or a plain ISO date
     * ({@code 2020-01-01}, snapped to start-of-day UTC). Returns {@code null}
     * for blank input. Invalid input falls through to {@code null} so a
     * malformed param doesn't 400 the whole request.
     */
    private static Instant parseStart(String s) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        try { return Instant.parse(t); } catch (DateTimeParseException ignored) {}
        try { return LocalDateTime.parse(t).toInstant(ZoneOffset.UTC); } catch (DateTimeParseException ignored) {}
        try { return LocalDate.parse(t).atStartOfDay(ZoneOffset.UTC).toInstant(); } catch (DateTimeParseException ignored) {}
        return null;
    }

    /**
     * Parses a "to" date param. Same accepted formats as {@link #parseStart},
     * but a plain ISO date is snapped to the very last nanosecond of that day
     * in UTC so the range filter is inclusive of the named day.
     */
    private static Instant parseEnd(String s) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        try { return Instant.parse(t); } catch (DateTimeParseException ignored) {}
        try { return LocalDateTime.parse(t).toInstant(ZoneOffset.UTC); } catch (DateTimeParseException ignored) {}
        try {
            return LocalDate.parse(t).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().minusNanos(1);
        } catch (DateTimeParseException ignored) {}
        return null;
    }
}
