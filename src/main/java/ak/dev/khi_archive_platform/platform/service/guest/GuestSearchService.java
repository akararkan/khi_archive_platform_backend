package ak.dev.khi_archive_platform.platform.service.guest;

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
import ak.dev.khi_archive_platform.platform.dto.guest.GuestVideoDTO;
import ak.dev.khi_archive_platform.platform.enums.Gender;
import ak.dev.khi_archive_platform.platform.model.audio.Audio;
import ak.dev.khi_archive_platform.platform.model.category.Category;
import ak.dev.khi_archive_platform.platform.model.image.Image;
import ak.dev.khi_archive_platform.platform.model.person.Person;
import ak.dev.khi_archive_platform.platform.model.project.Project;
import ak.dev.khi_archive_platform.platform.model.text.Text;
import ak.dev.khi_archive_platform.platform.model.video.Video;
import ak.dev.khi_archive_platform.platform.repo.audio.AudioRepository;
import ak.dev.khi_archive_platform.platform.repo.category.CategoryRepository;
import ak.dev.khi_archive_platform.platform.repo.image.ImageRepository;
import ak.dev.khi_archive_platform.platform.repo.person.PersonRepository;
import ak.dev.khi_archive_platform.platform.repo.project.ProjectRepository;
import ak.dev.khi_archive_platform.platform.repo.text.TextRepository;
import ak.dev.khi_archive_platform.platform.repo.video.VideoRepository;
import ak.dev.khi_archive_platform.platform.service.common.MediaSearchSqlBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Read-only search/filter facade for guest (unauthenticated) users.
 *
 * <h3>Algorithm</h3>
 * <ul>
 *   <li>Keyword search delegates to entity-level pg_trgm/LIKE native queries
 *       already used by authenticated paths — see each repo's
 *       {@code searchByText}. They prefilter to {@link #PREFILTER_LIMIT}
 *       candidates then rank by best-tier match.</li>
 *   <li>Structured filters (categoryCode, personCode, language, dialect,
 *       date ranges, tag/keyword/genre/subject containment) are applied
 *       in-memory after the keyword phase because the candidate set is
 *       already bounded. With no q at all, structured filters apply over
 *       the full active set returned by {@code findAllByRemovedAtIsNull},
 *       which the entity-level read caches keep warm.</li>
 *   <li>All in-memory predicates are case-insensitive on already-lowered
 *       needles; collection match is zero-allocation on the "any" path.</li>
 * </ul>
 *
 * <h3>What's hidden from guests</h3>
 * Every shape returned here is a {@code Guest…DTO} — see
 * {@link GuestMapper}. We strip path/volume/directory/auto-path,
 * lcc-classification, bit-rate / bit-depth / sample-rate / file-size,
 * version internals, and audit/trash bookkeeping (createdBy, removedAt,
 * version) so general visitors see only descriptive metadata.
 */
@Service
@RequiredArgsConstructor
public class GuestSearchService {

    private final ProjectRepository projectRepository;
    private final CategoryRepository categoryRepository;
    private final PersonRepository personRepository;
    private final AudioRepository audioRepository;
    private final VideoRepository videoRepository;
    private final TextRepository textRepository;
    private final GuestTrendingService trendingService;
    private final ImageRepository imageRepository;

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 500;
    static final int PREFILTER_LIMIT = 5000;
    static final double SIMILARITY_THRESHOLD = 0.2;
    static final int GLOBAL_SECTION_LIMIT = 10;
    static final int SUGGEST_DEFAULT_LIMIT = 10;
    static final int SUGGEST_MAX_LIMIT = 50;
    static final int FACET_BUCKET_LIMIT = 50;

    // ─── Global search ────────────────────────────────────────────────────────────

    /**
     * One call, every section. Each section is independently capped so the
     * payload stays bounded even on common queries.
     */
    @Transactional(readOnly = true)
    public GuestGlobalSearchDTO globalSearch(String q, Integer perSection) {
        String norm = normalize(q);
        int sectionLimit = clamp(perSection, GLOBAL_SECTION_LIMIT, MAX_LIMIT);
        if (norm == null) {
            return GuestGlobalSearchDTO.builder().query("").build();
        }
        trendingService.logSearch(norm);
        String like = MediaSearchSqlBuilder.escapeLike(norm);

        List<Project> projectHits = projectRepository.searchByText(norm, SIMILARITY_THRESHOLD, sectionLimit)
                .stream().filter(GuestSearchService::isProjectPubliclyVisible).toList();
        List<Category> categoryHits = categoryRepository.searchByText(norm, SIMILARITY_THRESHOLD, sectionLimit);
        List<Person> personHits = personRepository.searchByText(norm, SIMILARITY_THRESHOLD, sectionLimit);
        List<Project> scope = resolveProjectScope(norm);
        List<Audio> audioHits = capById(widenAudios(
                audioRepository.searchByText(like, norm, PREFILTER_LIMIT, sectionLimit), scope), sectionLimit)
                .stream().filter(GuestSearchService::isPubliclyVisible).toList();
        List<Video> videoHits = capById(widenVideos(
                videoRepository.searchByText(like, norm, PREFILTER_LIMIT, sectionLimit), scope), sectionLimit)
                .stream().filter(GuestSearchService::isPubliclyVisible).toList();
        List<Text> textHits = capById(widenTexts(
                textRepository.searchByText(like, norm, PREFILTER_LIMIT, sectionLimit), scope), sectionLimit)
                .stream().filter(GuestSearchService::isPubliclyVisible).toList();
        List<Image> imageHits = capById(widenImages(
                imageRepository.searchByText(like, norm, PREFILTER_LIMIT, sectionLimit), scope), sectionLimit)
                .stream().filter(GuestSearchService::isPubliclyVisible).toList();

        return GuestGlobalSearchDTO.builder()
                .query(norm)
                .projects(section(projectHits, p -> GuestMapper.toProject(p, mediaCounts(p))))
                .categories(section(categoryHits, c -> GuestMapper.toCategory(c, publicProjectCount(c))))
                .persons(section(personHits, p -> GuestMapper.toPerson(p, publicProjectCount(p))))
                .audios(section(audioHits, GuestMapper::toAudio))
                .videos(section(videoHits, GuestMapper::toVideo))
                .texts(section(textHits, GuestMapper::toText))
                .images(section(imageHits, GuestMapper::toImage))
                .build();
    }

    private static <S, T> GuestGlobalSearchDTO.Section<T> section(
            List<S> rows, java.util.function.Function<S, T> mapper) {
        List<T> mapped = rows.stream().map(mapper).toList();
        return GuestGlobalSearchDTO.Section.<T>builder()
                .total(mapped.size())
                .items(mapped)
                .build();
    }

    // ─── Projects ─────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<GuestProjectDTO> searchProjects(String q,
                                                String categoryCode,
                                                String personCode,
                                                List<String> tags,
                                                List<String> keywords,
                                                String sortBy,
                                                String sortDirection,
                                                Pageable pageable) {
        List<Project> source = (normalize(q) == null)
                ? projectRepository.findAllByRemovedAtIsNull()
                : projectRepository.searchByText(normalize(q), SIMILARITY_THRESHOLD, MAX_LIMIT);

        String wantedCategory = lower(categoryCode);
        String wantedPerson = lower(personCode);
        var wantedTags = lowerSet(tags);
        var wantedKeywords = lowerSet(keywords);

        List<Project> filtered = new ArrayList<>(source.size());
        for (Project p : source) {
            if (!isProjectPubliclyVisible(p)) continue;
            if (wantedPerson != null) {
                Person ps = p.getPerson();
                if (ps == null || ps.getPersonCode() == null
                        || !ps.getPersonCode().toLowerCase(Locale.ROOT).equals(wantedPerson)) continue;
            }
            if (wantedCategory != null) {
                List<Category> cats = p.getCategories();
                if (cats == null || cats.isEmpty()) continue;
                boolean any = false;
                for (Category c : cats) {
                    if (c.getCategoryCode() != null
                            && c.getCategoryCode().toLowerCase(Locale.ROOT).equals(wantedCategory)) {
                        any = true;
                        break;
                    }
                }
                if (!any) continue;
            }
            if (!wantedTags.isEmpty() && !anyMatch(p.getTags(), wantedTags)) continue;
            if (!wantedKeywords.isEmpty() && !anyMatch(p.getKeywords(), wantedKeywords)) continue;
            filtered.add(p);
        }

        Comparator<Project> cmp = projectComparator(sortBy);
        if (cmp != null) {
            if ("desc".equalsIgnoreCase(sortDirection)) cmp = cmp.reversed();
            filtered.sort(cmp);
        }

        return stampPage(paginate(filtered, pageable, p -> GuestMapper.toProject(p, mediaCounts(p))),
                "project", GuestProjectDTO::getProjectCode, GuestSearchService::applyMark);
    }

    @Transactional(readOnly = true)
    public Optional<GuestProjectDTO> getProjectByCode(String projectCode) {
        return projectRepository.findByProjectCodeAndRemovedAtIsNull(projectCode)
                .filter(GuestSearchService::isProjectPubliclyVisible)
                .map(p -> {
                    trendingService.logView("project", projectCode);
                    return GuestMapper.toProject(p, mediaCounts(p));
                });
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getProjectMedia(String projectCode, String type) {
        Project project = projectRepository.findByProjectCodeAndRemovedAtIsNull(projectCode).orElse(null);
        if (!isProjectPubliclyVisible(project)) return null;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectCode", project.getProjectCode());
        out.put("projectName", project.getProjectName());

        boolean all = type == null || type.isBlank() || "all".equalsIgnoreCase(type);
        if (all || "audio".equalsIgnoreCase(type) || "audios".equalsIgnoreCase(type)) {
            out.put("audios", audioRepository.findAllByProjectAndRemovedAtIsNull(project)
                    .stream().filter(GuestSearchService::isPubliclyVisible)
                    .map(GuestMapper::toAudio).toList());
        }
        if (all || "video".equalsIgnoreCase(type) || "videos".equalsIgnoreCase(type)) {
            out.put("videos", videoRepository.findAllByProjectAndRemovedAtIsNull(project)
                    .stream().filter(GuestSearchService::isPubliclyVisible)
                    .map(GuestMapper::toVideo).toList());
        }
        if (all || "text".equalsIgnoreCase(type) || "texts".equalsIgnoreCase(type)) {
            out.put("texts", textRepository.findAllByProjectAndRemovedAtIsNull(project)
                    .stream().filter(GuestSearchService::isPubliclyVisible)
                    .map(GuestMapper::toText).toList());
        }
        if (all || "image".equalsIgnoreCase(type) || "images".equalsIgnoreCase(type)) {
            out.put("images", imageRepository.findAllByProjectAndRemovedAtIsNull(project)
                    .stream().filter(GuestSearchService::isPubliclyVisible)
                    .map(GuestMapper::toImage).toList());
        }
        return out;
    }

    // ─── Categories ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<GuestCategoryDTO> searchCategories(String q, Pageable pageable) {
        String norm = normalize(q);
        List<Category> source = (norm == null)
                ? categoryRepository.findAllByRemovedAtIsNull()
                : categoryRepository.searchByText(norm, SIMILARITY_THRESHOLD, MAX_LIMIT);

        if (norm == null) {
            source.sort(Comparator.comparing(Category::getName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
        }

        return stampPage(
                paginate(source, pageable,
                        c -> GuestMapper.toCategory(c, publicProjectCount(c))),
                "category", GuestCategoryDTO::getCategoryCode, GuestSearchService::applyMark);
    }

    @Transactional(readOnly = true)
    public Optional<GuestCategoryDTO> getCategoryByCode(String categoryCode) {
        return categoryRepository.findByCategoryCodeAndRemovedAtIsNull(categoryCode)
                .map(c -> {
                    trendingService.logView("category", categoryCode);
                    return GuestMapper.toCategory(c, publicProjectCount(c));
                });
    }

    @Transactional(readOnly = true)
    public Page<GuestProjectDTO> getCategoryProjects(String categoryCode, Pageable pageable) {
        Category cat = categoryRepository.findByCategoryCodeAndRemovedAtIsNull(categoryCode).orElse(null);
        if (cat == null) return Page.empty(pageable);
        List<Project> rows = projectRepository.findAllByRemovedAtIsNull().stream()
                .filter(GuestSearchService::isProjectPubliclyVisible)
                .filter(p -> p.getCategories() != null
                        && p.getCategories().stream().anyMatch(c -> Objects.equals(c.getId(), cat.getId())))
                .sorted(Comparator.comparing(Project::getProjectName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();
        return stampPage(paginate(rows, pageable, p -> GuestMapper.toProject(p, mediaCounts(p))),
                "project", GuestProjectDTO::getProjectCode, GuestSearchService::applyMark);
    }

    // ─── Persons ──────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<GuestPersonDTO> searchPersons(String q,
                                              String region,
                                              Gender gender,
                                              List<String> personTypes,
                                              Pageable pageable) {
        String norm = normalize(q);
        List<Person> source = (norm == null)
                ? personRepository.findAllByRemovedAtIsNull()
                : personRepository.searchByText(norm, SIMILARITY_THRESHOLD, MAX_LIMIT);

        String wantedRegion = lower(region);
        var wantedTypes = lowerSet(personTypes);

        List<Person> filtered = source.stream()
                .filter(p -> wantedRegion == null
                        || (p.getRegion() != null && p.getRegion().toLowerCase(Locale.ROOT).equals(wantedRegion)))
                .filter(p -> gender == null || gender.equals(p.getGender()))
                .filter(p -> wantedTypes.isEmpty() || anyMatch(p.getPersonType(), wantedTypes))
                .collect(Collectors.toCollection(ArrayList::new));

        if (norm == null) {
            filtered.sort(Comparator.comparing(Person::getFullName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
        }

        return stampPage(
                paginate(filtered, pageable,
                        p -> GuestMapper.toPerson(p, publicProjectCount(p))),
                "person", GuestPersonDTO::getPersonCode, GuestSearchService::applyMark);
    }

    @Transactional(readOnly = true)
    public Optional<GuestPersonDTO> getPersonByCode(String personCode) {
        return personRepository.findByPersonCodeAndRemovedAtIsNull(personCode)
                .map(p -> {
                    trendingService.logView("person", personCode);
                    return GuestMapper.toPerson(p, publicProjectCount(p));
                });
    }

    @Transactional(readOnly = true)
    public Page<GuestProjectDTO> getPersonProjects(String personCode, Pageable pageable) {
        Person person = personRepository.findByPersonCodeAndRemovedAtIsNull(personCode).orElse(null);
        if (person == null) return Page.empty(pageable);
        List<Project> rows = projectRepository.findAllByPersonAndRemovedAtIsNull(person).stream()
                .filter(GuestSearchService::isProjectPubliclyVisible)
                .collect(Collectors.toCollection(ArrayList::new));
        rows.sort(Comparator.comparing(Project::getProjectName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
        return stampPage(paginate(rows, pageable, p -> GuestMapper.toProject(p, mediaCounts(p))),
                "project", GuestProjectDTO::getProjectCode, GuestSearchService::applyMark);
    }

    // ─── Audios ───────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<GuestAudioDTO> searchAudios(String q,
                                            String projectCode,
                                            String categoryCode,
                                            String personCode,
                                            String language,
                                            String dialect,
                                            String form,
                                            String typeOfBasta,
                                            String typeOfMaqam,
                                            String typeOfComposition,
                                            String typeOfPerformance,
                                            String composer,
                                            String producer,
                                            String speaker,
                                            String singer,
                                            String poet,
                                            List<String> contributors,
                                            String recordingVenue,
                                            String city,
                                            String region,
                                            String audience,
                                            String lyrics,
                                            List<String> subjects,
                                            List<String> genres,
                                            List<String> tags,
                                            List<String> keywords,
                                            Instant dateFrom,
                                            Instant dateTo,
                                            Instant publishedFrom,
                                            Instant publishedTo,
                                            String sortBy,
                                            String sortDirection,
                                            Pageable pageable) {
        String norm = normalize(q);
        List<Audio> source;
        if (norm == null) {
            source = audioRepository.findAllByRemovedAtIsNull();
        } else {
            String like = MediaSearchSqlBuilder.escapeLike(norm);
            source = audioRepository.searchByText(like, norm, PREFILTER_LIMIT, MAX_LIMIT);
            source = widenAudios(source, resolveProjectScope(norm));
        }

        String wProject = lower(projectCode);
        String wCategory = lower(categoryCode);
        String wPerson = lower(personCode);
        String wLang = lower(language);
        String wDialect = lower(dialect);
        String wForm = lower(form);
        String wBasta = lower(typeOfBasta);
        String wMaqam = lower(typeOfMaqam);
        String wComposition = lower(typeOfComposition);
        String wPerformance = lower(typeOfPerformance);
        String wComposer = lower(composer);
        String wProducer = lower(producer);
        String wSpeaker = lower(speaker);
        String wSinger = lower(singer);
        String wPoet = lower(poet);
        String wRecordingVenue = lower(recordingVenue);
        String wCity = lower(city);
        String wRegion = lower(region);
        String wAudience = lower(audience);
        String wLyrics = lower(lyrics);
        var wContributors = lowerSet(contributors);
        var wSubjects = lowerSet(subjects);
        var wGenres = lowerSet(genres);
        var wTags = lowerSet(tags);
        var wKeywords = lowerSet(keywords);

        List<Audio> filtered = new ArrayList<>(source.size());
        for (Audio a : source) {
            if (!isPubliclyVisible(a)) continue;
            if (!projectMatches(a.getProject(), wProject, wCategory, wPerson)) continue;
            if (wLang != null && !equalsLower(a.getLanguage(), wLang)) continue;
            if (wDialect != null && !equalsLower(a.getDialect(), wDialect)) continue;
            if (wForm != null && !equalsLower(a.getForm(), wForm)) continue;
            if (wBasta != null && !equalsLower(a.getTypeOfBasta(), wBasta)) continue;
            if (wMaqam != null && !equalsLower(a.getTypeOfMaqam(), wMaqam)) continue;
            if (wComposition != null && !equalsLower(a.getTypeOfComposition(), wComposition)) continue;
            if (wPerformance != null && !equalsLower(a.getTypeOfPerformance(), wPerformance)) continue;
            if (wComposer != null && !containsLower(a.getComposer(), wComposer)) continue;
            if (wProducer != null && !containsLower(a.getProducer(), wProducer)) continue;
            if (wSpeaker != null && !containsLower(a.getSpeaker(), wSpeaker)) continue;
            if (wSinger != null && !containsLower(a.getSinger(), wSinger)) continue;
            if (wPoet != null && !containsLower(a.getPoet(), wPoet)) continue;
            if (wRecordingVenue != null && !containsLower(a.getRecording_venue(), wRecordingVenue)) continue;
            if (wCity != null && !equalsLower(a.getCity(), wCity)) continue;
            if (wRegion != null && !equalsLower(a.getRegion(), wRegion)) continue;
            if (wAudience != null && !equalsLower(a.getAudience(), wAudience)) continue;
            if (wLyrics != null && !containsLower(a.getLyrics(), wLyrics)) continue;
            if (!wContributors.isEmpty() && !anyMatch(a.getContributors(), wContributors)) continue;
            if (!wSubjects.isEmpty() && !anyMatch(a.getSubject(), wSubjects)) continue;
            if (!wGenres.isEmpty() && !anyMatch(a.getGenre(), wGenres)) continue;
            if (!wTags.isEmpty() && !anyMatch(a.getTags(), wTags)) continue;
            if (!wKeywords.isEmpty() && !anyMatch(a.getKeywords(), wKeywords)) continue;
            if (!withinRange(a.getDate_created(), dateFrom, dateTo)) continue;
            if (!withinRange(a.getDate_published(), publishedFrom, publishedTo)) continue;
            filtered.add(a);
        }

        Comparator<Audio> cmp = audioComparator(sortBy);
        if (cmp != null) {
            if ("desc".equalsIgnoreCase(sortDirection)) cmp = cmp.reversed();
            filtered.sort(cmp);
        }

        return stampPage(paginate(filtered, pageable, GuestMapper::toAudio),
                "audio", GuestAudioDTO::getAudioCode, GuestSearchService::applyMark);
    }

    @Transactional(readOnly = true)
    public Optional<GuestAudioDTO> getAudioByCode(String audioCode) {
        return audioRepository.findByAudioCodeAndRemovedAtIsNull(audioCode)
                .filter(GuestSearchService::isPubliclyVisible)
                .map(a -> {
                    trendingService.logView("audio", audioCode);
                    return GuestMapper.toAudio(a);
                });
    }

    // ─── Videos ───────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<GuestVideoDTO> searchVideos(String q,
                                            String projectCode,
                                            String categoryCode,
                                            String personCode,
                                            String language,
                                            String dialect,
                                            String region,
                                            String event,
                                            String location,
                                            String creatorArtistDirector,
                                            String producer,
                                            String contributor,
                                            String personShownInVideo,
                                            String subtitle,
                                            String audience,
                                            String provenance,
                                            String videoStatus,
                                            String publisher,
                                            List<String> subjects,
                                            List<String> genres,
                                            List<String> colors,
                                            List<String> whereUsed,
                                            List<String> tags,
                                            List<String> keywords,
                                            Instant dateFrom,
                                            Instant dateTo,
                                            Instant publishedFrom,
                                            Instant publishedTo,
                                            String sortBy,
                                            String sortDirection,
                                            Pageable pageable) {
        String norm = normalize(q);
        List<Video> source;
        if (norm == null) {
            source = videoRepository.findAllByRemovedAtIsNull();
        } else {
            String like = MediaSearchSqlBuilder.escapeLike(norm);
            source = videoRepository.searchByText(like, norm, PREFILTER_LIMIT, MAX_LIMIT);
            source = widenVideos(source, resolveProjectScope(norm));
        }

        String wProject = lower(projectCode);
        String wCategory = lower(categoryCode);
        String wPerson = lower(personCode);
        String wLang = lower(language);
        String wDialect = lower(dialect);
        String wRegion = lower(region);
        String wEvent = lower(event);
        String wLocation = lower(location);
        String wDirector = lower(creatorArtistDirector);
        String wProducer = lower(producer);
        String wContributor = lower(contributor);
        String wPersonShown = lower(personShownInVideo);
        String wSubtitle = lower(subtitle);
        String wAudience = lower(audience);
        String wProvenance = lower(provenance);
        String wVideoStatus = lower(videoStatus);
        String wPublisher = lower(publisher);
        var wSubjects = lowerSet(subjects);
        var wGenres = lowerSet(genres);
        var wColors = lowerSet(colors);
        var wWhereUsed = lowerSet(whereUsed);
        var wTags = lowerSet(tags);
        var wKeywords = lowerSet(keywords);

        List<Video> filtered = new ArrayList<>(source.size());
        for (Video v : source) {
            if (!isPubliclyVisible(v)) continue;
            if (!projectMatches(v.getProject(), wProject, wCategory, wPerson)) continue;
            if (wLang != null && !equalsLower(v.getLanguage(), wLang)) continue;
            if (wDialect != null && !equalsLower(v.getDialect(), wDialect)) continue;
            if (wRegion != null && !equalsLower(v.getRegion(), wRegion)) continue;
            if (wEvent != null && !containsLower(v.getEvent(), wEvent)) continue;
            if (wLocation != null && !containsLower(v.getLocation(), wLocation)) continue;
            if (wDirector != null && !containsLower(v.getCreatorArtistDirector(), wDirector)) continue;
            if (wProducer != null && !containsLower(v.getProducer(), wProducer)) continue;
            if (wContributor != null && !containsLower(v.getContributor(), wContributor)) continue;
            if (wPersonShown != null && !containsLower(v.getPersonShownInVideo(), wPersonShown)) continue;
            if (wSubtitle != null && !containsLower(v.getSubtitle(), wSubtitle)) continue;
            if (wAudience != null && !equalsLower(v.getAudience(), wAudience)) continue;
            if (wProvenance != null && !containsLower(v.getProvenance(), wProvenance)) continue;
            if (wVideoStatus != null && !equalsLower(v.getVideoStatus(), wVideoStatus)) continue;
            if (wPublisher != null && !containsLower(v.getPublisher(), wPublisher)) continue;
            if (!wSubjects.isEmpty() && !anyMatch(v.getSubject(), wSubjects)) continue;
            if (!wGenres.isEmpty() && !anyMatch(v.getGenre(), wGenres)) continue;
            if (!wColors.isEmpty() && !anyMatch(v.getColorOfVideo(), wColors)) continue;
            if (!wWhereUsed.isEmpty() && !anyMatch(v.getWhereThisVideoUsed(), wWhereUsed)) continue;
            if (!wTags.isEmpty() && !anyMatch(v.getTags(), wTags)) continue;
            if (!wKeywords.isEmpty() && !anyMatch(v.getKeywords(), wKeywords)) continue;
            if (!withinRange(v.getDateCreated(), dateFrom, dateTo)) continue;
            if (!withinRange(v.getDatePublished(), publishedFrom, publishedTo)) continue;
            filtered.add(v);
        }

        Comparator<Video> cmp = videoComparator(sortBy);
        if (cmp != null) {
            if ("desc".equalsIgnoreCase(sortDirection)) cmp = cmp.reversed();
            filtered.sort(cmp);
        }

        return stampPage(paginate(filtered, pageable, GuestMapper::toVideo),
                "video", GuestVideoDTO::getVideoCode, GuestSearchService::applyMark);
    }

    @Transactional(readOnly = true)
    public Optional<GuestVideoDTO> getVideoByCode(String videoCode) {
        return videoRepository.findByVideoCodeAndRemovedAtIsNull(videoCode)
                .filter(GuestSearchService::isPubliclyVisible)
                .map(v -> {
                    trendingService.logView("video", videoCode);
                    return GuestMapper.toVideo(v);
                });
    }

    // ─── Texts ────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<GuestTextDTO> searchTexts(String q,
                                          String projectCode,
                                          String categoryCode,
                                          String personCode,
                                          String language,
                                          String dialect,
                                          String region,
                                          String documentType,
                                          String isbn,
                                          String author,
                                          String contributors,
                                          String script,
                                          String series,
                                          String edition,
                                          String volume,
                                          String printingHouse,
                                          String audience,
                                          String provenance,
                                          String publisher,
                                          List<String> subjects,
                                          List<String> genres,
                                          List<String> tags,
                                          List<String> keywords,
                                          Instant dateFrom,
                                          Instant dateTo,
                                          Instant publishedFrom,
                                          Instant publishedTo,
                                          Instant printDateFrom,
                                          Instant printDateTo,
                                          String sortBy,
                                          String sortDirection,
                                          Pageable pageable) {
        String norm = normalize(q);
        List<Text> source;
        if (norm == null) {
            source = textRepository.findAllByRemovedAtIsNull();
        } else {
            String like = MediaSearchSqlBuilder.escapeLike(norm);
            source = textRepository.searchByText(like, norm, PREFILTER_LIMIT, MAX_LIMIT);
            source = widenTexts(source, resolveProjectScope(norm));
        }

        String wProject = lower(projectCode);
        String wCategory = lower(categoryCode);
        String wPerson = lower(personCode);
        String wLang = lower(language);
        String wDialect = lower(dialect);
        String wRegion = lower(region);
        String wDocType = lower(documentType);
        String wIsbn = lower(isbn);
        String wAuthor = lower(author);
        String wContributors = lower(contributors);
        String wScript = lower(script);
        String wSeries = lower(series);
        String wEdition = lower(edition);
        String wVolume = lower(volume);
        String wPrintingHouse = lower(printingHouse);
        String wAudience = lower(audience);
        String wProvenance = lower(provenance);
        String wPublisher = lower(publisher);
        var wSubjects = lowerSet(subjects);
        var wGenres = lowerSet(genres);
        var wTags = lowerSet(tags);
        var wKeywords = lowerSet(keywords);

        List<Text> filtered = new ArrayList<>(source.size());
        for (Text t : source) {
            if (!isPubliclyVisible(t)) continue;
            if (!projectMatches(t.getProject(), wProject, wCategory, wPerson)) continue;
            if (wLang != null && !equalsLower(t.getLanguage(), wLang)) continue;
            if (wDialect != null && !equalsLower(t.getDialect(), wDialect)) continue;
            if (wRegion != null && !equalsLower(t.getRegion(), wRegion)) continue;
            if (wDocType != null && !equalsLower(t.getDocumentType(), wDocType)) continue;
            if (wIsbn != null && !containsLower(t.getIsbn(), wIsbn)) continue;
            if (wAuthor != null && !containsLower(t.getAuthor(), wAuthor)) continue;
            if (wContributors != null && !containsLower(t.getContributors(), wContributors)) continue;
            if (wScript != null && !equalsLower(t.getScript(), wScript)) continue;
            if (wSeries != null && !containsLower(t.getSeries(), wSeries)) continue;
            if (wEdition != null && !containsLower(t.getEdition(), wEdition)) continue;
            if (wVolume != null && !containsLower(t.getVolume(), wVolume)) continue;
            if (wPrintingHouse != null && !containsLower(t.getPrintingHouse(), wPrintingHouse)) continue;
            if (wAudience != null && !equalsLower(t.getAudience(), wAudience)) continue;
            if (wProvenance != null && !containsLower(t.getProvenance(), wProvenance)) continue;
            if (wPublisher != null && !containsLower(t.getPublisher(), wPublisher)) continue;
            if (!wSubjects.isEmpty() && !anyMatch(t.getSubject(), wSubjects)) continue;
            if (!wGenres.isEmpty() && !anyMatch(t.getGenre(), wGenres)) continue;
            if (!wTags.isEmpty() && !anyMatch(t.getTags(), wTags)) continue;
            if (!wKeywords.isEmpty() && !anyMatch(t.getKeywords(), wKeywords)) continue;
            if (!withinRange(t.getDateCreated(), dateFrom, dateTo)) continue;
            if (!withinRange(t.getDatePublished(), publishedFrom, publishedTo)) continue;
            if (!withinRange(t.getPrintDate(), printDateFrom, printDateTo)) continue;
            filtered.add(t);
        }

        Comparator<Text> cmp = textComparator(sortBy);
        if (cmp != null) {
            if ("desc".equalsIgnoreCase(sortDirection)) cmp = cmp.reversed();
            filtered.sort(cmp);
        }

        return stampPage(paginate(filtered, pageable, GuestMapper::toText),
                "text", GuestTextDTO::getTextCode, GuestSearchService::applyMark);
    }

    @Transactional(readOnly = true)
    public Optional<GuestTextDTO> getTextByCode(String textCode) {
        return textRepository.findByTextCodeAndRemovedAtIsNull(textCode)
                .filter(GuestSearchService::isPubliclyVisible)
                .map(t -> {
                    trendingService.logView("text", textCode);
                    return GuestMapper.toText(t);
                });
    }

    // ─── Images ───────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<GuestImageDTO> searchImages(String q,
                                            String projectCode,
                                            String categoryCode,
                                            String personCode,
                                            String language,
                                            String dialect,
                                            String region,
                                            String event,
                                            String location,
                                            String creatorArtistPhotographer,
                                            String contributor,
                                            String personShownInImage,
                                            String audience,
                                            String provenance,
                                            String photostory,
                                            String imageStatus,
                                            List<String> subjects,
                                            List<String> genres,
                                            List<String> colors,
                                            List<String> whereUsed,
                                            List<String> tags,
                                            List<String> keywords,
                                            Instant dateFrom,
                                            Instant dateTo,
                                            Instant publishedFrom,
                                            Instant publishedTo,
                                            String sortBy,
                                            String sortDirection,
                                            Pageable pageable) {
        String norm = normalize(q);
        List<Image> source;
        if (norm == null) {
            source = imageRepository.findAllByRemovedAtIsNull();
        } else {
            String like = MediaSearchSqlBuilder.escapeLike(norm);
            source = imageRepository.searchByText(like, norm, PREFILTER_LIMIT, MAX_LIMIT);
            source = widenImages(source, resolveProjectScope(norm));
        }

        String wProject = lower(projectCode);
        String wCategory = lower(categoryCode);
        String wPerson = lower(personCode);
        String wLang = lower(language);
        String wDialect = lower(dialect);
        String wRegion = lower(region);
        String wEvent = lower(event);
        String wLocation = lower(location);
        String wPhotographer = lower(creatorArtistPhotographer);
        String wContributor = lower(contributor);
        String wPersonShown = lower(personShownInImage);
        String wAudience = lower(audience);
        String wProvenance = lower(provenance);
        String wPhotostory = lower(photostory);
        String wImageStatus = lower(imageStatus);
        var wSubjects = lowerSet(subjects);
        var wGenres = lowerSet(genres);
        var wColors = lowerSet(colors);
        var wWhereUsed = lowerSet(whereUsed);
        var wTags = lowerSet(tags);
        var wKeywords = lowerSet(keywords);

        List<Image> filtered = new ArrayList<>(source.size());
        for (Image i : source) {
            if (!isPubliclyVisible(i)) continue;
            if (!projectMatches(i.getProject(), wProject, wCategory, wPerson)) continue;
            if (wLang != null && !equalsLower(i.getLanguage(), wLang)) continue;
            if (wDialect != null && !equalsLower(i.getDialect(), wDialect)) continue;
            if (wRegion != null && !equalsLower(i.getRegion(), wRegion)) continue;
            if (wEvent != null && !containsLower(i.getEvent(), wEvent)) continue;
            if (wLocation != null && !containsLower(i.getLocation(), wLocation)) continue;
            if (wPhotographer != null && !containsLower(i.getCreatorArtistPhotographer(), wPhotographer)) continue;
            if (wContributor != null && !containsLower(i.getContributor(), wContributor)) continue;
            if (wPersonShown != null && !containsLower(i.getPersonShownInImage(), wPersonShown)) continue;
            if (wAudience != null && !equalsLower(i.getAudience(), wAudience)) continue;
            if (wProvenance != null && !containsLower(i.getProvenance(), wProvenance)) continue;
            if (wPhotostory != null && !containsLower(i.getPhotostory(), wPhotostory)) continue;
            if (wImageStatus != null && !equalsLower(i.getImageStatus(), wImageStatus)) continue;
            if (!wSubjects.isEmpty() && !anyMatch(i.getSubject(), wSubjects)) continue;
            if (!wGenres.isEmpty() && !anyMatch(i.getGenre(), wGenres)) continue;
            if (!wColors.isEmpty() && !anyMatch(i.getColorOfImage(), wColors)) continue;
            if (!wWhereUsed.isEmpty() && !anyMatch(i.getWhereThisImageUsed(), wWhereUsed)) continue;
            if (!wTags.isEmpty() && !anyMatch(i.getTags(), wTags)) continue;
            if (!wKeywords.isEmpty() && !anyMatch(i.getKeywords(), wKeywords)) continue;
            if (!withinRange(i.getDateCreated(), dateFrom, dateTo)) continue;
            if (!withinRange(i.getDatePublished(), publishedFrom, publishedTo)) continue;
            filtered.add(i);
        }

        Comparator<Image> cmp = imageComparator(sortBy);
        if (cmp != null) {
            if ("desc".equalsIgnoreCase(sortDirection)) cmp = cmp.reversed();
            filtered.sort(cmp);
        }

        return stampPage(paginate(filtered, pageable, GuestMapper::toImage),
                "image", GuestImageDTO::getImageCode, GuestSearchService::applyMark);
    }

    @Transactional(readOnly = true)
    public Optional<GuestImageDTO> getImageByCode(String imageCode) {
        return imageRepository.findByImageCodeAndRemovedAtIsNull(imageCode)
                .filter(GuestSearchService::isPubliclyVisible)
                .map(i -> {
                    trendingService.logView("image", imageCode);
                    return GuestMapper.toImage(i);
                });
    }

    // ─── Grouped public media feed ───────────────────────────────────────────────

    /**
     * Public feed grouped into photos, sounds, videos and texts. Pagination is
     * applied independently per selected kind so every section can return its
     * own full DTOs and counts.
     *
     * @param q              free-text — matches titles, codes, description,
     *                       project name, person name, tags, keywords, subjects, genres
     * @param typesIn        which media kinds to include
     *                       ({@code image|video|audio|text}); null/empty = all
     *                       four. Projects and persons are never returned.
     *                       Rows are always grouped photos → sounds → videos →
     *                       texts; {@code sortBy} orders within each group.
     * @param projectCode    exact project code
     * @param categoryCode   exact category code
     * @param personCode     exact person code (via the project's owner)
     * @param language       exact match on entity.language
     * @param dialect        exact match on entity.dialect
     * @param region         exact match on entity.region
     * @param subjects       any-match against entity_subjects collection
     * @param genres         any-match against entity_genres collection
     * @param tags           any-match against entity_tags collection
     * @param keywords       any-match against entity_keywords collection
     * @param dateFrom       inclusive lower bound on date_created
     * @param dateTo         inclusive upper bound on date_created
     * @param sortBy         {@code relevance} (default when q present) |
     *                       {@code date} (default otherwise) | {@code datePublished} | {@code title}
     * @param sortDirection  {@code asc} | {@code desc}
     */
    @Transactional(readOnly = true)
    public GuestMediaFeedDTO feedAll(String q,
                                     List<String> typesIn,
                                     String projectCode,
                                     String categoryCode,
                                     String personCode,
                                     String language,
                                     String dialect,
                                     String region,
                                     List<String> subjects,
                                     List<String> genres,
                                     List<String> tags,
                                     List<String> keywords,
                                     Instant dateFrom,
                                     Instant dateTo,
                                     String sortBy,
                                     String sortDirection,
                                     Pageable pageable) {
        String norm = normalize(q);
        if (norm != null) trendingService.logSearch(norm);

        Set<String> types = parseTypes(typesIn);
        String effectiveSortBy = feedSortBy(norm, sortBy);
        String effectiveSortDirection = feedSortDirection(effectiveSortBy, sortDirection);

        Page<GuestImageDTO> images = types.contains("image")
                ? searchImages(q, projectCode, categoryCode, personCode,
                language, dialect, region,
                null, null, null, null, null, null, null, null, null,
                subjects, genres, null, null, tags, keywords,
                dateFrom, dateTo, null, null,
                effectiveSortBy, effectiveSortDirection, pageable)
                : Page.empty(pageable);

        Page<GuestAudioDTO> audios = types.contains("audio")
                ? searchAudios(q, projectCode, categoryCode, personCode,
                language, dialect,
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, region,
                null, null,
                subjects, genres, tags, keywords,
                dateFrom, dateTo, null, null,
                effectiveSortBy, effectiveSortDirection, pageable)
                : Page.empty(pageable);

        Page<GuestVideoDTO> videos = types.contains("video")
                ? searchVideos(q, projectCode, categoryCode, personCode,
                language, dialect, region,
                null, null, null, null, null, null, null, null, null, null, null,
                subjects, genres, null, null, tags, keywords,
                dateFrom, dateTo, null, null,
                effectiveSortBy, effectiveSortDirection, pageable)
                : Page.empty(pageable);

        Page<GuestTextDTO> texts = types.contains("text")
                ? searchTexts(q, projectCode, categoryCode, personCode,
                language, dialect, region,
                null, null, null, null, null, null, null, null, null, null, null, null,
                subjects, genres, tags, keywords,
                dateFrom, dateTo, null, null, null, null,
                effectiveSortBy, effectiveSortDirection, pageable)
                : Page.empty(pageable);

        return GuestMediaFeedDTO.builder()
                .order(FEED_KIND_ORDER)
                .images(toFeedSection("image", images))
                .audios(toFeedSection("audio", audios))
                .videos(toFeedSection("video", videos))
                .texts(toFeedSection("text", texts))
                .totalElements(images.getTotalElements()
                        + audios.getTotalElements()
                        + videos.getTotalElements()
                        + texts.getTotalElements())
                .page(pageable.getPageNumber())
                .size(pageable.getPageSize())
                .hasNext(images.hasNext() || audios.hasNext() || videos.hasNext() || texts.hasNext())
                .hasPrevious(images.hasPrevious() || audios.hasPrevious()
                        || videos.hasPrevious() || texts.hasPrevious())
                .build();
    }

    /**
     * The four media kinds the feed can surface — the default when no
     * {@code types} filter is sent. The feed is media-only: projects and
     * persons are NOT part of it (they have their own endpoints), so they're
     * absent here and any {@code types=project|person} request is ignored.
     */
    private static final List<String> FEED_KIND_ORDER = List.of("image", "audio", "video", "text");
    private static final Set<String> ALL_FEED_KINDS = Set.copyOf(FEED_KIND_ORDER);

    private static <T> GuestMediaFeedDTO.Section<T> toFeedSection(String kind, Page<T> page) {
        return GuestMediaFeedDTO.Section.<T>builder()
                .kind(kind)
                .content(page.getContent())
                .page(page.getNumber())
                .size(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .numberOfElements(page.getNumberOfElements())
                .first(page.isFirst())
                .last(page.isLast())
                .empty(page.isEmpty())
                .build();
    }

    private static String feedSortBy(String normalizedQuery, String sortBy) {
        String wanted = lower(sortBy);
        if (wanted == null || "relevance".equals(wanted)) {
            return normalizedQuery == null ? "date" : null;
        }
        return sortBy;
    }

    private static String feedSortDirection(String effectiveSortBy, String sortDirection) {
        String wanted = lower(sortDirection);
        if (wanted != null) return wanted;
        if (effectiveSortBy == null) return null;
        return switch (effectiveSortBy.toLowerCase(Locale.ROOT)) {
            case "date", "datecreated", "datepublished", "published",
                 "createdat", "created", "added" -> "desc";
            default -> "asc";
        };
    }

    private static Set<String> parseTypes(List<String> in) {
        if (in == null || in.isEmpty()) return ALL_FEED_KINDS;
        Set<String> out = new LinkedHashSet<>();
        for (String s : in) {
            if (s == null) continue;
            for (String value : s.split(",")) {
                switch (value.trim().toLowerCase(Locale.ROOT)) {
                    case "image", "images", "photo", "photos" -> out.add("image");
                    case "audio", "audios", "sound", "sounds" -> out.add("audio");
                    case "video", "videos"                    -> out.add("video");
                    case "text", "texts"                      -> out.add("text");
                    default -> { /* ignore unknown — incl. project / person */ }
                }
            }
        }
        return out.isEmpty() ? ALL_FEED_KINDS : out;
    }

    // ─── Suggest (autocomplete) ───────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<GuestSuggestionDTO> suggest(String q, Integer limit) {
        String norm = normalize(q);
        if (norm == null) return List.of();
        int cap = clamp(limit, SUGGEST_DEFAULT_LIMIT, SUGGEST_MAX_LIMIT);

        List<GuestSuggestionDTO> out = new ArrayList<>(cap * 4);

        // Per-section quick lookups, each capped at the user-asked cap.
        projectRepository.searchByText(norm, SIMILARITY_THRESHOLD, cap)
                .forEach(p -> out.add(GuestSuggestionDTO.builder()
                        .value(p.getProjectName())
                        .kind("project")
                        .code(p.getProjectCode())
                        .build()));
        categoryRepository.searchByText(norm, SIMILARITY_THRESHOLD, cap)
                .forEach(c -> out.add(GuestSuggestionDTO.builder()
                        .value(c.getName())
                        .kind("category")
                        .code(c.getCategoryCode())
                        .build()));
        personRepository.searchByText(norm, SIMILARITY_THRESHOLD, cap)
                .forEach(p -> out.add(GuestSuggestionDTO.builder()
                        .value(displayPersonName(p))
                        .kind("person")
                        .code(p.getPersonCode())
                        .build()));

        if (out.size() >= cap) return out.subList(0, cap);

        String like = MediaSearchSqlBuilder.escapeLike(norm);
        int remaining = cap - out.size();
        int perMedia = Math.max(1, remaining / 4);

        audioRepository.searchByText(like, norm, PREFILTER_LIMIT, perMedia)
                .forEach(a -> out.add(GuestSuggestionDTO.builder()
                        .value(firstNonBlank(a.getOriginTitle(), a.getAlterTitle(),
                                a.getCentral_kurdish_title(), a.getRomanized_title(), a.getAudioCode()))
                        .kind("audio").code(a.getAudioCode()).build()));
        videoRepository.searchByText(like, norm, PREFILTER_LIMIT, perMedia)
                .forEach(v -> out.add(GuestSuggestionDTO.builder()
                        .value(firstNonBlank(v.getOriginalTitle(), v.getAlternativeTitle(),
                                v.getTitleInCentralKurdish(), v.getRomanizedTitle(), v.getVideoCode()))
                        .kind("video").code(v.getVideoCode()).build()));
        textRepository.searchByText(like, norm, PREFILTER_LIMIT, perMedia)
                .forEach(t -> out.add(GuestSuggestionDTO.builder()
                        .value(firstNonBlank(t.getOriginalTitle(), t.getAlternativeTitle(),
                                t.getTitleInCentralKurdish(), t.getRomanizedTitle(), t.getTextCode()))
                        .kind("text").code(t.getTextCode()).build()));
        imageRepository.searchByText(like, norm, PREFILTER_LIMIT, perMedia)
                .forEach(i -> out.add(GuestSuggestionDTO.builder()
                        .value(firstNonBlank(i.getOriginalTitle(), i.getAlternativeTitle(),
                                i.getTitleInCentralKurdish(), i.getRomanizedTitle(), i.getImageCode()))
                        .kind("image").code(i.getImageCode()).build()));

        return out.size() > cap ? out.subList(0, cap) : out;
    }

    // ─── Facets (KCAC sidebar counts) ─────────────────────────────────────────────

    @Transactional(readOnly = true)
    public GuestFacetsDTO facets() {
        // Source data — read-once, in-memory grouping is fast for 1k–10k rows.
        // Counts must match the public feed: visible project + visible media only.
        List<Audio> audios = audioRepository.findAllByRemovedAtIsNull().stream()
                .filter(GuestSearchService::isPubliclyVisible)
                .toList();
        List<Video> videos = videoRepository.findAllByRemovedAtIsNull().stream()
                .filter(GuestSearchService::isPubliclyVisible)
                .toList();
        List<Text> texts = textRepository.findAllByRemovedAtIsNull().stream()
                .filter(GuestSearchService::isPubliclyVisible)
                .toList();
        List<Image> images = imageRepository.findAllByRemovedAtIsNull().stream()
                .filter(GuestSearchService::isPubliclyVisible)
                .toList();
        List<Project> projects = projectRepository.findAllByRemovedAtIsNull().stream()
                .filter(GuestSearchService::isProjectPubliclyVisible)
                .toList();

        Map<String, Long> languages = new HashMap<>();
        Map<String, Long> dialects = new HashMap<>();
        Map<String, Long> regions = new HashMap<>();
        Map<String, Long> genres = new HashMap<>();
        Map<String, Long> tags = new HashMap<>();
        Map<String, Long> keywords = new HashMap<>();
        Map<String, GuestFacetsDTO.Bucket> categoryBuckets = new HashMap<>();
        Map<String, GuestFacetsDTO.Bucket> personBuckets = new HashMap<>();

        for (Audio a : audios) {
            bumpLabel(languages, a.getLanguage());
            bumpLabel(dialects, a.getDialect());
            bumpLabel(regions, a.getRegion());
            bumpAll(genres, a.getGenre());
            bumpAll(tags, a.getTags());
            bumpAll(keywords, a.getKeywords());
        }
        for (Video v : videos) {
            bumpLabel(languages, v.getLanguage());
            bumpLabel(dialects, v.getDialect());
            bumpAll(genres, v.getGenre());
            bumpAll(tags, v.getTags());
            bumpAll(keywords, v.getKeywords());
        }
        for (Text t : texts) {
            bumpLabel(languages, t.getLanguage());
            bumpLabel(dialects, t.getDialect());
            bumpAll(genres, t.getGenre());
            bumpAll(tags, t.getTags());
            bumpAll(keywords, t.getKeywords());
        }
        for (Image i : images) {
            bumpAll(genres, i.getGenre());
            bumpAll(tags, i.getTags());
            bumpAll(keywords, i.getKeywords());
        }

        for (Project p : projects) {
            if (p.getCategories() != null) {
                for (Category c : p.getCategories()) {
                    String key = c.getCategoryCode();
                    if (key == null) continue;
                    GuestFacetsDTO.Bucket b = categoryBuckets.computeIfAbsent(key,
                            k -> GuestFacetsDTO.Bucket.builder().code(k).label(c.getName()).count(0).build());
                    b.setCount(b.getCount() + 1);
                }
            }
            Person ps = p.getPerson();
            if (ps != null && ps.getPersonCode() != null) {
                GuestFacetsDTO.Bucket b = personBuckets.computeIfAbsent(ps.getPersonCode(),
                        k -> GuestFacetsDTO.Bucket.builder().code(k).label(displayPersonName(ps)).count(0).build());
                b.setCount(b.getCount() + 1);
            }
            bumpAll(tags, p.getTags());
            bumpAll(keywords, p.getKeywords());
        }
        for (Person p : personRepository.findAllByRemovedAtIsNull()) {
            bumpLabel(regions, p.getRegion());
        }

        return GuestFacetsDTO.builder()
                .mediaTypes(GuestFacetsDTO.MediaTypeBucket.builder()
                        .audios(audios.size())
                        .videos(videos.size())
                        .texts(texts.size())
                        .images(images.size())
                        .projects(projects.size())
                        .build())
                .languages(toBuckets(languages))
                .dialects(toBuckets(dialects))
                .regions(toBuckets(regions))
                .genres(toBuckets(genres))
                .tags(toBuckets(tags))
                .keywords(toBuckets(keywords))
                .categories(rankBuckets(categoryBuckets.values()))
                .persons(rankBuckets(personBuckets.values()))
                .build();
    }

    // ─── Scope expansion (q → matching projects/persons → their media) ────────────

    /**
     * Resolves the broader project set that {@code q} should pull in: any
     * project whose name/code/tags match {@code q}, plus any project owned
     * by a person whose name matches {@code q}. Returned projects are
     * already trash-filtered.
     *
     * <p>This is what lets a search for "hasan zirak" surface his audios and
     * videos even when the recording titles don't contain his name.
     */
    private List<Project> resolveProjectScope(String q) {
        if (q == null || q.isBlank()) return List.of();
        Map<Long, Project> byId = new LinkedHashMap<>();
        for (Project p : projectRepository.searchByText(q, SIMILARITY_THRESHOLD, MAX_LIMIT)) {
            if (p != null && p.getId() != null) byId.put(p.getId(), p);
        }
        List<Person> personHits = personRepository.searchByText(q, SIMILARITY_THRESHOLD, MAX_LIMIT);
        if (!personHits.isEmpty()) {
            for (Project p : projectRepository.findAllByPersonInAndRemovedAtIsNull(personHits)) {
                if (p != null && p.getId() != null) byId.putIfAbsent(p.getId(), p);
            }
        }
        return new ArrayList<>(byId.values());
    }

    private List<Audio> widenAudios(List<Audio> base, List<Project> scope) {
        if (scope == null || scope.isEmpty()) return base;
        List<Audio> extra = audioRepository.findAllByProjectInAndRemovedAtIsNull(scope);
        if (extra.isEmpty()) return base;
        Map<Long, Audio> byId = new LinkedHashMap<>(base.size() + extra.size());
        for (Audio a : base) byId.put(a.getId(), a);
        for (Audio a : extra) byId.putIfAbsent(a.getId(), a);
        return new ArrayList<>(byId.values());
    }

    private List<Video> widenVideos(List<Video> base, List<Project> scope) {
        if (scope == null || scope.isEmpty()) return base;
        List<Video> extra = videoRepository.findAllByProjectInAndRemovedAtIsNull(scope);
        if (extra.isEmpty()) return base;
        Map<Long, Video> byId = new LinkedHashMap<>(base.size() + extra.size());
        for (Video v : base) byId.put(v.getId(), v);
        for (Video v : extra) byId.putIfAbsent(v.getId(), v);
        return new ArrayList<>(byId.values());
    }

    private List<Text> widenTexts(List<Text> base, List<Project> scope) {
        if (scope == null || scope.isEmpty()) return base;
        List<Text> extra = textRepository.findAllByProjectInAndRemovedAtIsNull(scope);
        if (extra.isEmpty()) return base;
        Map<Long, Text> byId = new LinkedHashMap<>(base.size() + extra.size());
        for (Text t : base) byId.put(t.getId(), t);
        for (Text t : extra) byId.putIfAbsent(t.getId(), t);
        return new ArrayList<>(byId.values());
    }

    private static <T> List<T> capById(List<T> rows, int limit) {
        if (rows == null || rows.size() <= limit) return rows;
        return new ArrayList<>(rows.subList(0, limit));
    }

    private List<Image> widenImages(List<Image> base, List<Project> scope) {
        if (scope == null || scope.isEmpty()) return base;
        List<Image> extra = imageRepository.findAllByProjectInAndRemovedAtIsNull(scope);
        if (extra.isEmpty()) return base;
        Map<Long, Image> byId = new LinkedHashMap<>(base.size() + extra.size());
        for (Image i : base) byId.put(i.getId(), i);
        for (Image i : extra) byId.putIfAbsent(i.getId(), i);
        return new ArrayList<>(byId.values());
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────────

    private GuestProjectDTO.MediaCounts mediaCounts(Project p) {
        if (p == null) return null;
        return GuestProjectDTO.MediaCounts.builder()
                .audios(audioRepository.countPublicByProject(p))
                .videos(videoRepository.countPublicByProject(p))
                .texts(textRepository.countPublicByProject(p))
                .images(imageRepository.countPublicByProject(p))
                .build();
    }

    private long publicProjectCount(Category category) {
        return category == null ? 0L : projectRepository.countPublicByCategory(category);
    }

    private long publicProjectCount(Person person) {
        return person == null ? 0L : projectRepository.countPublicByPerson(person);
    }

    private static boolean projectMatches(Project project,
                                          String wantedProjectCodeLower,
                                          String wantedCategoryCodeLower,
                                          String wantedPersonCodeLower) {
        if (project == null) {
            return wantedProjectCodeLower == null
                    && wantedCategoryCodeLower == null
                    && wantedPersonCodeLower == null;
        }
        if (wantedProjectCodeLower != null
                && (project.getProjectCode() == null
                || !project.getProjectCode().toLowerCase(Locale.ROOT).equals(wantedProjectCodeLower))) {
            return false;
        }
        if (wantedPersonCodeLower != null) {
            Person ps = project.getPerson();
            if (ps == null || ps.getPersonCode() == null
                    || !ps.getPersonCode().toLowerCase(Locale.ROOT).equals(wantedPersonCodeLower)) {
                return false;
            }
        }
        if (wantedCategoryCodeLower != null) {
            List<Category> cats = project.getCategories();
            if (cats == null || cats.isEmpty()) return false;
            boolean any = false;
            for (Category c : cats) {
                if (c.getCategoryCode() != null
                        && c.getCategoryCode().toLowerCase(Locale.ROOT).equals(wantedCategoryCodeLower)) {
                    any = true;
                    break;
                }
            }
            return any;
        }
        return true;
    }

    private static boolean isProjectPubliclyVisible(Project project) {
        return project != null
                && project.getRemovedAt() == null
                && !Boolean.FALSE.equals(project.getIsVisibleToPublic());
    }

    private static boolean isPubliclyVisible(Audio audio) {
        return audio != null
                && audio.getRemovedAt() == null
                && !Boolean.FALSE.equals(audio.getIsPublic())
                && isProjectPubliclyVisible(audio.getProject());
    }

    private static boolean isPubliclyVisible(Video video) {
        return video != null
                && video.getRemovedAt() == null
                && !Boolean.FALSE.equals(video.getIsPublic())
                && isProjectPubliclyVisible(video.getProject());
    }

    private static boolean isPubliclyVisible(Text text) {
        return text != null
                && text.getRemovedAt() == null
                && !Boolean.FALSE.equals(text.getIsPublic())
                && isProjectPubliclyVisible(text.getProject());
    }

    private static boolean isPubliclyVisible(Image image) {
        return image != null
                && image.getRemovedAt() == null
                && !Boolean.FALSE.equals(image.getIsPublic())
                && isProjectPubliclyVisible(image.getProject());
    }

    // ─── Trending stamp ───────────────────────────────────────────────────────────

    /**
     * Stamps isTrending / trendingRank / trendingScore onto every DTO in a page.
     * Uses the cached snapshot so there is zero extra DB cost per call.
     *
     * @param page        the paginated list to enrich
     * @param entityType  "audio" | "video" | "text" | "image" | "project" | "person" | "category"
     * @param codeGetter  extracts the entity code from the DTO
     * @param applier     writes the trending fields onto the DTO
     */
    private <T> Page<T> stampPage(Page<T> page,
                                  String entityType,
                                  java.util.function.Function<T, String> codeGetter,
                                  java.util.function.BiConsumer<T, GuestTrendingService.TrendingMark> applier) {
        if (page.isEmpty()) return page;
        java.util.Map<String, GuestTrendingService.TrendingMark> snap = trendingService.getSnapshot();
        if (snap.isEmpty()) return page;
        page.forEach(dto -> {
            GuestTrendingService.TrendingMark mark = snap.get(entityType + ":" + codeGetter.apply(dto));
            if (mark != null) applier.accept(dto, mark);
        });
        return page;
    }

    private static void applyMark(GuestAudioDTO dto, GuestTrendingService.TrendingMark m) {
        dto.setTrending(true); dto.setTrendingRank(m.rank()); dto.setTrendingScore(m.score());
    }
    private static void applyMark(GuestVideoDTO dto, GuestTrendingService.TrendingMark m) {
        dto.setTrending(true); dto.setTrendingRank(m.rank()); dto.setTrendingScore(m.score());
    }
    private static void applyMark(GuestTextDTO dto, GuestTrendingService.TrendingMark m) {
        dto.setTrending(true); dto.setTrendingRank(m.rank()); dto.setTrendingScore(m.score());
    }
    private static void applyMark(GuestImageDTO dto, GuestTrendingService.TrendingMark m) {
        dto.setTrending(true); dto.setTrendingRank(m.rank()); dto.setTrendingScore(m.score());
    }
    private static void applyMark(GuestProjectDTO dto, GuestTrendingService.TrendingMark m) {
        dto.setTrending(true); dto.setTrendingRank(m.rank()); dto.setTrendingScore(m.score());
    }
    private static void applyMark(GuestPersonDTO dto, GuestTrendingService.TrendingMark m) {
        dto.setTrending(true); dto.setTrendingRank(m.rank()); dto.setTrendingScore(m.score());
    }
    private static void applyMark(GuestCategoryDTO dto, GuestTrendingService.TrendingMark m) {
        dto.setTrending(true); dto.setTrendingRank(m.rank()); dto.setTrendingScore(m.score());
    }

    // ─── Pagination helper ────────────────────────────────────────────────────────

    private static <S, T> Page<T> paginate(List<S> source, Pageable pageable,
                                           java.util.function.Function<S, T> mapper) {
        if (source == null || source.isEmpty()) return new PageImpl<>(List.of(), pageable, 0);
        long total = source.size();
        int from = (int) Math.min(pageable.getOffset(), total);
        int to = (int) Math.min(from + pageable.getPageSize(), total);
        List<T> page = source.subList(from, to).stream().map(mapper).toList();
        return new PageImpl<>(page, pageable, total);
    }

    private static int clamp(Integer v, int defaultVal, int max) {
        if (v == null || v <= 0) return defaultVal;
        return Math.min(v, max);
    }

    private static String normalize(String q) {
        if (q == null) return null;
        String t = q.trim();
        return t.isEmpty() ? null : t;
    }

    private static String lower(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t.toLowerCase(Locale.ROOT);
    }

    private static java.util.Set<String> lowerSet(List<String> in) {
        if (in == null || in.isEmpty()) return java.util.Set.of();
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (String s : in) {
            String n = lower(s);
            if (n != null) out.add(n);
        }
        return out;
    }

    private static boolean equalsLower(String value, String needleLower) {
        return value != null && value.toLowerCase(Locale.ROOT).equals(needleLower);
    }

    private static boolean containsLower(String value, String needleLower) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needleLower);
    }

    private static boolean anyMatch(List<String> field, java.util.Set<String> wantedLower) {
        if (field == null || field.isEmpty() || wantedLower.isEmpty()) return false;
        for (String s : field) {
            if (s == null) continue;
            if (wantedLower.contains(s.trim().toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private static boolean withinRange(Instant value, Instant from, Instant to) {
        if (from == null && to == null) return true;
        if (value == null) return false;
        if (from != null && value.isBefore(from)) return false;
        if (to != null && value.isAfter(to)) return false;
        return true;
    }

    private static void bumpLabel(Map<String, Long> map, String label) {
        if (label == null) return;
        String t = label.trim();
        if (t.isEmpty()) return;
        map.merge(t, 1L, Long::sum);
    }

    private static void bumpAll(Map<String, Long> map, Collection<String> values) {
        if (values == null) return;
        for (String v : values) bumpLabel(map, v);
    }

    private static List<GuestFacetsDTO.Bucket> toBuckets(Map<String, Long> map) {
        return map.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, Long>>comparingLong(Map.Entry::getValue).reversed()
                        .thenComparing(Map.Entry::getKey, String.CASE_INSENSITIVE_ORDER))
                .limit(FACET_BUCKET_LIMIT)
                .map(e -> GuestFacetsDTO.Bucket.builder().label(e.getKey()).count(e.getValue()).build())
                .toList();
    }

    private static List<GuestFacetsDTO.Bucket> rankBuckets(Collection<GuestFacetsDTO.Bucket> buckets) {
        return buckets.stream()
                .sorted(Comparator.comparingLong(GuestFacetsDTO.Bucket::getCount).reversed()
                        .thenComparing(GuestFacetsDTO.Bucket::getLabel,
                                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .limit(FACET_BUCKET_LIMIT)
                .toList();
    }

    private static String firstNonBlank(String... candidates) {
        if (candidates == null) return null;
        for (String s : candidates) {
            if (s != null && !s.isBlank()) return s;
        }
        return null;
    }

    private static String displayPersonName(Person p) {
        if (p == null) return null;
        return firstNonBlank(p.getFullName(), p.getRomanizedName(), p.getNickname(), p.getPersonCode());
    }

    // ─── Sort comparators ─────────────────────────────────────────────────────────

    private static Comparator<Project> projectComparator(String sortBy) {
        if (sortBy == null || sortBy.isBlank()) return null;
        return switch (sortBy.toLowerCase(Locale.ROOT)) {
            case "name", "alpha", "alphabet", "alphabetical", "projectname" ->
                    Comparator.comparing(Project::getProjectName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "code", "projectcode" ->
                    Comparator.comparing(Project::getProjectCode, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "createdat", "created", "added" ->
                    Comparator.comparing(Project::getCreatedAt, Comparator.nullsLast(Instant::compareTo));
            case "updatedat", "updated", "modified" ->
                    Comparator.comparing(Project::getUpdatedAt, Comparator.nullsLast(Instant::compareTo));
            default -> null;
        };
    }

    private static Comparator<Audio> audioComparator(String sortBy) {
        if (sortBy == null || sortBy.isBlank()) return null;
        return switch (sortBy.toLowerCase(Locale.ROOT)) {
            case "title", "name", "alpha", "alphabet", "origintitle" ->
                    Comparator.comparing(Audio::getOriginTitle, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "code", "audiocode" ->
                    Comparator.comparing(Audio::getAudioCode, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "date", "datecreated" ->
                    Comparator.comparing(Audio::getDate_created, Comparator.nullsLast(Instant::compareTo));
            case "published", "datepublished" ->
                    Comparator.comparing(Audio::getDate_published, Comparator.nullsLast(Instant::compareTo));
            case "createdat", "created", "added" ->
                    Comparator.comparing(Audio::getCreatedAt, Comparator.nullsLast(Instant::compareTo));
            default -> null;
        };
    }

    private static Comparator<Video> videoComparator(String sortBy) {
        if (sortBy == null || sortBy.isBlank()) return null;
        return switch (sortBy.toLowerCase(Locale.ROOT)) {
            case "title", "name", "alpha", "alphabet", "originaltitle" ->
                    Comparator.comparing(Video::getOriginalTitle, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "code", "videocode" ->
                    Comparator.comparing(Video::getVideoCode, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "date", "datecreated" ->
                    Comparator.comparing(Video::getDateCreated, Comparator.nullsLast(Instant::compareTo));
            case "published", "datepublished" ->
                    Comparator.comparing(Video::getDatePublished, Comparator.nullsLast(Instant::compareTo));
            case "createdat", "created", "added" ->
                    Comparator.comparing(Video::getCreatedAt, Comparator.nullsLast(Instant::compareTo));
            default -> null;
        };
    }

    private static Comparator<Text> textComparator(String sortBy) {
        if (sortBy == null || sortBy.isBlank()) return null;
        return switch (sortBy.toLowerCase(Locale.ROOT)) {
            case "title", "name", "alpha", "alphabet", "originaltitle" ->
                    Comparator.comparing(Text::getOriginalTitle, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "code", "textcode" ->
                    Comparator.comparing(Text::getTextCode, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "date", "datecreated" ->
                    Comparator.comparing(Text::getDateCreated, Comparator.nullsLast(Instant::compareTo));
            case "published", "datepublished" ->
                    Comparator.comparing(Text::getDatePublished, Comparator.nullsLast(Instant::compareTo));
            case "createdat", "created", "added" ->
                    Comparator.comparing(Text::getCreatedAt, Comparator.nullsLast(Instant::compareTo));
            default -> null;
        };
    }

    private static Comparator<Image> imageComparator(String sortBy) {
        if (sortBy == null || sortBy.isBlank()) return null;
        return switch (sortBy.toLowerCase(Locale.ROOT)) {
            case "title", "name", "alpha", "alphabet", "originaltitle" ->
                    Comparator.comparing(Image::getOriginalTitle, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "code", "imagecode" ->
                    Comparator.comparing(Image::getImageCode, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "date", "datecreated" ->
                    Comparator.comparing(Image::getDateCreated, Comparator.nullsLast(Instant::compareTo));
            case "published", "datepublished" ->
                    Comparator.comparing(Image::getDatePublished, Comparator.nullsLast(Instant::compareTo));
            case "createdat", "created", "added" ->
                    Comparator.comparing(Image::getCreatedAt, Comparator.nullsLast(Instant::compareTo));
            default -> null;
        };
    }
}
