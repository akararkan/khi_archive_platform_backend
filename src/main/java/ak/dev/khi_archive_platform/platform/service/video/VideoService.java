package ak.dev.khi_archive_platform.platform.service.video;

import ak.dev.khi_archive_platform.S3Service;
import ak.dev.khi_archive_platform.platform.dto.video.VideoBaseRequestDTO;
import ak.dev.khi_archive_platform.platform.dto.video.VideoBulkCreateRequestDTO;
import ak.dev.khi_archive_platform.platform.dto.video.VideoCreateRequestDTO;
import ak.dev.khi_archive_platform.platform.dto.video.VideoResponseDTO;
import ak.dev.khi_archive_platform.platform.dto.video.VideoUpdateRequestDTO;
import ak.dev.khi_archive_platform.platform.enums.VideoAuditAction;
import ak.dev.khi_archive_platform.platform.exceptions.ProjectNotFoundException;
import ak.dev.khi_archive_platform.platform.exceptions.VideoAlreadyExistsException;
import ak.dev.khi_archive_platform.platform.exceptions.VideoNotFoundException;
import ak.dev.khi_archive_platform.platform.exceptions.VideoValidationException;
import ak.dev.khi_archive_platform.platform.model.project.Project;
import ak.dev.khi_archive_platform.platform.model.video.Video;
import ak.dev.khi_archive_platform.platform.repo.project.ProjectRepository;
import ak.dev.khi_archive_platform.platform.repo.video.VideoRepository;
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
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional
public class VideoService {

    private static final String VIDEO_FOLDER = "videos";
    private static final Set<String> VALID_VERSIONS = Set.of(
            "RAW", "MASTER", "RESTORED", "ARCHIVE", "ORIGINAL",
            "4K_MASTER", "PROFESSIONAL"
    );

    private final VideoRepository videoRepository;
    private final ProjectRepository projectRepository;
    private final VideoAuditService videoAuditService;
    private final S3Service s3Service;
    private final VideoReadCache readCache;
    private final CodeGenLock codeGenLock;

    @PersistenceContext
    private EntityManager entityManager;

    private static final MediaSearchSqlBuilder.Spec VIDEO_SEARCH_SPEC = new MediaSearchSqlBuilder.Spec(
            "videos",
            "id",
            List.of(
                    "original_title", "alternative_title", "title_in_central_kurdish", "romanized_title",
                    "video_code", "file_name",
                    "creator_artist_director", "producer", "person_shown_in_video",
                    "event", "location"
            ),
            List.of(
                    "video_code", "file_name", "volume_name", "directory", "path_in_external_volume", "auto_path",
                    "original_title", "alternative_title", "title_in_central_kurdish", "romanized_title",
                    "event", "location", "description",
                    "person_shown_in_video", "video_version", "resolution", "video_codec", "audio_codec", "audio_channels",
                    "language", "dialect", "subtitle",
                    "creator_artist_director", "producer", "contributor", "audience",
                    "accrual_method", "provenance", "video_status", "archive_cataloging",
                    "physical_label", "location_in_archive_room", "lcc_classification", "note",
                    "copyright", "right_owner", "license_type", "usage_rights", "availability", "owner", "publisher"
            ),
            List.of(
                    new MediaSearchSqlBuilder.ChildTable("video_subjects", "video_id", "subject"),
                    new MediaSearchSqlBuilder.ChildTable("video_genres",   "video_id", "genre"),
                    new MediaSearchSqlBuilder.ChildTable("video_colors",   "video_id", "color"),
                    new MediaSearchSqlBuilder.ChildTable("video_usages",   "video_id", "usage_context"),
                    new MediaSearchSqlBuilder.ChildTable("video_tags",     "video_id", "tag"),
                    new MediaSearchSqlBuilder.ChildTable("video_keywords", "video_id", "keyword")
            )
    );

    public VideoResponseDTO create(VideoCreateRequestDTO dto,
                                   MultipartFile videoFile,
                                   Authentication authentication,
                                   HttpServletRequest request) {
        validateCreate(dto, videoFile);

        Project project = resolveProject(dto.getProjectCode());

        String videoVersion = dto.getVideoVersion().toUpperCase(Locale.ROOT);
        Integer versionNumber = dto.getVersionNumber();
        Integer copyNumber = dto.getCopyNumber();
        if (!VALID_VERSIONS.contains(videoVersion)) {
            throw new VideoValidationException("Video version must be one of: RAW, MASTER, RESTORED, ARCHIVE, ORIGINAL, 4K_MASTER, PROFESSIONAL");
        }
        if (versionNumber == null || versionNumber < 1) {
            throw new VideoValidationException("Version number is required and must be at least 1");
        }
        if (copyNumber == null || copyNumber < 1) {
            throw new VideoValidationException("Copy number is required and must be at least 1");
        }

        // Serialise concurrent creates for the same project so the
        // count-based sequence number can't collide.
        codeGenLock.lock("video-code:" + project.getId());
        String videoCode = generateVideoCode(project, videoVersion, versionNumber, copyNumber);

        if (videoRepository.existsByVideoCode(videoCode)) {
            throw new VideoAlreadyExistsException("Video code already exists: " + videoCode);
        }

        Video video = new Video();
        video.setVideoCode(videoCode);
        video.setProject(project);
        applyDto(video, dto);
        video.setVideoFileUrl(uploadVideoFile(videoFile, videoCode));
        touchCreateAudit(video, authentication);

        Video saved = videoRepository.save(video);
        readCache.evictAll();
        videoAuditService.record(saved, VideoAuditAction.CREATE, authentication, request, buildCreateDetails(saved));
        return toResponse(saved);
    }

    public record BulkCreateResult(int requested, int inserted, int skipped, long elapsedMs) {}

    /**
     * Bulk-create video records from a JSON array. Each entry carries its own
     * {@code videoFileUrl} (no multipart upload). Video codes are auto-generated
     * using an in-memory per-project counter. Rows that fail validation or
     * whose generated code already exists are skipped. One audit summary.
     */
    public BulkCreateResult createAll(List<VideoBulkCreateRequestDTO> dtos,
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

        List<Video> toInsert = new ArrayList<>(dtos.size());
        int skipped = 0;
        for (VideoBulkCreateRequestDTO dto : dtos) {
            if (dto == null || dto.getProjectCode() == null || dto.getProjectCode().isBlank()
                    || dto.getVideoVersion() == null
                    || dto.getVersionNumber() == null || dto.getVersionNumber() < 1
                    || dto.getCopyNumber() == null || dto.getCopyNumber() < 1) {
                skipped++;
                continue;
            }
            String version = dto.getVideoVersion().toUpperCase(Locale.ROOT);
            if (!VALID_VERSIONS.contains(version)) {
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
                codeGenLock.lock("video-code:" + pid);
                return videoRepository.countByProject(project) + 1L;
            });
            String parentCode = project.getPerson() != null
                    ? project.getPerson().getPersonCode().toUpperCase(Locale.ROOT)
                    : project.getCategories().get(0).getCategoryCode().toUpperCase(Locale.ROOT);
            String videoCode = parentCode
                    + "_VID_" + version
                    + "_V" + dto.getVersionNumber()
                    + "_Copy(" + dto.getCopyNumber() + ")"
                    + "_" + String.format(Locale.ROOT, "%06d", seq);
            nextSeqByProject.put(project.getId(), seq + 1);

            if (videoRepository.existsByVideoCode(videoCode)) {
                skipped++;
                continue;
            }

            Video video = new Video();
            video.setVideoCode(videoCode);
            video.setProject(project);
            applyDto(video, dto);
            video.setVideoFileUrl(dto.getVideoFileUrl());
            video.setCreatedAt(now);
            video.setUpdatedAt(now);
            video.setCreatedBy(actor);
            video.setUpdatedBy(actor);
            toInsert.add(video);
        }

        videoRepository.saveAll(toInsert);
        readCache.evictAll();

        long elapsed = System.currentTimeMillis() - start;
        videoAuditService.record(null, VideoAuditAction.CREATE, authentication, request,
                "Bulk created videos: requested=" + dtos.size()
                        + " inserted=" + toInsert.size()
                        + " skipped=" + skipped
                        + " elapsedMs=" + elapsed);
        return new BulkCreateResult(dtos.size(), toInsert.size(), skipped, elapsed);
    }

    /** Fast path: served from Redis on hit; on miss loads with batched fetches and caches. */
    @Transactional(readOnly = true)
    public Page<VideoResponseDTO> getAll(Pageable pageable, Authentication authentication, HttpServletRequest request) {
        List<VideoResponseDTO> all = readCache.getAllActive();
        Page<VideoResponseDTO> page = PaginationSupport.sliceList(all, pageable);
        videoAuditService.record(null, VideoAuditAction.LIST, authentication, request,
                "Listed active video records (page=" + page.getNumber()
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
    public List<VideoResponseDTO> search(String query,
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
                VIDEO_SEARCH_SPEC, tokens, SEARCH_PREFILTER_LIMIT, effectiveLimit);
        jakarta.persistence.Query nq = entityManager.createNativeQuery(built.sql(), Video.class);
        built.params().forEach(nq::setParameter);
        @SuppressWarnings("unchecked")
        List<Video> rows = (List<Video>) nq.getResultList();
        List<VideoResponseDTO> result = rows.stream().map(this::toResponse).toList();

        videoAuditService.record(null, VideoAuditAction.SEARCH, authentication, request,
                "Searched videos q=\"" + normalized + "\" tokens=" + tokens
                        + " limit=" + effectiveLimit + " hits=" + result.size());
        return result;
    }

    @Transactional(readOnly = true)
    public VideoResponseDTO getByVideoCode(String videoCode,
                                           Authentication authentication,
                                           HttpServletRequest request) {
        String normalized = normalizeRequiredCode(videoCode, "Video code");
        Video video = videoRepository.findByVideoCodeAndRemovedAtIsNull(normalized)
                .orElseThrow(() -> new VideoNotFoundException("Video not found: " + videoCode));
        videoAuditService.record(video, VideoAuditAction.READ, authentication, request, "Read video record");
        return toResponse(video);
    }

    public VideoResponseDTO update(String videoCode,
                                   VideoUpdateRequestDTO dto,
                                   MultipartFile videoFile,
                                   Authentication authentication,
                                   HttpServletRequest request) {
        String normalized = normalizeRequiredCode(videoCode, "Video code");
        Video video = videoRepository.findByVideoCodeAndRemovedAtIsNull(normalized)
                .orElseThrow(() -> new VideoNotFoundException("Video not found: " + videoCode));

        Video before = snapshot(video);
        if (dto != null) {
            validateProjectNotChanged(video, dto);
            applyDto(video, dto);
        }

        String oldVideoFileUrl = video.getVideoFileUrl();
        if (videoFile != null && !videoFile.isEmpty()) {
            String newVideoFileUrl = uploadVideoFile(videoFile, normalized);
            video.setVideoFileUrl(newVideoFileUrl);
            if (oldVideoFileUrl != null && !Objects.equals(oldVideoFileUrl, newVideoFileUrl)) {
                deleteStoredFile(oldVideoFileUrl);
            }
        }

        touchUpdateAudit(video, authentication);
        Video saved = videoRepository.save(video);
        readCache.evictAll();
        videoAuditService.record(saved, VideoAuditAction.UPDATE, authentication, request, buildUpdateDetails(before, saved));
        return toResponse(saved);
    }

    /**
     * Soft delete (trash). The S3 file is preserved so the record can be
     * restored later. Admin-only via {@code video:delete} authority.
     */
    public void delete(String videoCode,
                       Authentication authentication,
                       HttpServletRequest request) {
        String normalized = normalizeRequiredCode(videoCode, "Video code");
        Video video = videoRepository.findByVideoCodeAndRemovedAtIsNull(normalized)
                .orElseThrow(() -> new VideoNotFoundException("Video not found: " + videoCode));

        video.setRemovedAt(Instant.now());
        video.setRemovedBy(resolveActorUsername(authentication));
        Video saved = videoRepository.save(video);
        readCache.evictAll();
        videoAuditService.record(saved, VideoAuditAction.DELETE, authentication, request,
                "Sent video record to trash");
    }

    /**
     * Restore a video record from trash. Admin-only. Fails if the parent
     * project is itself in trash — restore the project first.
     */
    public VideoResponseDTO restore(String videoCode,
                                    Authentication authentication,
                                    HttpServletRequest request) {
        requireAdminRole(authentication);
        String normalized = normalizeRequiredCode(videoCode, "Video code");
        Video video = videoRepository.findByVideoCode(normalized)
                .orElseThrow(() -> new VideoNotFoundException("Video not found: " + videoCode));

        if (video.getRemovedAt() == null) {
            throw new VideoValidationException("Video is not in trash: " + videoCode);
        }
        if (video.getProject() != null && video.getProject().getRemovedAt() != null) {
            throw new VideoValidationException(
                    "Cannot restore video while its project is in trash. Restore the project first.");
        }

        video.setRemovedAt(null);
        video.setRemovedBy(null);
        video.setUpdatedAt(Instant.now());
        video.setUpdatedBy(resolveActorUsername(authentication));
        Video saved = videoRepository.save(video);
        readCache.evictAll();
        videoAuditService.record(saved, VideoAuditAction.RESTORE, authentication, request,
                "Restored video record from trash");
        return toResponse(saved);
    }

    /**
     * Permanently delete a video record from the trash. Admin-only. The record
     * must already be in trash. Removes both the database row and the S3 file.
     */
    public void purge(String videoCode,
                      Authentication authentication,
                      HttpServletRequest request) {
        requireAdminRole(authentication);
        String normalized = normalizeRequiredCode(videoCode, "Video code");
        Video video = videoRepository.findByVideoCode(normalized)
                .orElseThrow(() -> new VideoNotFoundException("Video not found: " + videoCode));

        if (video.getRemovedAt() == null) {
            throw new VideoValidationException(
                    "Video must be in trash before permanent deletion. Trash it first.");
        }

        String fileUrl = video.getVideoFileUrl();
        videoAuditService.record(video, VideoAuditAction.PURGE, authentication, request,
                "Permanently deleted video record from trash");
        videoRepository.delete(video);
        readCache.evictAll();
        deleteStoredFile(fileUrl);
    }

    /**
     * List video records in the trash. Admin-only.
     */
    @Transactional(readOnly = true)
    public Page<VideoResponseDTO> getTrash(Pageable pageable,
                                           Authentication authentication,
                                           HttpServletRequest request) {
        requireAdminRole(authentication);
        List<VideoResponseDTO> all = videoRepository.findAllByRemovedAtIsNotNull().stream()
                .map(this::toResponse)
                .toList();
        Page<VideoResponseDTO> page = PaginationSupport.sliceList(all, pageable);
        videoAuditService.record(null, VideoAuditAction.LIST, authentication, request,
                "Listed video trash (page=" + page.getNumber()
                        + " size=" + page.getSize()
                        + " returned=" + page.getNumberOfElements()
                        + " total=" + page.getTotalElements() + ")");
        return page;
    }

    // ─── Validation ──────────────────────────────────────────────────────────────

    private void validateCreate(VideoCreateRequestDTO dto, MultipartFile videoFile) {
        if (dto == null) {
            throw new VideoValidationException("Video payload is required");
        }
        if (videoFile == null || videoFile.isEmpty()) {
            throw new VideoValidationException("Video file is required");
        }
        if (dto.getProjectCode() == null || dto.getProjectCode().isBlank()) {
            throw new VideoValidationException("Project code is required");
        }
    }

    private void validateProjectNotChanged(Video video, VideoBaseRequestDTO dto) {
        if (dto.getProjectCode() != null && !dto.getProjectCode().isBlank()) {
            String currentProjectCode = video.getProject() != null ? video.getProject().getProjectCode() : null;
            if (!dto.getProjectCode().trim().equals(currentProjectCode)) {
                throw new VideoValidationException("Video project cannot be changed after creation. Create a new video record instead.");
            }
        }
    }

    // ─── Code Generation ─────────────────────────────────────────────────────────

    /**
     * Generates video code in the format: PARENT_VID_VERSION_VN_Copy(CN)_SEQUENCE
     * <p>
     * If the project has a person: PERSONCODE_VID_MASTER_V1_Copy(1)_000001
     * If the project has no person: CATEGORYCODE_VID_MASTER_V1_Copy(1)_000001
     */
    private String generateVideoCode(Project project, String videoVersion, Integer versionNumber, Integer copyNumber) {
        String parentCode;
        if (project.getPerson() != null) {
            parentCode = project.getPerson().getPersonCode().toUpperCase(Locale.ROOT);
        } else {
            parentCode = project.getCategories().get(0).getCategoryCode().toUpperCase(Locale.ROOT);
        }

        long sequence = videoRepository.countByProject(project) + 1;

        return parentCode
                + "_VID_" + videoVersion
                + "_V" + versionNumber
                + "_Copy(" + copyNumber + ")"
                + "_" + String.format(Locale.ROOT, "%06d", sequence);
    }

    // ─── DTO Mapping ─────────────────────────────────────────────────────────────

    private void applyDto(Video video, VideoBaseRequestDTO dto) {
        if (dto == null) {
            return;
        }

        BeanUtils.copyProperties(dto, video,
                "projectCode", "physicalAvailability", "videoVersion");

        if (dto.getPhysicalAvailability() != null) video.setPhysicalAvailability(dto.getPhysicalAvailability());
        if (dto.getVideoVersion() != null) video.setVideoVersion(dto.getVideoVersion().toUpperCase(Locale.ROOT));
    }

    VideoResponseDTO toResponse(Video video) {
        if (video == null) {
            return null;
        }

        Project project = video.getProject();
        return VideoResponseDTO.builder()
                .id(video.getId())
                .videoCode(video.getVideoCode())
                // Project info
                .projectId(project != null ? project.getId() : null)
                .projectCode(project != null ? project.getProjectCode() : null)
                .projectName(project != null ? project.getProjectName() : null)
                // Person info (via project)
                .personId(project != null && project.getPerson() != null ? project.getPerson().getId() : null)
                .personCode(project != null && project.getPerson() != null ? project.getPerson().getPersonCode() : null)
                .personName(project != null && project.getPerson() != null ? project.getPerson().getFullName() : null)
                // Categories (via project)
                .categoryCodes(project != null && project.getCategories() != null
                        ? project.getCategories().stream().map(c -> c.getCategoryCode()).toList()
                        : null)
                .videoFileUrl(video.getVideoFileUrl())
                // File & Path
                .fileName(video.getFileName())
                .volumeName(video.getVolumeName())
                .directory(video.getDirectory())
                .pathInExternalVolume(video.getPathInExternalVolume())
                .autoPath(video.getAutoPath())
                // Titles
                .originalTitle(video.getOriginalTitle())
                .alternativeTitle(video.getAlternativeTitle())
                .titleInCentralKurdish(video.getTitleInCentralKurdish())
                .romanizedTitle(video.getRomanizedTitle())
                // Classification
                .subject(copyList(video.getSubject()))
                .genre(copyList(video.getGenre()))
                .event(video.getEvent())
                .location(video.getLocation())
                .description(video.getDescription())
                // Video Details
                .personShownInVideo(video.getPersonShownInVideo())
                .colorOfVideo(copyList(video.getColorOfVideo()))
                .videoVersion(video.getVideoVersion())
                .versionNumber(video.getVersionNumber())
                .copyNumber(video.getCopyNumber())
                .whereThisVideoUsed(copyList(video.getWhereThisVideoUsed()))
                // Technical
                .fileSize(video.getFileSize())
                .extension(video.getExtension())
                .orientation(video.getOrientation())
                .dimension(video.getDimension())
                .resolution(video.getResolution())
                .duration(video.getDuration())
                .bitDepth(video.getBitDepth())
                .frameRate(video.getFrameRate())
                .overallBitRate(video.getOverallBitRate())
                .videoCodec(video.getVideoCodec())
                .audioCodec(video.getAudioCodec())
                .audioChannels(video.getAudioChannels())
                // Language
                .language(video.getLanguage())
                .dialect(video.getDialect())
                .subtitle(video.getSubtitle())
                // People
                .creatorArtistDirector(video.getCreatorArtistDirector())
                .producer(video.getProducer())
                .contributor(video.getContributor())
                .audience(video.getAudience())
                // Archival
                .accrualMethod(video.getAccrualMethod())
                .provenance(video.getProvenance())
                .videoStatus(video.getVideoStatus())
                .archiveCataloging(video.getArchiveCataloging())
                .physicalAvailability(video.isPhysicalAvailability())
                .physicalLabel(video.getPhysicalLabel())
                .locationInArchiveRoom(video.getLocationInArchiveRoom())
                .lccClassification(video.getLccClassification())
                .note(video.getNote())
                // Tags & Keywords
                .tags(copyList(video.getTags()))
                .keywords(copyList(video.getKeywords()))
                // Dates
                .dateCreated(video.getDateCreated())
                .dateModified(video.getDateModified())
                .datePublished(video.getDatePublished())
                // Rights
                .copyright(video.getCopyright())
                .rightOwner(video.getRightOwner())
                .dateCopyrighted(video.getDateCopyrighted())
                .licenseType(video.getLicenseType())
                .usageRights(video.getUsageRights())
                .availability(video.getAvailability())
                .owner(video.getOwner())
                .publisher(video.getPublisher())
                // Audit
                .createdAt(video.getCreatedAt())
                .updatedAt(video.getUpdatedAt())
                .removedAt(video.getRemovedAt())
                .createdBy(video.getCreatedBy())
                .updatedBy(video.getUpdatedBy())
                .removedBy(video.getRemovedBy())
                .build();
    }

    // ─── Audit Details ───────────────────────────────────────────────────────────

    private String buildCreateDetails(Video video) {
        String projectInfo = video.getProject() != null ? video.getProject().getProjectCode() : "none";
        return "Created video record with code=" + video.getVideoCode()
                + " project=" + projectInfo
                + " videoFileUrl=" + video.getVideoFileUrl();
    }

    private String buildUpdateDetails(Video before, Video after) {
        List<String> changes = new ArrayList<>();
        addChange(changes, "fileName", before.getFileName(), after.getFileName());
        addChange(changes, "volumeName", before.getVolumeName(), after.getVolumeName());
        addChange(changes, "directory", before.getDirectory(), after.getDirectory());
        addChange(changes, "pathInExternalVolume", before.getPathInExternalVolume(), after.getPathInExternalVolume());
        addChange(changes, "autoPath", before.getAutoPath(), after.getAutoPath());
        addChange(changes, "originalTitle", before.getOriginalTitle(), after.getOriginalTitle());
        addChange(changes, "alternativeTitle", before.getAlternativeTitle(), after.getAlternativeTitle());
        addChange(changes, "titleInCentralKurdish", before.getTitleInCentralKurdish(), after.getTitleInCentralKurdish());
        addChange(changes, "romanizedTitle", before.getRomanizedTitle(), after.getRomanizedTitle());
        addChange(changes, "subject", before.getSubject(), after.getSubject());
        addChange(changes, "genre", before.getGenre(), after.getGenre());
        addChange(changes, "event", before.getEvent(), after.getEvent());
        addChange(changes, "location", before.getLocation(), after.getLocation());
        addChange(changes, "description", before.getDescription(), after.getDescription());
        addChange(changes, "personShownInVideo", before.getPersonShownInVideo(), after.getPersonShownInVideo());
        addChange(changes, "colorOfVideo", before.getColorOfVideo(), after.getColorOfVideo());
        addChange(changes, "videoVersion", before.getVideoVersion(), after.getVideoVersion());
        addChange(changes, "versionNumber", before.getVersionNumber(), after.getVersionNumber());
        addChange(changes, "copyNumber", before.getCopyNumber(), after.getCopyNumber());
        addChange(changes, "whereThisVideoUsed", before.getWhereThisVideoUsed(), after.getWhereThisVideoUsed());
        addChange(changes, "fileSize", before.getFileSize(), after.getFileSize());
        addChange(changes, "extension", before.getExtension(), after.getExtension());
        addChange(changes, "orientation", before.getOrientation(), after.getOrientation());
        addChange(changes, "dimension", before.getDimension(), after.getDimension());
        addChange(changes, "resolution", before.getResolution(), after.getResolution());
        addChange(changes, "duration", before.getDuration(), after.getDuration());
        addChange(changes, "bitDepth", before.getBitDepth(), after.getBitDepth());
        addChange(changes, "frameRate", before.getFrameRate(), after.getFrameRate());
        addChange(changes, "overallBitRate", before.getOverallBitRate(), after.getOverallBitRate());
        addChange(changes, "videoCodec", before.getVideoCodec(), after.getVideoCodec());
        addChange(changes, "audioCodec", before.getAudioCodec(), after.getAudioCodec());
        addChange(changes, "audioChannels", before.getAudioChannels(), after.getAudioChannels());
        addChange(changes, "language", before.getLanguage(), after.getLanguage());
        addChange(changes, "dialect", before.getDialect(), after.getDialect());
        addChange(changes, "subtitle", before.getSubtitle(), after.getSubtitle());
        addChange(changes, "creatorArtistDirector", before.getCreatorArtistDirector(), after.getCreatorArtistDirector());
        addChange(changes, "producer", before.getProducer(), after.getProducer());
        addChange(changes, "contributor", before.getContributor(), after.getContributor());
        addChange(changes, "audience", before.getAudience(), after.getAudience());
        addChange(changes, "accrualMethod", before.getAccrualMethod(), after.getAccrualMethod());
        addChange(changes, "provenance", before.getProvenance(), after.getProvenance());
        addChange(changes, "videoStatus", before.getVideoStatus(), after.getVideoStatus());
        addChange(changes, "archiveCataloging", before.getArchiveCataloging(), after.getArchiveCataloging());
        addChange(changes, "physicalAvailability", before.isPhysicalAvailability(), after.isPhysicalAvailability());
        addChange(changes, "physicalLabel", before.getPhysicalLabel(), after.getPhysicalLabel());
        addChange(changes, "locationInArchiveRoom", before.getLocationInArchiveRoom(), after.getLocationInArchiveRoom());
        addChange(changes, "lccClassification", before.getLccClassification(), after.getLccClassification());
        addChange(changes, "note", before.getNote(), after.getNote());
        addChange(changes, "tags", before.getTags(), after.getTags());
        addChange(changes, "keywords", before.getKeywords(), after.getKeywords());
        addChange(changes, "copyright", before.getCopyright(), after.getCopyright());
        addChange(changes, "rightOwner", before.getRightOwner(), after.getRightOwner());
        addChange(changes, "dateCopyrighted", before.getDateCopyrighted(), after.getDateCopyrighted());
        addChange(changes, "licenseType", before.getLicenseType(), after.getLicenseType());
        addChange(changes, "usageRights", before.getUsageRights(), after.getUsageRights());
        addChange(changes, "availability", before.getAvailability(), after.getAvailability());
        addChange(changes, "owner", before.getOwner(), after.getOwner());
        addChange(changes, "publisher", before.getPublisher(), after.getPublisher());
        addChange(changes, "videoFileUrl", before.getVideoFileUrl(), after.getVideoFileUrl());

        if (changes.isEmpty()) {
            return "Updated video record (no field changes detected)";
        }
        return "Updated video record: " + String.join(" | ", changes);
    }

    private void addChange(List<String> changes, String field, Object before, Object after) {
        if (!Objects.equals(before, after)) {
            changes.add(field + ": " + before + " -> " + after);
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    private Project resolveProject(String projectCode) {
        if (projectCode == null || projectCode.isBlank()) {
            throw new VideoValidationException("Project code is required");
        }
        return projectRepository.findByProjectCodeAndRemovedAtIsNull(projectCode.trim())
                .orElseThrow(() -> new ProjectNotFoundException("Project not found: " + projectCode));
    }

    private String uploadVideoFile(MultipartFile videoFile, String videoCode) {
        if (videoFile == null || videoFile.isEmpty()) {
            return null;
        }
        return s3Service.upload(videoFile, VIDEO_FOLDER + "/" + videoCode);
    }

    private void deleteStoredFile(String videoFileUrl) {
        if (videoFileUrl != null && !videoFileUrl.isBlank() && s3Service.isOurS3Url(videoFileUrl)) {
            s3Service.deleteFile(videoFileUrl);
        }
    }

    private String normalizeRequiredCode(String code, String label) {
        if (code == null) {
            throw new VideoValidationException(label + " is required");
        }
        String trimmed = code.trim();
        if (trimmed.isBlank()) {
            throw new VideoValidationException(label + " is required");
        }
        return trimmed;
    }

    private void touchCreateAudit(Video video, Authentication authentication) {
        Instant now = Instant.now();
        String actor = resolveActorUsername(authentication);
        video.setCreatedAt(now);
        video.setUpdatedAt(now);
        video.setCreatedBy(actor);
        video.setUpdatedBy(actor);
    }

    private void touchUpdateAudit(Video video, Authentication authentication) {
        video.setUpdatedAt(Instant.now());
        video.setUpdatedBy(resolveActorUsername(authentication));
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
                .anyMatch("video:delete"::equals);
        if (!canHardDelete) {
            throw new AccessDeniedException("Only ADMIN can permanently delete video records");
        }
    }

    private Video snapshot(Video video) {
        if (video == null) {
            return null;
        }
        Video copy = new Video();
        BeanUtils.copyProperties(video, copy);
        return copy;
    }

    private List<String> copyList(List<String> list) {
        return list == null ? null : new ArrayList<>(list);
    }

    /** Escape SQL LIKE wildcards in user input. See ImageService for details. */
    private static String escapeLikeWildcards(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
