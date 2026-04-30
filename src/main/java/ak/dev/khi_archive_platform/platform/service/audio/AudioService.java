package ak.dev.khi_archive_platform.platform.service.audio;

import ak.dev.khi_archive_platform.S3Service;
import ak.dev.khi_archive_platform.platform.dto.audio.AudioBaseRequestDTO;
import ak.dev.khi_archive_platform.platform.dto.audio.AudioBulkCreateRequestDTO;
import ak.dev.khi_archive_platform.platform.dto.audio.AudioCreateRequestDTO;
import ak.dev.khi_archive_platform.platform.dto.audio.AudioResponseDTO;
import ak.dev.khi_archive_platform.platform.dto.audio.AudioUpdateRequestDTO;
import ak.dev.khi_archive_platform.platform.enums.AudioAuditAction;
import ak.dev.khi_archive_platform.platform.exceptions.AudioAlreadyExistsException;
import ak.dev.khi_archive_platform.platform.exceptions.AudioNotFoundException;
import ak.dev.khi_archive_platform.platform.exceptions.AudioValidationException;
import ak.dev.khi_archive_platform.platform.exceptions.ProjectNotFoundException;
import ak.dev.khi_archive_platform.platform.model.audio.Audio;
import ak.dev.khi_archive_platform.platform.model.project.Project;
import ak.dev.khi_archive_platform.platform.repo.audio.AudioRepository;
import ak.dev.khi_archive_platform.platform.repo.project.ProjectRepository;
import ak.dev.khi_archive_platform.platform.service.common.CodeGenLock;
import ak.dev.khi_archive_platform.platform.service.common.MediaSearchSqlBuilder;
import ak.dev.khi_archive_platform.platform.service.common.PaginationSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Transactional
public class AudioService {

    private static final String AUDIO_FOLDER = "audios";

    private final AudioRepository audioRepository;
    private final ProjectRepository projectRepository;
    private final AudioAuditService audioAuditService;
    private final S3Service s3Service;
    private final AudioReadCache readCache;
    private final CodeGenLock codeGenLock;

    @PersistenceContext
    private EntityManager entityManager;

    private static final MediaSearchSqlBuilder.Spec AUDIO_SEARCH_SPEC = new MediaSearchSqlBuilder.Spec(
            "audios",
            "id",
            List.of(
                    "origin_title", "alter_title", "central_kurdish_title", "romanized_title",
                    "audio_code", "fullname",
                    "speaker", "composer", "poet", "producer",
                    "city", "region", "type_of_basta", "type_of_maqam"
            ),
            List.of(
                    "audio_code", "fullname", "volume_name", "directory_name", "path_in_external", "auto_path",
                    "origin_title", "alter_title", "central_kurdish_title", "romanized_title",
                    "form", "type_of_basta", "type_of_maqam",
                    "abstract_text", "description",
                    "speaker", "producer", "composer",
                    "language", "dialect", "type_of_composition", "type_of_performance",
                    "lyrics", "poet",
                    "recording_venue", "city", "region", "audience",
                    "physical_label", "location_archive",
                    "degitized_by", "degitization_equipment", "audio_file_note",
                    "audio_channel", "audio_version",
                    "lcc_classification", "accrual_method", "provenance",
                    "copyright", "right_owner", "availability", "license_type", "usage_rights",
                    "owner", "publisher", "archive_local_note"
            ),
            List.of(
                    new MediaSearchSqlBuilder.ChildTable("audio_genres",       "audio_id", "genre"),
                    new MediaSearchSqlBuilder.ChildTable("audio_contributors", "audio_id", "contributor"),
                    new MediaSearchSqlBuilder.ChildTable("audio_tags",         "audio_id", "tag"),
                    new MediaSearchSqlBuilder.ChildTable("audio_keywords",     "audio_id", "keyword")
            )
    );

    public AudioResponseDTO create(AudioCreateRequestDTO dto,
                                   MultipartFile audioFile,
                                   Authentication authentication,
                                   HttpServletRequest request) {
        validateCreate(dto, audioFile);

        Project project = resolveProject(dto.getProjectCode());

        String audioVersion = dto.getAudioVersion().toUpperCase(Locale.ROOT);
        Integer versionNumber = dto.getVersionNumber();
        Integer copyNumber = dto.getCopyNumber();
        if (!"RAW".equals(audioVersion) && !"MASTER".equals(audioVersion)) {
            throw new AudioValidationException("Audio version must be RAW or MASTER");
        }
        if (versionNumber == null || versionNumber < 1) {
            throw new AudioValidationException("Version number is required and must be at least 1");
        }
        if (copyNumber == null || copyNumber < 1) {
            throw new AudioValidationException("Copy number is required and must be at least 1");
        }

        // Serialise concurrent creates for the same project so the
        // count-based sequence number can't collide.
        codeGenLock.lock("audio-code:" + project.getId());
        String audioCode = generateAudioCode(project, audioVersion, versionNumber, copyNumber);

        if (audioRepository.existsByAudioCode(audioCode)) {
            throw new AudioAlreadyExistsException("Audio code already exists: " + audioCode);
        }

        Audio audio = new Audio();
        audio.setAudioCode(audioCode);
        audio.setProject(project);
        applyDto(audio, dto);
        audio.setAudioFileUrl(uploadAudioFile(audioFile, audioCode));
        touchCreateAudit(audio, authentication);

        Audio saved = audioRepository.save(audio);
        readCache.evictAll();
        audioAuditService.record(saved, AudioAuditAction.CREATE, authentication, request, buildCreateDetails(saved));
        return toResponse(saved);
    }

    public record BulkCreateResult(int requested, int inserted, int skipped, long elapsedMs) {}

    /**
     * Bulk-create audio records from a JSON array. Each entry carries its own
     * {@code audioFileUrl} (no multipart upload). Audio codes are auto-generated
     * using an in-memory per-project counter. Rows that fail validation or
     * whose generated code already exists are skipped. One audit summary.
     */
    public BulkCreateResult createAll(List<AudioBulkCreateRequestDTO> dtos,
                                      Authentication authentication,
                                      HttpServletRequest request) {
        if (dtos == null || dtos.isEmpty()) {
            return new BulkCreateResult(0, 0, 0, 0);
        }
        long start = System.currentTimeMillis();
        Instant now = Instant.now();
        String actor = resolveActorUsername(authentication);

        Map<String, Project> projectByCode = new HashMap<>();
        Map<Long, Long> nextSeqByProject = new HashMap<>();

        List<Audio> toInsert = new ArrayList<>(dtos.size());
        int skipped = 0;
        for (AudioBulkCreateRequestDTO dto : dtos) {
            if (dto == null || dto.getProjectCode() == null || dto.getProjectCode().isBlank()
                    || dto.getAudioVersion() == null
                    || dto.getVersionNumber() == null || dto.getVersionNumber() < 1
                    || dto.getCopyNumber() == null || dto.getCopyNumber() < 1) {
                skipped++;
                continue;
            }
            String version = dto.getAudioVersion().toUpperCase(Locale.ROOT);
            if (!"RAW".equals(version) && !"MASTER".equals(version)) {
                skipped++;
                continue;
            }

            Project project;
            try {
                project = projectByCode.computeIfAbsent(dto.getProjectCode().trim(), this::resolveProject);
            } catch (Exception e) {
                skipped++;
                continue;
            }

            long seq = nextSeqByProject.computeIfAbsent(project.getId(), pid -> {
                codeGenLock.lock("audio-code:" + pid);
                return audioRepository.countByProject(project) + 1L;
            });
            String parentCode = project.getPerson() != null
                    ? project.getPerson().getPersonCode().toUpperCase(Locale.ROOT)
                    : project.getCategories().get(0).getCategoryCode().toUpperCase(Locale.ROOT);
            String audioCode = parentCode
                    + "_AUD_" + version
                    + "_V" + dto.getVersionNumber()
                    + "_Copy(" + dto.getCopyNumber() + ")"
                    + "_" + String.format(Locale.ROOT, "%06d", seq);
            nextSeqByProject.put(project.getId(), seq + 1);

            if (audioRepository.existsByAudioCode(audioCode)) {
                skipped++;
                continue;
            }

            Audio audio = new Audio();
            audio.setAudioCode(audioCode);
            audio.setProject(project);
            applyDto(audio, dto);
            audio.setAudioFileUrl(dto.getAudioFileUrl());
            audio.setCreatedAt(now);
            audio.setUpdatedAt(now);
            audio.setCreatedBy(actor);
            audio.setUpdatedBy(actor);
            toInsert.add(audio);
        }

        audioRepository.saveAll(toInsert);
        readCache.evictAll();

        long elapsed = System.currentTimeMillis() - start;
        audioAuditService.record(null, AudioAuditAction.CREATE, authentication, request,
                "Bulk created audios: requested=" + dtos.size()
                        + " inserted=" + toInsert.size()
                        + " skipped=" + skipped
                        + " elapsedMs=" + elapsed);
        return new BulkCreateResult(dtos.size(), toInsert.size(), skipped, elapsed);
    }

    /** Fast path: served from Redis on hit; on miss loads with batched fetches and caches. */
    @Transactional(readOnly = true)
    public Page<AudioResponseDTO> getAll(Pageable pageable, Authentication authentication, HttpServletRequest request) {
        List<AudioResponseDTO> all = readCache.getAllActive();
        Page<AudioResponseDTO> page = PaginationSupport.sliceList(all, pageable);
        audioAuditService.record(null, AudioAuditAction.LIST, authentication, request,
                "Listed active audio records (page=" + page.getNumber()
                        + " size=" + page.getSize()
                        + " returned=" + page.getNumberOfElements()
                        + " total=" + page.getTotalElements() + ")");
        return page;
    }

    private static final int SEARCH_DEFAULT_LIMIT = 20;
    private static final int SEARCH_MAX_LIMIT = 100;
    private static final int SEARCH_PREFILTER_LIMIT = 2000;

    /**
     * Multi-token AND search. See ImageService.search for the full algorithm.
     */
    @Transactional(readOnly = true)
    public List<AudioResponseDTO> search(String query,
                                         Integer limit,
                                         Authentication authentication,
                                         HttpServletRequest request) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String normalized = query.trim();
        int effectiveLimit = (limit == null || limit <= 0)
                ? SEARCH_DEFAULT_LIMIT
                : Math.min(limit, SEARCH_MAX_LIMIT);

        List<String> tokens = MediaSearchSqlBuilder.tokenize(normalized);
        if (tokens.isEmpty()) {
            return List.of();
        }
        MediaSearchSqlBuilder.Built built = MediaSearchSqlBuilder.build(
                AUDIO_SEARCH_SPEC, tokens, SEARCH_PREFILTER_LIMIT, effectiveLimit);
        jakarta.persistence.Query nq = entityManager.createNativeQuery(built.sql(), Audio.class);
        built.params().forEach(nq::setParameter);
        @SuppressWarnings("unchecked")
        List<Audio> rows = (List<Audio>) nq.getResultList();
        List<AudioResponseDTO> result = rows.stream().map(this::toResponse).toList();

        audioAuditService.record(null, AudioAuditAction.SEARCH, authentication, request,
                "Searched audios q=\"" + normalized + "\" tokens=" + tokens
                        + " limit=" + effectiveLimit + " hits=" + result.size());
        return result;
    }

    @Transactional(readOnly = true)
    public AudioResponseDTO getByAudioCode(String audioCode,
                                           Authentication authentication,
                                           HttpServletRequest request) {
        String normalized = normalizeRequiredCode(audioCode, "Audio code");
        Audio audio = audioRepository.findByAudioCodeAndRemovedAtIsNull(normalized)
                .orElseThrow(() -> new AudioNotFoundException("Audio not found: " + audioCode));
        audioAuditService.record(audio, AudioAuditAction.READ, authentication, request, "Read audio record");
        return toResponse(audio);
    }

    public AudioResponseDTO update(String audioCode,
                                   AudioUpdateRequestDTO dto,
                                   MultipartFile audioFile,
                                   Authentication authentication,
                                   HttpServletRequest request) {
        String normalized = normalizeRequiredCode(audioCode, "Audio code");
        Audio audio = audioRepository.findByAudioCodeAndRemovedAtIsNull(normalized)
                .orElseThrow(() -> new AudioNotFoundException("Audio not found: " + audioCode));

        Audio before = snapshot(audio);
        if (dto != null) {
            validateProjectNotChanged(audio, dto);
            applyDto(audio, dto);
        }

        String oldAudioFileUrl = audio.getAudioFileUrl();
        if (audioFile != null && !audioFile.isEmpty()) {
            String newAudioFileUrl = uploadAudioFile(audioFile, normalized);
            audio.setAudioFileUrl(newAudioFileUrl);
            if (oldAudioFileUrl != null && !Objects.equals(oldAudioFileUrl, newAudioFileUrl)) {
                deleteStoredFile(oldAudioFileUrl);
            }
        }

        touchUpdateAudit(audio, authentication);
        Audio saved = audioRepository.save(audio);
        readCache.evictAll();
        audioAuditService.record(saved, AudioAuditAction.UPDATE, authentication, request, buildUpdateDetails(before, saved));
        return toResponse(saved);
    }

    /**
     * Soft delete (trash). The S3 file is preserved so the record can be
     * restored later. Admin-only via {@code audio:delete} authority.
     */
    public void delete(String audioCode,
                       Authentication authentication,
                       HttpServletRequest request) {
        String normalized = normalizeRequiredCode(audioCode, "Audio code");
        Audio audio = audioRepository.findByAudioCodeAndRemovedAtIsNull(normalized)
                .orElseThrow(() -> new AudioNotFoundException("Audio not found: " + audioCode));

        audio.setRemovedAt(Instant.now());
        audio.setRemovedBy(resolveActorUsername(authentication));
        Audio saved = audioRepository.save(audio);
        readCache.evictAll();
        audioAuditService.record(saved, AudioAuditAction.DELETE, authentication, request,
                "Sent audio record to trash");
    }

    /**
     * Restore an audio record from trash. Admin-only. Fails if the parent
     * project is itself in trash — restore the project first.
     */
    public AudioResponseDTO restore(String audioCode,
                                    Authentication authentication,
                                    HttpServletRequest request) {
        requireAdminRole(authentication);
        String normalized = normalizeRequiredCode(audioCode, "Audio code");
        Audio audio = audioRepository.findByAudioCode(normalized)
                .orElseThrow(() -> new AudioNotFoundException("Audio not found: " + audioCode));

        if (audio.getRemovedAt() == null) {
            throw new AudioValidationException("Audio is not in trash: " + audioCode);
        }
        if (audio.getProject() != null && audio.getProject().getRemovedAt() != null) {
            throw new AudioValidationException(
                    "Cannot restore audio while its project is in trash. Restore the project first.");
        }

        audio.setRemovedAt(null);
        audio.setRemovedBy(null);
        audio.setUpdatedAt(Instant.now());
        audio.setUpdatedBy(resolveActorUsername(authentication));
        Audio saved = audioRepository.save(audio);
        readCache.evictAll();
        audioAuditService.record(saved, AudioAuditAction.RESTORE, authentication, request,
                "Restored audio record from trash");
        return toResponse(saved);
    }

    /**
     * Permanently delete an audio record from the trash. Admin-only. The record
     * must already be in trash. Removes both the database row and the S3 file.
     */
    public void purge(String audioCode,
                      Authentication authentication,
                      HttpServletRequest request) {
        requireAdminRole(authentication);
        String normalized = normalizeRequiredCode(audioCode, "Audio code");
        Audio audio = audioRepository.findByAudioCode(normalized)
                .orElseThrow(() -> new AudioNotFoundException("Audio not found: " + audioCode));

        if (audio.getRemovedAt() == null) {
            throw new AudioValidationException(
                    "Audio must be in trash before permanent deletion. Trash it first.");
        }

        String fileUrl = audio.getAudioFileUrl();
        audioAuditService.record(audio, AudioAuditAction.PURGE, authentication, request,
                "Permanently deleted audio record from trash");
        audioRepository.delete(audio);
        readCache.evictAll();
        deleteStoredFile(fileUrl);
    }

    /**
     * List audio records in the trash. Admin-only.
     */
    @Transactional(readOnly = true)
    public Page<AudioResponseDTO> getTrash(Pageable pageable,
                                           Authentication authentication,
                                           HttpServletRequest request) {
        requireAdminRole(authentication);
        List<AudioResponseDTO> all = audioRepository.findAllByRemovedAtIsNotNull().stream()
                .map(this::toResponse)
                .toList();
        Page<AudioResponseDTO> page = PaginationSupport.sliceList(all, pageable);
        audioAuditService.record(null, AudioAuditAction.LIST, authentication, request,
                "Listed audio trash (page=" + page.getNumber()
                        + " size=" + page.getSize()
                        + " returned=" + page.getNumberOfElements()
                        + " total=" + page.getTotalElements() + ")");
        return page;
    }

    // ─── Validation ──────────────────────────────────────────────────────────────

    private void validateCreate(AudioCreateRequestDTO dto, MultipartFile audioFile) {
        if (dto == null) {
            throw new AudioValidationException("Audio payload is required");
        }
        if (audioFile == null || audioFile.isEmpty()) {
            throw new AudioValidationException("Audio file is required");
        }
        if (dto.getProjectCode() == null || dto.getProjectCode().isBlank()) {
            throw new AudioValidationException("Project code is required");
        }
    }

    private void validateProjectNotChanged(Audio audio, AudioBaseRequestDTO dto) {
        if (dto.getProjectCode() != null && !dto.getProjectCode().isBlank()) {
            String currentProjectCode = audio.getProject() != null ? audio.getProject().getProjectCode() : null;
            if (!dto.getProjectCode().trim().equals(currentProjectCode)) {
                throw new AudioValidationException("Audio project cannot be changed after creation. Create a new audio record instead.");
            }
        }
    }

    // ─── Code Generation ─────────────────────────────────────────────────────────

    /**
     * Generates audio code in the format: PARENT_AUD_VERSION_VN_Copy(CN)_SEQUENCE
     * <p>
     * If the project has a person: PERSONCODE_AUD_RAW_V1_Copy(1)_000001
     * If the project has no person: CATEGORYCODE_AUD_RAW_V1_Copy(1)_000001
     */
    private String generateAudioCode(Project project, String audioVersion, Integer versionNumber, Integer copyNumber) {
        String parentCode;
        if (project.getPerson() != null) {
            parentCode = project.getPerson().getPersonCode().toUpperCase(Locale.ROOT);
        } else {
            // Use the first category code for untitled projects
            parentCode = project.getCategories().get(0).getCategoryCode().toUpperCase(Locale.ROOT);
        }

        long sequence = audioRepository.countByProject(project) + 1;

        return parentCode
                + "_AUD_" + audioVersion
                + "_V" + versionNumber
                + "_Copy(" + copyNumber + ")"
                + "_" + String.format(Locale.ROOT, "%06d", sequence);
    }

    // ─── DTO Mapping ─────────────────────────────────────────────────────────────

    private void applyDto(Audio audio, AudioBaseRequestDTO dto) {
        if (dto == null) {
            return;
        }

        BeanUtils.copyProperties(dto, audio,
                "projectCode",
                "fullName", "pathInExternal", "autoPath",
                "centralKurdishTitle", "romanizedTitle",
                "recordingVenue", "dateCreated", "datePublished", "dateModified",
                "lccClassification", "physicalAvailability");

        if (dto.getFullName() != null) audio.setFullname(dto.getFullName());
        if (dto.getPathInExternal() != null) audio.setPath_in_external(dto.getPathInExternal());
        if (dto.getAutoPath() != null) audio.setAuto_path(dto.getAutoPath());
        if (dto.getCentralKurdishTitle() != null) audio.setCentral_kurdish_title(dto.getCentralKurdishTitle());
        if (dto.getRomanizedTitle() != null) audio.setRomanized_title(dto.getRomanizedTitle());
        if (dto.getRecordingVenue() != null) audio.setRecording_venue(dto.getRecordingVenue());
        if (dto.getDateCreated() != null) audio.setDate_created(dto.getDateCreated());
        if (dto.getDatePublished() != null) audio.setDate_published(dto.getDatePublished());
        if (dto.getDateModified() != null) audio.setDate_modified(dto.getDateModified());
        if (dto.getLccClassification() != null) audio.setLcc_classification(dto.getLccClassification());
        if (dto.getPhysicalAvailability() != null) audio.setPhysicalAvailability(dto.getPhysicalAvailability());
        if (dto.getAudioVersion() != null) audio.setAudioVersion(dto.getAudioVersion().toUpperCase(Locale.ROOT));
    }

    AudioResponseDTO toResponse(Audio audio) {
        if (audio == null) {
            return null;
        }

        Project project = audio.getProject();
        AudioResponseDTO response = new AudioResponseDTO();
        response.setId(audio.getId());
        response.setAudioCode(audio.getAudioCode());

        // Project info
        response.setProjectId(project != null ? project.getId() : null);
        response.setProjectCode(project != null ? project.getProjectCode() : null);
        response.setProjectName(project != null ? project.getProjectName() : null);

        // Person info (via project)
        response.setPersonId(project != null && project.getPerson() != null ? project.getPerson().getId() : null);
        response.setPersonCode(project != null && project.getPerson() != null ? project.getPerson().getPersonCode() : null);
        response.setPersonName(project != null && project.getPerson() != null ? project.getPerson().getFullName() : null);

        // Category info (via project)
        response.setCategoryCodes(project != null && project.getCategories() != null
                ? project.getCategories().stream().map(c -> c.getCategoryCode()).toList()
                : null);

        response.setAudioFileUrl(audio.getAudioFileUrl());
        response.setFullName(audio.getFullname());
        response.setVolumeName(audio.getVolumeName());
        response.setDirectoryName(audio.getDirectoryName());
        response.setPathInExternal(audio.getPath_in_external());
        response.setAutoPath(audio.getAuto_path());
        response.setOriginTitle(audio.getOriginTitle());
        response.setAlterTitle(audio.getAlterTitle());
        response.setCentralKurdishTitle(audio.getCentral_kurdish_title());
        response.setRomanizedTitle(audio.getRomanized_title());
        response.setForm(audio.getForm());
        response.setTypeOfBasta(audio.getTypeOfBasta());
        response.setTypeOfMaqam(audio.getTypeOfMaqam());
        response.setGenre(audio.getGenre() == null ? null : new ArrayList<>(audio.getGenre()));
        response.setAbstractText(audio.getAbstractText());
        response.setDescription(audio.getDescription());
        response.setSpeaker(audio.getSpeaker());
        response.setProducer(audio.getProducer());
        response.setComposer(audio.getComposer());
        response.setContributors(audio.getContributors() == null ? null : new ArrayList<>(audio.getContributors()));
        response.setLanguage(audio.getLanguage());
        response.setDialect(audio.getDialect());
        response.setTypeOfComposition(audio.getTypeOfComposition());
        response.setTypeOfPerformance(audio.getTypeOfPerformance());
        response.setLyrics(audio.getLyrics());
        response.setPoet(audio.getPoet());
        response.setRecordingVenue(audio.getRecording_venue());
        response.setCity(audio.getCity());
        response.setRegion(audio.getRegion());
        response.setDateCreated(audio.getDate_created());
        response.setDatePublished(audio.getDate_published());
        response.setDateModified(audio.getDate_modified());
        response.setAudience(audio.getAudience());
        response.setTags(audio.getTags() == null ? null : new ArrayList<>(audio.getTags()));
        response.setKeywords(audio.getKeywords() == null ? null : new ArrayList<>(audio.getKeywords()));
        response.setPhysicalAvailability(audio.isPhysicalAvailability());
        response.setPhysicalLabel(audio.getPhysicalLabel());
        response.setLocationArchive(audio.getLocationArchive());
        response.setDegitizedBy(audio.getDegitizedBy());
        response.setDegitizationEquipment(audio.getDegitizationEquipment());
        response.setAudioFileNote(audio.getAudioFileNote());
        response.setAudioChannel(audio.getAudioChannel());
        response.setFileExtension(audio.getFileExtension());
        response.setFileSize(audio.getFileSize());
        response.setBitRate(audio.getBitRate());
        response.setBitDepth(audio.getBitDepth());
        response.setSampleRate(audio.getSampleRate());
        response.setAudioQualityOutOf10(audio.getAudioQualityOutOf10());
        response.setAudioVersion(audio.getAudioVersion());
        response.setVersionNumber(audio.getVersionNumber());
        response.setCopyNumber(audio.getCopyNumber());
        response.setLccClassification(audio.getLcc_classification());
        response.setAccrualMethod(audio.getAccrualMethod());
        response.setProvenance(audio.getProvenance());
        response.setCopyright(audio.getCopyright());
        response.setRightOwner(audio.getRightOwner());
        response.setDateCopyrighted(audio.getDateCopyRighted());
        response.setAvailability(audio.getAvailability());
        response.setLicenseType(audio.getLicenseType());
        response.setUsageRights(audio.getUsageRights());
        response.setOwner(audio.getOwner());
        response.setPublisher(audio.getPublisher());
        response.setArchiveLocalNote(audio.getArchiveLocalNote());
        response.setCreatedAt(audio.getCreatedAt());
        response.setUpdatedAt(audio.getUpdatedAt());
        response.setRemovedAt(audio.getRemovedAt());
        response.setCreatedBy(audio.getCreatedBy());
        response.setUpdatedBy(audio.getUpdatedBy());
        response.setRemovedBy(audio.getRemovedBy());
        return response;
    }

    // ─── Audit Details ───────────────────────────────────────────────────────────

    private String buildCreateDetails(Audio audio) {
        String projectInfo = audio.getProject() != null ? audio.getProject().getProjectCode() : "none";
        return "Created audio record with code=" + audio.getAudioCode()
                + " project=" + projectInfo
                + " audioFileUrl=" + audio.getAudioFileUrl();
    }

    private String buildUpdateDetails(Audio before, Audio after) {
        List<String> changes = new ArrayList<>();
        addChange(changes, "fullName", before.getFullname(), after.getFullname());
        addChange(changes, "volumeName", before.getVolumeName(), after.getVolumeName());
        addChange(changes, "directoryName", before.getDirectoryName(), after.getDirectoryName());
        addChange(changes, "pathInExternal", before.getPath_in_external(), after.getPath_in_external());
        addChange(changes, "autoPath", before.getAuto_path(), after.getAuto_path());
        addChange(changes, "originTitle", before.getOriginTitle(), after.getOriginTitle());
        addChange(changes, "alterTitle", before.getAlterTitle(), after.getAlterTitle());
        addChange(changes, "centralKurdishTitle", before.getCentral_kurdish_title(), after.getCentral_kurdish_title());
        addChange(changes, "romanizedTitle", before.getRomanized_title(), after.getRomanized_title());
        addChange(changes, "form", before.getForm(), after.getForm());
        addChange(changes, "typeOfBasta", before.getTypeOfBasta(), after.getTypeOfBasta());
        addChange(changes, "typeOfMaqam", before.getTypeOfMaqam(), after.getTypeOfMaqam());
        addChange(changes, "genre", before.getGenre(), after.getGenre());
        addChange(changes, "abstractText", before.getAbstractText(), after.getAbstractText());
        addChange(changes, "description", before.getDescription(), after.getDescription());
        addChange(changes, "speaker", before.getSpeaker(), after.getSpeaker());
        addChange(changes, "producer", before.getProducer(), after.getProducer());
        addChange(changes, "composer", before.getComposer(), after.getComposer());
        addChange(changes, "contributors", before.getContributors(), after.getContributors());
        addChange(changes, "language", before.getLanguage(), after.getLanguage());
        addChange(changes, "dialect", before.getDialect(), after.getDialect());
        addChange(changes, "typeOfComposition", before.getTypeOfComposition(), after.getTypeOfComposition());
        addChange(changes, "typeOfPerformance", before.getTypeOfPerformance(), after.getTypeOfPerformance());
        addChange(changes, "lyrics", before.getLyrics(), after.getLyrics());
        addChange(changes, "poet", before.getPoet(), after.getPoet());
        addChange(changes, "recordingVenue", before.getRecording_venue(), after.getRecording_venue());
        addChange(changes, "city", before.getCity(), after.getCity());
        addChange(changes, "region", before.getRegion(), after.getRegion());
        addChange(changes, "dateCreated", before.getDate_created(), after.getDate_created());
        addChange(changes, "datePublished", before.getDate_published(), after.getDate_published());
        addChange(changes, "dateModified", before.getDate_modified(), after.getDate_modified());
        addChange(changes, "audience", before.getAudience(), after.getAudience());
        addChange(changes, "tags", before.getTags(), after.getTags());
        addChange(changes, "keywords", before.getKeywords(), after.getKeywords());
        addChange(changes, "physicalAvailability", before.isPhysicalAvailability(), after.isPhysicalAvailability());
        addChange(changes, "physicalLabel", before.getPhysicalLabel(), after.getPhysicalLabel());
        addChange(changes, "locationArchive", before.getLocationArchive(), after.getLocationArchive());
        addChange(changes, "degitizedBy", before.getDegitizedBy(), after.getDegitizedBy());
        addChange(changes, "degitizationEquipment", before.getDegitizationEquipment(), after.getDegitizationEquipment());
        addChange(changes, "audioFileNote", before.getAudioFileNote(), after.getAudioFileNote());
        addChange(changes, "audioChannel", before.getAudioChannel(), after.getAudioChannel());
        addChange(changes, "fileExtension", before.getFileExtension(), after.getFileExtension());
        addChange(changes, "fileSize", before.getFileSize(), after.getFileSize());
        addChange(changes, "bitRate", before.getBitRate(), after.getBitRate());
        addChange(changes, "bitDepth", before.getBitDepth(), after.getBitDepth());
        addChange(changes, "sampleRate", before.getSampleRate(), after.getSampleRate());
        addChange(changes, "audioQualityOutOf10", before.getAudioQualityOutOf10(), after.getAudioQualityOutOf10());
        addChange(changes, "audioVersion", before.getAudioVersion(), after.getAudioVersion());
        addChange(changes, "versionNumber", before.getVersionNumber(), after.getVersionNumber());
        addChange(changes, "copyNumber", before.getCopyNumber(), after.getCopyNumber());
        addChange(changes, "lccClassification", before.getLcc_classification(), after.getLcc_classification());
        addChange(changes, "accrualMethod", before.getAccrualMethod(), after.getAccrualMethod());
        addChange(changes, "provenance", before.getProvenance(), after.getProvenance());
        addChange(changes, "copyright", before.getCopyright(), after.getCopyright());
        addChange(changes, "rightOwner", before.getRightOwner(), after.getRightOwner());
        addChange(changes, "dateCopyrighted", before.getDateCopyRighted(), after.getDateCopyRighted());
        addChange(changes, "availability", before.getAvailability(), after.getAvailability());
        addChange(changes, "licenseType", before.getLicenseType(), after.getLicenseType());
        addChange(changes, "usageRights", before.getUsageRights(), after.getUsageRights());
        addChange(changes, "owner", before.getOwner(), after.getOwner());
        addChange(changes, "publisher", before.getPublisher(), after.getPublisher());
        addChange(changes, "archiveLocalNote", before.getArchiveLocalNote(), after.getArchiveLocalNote());
        addChange(changes, "audioFileUrl", before.getAudioFileUrl(), after.getAudioFileUrl());

        if (changes.isEmpty()) {
            return "Updated audio record (no field changes detected)";
        }
        return "Updated audio record: " + String.join(" | ", changes);
    }

    private void addChange(List<String> changes, String field, Object before, Object after) {
        if (!Objects.equals(before, after)) {
            changes.add(field + ": " + before + " -> " + after);
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    private Project resolveProject(String projectCode) {
        if (projectCode == null || projectCode.isBlank()) {
            throw new AudioValidationException("Project code is required");
        }
        return projectRepository.findByProjectCodeAndRemovedAtIsNull(projectCode.trim())
                .orElseThrow(() -> new ProjectNotFoundException("Project not found: " + projectCode));
    }

    private String uploadAudioFile(MultipartFile audioFile, String audioCode) {
        if (audioFile == null || audioFile.isEmpty()) {
            return null;
        }
        return s3Service.upload(audioFile, AUDIO_FOLDER + "/" + audioCode);
    }

    private void deleteStoredFile(String audioFileUrl) {
        if (audioFileUrl != null && !audioFileUrl.isBlank() && s3Service.isOurS3Url(audioFileUrl)) {
            s3Service.deleteFile(audioFileUrl);
        }
    }

    private String normalizeRequiredCode(String code, String label) {
        if (code == null) {
            throw new AudioValidationException(label + " is required");
        }
        String trimmed = code.trim();
        if (trimmed.isBlank()) {
            throw new AudioValidationException(label + " is required");
        }
        return trimmed;
    }

    private void touchCreateAudit(Audio audio, Authentication authentication) {
        Instant now = Instant.now();
        String actor = resolveActorUsername(authentication);
        audio.setCreatedAt(now);
        audio.setUpdatedAt(now);
        audio.setCreatedBy(actor);
        audio.setUpdatedBy(actor);
    }

    private void touchUpdateAudit(Audio audio, Authentication authentication) {
        audio.setUpdatedAt(Instant.now());
        audio.setUpdatedBy(resolveActorUsername(authentication));
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
                .anyMatch("audio:delete"::equals);
        if (!canHardDelete) {
            throw new AccessDeniedException("Only ADMIN can permanently delete audio records");
        }
    }

    private Audio snapshot(Audio audio) {
        if (audio == null) {
            return null;
        }
        Audio copy = new Audio();
        BeanUtils.copyProperties(audio, copy);
        return copy;
    }

    /** Escape SQL LIKE wildcards in user input. See ImageService for details. */
    private static String escapeLikeWildcards(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
