package ak.dev.khi_archive_platform.platform.service.text;

import ak.dev.khi_archive_platform.S3Service;
import ak.dev.khi_archive_platform.platform.dto.text.TextBaseRequestDTO;
import ak.dev.khi_archive_platform.platform.dto.text.TextBulkCreateRequestDTO;
import ak.dev.khi_archive_platform.platform.dto.text.TextCreateRequestDTO;
import ak.dev.khi_archive_platform.platform.dto.text.TextResponseDTO;
import ak.dev.khi_archive_platform.platform.dto.text.TextUpdateRequestDTO;
import ak.dev.khi_archive_platform.platform.enums.TextAuditAction;
import ak.dev.khi_archive_platform.platform.exceptions.ProjectNotFoundException;
import ak.dev.khi_archive_platform.platform.exceptions.TextAlreadyExistsException;
import ak.dev.khi_archive_platform.platform.exceptions.TextNotFoundException;
import ak.dev.khi_archive_platform.platform.exceptions.TextValidationException;
import ak.dev.khi_archive_platform.platform.model.project.Project;
import ak.dev.khi_archive_platform.platform.model.text.Text;
import ak.dev.khi_archive_platform.platform.repo.project.ProjectRepository;
import ak.dev.khi_archive_platform.platform.repo.text.TextRepository;
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
public class TextService {

    private static final String TEXT_FOLDER = "texts";
    private static final Set<String> VALID_VERSIONS = Set.of(
            "RAW", "MASTER", "RESTORED", "ARCHIVE", "ORIGINAL",
            "DIGITIZED", "PROFESSIONAL"
    );

    private final TextRepository textRepository;
    private final ProjectRepository projectRepository;
    private final TextAuditService textAuditService;
    private final S3Service s3Service;
    private final TextReadCache readCache;

    @PersistenceContext
    private EntityManager entityManager;

    private static final MediaSearchSqlBuilder.Spec TEXT_SEARCH_SPEC = new MediaSearchSqlBuilder.Spec(
            "texts",
            "id",
            List.of(
                    "original_title", "alternative_title", "title_in_central_kurdish", "romanized_title",
                    "text_code", "file_name",
                    "author", "isbn"
            ),
            List.of(
                    "text_code", "file_name", "volume_name", "directory", "path_in_external_volume", "auto_path",
                    "original_title", "alternative_title", "title_in_central_kurdish", "romanized_title",
                    "document_type", "description",
                    "script", "transcription", "isbn", "assignment_number", "edition", "volume", "series", "text_version",
                    "language", "dialect",
                    "author", "contributors", "printing_house", "audience",
                    "accrual_method", "provenance", "text_status", "archive_cataloging",
                    "physical_label", "location_in_archive_room", "lcc_classification", "note",
                    "copyright", "right_owner", "license_type", "usage_rights", "availability", "owner", "publisher"
            ),
            List.of(
                    new MediaSearchSqlBuilder.ChildTable("text_subjects", "text_id", "subject"),
                    new MediaSearchSqlBuilder.ChildTable("text_genres",   "text_id", "genre"),
                    new MediaSearchSqlBuilder.ChildTable("text_tags",     "text_id", "tag"),
                    new MediaSearchSqlBuilder.ChildTable("text_keywords", "text_id", "keyword")
            )
    );

    public TextResponseDTO create(TextCreateRequestDTO dto,
                                  MultipartFile textFile,
                                  Authentication authentication,
                                  HttpServletRequest request) {
        validateCreate(dto, textFile);

        Project project = resolveProject(dto.getProjectCode());

        String textVersion = dto.getTextVersion().toUpperCase(Locale.ROOT);
        Integer versionNumber = dto.getVersionNumber();
        Integer copyNumber = dto.getCopyNumber();
        if (!VALID_VERSIONS.contains(textVersion)) {
            throw new TextValidationException("Text version must be one of: RAW, MASTER, RESTORED, ARCHIVE, ORIGINAL, DIGITIZED, PROFESSIONAL");
        }
        if (versionNumber == null || versionNumber < 1) {
            throw new TextValidationException("Version number is required and must be at least 1");
        }
        if (copyNumber == null || copyNumber < 1) {
            throw new TextValidationException("Copy number is required and must be at least 1");
        }

        String textCode = generateTextCode(project, textVersion, versionNumber, copyNumber);

        if (textRepository.existsByTextCode(textCode)) {
            throw new TextAlreadyExistsException("Text code already exists: " + textCode);
        }

        Text text = new Text();
        text.setTextCode(textCode);
        text.setProject(project);
        applyDto(text, dto);
        text.setTextFileUrl(uploadTextFile(textFile, textCode));
        touchCreateAudit(text, authentication);

        Text saved = textRepository.save(text);
        readCache.evictAll();
        textAuditService.record(saved, TextAuditAction.CREATE, authentication, request, buildCreateDetails(saved));
        return toResponse(saved);
    }

    public record BulkCreateResult(int requested, int inserted, int skipped, long elapsedMs) {}

    /**
     * Bulk-create text records from a JSON array. Each entry carries its own
     * {@code textFileUrl} (no multipart upload). Text codes are auto-generated
     * using an in-memory per-project counter. Rows that fail validation or
     * whose generated code already exists are skipped. One audit summary.
     */
    public BulkCreateResult createAll(List<TextBulkCreateRequestDTO> dtos,
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

        List<Text> toInsert = new ArrayList<>(dtos.size());
        int skipped = 0;
        for (TextBulkCreateRequestDTO dto : dtos) {
            if (dto == null || dto.getProjectCode() == null || dto.getProjectCode().isBlank()
                    || dto.getTextVersion() == null
                    || dto.getVersionNumber() == null || dto.getVersionNumber() < 1
                    || dto.getCopyNumber() == null || dto.getCopyNumber() < 1) {
                skipped++;
                continue;
            }
            String version = dto.getTextVersion().toUpperCase(Locale.ROOT);
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

            long seq = nextSeqByProject.computeIfAbsent(project.getId(),
                    pid -> textRepository.countByProject(project) + 1L);
            String parentCode = project.getPerson() != null
                    ? project.getPerson().getPersonCode().toUpperCase(Locale.ROOT)
                    : project.getCategories().get(0).getCategoryCode().toUpperCase(Locale.ROOT);
            String textCode = parentCode
                    + "_TXT_" + version
                    + "_V" + dto.getVersionNumber()
                    + "_Copy(" + dto.getCopyNumber() + ")"
                    + "_" + String.format(Locale.ROOT, "%06d", seq);
            nextSeqByProject.put(project.getId(), seq + 1);

            if (textRepository.existsByTextCode(textCode)) {
                skipped++;
                continue;
            }

            Text text = new Text();
            text.setTextCode(textCode);
            text.setProject(project);
            applyDto(text, dto);
            text.setTextFileUrl(dto.getTextFileUrl());
            text.setCreatedAt(now);
            text.setUpdatedAt(now);
            text.setCreatedBy(actor);
            text.setUpdatedBy(actor);
            toInsert.add(text);
        }

        textRepository.saveAll(toInsert);
        readCache.evictAll();

        long elapsed = System.currentTimeMillis() - start;
        textAuditService.record(null, TextAuditAction.CREATE, authentication, request,
                "Bulk created texts: requested=" + dtos.size()
                        + " inserted=" + toInsert.size()
                        + " skipped=" + skipped
                        + " elapsedMs=" + elapsed);
        return new BulkCreateResult(dtos.size(), toInsert.size(), skipped, elapsed);
    }

    /** Fast path: served from Redis on hit; on miss loads with batched fetches and caches. */
    @Transactional(readOnly = true)
    public Page<TextResponseDTO> getAll(Pageable pageable, Authentication authentication, HttpServletRequest request) {
        List<TextResponseDTO> all = readCache.getAllActive();
        Page<TextResponseDTO> page = PaginationSupport.sliceList(all, pageable);
        textAuditService.record(null, TextAuditAction.LIST, authentication, request,
                "Listed active text records (page=" + page.getNumber()
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
    public List<TextResponseDTO> search(String query,
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
                TEXT_SEARCH_SPEC, tokens, SEARCH_PREFILTER_LIMIT, effectiveLimit);
        jakarta.persistence.Query nq = entityManager.createNativeQuery(built.sql(), Text.class);
        built.params().forEach(nq::setParameter);
        @SuppressWarnings("unchecked")
        List<Text> rows = (List<Text>) nq.getResultList();
        List<TextResponseDTO> result = rows.stream().map(this::toResponse).toList();

        textAuditService.record(null, TextAuditAction.SEARCH, authentication, request,
                "Searched texts q=\"" + normalized + "\" tokens=" + tokens
                        + " limit=" + effectiveLimit + " hits=" + result.size());
        return result;
    }

    @Transactional(readOnly = true)
    public TextResponseDTO getByTextCode(String textCode,
                                         Authentication authentication,
                                         HttpServletRequest request) {
        String normalized = normalizeRequiredCode(textCode, "Text code");
        Text text = textRepository.findByTextCodeAndRemovedAtIsNull(normalized)
                .orElseThrow(() -> new TextNotFoundException("Text not found: " + textCode));
        textAuditService.record(text, TextAuditAction.READ, authentication, request, "Read text record");
        return toResponse(text);
    }

    public TextResponseDTO update(String textCode,
                                  TextUpdateRequestDTO dto,
                                  MultipartFile textFile,
                                  Authentication authentication,
                                  HttpServletRequest request) {
        String normalized = normalizeRequiredCode(textCode, "Text code");
        Text text = textRepository.findByTextCodeAndRemovedAtIsNull(normalized)
                .orElseThrow(() -> new TextNotFoundException("Text not found: " + textCode));

        Text before = snapshot(text);
        if (dto != null) {
            validateProjectNotChanged(text, dto);
            applyDto(text, dto);
        }

        String oldTextFileUrl = text.getTextFileUrl();
        if (textFile != null && !textFile.isEmpty()) {
            String newTextFileUrl = uploadTextFile(textFile, normalized);
            text.setTextFileUrl(newTextFileUrl);
            if (oldTextFileUrl != null && !Objects.equals(oldTextFileUrl, newTextFileUrl)) {
                deleteStoredFile(oldTextFileUrl);
            }
        }

        touchUpdateAudit(text, authentication);
        Text saved = textRepository.save(text);
        readCache.evictAll();
        textAuditService.record(saved, TextAuditAction.UPDATE, authentication, request, buildUpdateDetails(before, saved));
        return toResponse(saved);
    }

    /**
     * Soft remove — marks the text as removed but keeps data in the database.
     */
    public void remove(String textCode,
                       Authentication authentication,
                       HttpServletRequest request) {
        String normalized = normalizeRequiredCode(textCode, "Text code");
        Text text = textRepository.findByTextCodeAndRemovedAtIsNull(normalized)
                .orElseThrow(() -> new TextNotFoundException("Text not found: " + textCode));

        text.setRemovedAt(Instant.now());
        text.setRemovedBy(resolveActorUsername(authentication));
        Text saved = textRepository.save(text);
        readCache.evictAll();
        textAuditService.record(saved, TextAuditAction.REMOVE, authentication, request, "Removed text record (soft delete)");
    }

    /**
     * Hard delete — permanently removes the row from the database and the file from S3.
     * Restricted to ADMIN role (authority {@code text:delete}) only.
     */
    public void delete(String textCode,
                       Authentication authentication,
                       HttpServletRequest request) {
        requireAdminRole(authentication);
        String normalized = normalizeRequiredCode(textCode, "Text code");
        Text text = textRepository.findByTextCodeAndRemovedAtIsNull(normalized)
                .or(() -> textRepository.findAll().stream()
                        .filter(t -> t.getTextCode().equals(normalized))
                        .findFirst())
                .orElseThrow(() -> new TextNotFoundException("Text not found: " + textCode));

        String textFileUrl = text.getTextFileUrl();
        textAuditService.record(text, TextAuditAction.DELETE, authentication, request, "Permanently deleted text record");
        textRepository.delete(text);
        readCache.evictAll();
        deleteStoredFile(textFileUrl);
    }

    // ─── Validation ──────────────────────────────────────────────────────────────

    private void validateCreate(TextCreateRequestDTO dto, MultipartFile textFile) {
        if (dto == null) {
            throw new TextValidationException("Text payload is required");
        }
        if (textFile == null || textFile.isEmpty()) {
            throw new TextValidationException("Text file is required");
        }
        if (dto.getProjectCode() == null || dto.getProjectCode().isBlank()) {
            throw new TextValidationException("Project code is required");
        }
    }

    private void validateProjectNotChanged(Text text, TextBaseRequestDTO dto) {
        if (dto.getProjectCode() != null && !dto.getProjectCode().isBlank()) {
            String currentProjectCode = text.getProject() != null ? text.getProject().getProjectCode() : null;
            if (!dto.getProjectCode().trim().equals(currentProjectCode)) {
                throw new TextValidationException("Text project cannot be changed after creation. Create a new text record instead.");
            }
        }
    }

    // ─── Code Generation ─────────────────────────────────────────────────────────

    /**
     * Generates text code in the format: PARENT_TXT_VERSION_VN_Copy(CN)_SEQUENCE
     * <p>
     * If the project has a person: PERSONCODE_TXT_MASTER_V1_Copy(1)_000001
     * If the project has no person: CATEGORYCODE_TXT_MASTER_V1_Copy(1)_000001
     */
    private String generateTextCode(Project project, String textVersion, Integer versionNumber, Integer copyNumber) {
        String parentCode;
        if (project.getPerson() != null) {
            parentCode = project.getPerson().getPersonCode().toUpperCase(Locale.ROOT);
        } else {
            parentCode = project.getCategories().get(0).getCategoryCode().toUpperCase(Locale.ROOT);
        }

        long sequence = textRepository.countByProject(project) + 1;

        return parentCode
                + "_TXT_" + textVersion
                + "_V" + versionNumber
                + "_Copy(" + copyNumber + ")"
                + "_" + String.format(Locale.ROOT, "%06d", sequence);
    }

    // ─── DTO Mapping ─────────────────────────────────────────────────────────────

    private void applyDto(Text text, TextBaseRequestDTO dto) {
        if (dto == null) {
            return;
        }

        BeanUtils.copyProperties(dto, text,
                "projectCode", "physicalAvailability", "textVersion");

        if (dto.getPhysicalAvailability() != null) text.setPhysicalAvailability(dto.getPhysicalAvailability());
        if (dto.getTextVersion() != null) text.setTextVersion(dto.getTextVersion().toUpperCase(Locale.ROOT));
    }

    TextResponseDTO toResponse(Text text) {
        if (text == null) {
            return null;
        }

        Project project = text.getProject();
        return TextResponseDTO.builder()
                .id(text.getId())
                .textCode(text.getTextCode())
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
                .textFileUrl(text.getTextFileUrl())
                // File & Path
                .fileName(text.getFileName())
                .volumeName(text.getVolumeName())
                .directory(text.getDirectory())
                .pathInExternalVolume(text.getPathInExternalVolume())
                .autoPath(text.getAutoPath())
                // Titles
                .originalTitle(text.getOriginalTitle())
                .alternativeTitle(text.getAlternativeTitle())
                .titleInCentralKurdish(text.getTitleInCentralKurdish())
                .romanizedTitle(text.getRomanizedTitle())
                // Classification
                .subject(copyList(text.getSubject()))
                .genre(copyList(text.getGenre()))
                .documentType(text.getDocumentType())
                .description(text.getDescription())
                // Text Details
                .script(text.getScript())
                .transcription(text.getTranscription())
                .isbn(text.getIsbn())
                .assignmentNumber(text.getAssignmentNumber())
                .edition(text.getEdition())
                .volume(text.getVolume())
                .series(text.getSeries())
                .textVersion(text.getTextVersion())
                .versionNumber(text.getVersionNumber())
                .copyNumber(text.getCopyNumber())
                // Technical
                .fileSize(text.getFileSize())
                .extension(text.getExtension())
                .orientation(text.getOrientation())
                .pageCount(text.getPageCount())
                .size(text.getSize())
                .physicalDimensions(text.getPhysicalDimensions())
                // Language
                .language(text.getLanguage())
                .dialect(text.getDialect())
                // People
                .author(text.getAuthor())
                .contributors(text.getContributors())
                .printingHouse(text.getPrintingHouse())
                .audience(text.getAudience())
                // Archival
                .accrualMethod(text.getAccrualMethod())
                .provenance(text.getProvenance())
                .textStatus(text.getTextStatus())
                .archiveCataloging(text.getArchiveCataloging())
                .physicalAvailability(text.isPhysicalAvailability())
                .physicalLabel(text.getPhysicalLabel())
                .locationInArchiveRoom(text.getLocationInArchiveRoom())
                .lccClassification(text.getLccClassification())
                .note(text.getNote())
                // Tags & Keywords
                .tags(copyList(text.getTags()))
                .keywords(copyList(text.getKeywords()))
                // Dates
                .dateCreated(text.getDateCreated())
                .printDate(text.getPrintDate())
                .dateModified(text.getDateModified())
                .datePublished(text.getDatePublished())
                // Rights
                .copyright(text.getCopyright())
                .rightOwner(text.getRightOwner())
                .dateCopyrighted(text.getDateCopyrighted())
                .licenseType(text.getLicenseType())
                .usageRights(text.getUsageRights())
                .availability(text.getAvailability())
                .owner(text.getOwner())
                .publisher(text.getPublisher())
                // Audit
                .createdAt(text.getCreatedAt())
                .updatedAt(text.getUpdatedAt())
                .removedAt(text.getRemovedAt())
                .createdBy(text.getCreatedBy())
                .updatedBy(text.getUpdatedBy())
                .removedBy(text.getRemovedBy())
                .build();
    }

    // ─── Audit Details ───────────────────────────────────────────────────────────

    private String buildCreateDetails(Text text) {
        String projectInfo = text.getProject() != null ? text.getProject().getProjectCode() : "none";
        return "Created text record with code=" + text.getTextCode()
                + " project=" + projectInfo
                + " textFileUrl=" + text.getTextFileUrl();
    }

    private String buildUpdateDetails(Text before, Text after) {
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
        addChange(changes, "documentType", before.getDocumentType(), after.getDocumentType());
        addChange(changes, "description", before.getDescription(), after.getDescription());
        addChange(changes, "script", before.getScript(), after.getScript());
        addChange(changes, "transcription", before.getTranscription(), after.getTranscription());
        addChange(changes, "isbn", before.getIsbn(), after.getIsbn());
        addChange(changes, "assignmentNumber", before.getAssignmentNumber(), after.getAssignmentNumber());
        addChange(changes, "edition", before.getEdition(), after.getEdition());
        addChange(changes, "volume", before.getVolume(), after.getVolume());
        addChange(changes, "series", before.getSeries(), after.getSeries());
        addChange(changes, "textVersion", before.getTextVersion(), after.getTextVersion());
        addChange(changes, "versionNumber", before.getVersionNumber(), after.getVersionNumber());
        addChange(changes, "copyNumber", before.getCopyNumber(), after.getCopyNumber());
        addChange(changes, "fileSize", before.getFileSize(), after.getFileSize());
        addChange(changes, "extension", before.getExtension(), after.getExtension());
        addChange(changes, "orientation", before.getOrientation(), after.getOrientation());
        addChange(changes, "pageCount", before.getPageCount(), after.getPageCount());
        addChange(changes, "size", before.getSize(), after.getSize());
        addChange(changes, "physicalDimensions", before.getPhysicalDimensions(), after.getPhysicalDimensions());
        addChange(changes, "language", before.getLanguage(), after.getLanguage());
        addChange(changes, "dialect", before.getDialect(), after.getDialect());
        addChange(changes, "author", before.getAuthor(), after.getAuthor());
        addChange(changes, "contributors", before.getContributors(), after.getContributors());
        addChange(changes, "printingHouse", before.getPrintingHouse(), after.getPrintingHouse());
        addChange(changes, "audience", before.getAudience(), after.getAudience());
        addChange(changes, "accrualMethod", before.getAccrualMethod(), after.getAccrualMethod());
        addChange(changes, "provenance", before.getProvenance(), after.getProvenance());
        addChange(changes, "textStatus", before.getTextStatus(), after.getTextStatus());
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
        addChange(changes, "textFileUrl", before.getTextFileUrl(), after.getTextFileUrl());

        if (changes.isEmpty()) {
            return "Updated text record (no field changes detected)";
        }
        return "Updated text record: " + String.join(" | ", changes);
    }

    private void addChange(List<String> changes, String field, Object before, Object after) {
        if (!Objects.equals(before, after)) {
            changes.add(field + ": " + before + " -> " + after);
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    private Project resolveProject(String projectCode) {
        if (projectCode == null || projectCode.isBlank()) {
            throw new TextValidationException("Project code is required");
        }
        return projectRepository.findByProjectCodeAndRemovedAtIsNull(projectCode.trim())
                .orElseThrow(() -> new ProjectNotFoundException("Project not found: " + projectCode));
    }

    private String uploadTextFile(MultipartFile textFile, String textCode) {
        if (textFile == null || textFile.isEmpty()) {
            return null;
        }
        return s3Service.upload(textFile, TEXT_FOLDER + "/" + textCode);
    }

    private void deleteStoredFile(String textFileUrl) {
        if (textFileUrl != null && !textFileUrl.isBlank() && s3Service.isOurS3Url(textFileUrl)) {
            s3Service.deleteFile(textFileUrl);
        }
    }

    private String normalizeRequiredCode(String code, String label) {
        if (code == null) {
            throw new TextValidationException(label + " is required");
        }
        String trimmed = code.trim();
        if (trimmed.isBlank()) {
            throw new TextValidationException(label + " is required");
        }
        return trimmed;
    }

    private void touchCreateAudit(Text text, Authentication authentication) {
        Instant now = Instant.now();
        String actor = resolveActorUsername(authentication);
        text.setCreatedAt(now);
        text.setUpdatedAt(now);
        text.setCreatedBy(actor);
        text.setUpdatedBy(actor);
    }

    private void touchUpdateAudit(Text text, Authentication authentication) {
        text.setUpdatedAt(Instant.now());
        text.setUpdatedBy(resolveActorUsername(authentication));
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
                .anyMatch("text:delete"::equals);
        if (!canHardDelete) {
            throw new AccessDeniedException("Only ADMIN can permanently delete text records");
        }
    }

    private Text snapshot(Text text) {
        if (text == null) {
            return null;
        }
        Text copy = new Text();
        BeanUtils.copyProperties(text, copy);
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
