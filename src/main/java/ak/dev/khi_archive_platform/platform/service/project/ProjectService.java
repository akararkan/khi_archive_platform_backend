package ak.dev.khi_archive_platform.platform.service.project;

import ak.dev.khi_archive_platform.S3Service;
import ak.dev.khi_archive_platform.platform.dto.project.ProjectCreateRequestDTO;
import ak.dev.khi_archive_platform.platform.dto.project.ProjectResponseDTO;
import ak.dev.khi_archive_platform.platform.dto.project.ProjectUpdateRequestDTO;
import ak.dev.khi_archive_platform.platform.enums.AudioAuditAction;
import ak.dev.khi_archive_platform.platform.enums.ImageAuditAction;
import ak.dev.khi_archive_platform.platform.enums.ProjectAuditAction;
import ak.dev.khi_archive_platform.platform.enums.TextAuditAction;
import ak.dev.khi_archive_platform.platform.enums.VideoAuditAction;
import ak.dev.khi_archive_platform.platform.model.audio.Audio;
import ak.dev.khi_archive_platform.platform.model.image.Image;
import ak.dev.khi_archive_platform.platform.model.text.Text;
import ak.dev.khi_archive_platform.platform.model.video.Video;
import ak.dev.khi_archive_platform.platform.exceptions.*;
import ak.dev.khi_archive_platform.platform.model.category.Category;
import ak.dev.khi_archive_platform.platform.model.person.Person;
import ak.dev.khi_archive_platform.platform.model.project.Project;
import ak.dev.khi_archive_platform.platform.repo.audio.AudioRepository;
import ak.dev.khi_archive_platform.platform.repo.category.CategoryRepository;
import ak.dev.khi_archive_platform.platform.repo.image.ImageRepository;
import ak.dev.khi_archive_platform.platform.repo.person.PersonRepository;
import ak.dev.khi_archive_platform.platform.repo.project.ProjectRepository;
import ak.dev.khi_archive_platform.platform.repo.text.TextRepository;
import ak.dev.khi_archive_platform.platform.repo.video.VideoRepository;
import ak.dev.khi_archive_platform.platform.service.audio.AudioAuditService;
import ak.dev.khi_archive_platform.platform.service.audio.AudioReadCache;
import ak.dev.khi_archive_platform.platform.service.category.CategoryCodeHelper;
import ak.dev.khi_archive_platform.platform.service.common.CodeGenLock;
import ak.dev.khi_archive_platform.platform.service.common.PaginationSupport;
import ak.dev.khi_archive_platform.platform.service.image.ImageAuditService;
import ak.dev.khi_archive_platform.platform.service.image.ImageReadCache;
import ak.dev.khi_archive_platform.platform.service.text.TextAuditService;
import ak.dev.khi_archive_platform.platform.service.text.TextReadCache;
import ak.dev.khi_archive_platform.platform.service.video.VideoAuditService;
import ak.dev.khi_archive_platform.platform.service.video.VideoReadCache;
import ak.dev.khi_archive_platform.user.consts.ValidationPatterns;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final PersonRepository personRepository;
    private final CategoryRepository categoryRepository;
    private final AudioRepository audioRepository;
    private final VideoRepository videoRepository;
    private final ImageRepository imageRepository;
    private final TextRepository textRepository;
    private final ProjectAuditService auditService;
    private final AudioAuditService audioAuditService;
    private final VideoAuditService videoAuditService;
    private final ImageAuditService imageAuditService;
    private final TextAuditService textAuditService;
    private final ProjectReadCache readCache;
    private final AudioReadCache audioReadCache;
    private final VideoReadCache videoReadCache;
    private final ImageReadCache imageReadCache;
    private final TextReadCache textReadCache;
    private final S3Service s3Service;
    private final CodeGenLock codeGenLock;

    public ProjectResponseDTO create(ProjectCreateRequestDTO dto,
                                     Authentication authentication,
                                     HttpServletRequest request) {
        if (dto == null) {
            throw new ProjectValidationException("Project payload is required");
        }
        if (dto.getCategoryCodes() == null || dto.getCategoryCodes().isEmpty()) {
            throw new ProjectValidationException("At least one category code is required");
        }

        List<Category> categories = resolveCategories(dto.getCategoryCodes());
        Person person = resolvePerson(dto.getPersonCode());

        // Serialise code generation per prefix so two concurrent creates never
        // produce the same project code. Different prefixes proceed in parallel.
        codeGenLock.lock("project-code:" + (person != null
                ? person.getPersonCode().toUpperCase(Locale.ROOT)
                : "UNTITLED"));
        String projectCode = generateProjectCode(person, categories);
        if (projectRepository.existsByProjectCodeAndRemovedAtIsNull(projectCode)) {
            throw new ProjectAlreadyExistsException("Project already exists with code: " + projectCode);
        }

        Project project = Project.builder()
                .projectCode(projectCode)
                .projectName(dto.getProjectName())
                .person(person)
                .categories(new ArrayList<>(categories))
                .description(dto.getDescription())
                .tags(dto.getTags() != null ? new ArrayList<>(dto.getTags()) : new ArrayList<>())
                .keywords(dto.getKeywords() != null ? new ArrayList<>(dto.getKeywords()) : new ArrayList<>())
                .build();

        touchCreateAudit(project, authentication);
        Project saved = projectRepository.save(project);
        readCache.evictAll();

        String categorySummary = categories.stream()
                .map(Category::getCategoryCode)
                .collect(Collectors.joining(", "));
        auditService.record(saved, ProjectAuditAction.CREATE, authentication, request,
                "Created project with code=" + saved.getProjectCode()
                        + " person=" + (person != null ? person.getPersonCode() : "UNTITLED")
                        + " categories=[" + categorySummary + "]");
        return toResponse(saved);
    }

    public record BulkCreateResult(int requested, int inserted, int skipped, long elapsedMs) {}

    /**
     * Bulk-create projects in a single transaction. Project codes are
     * auto-generated using an in-memory per-prefix counter so we don't issue a
     * count() per insert. Rows whose generated code already exists are skipped.
     * One audit row records the batch summary.
     */
    public BulkCreateResult createAll(List<ProjectCreateRequestDTO> dtos,
                                      Authentication authentication,
                                      HttpServletRequest request) {
        if (dtos == null || dtos.isEmpty()) {
            return new BulkCreateResult(0, 0, 0, 0);
        }
        long start = System.currentTimeMillis();
        Instant now = Instant.now();
        String actor = resolveActorUsername(authentication);

        // Prefix → next sequence number. Untitled projects share one counter;
        // person-coded projects each have their own.
        Map<String, Long> nextSeq = new HashMap<>();

        List<Project> toInsert = new ArrayList<>(dtos.size());
        int skipped = 0;
        for (ProjectCreateRequestDTO dto : dtos) {
            if (dto == null
                    || dto.getProjectName() == null || dto.getProjectName().isBlank()
                    || dto.getCategoryCodes() == null || dto.getCategoryCodes().isEmpty()) {
                skipped++;
                continue;
            }
            List<Category> categories;
            Person person;
            try {
                categories = resolveCategories(dto.getCategoryCodes());
                person = resolvePerson(dto.getPersonCode());
            } catch (Exception e) {
                skipped++;
                continue;
            }

            String prefix = person != null
                    ? person.getPersonCode().toUpperCase(Locale.ROOT)
                    : "UNTITLED";
            // Lock once per prefix so concurrent bulk requests don't race on the counter.
            long seq = nextSeq.computeIfAbsent(prefix, p -> {
                codeGenLock.lock("project-code:" + p);
                return (person != null
                        ? projectRepository.countByPerson(person)
                        : projectRepository.countByPersonIsNull()) + 1L;
            });
            String projectCode = prefix + "_PROJ_" + String.format(Locale.ROOT, "%06d", seq);
            nextSeq.put(prefix, seq + 1);

            if (projectRepository.existsByProjectCodeAndRemovedAtIsNull(projectCode)) {
                skipped++;
                continue;
            }

            Project project = Project.builder()
                    .projectCode(projectCode)
                    .projectName(dto.getProjectName())
                    .person(person)
                    .categories(new ArrayList<>(categories))
                    .description(dto.getDescription())
                    .tags(dto.getTags() != null ? new ArrayList<>(dto.getTags()) : new ArrayList<>())
                    .keywords(dto.getKeywords() != null ? new ArrayList<>(dto.getKeywords()) : new ArrayList<>())
                    .createdAt(now)
                    .updatedAt(now)
                    .createdBy(actor)
                    .updatedBy(actor)
                    .build();
            toInsert.add(project);
        }

        projectRepository.saveAll(toInsert);
        readCache.evictAll();

        long elapsed = System.currentTimeMillis() - start;
        auditService.record(null, ProjectAuditAction.CREATE, authentication, request,
                "Bulk created projects: requested=" + dtos.size()
                        + " inserted=" + toInsert.size()
                        + " skipped=" + skipped
                        + " elapsedMs=" + elapsed);
        return new BulkCreateResult(dtos.size(), toInsert.size(), skipped, elapsed);
    }

    /**
     * Fast path: served from Redis on cache hit. On miss, one query loads
     * active projects; collections are batched-fetched (no N+1) thanks to
     * Hibernate's {@code default_batch_fetch_size}, then mapped to DTOs and
     * cached for 10 minutes. Audit is always recorded (cache fronts the read
     * but not the audit row).
     */
    @Transactional(readOnly = true)
    public Page<ProjectResponseDTO> getAll(Pageable pageable, Authentication authentication, HttpServletRequest request) {
        List<ProjectResponseDTO> all = readCache.getAllActive();
        Page<ProjectResponseDTO> page = PaginationSupport.sliceList(all, pageable);
        auditService.record(null, ProjectAuditAction.LIST, authentication, request,
                "Listed active projects (page=" + page.getNumber()
                        + " size=" + page.getSize()
                        + " returned=" + page.getNumberOfElements()
                        + " total=" + page.getTotalElements() + ")");
        return page;
    }

    @Transactional(readOnly = true)
    public ProjectResponseDTO getByProjectCode(String projectCode,
                                               Authentication authentication,
                                               HttpServletRequest request) {
        String normalized = normalizeProjectCode(projectCode);
        Project project = projectRepository.findByProjectCodeAndRemovedAtIsNull(normalized)
                .orElseThrow(() -> new ProjectNotFoundException("Project not found: " + projectCode));
        // Build the DTO while the outer session is still healthy: auditService.record
        // runs in REQUIRES_NEW, which suspends this session and prevents subsequent
        // lazy initialization of tags/keywords on the same entity.
        ProjectResponseDTO response = toResponse(project);
        auditService.record(project, ProjectAuditAction.READ, authentication, request,
                "Read project record");
        return response;
    }

    public ProjectResponseDTO update(String projectCode,
                                     ProjectUpdateRequestDTO dto,
                                     Authentication authentication,
                                     HttpServletRequest request) {
        String normalized = normalizeProjectCode(projectCode);
        Project project = projectRepository.findByProjectCodeAndRemovedAtIsNull(normalized)
                .orElseThrow(() -> new ProjectNotFoundException("Project not found: " + projectCode));

        StringBuilder changes = new StringBuilder();
        if (dto.getProjectName() != null && !dto.getProjectName().equals(project.getProjectName())) {
            changes.append("projectName: ").append(project.getProjectName()).append(" -> ").append(dto.getProjectName()).append(" | ");
            project.setProjectName(dto.getProjectName());
        }
        if (dto.getDescription() != null && !dto.getDescription().equals(project.getDescription())) {
            changes.append("description changed | ");
            project.setDescription(dto.getDescription());
        }
        if (dto.getCategoryCodes() != null) {
            List<Category> newCategories = resolveCategories(dto.getCategoryCodes());
            List<String> oldCodes = project.getCategories().stream().map(Category::getCategoryCode).toList();
            List<String> newCodes = newCategories.stream().map(Category::getCategoryCode).toList();
            changes.append("categories: ").append(oldCodes).append(" -> ").append(newCodes).append(" | ");
            project.setCategories(new ArrayList<>(newCategories));
        }
        if (dto.getTags() != null) {
            changes.append("tags: ").append(project.getTags()).append(" -> ").append(dto.getTags()).append(" | ");
            project.setTags(new ArrayList<>(dto.getTags()));
        }
        if (dto.getKeywords() != null) {
            changes.append("keywords: ").append(project.getKeywords()).append(" -> ").append(dto.getKeywords()).append(" | ");
            project.setKeywords(new ArrayList<>(dto.getKeywords()));
        }

        touchUpdateAudit(project, authentication);
        Project saved = projectRepository.save(project);
        readCache.evictAll();
        String detail = changes.isEmpty()
                ? "Updated project (no field changes detected)"
                : "Updated project: " + trimTrailingSeparator(changes.toString());
        // Build DTO before REQUIRES_NEW audit suspends this session — see getByProjectCode.
        ProjectResponseDTO response = toResponse(saved);
        auditService.record(saved, ProjectAuditAction.UPDATE, authentication, request, detail);
        return response;
    }

    /**
     * Soft delete (trash). Sends the project to the trash and cascades to its
     * media (audio, video, image, text) so they're trashed alongside it. The
     * linked person and categories are intentionally untouched — they're shared
     * resources that may be referenced by other projects.
     */
    public void delete(String projectCode,
                       Authentication authentication,
                       HttpServletRequest request) {
        String normalized = normalizeProjectCode(projectCode);
        Project project = projectRepository.findByProjectCodeAndRemovedAtIsNull(normalized)
                .orElseThrow(() -> new ProjectNotFoundException("Project not found: " + projectCode));

        Instant now = Instant.now();
        String actor = resolveActorUsername(authentication);

        // Snapshot the active media list BEFORE the bulk-update cascade fires.
        // Each entity's project graph is touched while the persistence context
        // is still intact, so the per-row cascade audits below can read
        // project/person/category fields even though the entity is detached
        // after `clearAutomatically=true` evicts everything.
        List<Audio> cascadedAudios = activeMedia(audioRepository.findAllByProject(project), Audio::getRemovedAt);
        List<Video> cascadedVideos = activeMedia(videoRepository.findAllByProject(project), Video::getRemovedAt);
        List<Image> cascadedImages = activeMedia(imageRepository.findAllByProject(project), Image::getRemovedAt);
        List<Text>  cascadedTexts  = activeMedia(textRepository.findAllByProject(project),  Text::getRemovedAt);
        cascadedAudios.forEach(this::touchAudioProjectGraph);
        cascadedVideos.forEach(this::touchVideoProjectGraph);
        cascadedImages.forEach(this::touchImageProjectGraph);
        cascadedTexts .forEach(this::touchTextProjectGraph);

        // Save the project BEFORE the cascade @Modifying queries fire. Those queries
        // run with clearAutomatically=true, which evicts the project from the
        // persistence context; a subsequent save() would then merge a fresh load
        // without our EntityGraph and re-introduce a lazy categories proxy that
        // auditService.record() (REQUIRES_NEW) cannot initialize.
        project.setRemovedAt(now);
        project.setRemovedBy(actor);
        Project saved = projectRepository.save(project);

        int trashedAudios = audioRepository.softTrashByProject(saved, now, actor);
        int trashedVideos = videoRepository.softTrashByProject(saved, now, actor);
        int trashedImages = imageRepository.softTrashByProject(saved, now, actor);
        int trashedTexts  = textRepository.softTrashByProject(saved, now, actor);

        readCache.evictAll();
        if (trashedAudios > 0) audioReadCache.evictAll();
        if (trashedVideos > 0) videoReadCache.evictAll();
        if (trashedImages > 0) imageReadCache.evictAll();
        if (trashedTexts  > 0) textReadCache.evictAll();

        // Per-row cascade audits: one DELETE row per cascaded media so the
        // entity audit log shows the soft-trash even though the SQL UPDATE
        // bypassed the entity service layer.
        String cascadeDetails = "Trashed via project cascade (project=" + saved.getProjectCode() + ")";
        for (Audio a : cascadedAudios) audioAuditService.record(a, AudioAuditAction.DELETE, authentication, request, cascadeDetails);
        for (Video v : cascadedVideos) videoAuditService.record(v, VideoAuditAction.DELETE, authentication, request, cascadeDetails);
        for (Image i : cascadedImages) imageAuditService.record(i, ImageAuditAction.DELETE, authentication, request, cascadeDetails);
        for (Text  t : cascadedTexts)  textAuditService.record(t,  TextAuditAction.DELETE,  authentication, request, cascadeDetails);

        auditService.record(saved, ProjectAuditAction.DELETE, authentication, request,
                "Sent project to trash (audios=" + trashedAudios
                        + " videos=" + trashedVideos
                        + " images=" + trashedImages
                        + " texts="  + trashedTexts + ")");
    }

    /**
     * Cascade-restore result returned from {@link #restore}. Lets the caller
     * tell the user how many media records came back with the project.
     */
    public record RestoreResult(
            ProjectResponseDTO project,
            int restoredAudios,
            int restoredVideos,
            int restoredImages,
            int restoredTexts
    ) {}

    /**
     * Restore a project from trash. Admin-only. Cascades to every media
     * record (audio/video/image/text) currently in trash for this project —
     * they come back to active state alongside the project. Categories and
     * the linked person are unaffected (they were never trashed).
     */
    public RestoreResult restore(String projectCode,
                                 Authentication authentication,
                                 HttpServletRequest request) {
        requireAdminRole(authentication);
        String normalized = normalizeProjectCode(projectCode);
        Project project = projectRepository.findByProjectCode(normalized)
                .orElseThrow(() -> new ProjectNotFoundException("Project not found: " + projectCode));

        if (project.getRemovedAt() == null) {
            throw new ProjectValidationException("Project is not in trash: " + projectCode);
        }
        if (projectRepository.existsByProjectCodeAndRemovedAtIsNull(normalized)) {
            throw new ProjectAlreadyExistsException("An active project with this code already exists: " + projectCode);
        }

        // Snapshot the trashed media list BEFORE the cascade @Modifying queries
        // fire (they run with clearAutomatically=true). Each entity's project
        // graph is touched so the per-row cascade audits below have everything
        // they need even after detachment.
        List<Audio> cascadedAudios = trashedMedia(audioRepository.findAllByProject(project), Audio::getRemovedAt);
        List<Video> cascadedVideos = trashedMedia(videoRepository.findAllByProject(project), Video::getRemovedAt);
        List<Image> cascadedImages = trashedMedia(imageRepository.findAllByProject(project), Image::getRemovedAt);
        List<Text>  cascadedTexts  = trashedMedia(textRepository.findAllByProject(project),  Text::getRemovedAt);
        cascadedAudios.forEach(this::touchAudioProjectGraph);
        cascadedVideos.forEach(this::touchVideoProjectGraph);
        cascadedImages.forEach(this::touchImageProjectGraph);
        cascadedTexts .forEach(this::touchTextProjectGraph);

        project.setRemovedAt(null);
        project.setRemovedBy(null);
        project.setUpdatedAt(Instant.now());
        project.setUpdatedBy(resolveActorUsername(authentication));
        Project saved = projectRepository.save(project);

        // Build the DTO before the cascade @Modifying queries fire — they run with
        // clearAutomatically=true and detach the project, after which lazy tags/
        // keywords can no longer be initialized.
        ProjectResponseDTO response = toResponse(saved);

        int restoredAudios = audioRepository.restoreByProject(saved);
        int restoredVideos = videoRepository.restoreByProject(saved);
        int restoredImages = imageRepository.restoreByProject(saved);
        int restoredTexts  = textRepository.restoreByProject(saved);

        readCache.evictAll();
        if (restoredAudios > 0) audioReadCache.evictAll();
        if (restoredVideos > 0) videoReadCache.evictAll();
        if (restoredImages > 0) imageReadCache.evictAll();
        if (restoredTexts  > 0) textReadCache.evictAll();

        // Per-row cascade audits: one RESTORE row per media that came back.
        String cascadeDetails = "Restored via project cascade (project=" + saved.getProjectCode() + ")";
        for (Audio a : cascadedAudios) audioAuditService.record(a, AudioAuditAction.RESTORE, authentication, request, cascadeDetails);
        for (Video v : cascadedVideos) videoAuditService.record(v, VideoAuditAction.RESTORE, authentication, request, cascadeDetails);
        for (Image i : cascadedImages) imageAuditService.record(i, ImageAuditAction.RESTORE, authentication, request, cascadeDetails);
        for (Text  t : cascadedTexts)  textAuditService.record(t,  TextAuditAction.RESTORE,  authentication, request, cascadeDetails);

        auditService.record(saved, ProjectAuditAction.RESTORE, authentication, request,
                "Restored project from trash (audios=" + restoredAudios
                        + " videos=" + restoredVideos
                        + " images=" + restoredImages
                        + " texts="  + restoredTexts + ")");
        return new RestoreResult(response, restoredAudios, restoredVideos, restoredImages, restoredTexts);
    }

    /**
     * Permanently delete a project from the trash. Admin-only. Cascades to all
     * media (audio/video/image/text) belonging to the project — both database
     * rows and S3 files. The project itself must already be in trash. The
     * linked person and categories are left intact (they are shared resources).
     */
    public void purge(String projectCode,
                      Authentication authentication,
                      HttpServletRequest request) {
        requireAdminRole(authentication);
        String normalized = normalizeProjectCode(projectCode);
        Project project = projectRepository.findByProjectCode(normalized)
                .orElseThrow(() -> new ProjectNotFoundException("Project not found: " + projectCode));

        if (project.getRemovedAt() == null) {
            throw new ProjectValidationException(
                    "Project must be in trash before permanent deletion. Trash it first.");
        }

        List<Audio> audios = audioRepository.findAllByProject(project);
        List<Video> videos = videoRepository.findAllByProject(project);
        List<Image> images = imageRepository.findAllByProject(project);
        List<Text>  texts  = textRepository.findAllByProject(project);
        audios.forEach(this::touchAudioProjectGraph);
        videos.forEach(this::touchVideoProjectGraph);
        images.forEach(this::touchImageProjectGraph);
        texts .forEach(this::touchTextProjectGraph);

        for (Audio a : audios) deleteStoredFile(a.getAudioFileUrl());
        for (Video v : videos) deleteStoredFile(v.getVideoFileUrl());
        for (Image i : images) deleteStoredFile(i.getImageFileUrl());
        for (Text  t : texts)  deleteStoredFile(t.getTextFileUrl());

        // Per-row cascade audits emitted BEFORE the deleteAll wipes the rows.
        // Each REQUIRES_NEW commit captures the entity even if the surrounding
        // outer transaction were to fail later.
        String cascadeDetails = "Purged via project cascade (project=" + project.getProjectCode() + ")";
        for (Audio a : audios) audioAuditService.record(a, AudioAuditAction.PURGE, authentication, request, cascadeDetails);
        for (Video v : videos) videoAuditService.record(v, VideoAuditAction.PURGE, authentication, request, cascadeDetails);
        for (Image i : images) imageAuditService.record(i, ImageAuditAction.PURGE, authentication, request, cascadeDetails);
        for (Text  t : texts)  textAuditService.record(t,  TextAuditAction.PURGE,  authentication, request, cascadeDetails);

        audioRepository.deleteAll(audios);
        videoRepository.deleteAll(videos);
        imageRepository.deleteAll(images);
        textRepository.deleteAll(texts);

        auditService.record(project, ProjectAuditAction.PURGE, authentication, request,
                "Permanently deleted project (audios=" + audios.size()
                        + " videos=" + videos.size()
                        + " images=" + images.size()
                        + " texts="  + texts.size() + ")");
        projectRepository.delete(project);

        readCache.evictAll();
        if (!audios.isEmpty()) audioReadCache.evictAll();
        if (!videos.isEmpty()) videoReadCache.evictAll();
        if (!images.isEmpty()) imageReadCache.evictAll();
        if (!texts.isEmpty())  textReadCache.evictAll();
    }

    private void deleteStoredFile(String url) {
        if (url != null && !url.isBlank() && s3Service.isOurS3Url(url)) {
            s3Service.deleteFile(url);
        }
    }

    /**
     * List projects in the trash. Admin-only.
     */
    @Transactional(readOnly = true)
    public Page<ProjectResponseDTO> getTrash(Pageable pageable,
                                             Authentication authentication,
                                             HttpServletRequest request) {
        requireAdminRole(authentication);
        List<ProjectResponseDTO> all = projectRepository.findAllByRemovedAtIsNotNull().stream()
                .map(this::toResponse)
                .toList();
        Page<ProjectResponseDTO> page = PaginationSupport.sliceList(all, pageable);
        auditService.record(null, ProjectAuditAction.LIST, authentication, request,
                "Listed projects in trash (page=" + page.getNumber()
                        + " size=" + page.getSize()
                        + " returned=" + page.getNumberOfElements()
                        + " total=" + page.getTotalElements() + ")");
        return page;
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    /**
     * Generates a unique project code with a sequence number.
     * <p>
     * One person can have many projects, so the code includes a sequence:
     * <ul>
     *   <li>Person project: PERSONCODE_PROJ_000001, PERSONCODE_PROJ_000002, ...</li>
     *   <li>Untitled project: UNTITLED_PROJ_000001, UNTITLED_PROJ_000002, ...</li>
     * </ul>
     */
    private String generateProjectCode(Person person, List<Category> categories) {
        String prefix = person != null
                ? person.getPersonCode().toUpperCase(Locale.ROOT)
                : "UNTITLED";

        long sequence = (person != null
                ? projectRepository.countByPerson(person)
                : projectRepository.countByPersonIsNull()) + 1;

        return prefix + "_PROJ_" + String.format(Locale.ROOT, "%06d", sequence);
    }

    private List<Category> resolveCategories(List<String> categoryCodes) {
        if (categoryCodes == null || categoryCodes.isEmpty()) {
            throw new ProjectValidationException("At least one category code is required");
        }
        List<Category> categories = new ArrayList<>();
        for (String code : categoryCodes) {
            String normalized = CategoryCodeHelper.normalizeAndValidate(code);
            Category category = categoryRepository.findByCategoryCodeAndRemovedAtIsNull(normalized)
                    .orElseThrow(() -> new CategoryNotFoundException("Category not found: " + code));
            categories.add(category);
        }
        return categories;
    }

    private Person resolvePerson(String personCode) {
        if (personCode == null || personCode.isBlank()) {
            return null;
        }
        String trimmed = personCode.trim();
        if (!trimmed.matches(ValidationPatterns.PERSON_CODE)) {
            throw new ProjectValidationException("Invalid person code format: " + trimmed);
        }
        return personRepository.findByPersonCodeAndRemovedAtIsNull(trimmed)
                .orElseThrow(() -> new PersonNotFoundException("Person not found: " + trimmed));
    }

    private String normalizeProjectCode(String projectCode) {
        if (projectCode == null || projectCode.isBlank()) {
            throw new ProjectValidationException("Project code is required");
        }
        return projectCode.trim();
    }

    private void touchCreateAudit(Project project, Authentication authentication) {
        Instant now = Instant.now();
        String actor = resolveActorUsername(authentication);
        project.setCreatedAt(now);
        project.setUpdatedAt(now);
        project.setCreatedBy(actor);
        project.setUpdatedBy(actor);
    }

    private void touchUpdateAudit(Project project, Authentication authentication) {
        project.setUpdatedAt(Instant.now());
        project.setUpdatedBy(resolveActorUsername(authentication));
    }

    private String resolveActorUsername(Authentication authentication) {
        return authentication == null ? "anonymous" : authentication.getName();
    }

    private void requireAdminRole(Authentication authentication) {
        if (authentication == null) {
            throw new AccessDeniedException("Authentication is required for this operation");
        }
        boolean canHardDelete = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("project:delete"::equals);
        if (!canHardDelete) {
            throw new AccessDeniedException("Only ADMIN can permanently delete project records");
        }
    }

    private ProjectResponseDTO toResponse(Project project) {
        List<ProjectResponseDTO.CategorySummary> categorySummaries = null;
        if (project.getCategories() != null) {
            categorySummaries = project.getCategories().stream()
                    .map(c -> ProjectResponseDTO.CategorySummary.builder()
                            .id(c.getId())
                            .categoryCode(c.getCategoryCode())
                            .categoryName(c.getName())
                            .build())
                    .toList();
        }

        return ProjectResponseDTO.builder()
                .id(project.getId())
                .projectCode(project.getProjectCode())
                .projectName(project.getProjectName())
                .personId(project.getPerson() != null ? project.getPerson().getId() : null)
                .personCode(project.getPerson() != null ? project.getPerson().getPersonCode() : null)
                .personName(project.getPerson() != null ? project.getPerson().getFullName() : null)
                .categories(categorySummaries)
                .description(project.getDescription())
                .tags(project.getTags() != null ? new ArrayList<>(project.getTags()) : null)
                .keywords(project.getKeywords() != null ? new ArrayList<>(project.getKeywords()) : null)
                .createdAt(project.getCreatedAt())
                .updatedAt(project.getUpdatedAt())
                .removedAt(project.getRemovedAt())
                .createdBy(project.getCreatedBy())
                .updatedBy(project.getUpdatedBy())
                .removedBy(project.getRemovedBy())
                .build();
    }

    private String trimTrailingSeparator(String value) {
        return value.endsWith(" | ") ? value.substring(0, value.length() - 3) : value;
    }

    // ─── Cascade-audit helpers ──────────────────────────────────────────────

    /** Filter to media rows currently active (removedAt IS NULL). */
    private static <T> List<T> activeMedia(List<T> all, java.util.function.Function<T, Instant> removedAt) {
        return all.stream().filter(t -> removedAt.apply(t) == null).toList();
    }

    /** Filter to media rows currently in trash (removedAt IS NOT NULL). */
    private static <T> List<T> trashedMedia(List<T> all, java.util.function.Function<T, Instant> removedAt) {
        return all.stream().filter(t -> removedAt.apply(t) != null).toList();
    }

    /**
     * Force-init the lazy associations that the corresponding *AuditService.record(...)
     * reads. Must run while the persistence context is still alive — once the cascade
     * @Modifying queries fire with clearAutomatically=true, the entity is detached and
     * lazy proxies can't be resolved. Touching getProjectCode() is enough to materialise
     * the full graph because Project itself is loaded with EntityGraph(categories, person).
     */
    private void touchAudioProjectGraph(Audio a) { if (a.getProject() != null) a.getProject().getProjectCode(); }
    private void touchVideoProjectGraph(Video v) { if (v.getProject() != null) v.getProject().getProjectCode(); }
    private void touchImageProjectGraph(Image i) { if (i.getProject() != null) i.getProject().getProjectCode(); }
    private void touchTextProjectGraph(Text t)   { if (t.getProject() != null) t.getProject().getProjectCode(); }
}
