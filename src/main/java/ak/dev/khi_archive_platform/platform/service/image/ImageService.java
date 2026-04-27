package ak.dev.khi_archive_platform.platform.service.image;

import ak.dev.khi_archive_platform.S3Service;
import ak.dev.khi_archive_platform.platform.dto.image.ImageBaseRequestDTO;
import ak.dev.khi_archive_platform.platform.dto.image.ImageCreateRequestDTO;
import ak.dev.khi_archive_platform.platform.dto.image.ImageResponseDTO;
import ak.dev.khi_archive_platform.platform.dto.image.ImageUpdateRequestDTO;
import ak.dev.khi_archive_platform.platform.enums.ImageAuditAction;
import ak.dev.khi_archive_platform.platform.exceptions.ImageAlreadyExistsException;
import ak.dev.khi_archive_platform.platform.exceptions.ImageNotFoundException;
import ak.dev.khi_archive_platform.platform.exceptions.ImageValidationException;
import ak.dev.khi_archive_platform.platform.exceptions.ProjectNotFoundException;
import ak.dev.khi_archive_platform.platform.model.image.Image;
import ak.dev.khi_archive_platform.platform.model.project.Project;
import ak.dev.khi_archive_platform.platform.repo.image.ImageRepository;
import ak.dev.khi_archive_platform.platform.repo.project.ProjectRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional
public class ImageService {

    private static final String IMAGE_FOLDER = "images";
    private static final Set<String> VALID_VERSIONS = Set.of(
            "RAW", "MASTER", "RESTORED", "ARCHIVE", "ORIGINAL",
            "HIGH_RES", "PROFESSIONAL"
    );

    private final ImageRepository imageRepository;
    private final ProjectRepository projectRepository;
    private final ImageAuditService imageAuditService;
    private final S3Service s3Service;

    public ImageResponseDTO create(ImageCreateRequestDTO dto,
                                   MultipartFile imageFile,
                                   Authentication authentication,
                                   HttpServletRequest request) {
        validateCreate(dto, imageFile);

        Project project = resolveProject(dto.getProjectCode());

        String imageVersion = dto.getImageVersion().toUpperCase(Locale.ROOT);
        Integer versionNumber = dto.getVersionNumber();
        Integer copyNumber = dto.getCopyNumber();
        if (!VALID_VERSIONS.contains(imageVersion)) {
            throw new ImageValidationException("Image version must be one of: RAW, MASTER, RESTORED, ARCHIVE, ORIGINAL, HIGH_RES, PROFESSIONAL");
        }
        if (versionNumber == null || versionNumber < 1) {
            throw new ImageValidationException("Version number is required and must be at least 1");
        }
        if (copyNumber == null || copyNumber < 1) {
            throw new ImageValidationException("Copy number is required and must be at least 1");
        }

        String imageCode = generateImageCode(project, imageVersion, versionNumber, copyNumber);

        if (imageRepository.existsByImageCode(imageCode)) {
            throw new ImageAlreadyExistsException("Image code already exists: " + imageCode);
        }

        Image image = new Image();
        image.setImageCode(imageCode);
        image.setProject(project);
        applyDto(image, dto);
        image.setImageFileUrl(uploadImageFile(imageFile, imageCode));
        touchCreateAudit(image, authentication);

        Image saved = imageRepository.save(image);
        imageAuditService.record(saved, ImageAuditAction.CREATE, authentication, request, buildCreateDetails(saved));
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<ImageResponseDTO> getAll(Authentication authentication, HttpServletRequest request) {
        List<ImageResponseDTO> result = imageRepository.findAllByRemovedAtIsNull().stream()
                .map(this::toResponse)
                .toList();
        imageAuditService.record(null, ImageAuditAction.LIST, authentication, request, "Listed active image records");
        return result;
    }

    @Transactional(readOnly = true)
    public ImageResponseDTO getByImageCode(String imageCode,
                                           Authentication authentication,
                                           HttpServletRequest request) {
        String normalized = normalizeRequiredCode(imageCode, "Image code");
        Image image = imageRepository.findByImageCodeAndRemovedAtIsNull(normalized)
                .orElseThrow(() -> new ImageNotFoundException("Image not found: " + imageCode));
        imageAuditService.record(image, ImageAuditAction.READ, authentication, request, "Read image record");
        return toResponse(image);
    }

    public ImageResponseDTO update(String imageCode,
                                   ImageUpdateRequestDTO dto,
                                   MultipartFile imageFile,
                                   Authentication authentication,
                                   HttpServletRequest request) {
        String normalized = normalizeRequiredCode(imageCode, "Image code");
        Image image = imageRepository.findByImageCodeAndRemovedAtIsNull(normalized)
                .orElseThrow(() -> new ImageNotFoundException("Image not found: " + imageCode));

        Image before = snapshot(image);
        if (dto != null) {
            validateProjectNotChanged(image, dto);
            applyDto(image, dto);
        }

        String oldImageFileUrl = image.getImageFileUrl();
        if (imageFile != null && !imageFile.isEmpty()) {
            String newImageFileUrl = uploadImageFile(imageFile, normalized);
            image.setImageFileUrl(newImageFileUrl);
            if (oldImageFileUrl != null && !Objects.equals(oldImageFileUrl, newImageFileUrl)) {
                deleteStoredFile(oldImageFileUrl);
            }
        }

        touchUpdateAudit(image, authentication);
        Image saved = imageRepository.save(image);
        imageAuditService.record(saved, ImageAuditAction.UPDATE, authentication, request, buildUpdateDetails(before, saved));
        return toResponse(saved);
    }

    /**
     * Soft remove — marks the image as removed but keeps data in the database.
     */
    public void remove(String imageCode,
                       Authentication authentication,
                       HttpServletRequest request) {
        String normalized = normalizeRequiredCode(imageCode, "Image code");
        Image image = imageRepository.findByImageCodeAndRemovedAtIsNull(normalized)
                .orElseThrow(() -> new ImageNotFoundException("Image not found: " + imageCode));

        image.setRemovedAt(Instant.now());
        image.setRemovedBy(resolveActorUsername(authentication));
        Image saved = imageRepository.save(image);
        imageAuditService.record(saved, ImageAuditAction.REMOVE, authentication, request, "Removed image record (soft delete)");
    }

    /**
     * Hard delete — permanently removes the row from the database and the file from S3.
     * Restricted to ADMIN and SUPER_ADMIN roles only.
     */
    public void delete(String imageCode,
                       Authentication authentication,
                       HttpServletRequest request) {
        requireAdminRole(authentication);
        String normalized = normalizeRequiredCode(imageCode, "Image code");
        Image image = imageRepository.findByImageCodeAndRemovedAtIsNull(normalized)
                .or(() -> imageRepository.findAll().stream()
                        .filter(i -> i.getImageCode().equals(normalized))
                        .findFirst())
                .orElseThrow(() -> new ImageNotFoundException("Image not found: " + imageCode));

        String imageFileUrl = image.getImageFileUrl();
        imageAuditService.record(image, ImageAuditAction.DELETE, authentication, request, "Permanently deleted image record");
        imageRepository.delete(image);
        deleteStoredFile(imageFileUrl);
    }

    // ─── Validation ──────────────────────────────────────────────────────────────

    private void validateCreate(ImageCreateRequestDTO dto, MultipartFile imageFile) {
        if (dto == null) {
            throw new ImageValidationException("Image payload is required");
        }
        if (imageFile == null || imageFile.isEmpty()) {
            throw new ImageValidationException("Image file is required");
        }
        if (dto.getProjectCode() == null || dto.getProjectCode().isBlank()) {
            throw new ImageValidationException("Project code is required");
        }
    }

    private void validateProjectNotChanged(Image image, ImageBaseRequestDTO dto) {
        if (dto.getProjectCode() != null && !dto.getProjectCode().isBlank()) {
            String currentProjectCode = image.getProject() != null ? image.getProject().getProjectCode() : null;
            if (!dto.getProjectCode().trim().equals(currentProjectCode)) {
                throw new ImageValidationException("Image project cannot be changed after creation. Create a new image record instead.");
            }
        }
    }

    // ─── Code Generation ─────────────────────────────────────────────────────────

    /**
     * Generates image code in the format: PARENT_IMG_VERSION_VN_Copy(CN)_SEQUENCE
     * <p>
     * If the project has a person: PERSONCODE_IMG_MASTER_V1_Copy(1)_000001
     * If the project has no person: CATEGORYCODE_IMG_MASTER_V1_Copy(1)_000001
     */
    private String generateImageCode(Project project, String imageVersion, Integer versionNumber, Integer copyNumber) {
        String parentCode;
        if (project.getPerson() != null) {
            parentCode = project.getPerson().getPersonCode().toUpperCase(Locale.ROOT);
        } else {
            parentCode = project.getCategories().get(0).getCategoryCode().toUpperCase(Locale.ROOT);
        }

        long sequence = imageRepository.countByProject(project) + 1;

        return parentCode
                + "_IMG_" + imageVersion
                + "_V" + versionNumber
                + "_Copy(" + copyNumber + ")"
                + "_" + String.format(Locale.ROOT, "%06d", sequence);
    }

    // ─── DTO Mapping ─────────────────────────────────────────────────────────────

    private void applyDto(Image image, ImageBaseRequestDTO dto) {
        if (dto == null) {
            return;
        }

        BeanUtils.copyProperties(dto, image,
                "projectCode", "physicalAvailability", "imageVersion");

        if (dto.getPhysicalAvailability() != null) image.setPhysicalAvailability(dto.getPhysicalAvailability());
        if (dto.getImageVersion() != null) image.setImageVersion(dto.getImageVersion().toUpperCase(Locale.ROOT));
    }

    private ImageResponseDTO toResponse(Image image) {
        if (image == null) {
            return null;
        }

        Project project = image.getProject();
        return ImageResponseDTO.builder()
                .id(image.getId())
                .imageCode(image.getImageCode())
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
                .imageFileUrl(image.getImageFileUrl())
                // File & Path
                .fileName(image.getFileName())
                .volumeName(image.getVolumeName())
                .directory(image.getDirectory())
                .pathInExternalVolume(image.getPathInExternalVolume())
                .autoPath(image.getAutoPath())
                // Titles
                .originalTitle(image.getOriginalTitle())
                .alternativeTitle(image.getAlternativeTitle())
                .titleInCentralKurdish(image.getTitleInCentralKurdish())
                .romanizedTitle(image.getRomanizedTitle())
                // Classification
                .subject(copyList(image.getSubject()))
                .form(image.getForm())
                .genre(copyList(image.getGenre()))
                .event(image.getEvent())
                .location(image.getLocation())
                .description(image.getDescription())
                // Image Details
                .personShownInImage(image.getPersonShownInImage())
                .colorOfImage(copyList(image.getColorOfImage()))
                .imageVersion(image.getImageVersion())
                .versionNumber(image.getVersionNumber())
                .copyNumber(image.getCopyNumber())
                .whereThisImageUsed(copyList(image.getWhereThisImageUsed()))
                // Technical
                .fileSize(image.getFileSize())
                .extension(image.getExtension())
                .orientation(image.getOrientation())
                .dimension(image.getDimension())
                .bitDepth(image.getBitDepth())
                .dpi(image.getDpi())
                // Equipment
                .manufacturer(image.getManufacturer())
                .model(image.getModel())
                .lens(image.getLens())
                // People
                .creatorArtistPhotographer(image.getCreatorArtistPhotographer())
                .contributor(image.getContributor())
                .audience(image.getAudience())
                // Archival
                .accrualMethod(image.getAccrualMethod())
                .provenance(image.getProvenance())
                .photostory(image.getPhotostory())
                .imageStatus(image.getImageStatus())
                .archiveCataloging(image.getArchiveCataloging())
                .physicalAvailability(image.isPhysicalAvailability())
                .physicalLabel(image.getPhysicalLabel())
                .locationInArchiveRoom(image.getLocationInArchiveRoom())
                .lccClassification(image.getLccClassification())
                .note(image.getNote())
                // Tags & Keywords
                .tags(copyList(image.getTags()))
                .keywords(copyList(image.getKeywords()))
                // Dates
                .dateCreated(image.getDateCreated())
                .dateModified(image.getDateModified())
                .datePublished(image.getDatePublished())
                // Rights
                .copyright(image.getCopyright())
                .rightOwner(image.getRightOwner())
                .dateCopyrighted(image.getDateCopyrighted())
                .licenseType(image.getLicenseType())
                .usageRights(image.getUsageRights())
                .availability(image.getAvailability())
                .owner(image.getOwner())
                .publisher(image.getPublisher())
                // Audit
                .createdAt(image.getCreatedAt())
                .updatedAt(image.getUpdatedAt())
                .removedAt(image.getRemovedAt())
                .createdBy(image.getCreatedBy())
                .updatedBy(image.getUpdatedBy())
                .removedBy(image.getRemovedBy())
                .build();
    }

    // ─── Audit Details ───────────────────────────────────────────────────────────

    private String buildCreateDetails(Image image) {
        String projectInfo = image.getProject() != null ? image.getProject().getProjectCode() : "none";
        return "Created image record with code=" + image.getImageCode()
                + " project=" + projectInfo
                + " imageFileUrl=" + image.getImageFileUrl();
    }

    private String buildUpdateDetails(Image before, Image after) {
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
        addChange(changes, "form", before.getForm(), after.getForm());
        addChange(changes, "genre", before.getGenre(), after.getGenre());
        addChange(changes, "event", before.getEvent(), after.getEvent());
        addChange(changes, "location", before.getLocation(), after.getLocation());
        addChange(changes, "description", before.getDescription(), after.getDescription());
        addChange(changes, "personShownInImage", before.getPersonShownInImage(), after.getPersonShownInImage());
        addChange(changes, "colorOfImage", before.getColorOfImage(), after.getColorOfImage());
        addChange(changes, "imageVersion", before.getImageVersion(), after.getImageVersion());
        addChange(changes, "versionNumber", before.getVersionNumber(), after.getVersionNumber());
        addChange(changes, "copyNumber", before.getCopyNumber(), after.getCopyNumber());
        addChange(changes, "whereThisImageUsed", before.getWhereThisImageUsed(), after.getWhereThisImageUsed());
        addChange(changes, "fileSize", before.getFileSize(), after.getFileSize());
        addChange(changes, "extension", before.getExtension(), after.getExtension());
        addChange(changes, "orientation", before.getOrientation(), after.getOrientation());
        addChange(changes, "dimension", before.getDimension(), after.getDimension());
        addChange(changes, "bitDepth", before.getBitDepth(), after.getBitDepth());
        addChange(changes, "dpi", before.getDpi(), after.getDpi());
        addChange(changes, "manufacturer", before.getManufacturer(), after.getManufacturer());
        addChange(changes, "model", before.getModel(), after.getModel());
        addChange(changes, "lens", before.getLens(), after.getLens());
        addChange(changes, "creatorArtistPhotographer", before.getCreatorArtistPhotographer(), after.getCreatorArtistPhotographer());
        addChange(changes, "contributor", before.getContributor(), after.getContributor());
        addChange(changes, "audience", before.getAudience(), after.getAudience());
        addChange(changes, "accrualMethod", before.getAccrualMethod(), after.getAccrualMethod());
        addChange(changes, "provenance", before.getProvenance(), after.getProvenance());
        addChange(changes, "photostory", before.getPhotostory(), after.getPhotostory());
        addChange(changes, "imageStatus", before.getImageStatus(), after.getImageStatus());
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
        addChange(changes, "imageFileUrl", before.getImageFileUrl(), after.getImageFileUrl());

        if (changes.isEmpty()) {
            return "Updated image record (no field changes detected)";
        }
        return "Updated image record: " + String.join(" | ", changes);
    }

    private void addChange(List<String> changes, String field, Object before, Object after) {
        if (!Objects.equals(before, after)) {
            changes.add(field + ": " + before + " -> " + after);
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    private Project resolveProject(String projectCode) {
        if (projectCode == null || projectCode.isBlank()) {
            throw new ImageValidationException("Project code is required");
        }
        return projectRepository.findByProjectCodeAndRemovedAtIsNull(projectCode.trim())
                .orElseThrow(() -> new ProjectNotFoundException("Project not found: " + projectCode));
    }

    private String uploadImageFile(MultipartFile imageFile, String imageCode) {
        if (imageFile == null || imageFile.isEmpty()) {
            return null;
        }
        return s3Service.upload(imageFile, IMAGE_FOLDER + "/" + imageCode);
    }

    private void deleteStoredFile(String imageFileUrl) {
        if (imageFileUrl != null && !imageFileUrl.isBlank() && s3Service.isOurS3Url(imageFileUrl)) {
            s3Service.deleteFile(imageFileUrl);
        }
    }

    private String normalizeRequiredCode(String code, String label) {
        if (code == null) {
            throw new ImageValidationException(label + " is required");
        }
        String trimmed = code.trim();
        if (trimmed.isBlank()) {
            throw new ImageValidationException(label + " is required");
        }
        return trimmed;
    }

    private void touchCreateAudit(Image image, Authentication authentication) {
        Instant now = Instant.now();
        String actor = resolveActorUsername(authentication);
        image.setCreatedAt(now);
        image.setUpdatedAt(now);
        image.setCreatedBy(actor);
        image.setUpdatedBy(actor);
    }

    private void touchUpdateAudit(Image image, Authentication authentication) {
        image.setUpdatedAt(Instant.now());
        image.setUpdatedBy(resolveActorUsername(authentication));
    }

    private String resolveActorUsername(Authentication authentication) {
        return authentication == null ? "anonymous" : authentication.getName();
    }

    private void requireAdminRole(Authentication authentication) {
        if (authentication == null) {
            throw new AccessDeniedException("Authentication is required for this operation");
        }
        boolean isAdmin = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> "ROLE_ADMIN".equals(a) || "ROLE_SUPER_ADMIN".equals(a));
        if (!isAdmin) {
            throw new AccessDeniedException("Only ADMIN or SUPER_ADMIN can permanently delete records");
        }
    }

    private Image snapshot(Image image) {
        if (image == null) {
            return null;
        }
        Image copy = new Image();
        BeanUtils.copyProperties(image, copy);
        return copy;
    }

    private List<String> copyList(List<String> list) {
        return list == null ? null : new ArrayList<>(list);
    }
}
