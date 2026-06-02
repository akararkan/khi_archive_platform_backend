package ak.dev.khi_archive_platform.platform.service.correction;

import ak.dev.khi_archive_platform.platform.dto.correction.*;
import ak.dev.khi_archive_platform.platform.enums.CorrectionMediaType;
import ak.dev.khi_archive_platform.platform.enums.CorrectionStatus;
import ak.dev.khi_archive_platform.platform.enums.GuestCorrectionAuditAction;
import ak.dev.khi_archive_platform.platform.exceptions.CorrectionAlreadyProcessedException;
import ak.dev.khi_archive_platform.platform.exceptions.GuestCorrectionNotFoundException;
import ak.dev.khi_archive_platform.platform.model.correction.GuestCorrection;
import ak.dev.khi_archive_platform.platform.repo.audio.AudioRepository;
import ak.dev.khi_archive_platform.platform.repo.correction.GuestCorrectionRepository;
import ak.dev.khi_archive_platform.platform.repo.image.ImageRepository;
import ak.dev.khi_archive_platform.platform.repo.text.TextRepository;
import ak.dev.khi_archive_platform.platform.repo.video.VideoRepository;
import ak.dev.khi_archive_platform.user.dto.admin.UserWarningCreateRequestDTO;
import ak.dev.khi_archive_platform.user.enums.WarningSeverity;
import ak.dev.khi_archive_platform.user.exceptions.IllegalAdminOperationException;
import ak.dev.khi_archive_platform.user.model.User;
import ak.dev.khi_archive_platform.user.repo.UserRepository;
import ak.dev.khi_archive_platform.user.service.UserWarningService;
import jakarta.persistence.criteria.Predicate;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class GuestCorrectionService {

    private static final int DEFAULT_PAGE_SIZE = 25;
    private static final int MAX_PAGE_SIZE = 200;

    private final GuestCorrectionRepository correctionRepository;
    private final GuestCorrectionAuditService auditService;
    private final AudioRepository audioRepository;
    private final VideoRepository videoRepository;
    private final ImageRepository imageRepository;
    private final TextRepository textRepository;
    private final UserRepository userRepository;
    private final UserWarningService userWarningService;

    // ─── Guest actions ──────────────────────────────────────────────────────

    /** Authenticated guest (or any logged-in user) submits a correction suggestion. */
    public GuestCorrectionResponseDTO submit(GuestCorrectionSubmitRequestDTO dto,
                                             Authentication auth,
                                             HttpServletRequest request) {
        User actor = requireActor(auth);

        MediaInfo info = resolveMediaInfo(dto.getMediaType(), dto.getMediaCode());

        GuestCorrection correction = GuestCorrection.builder()
                .mediaType(dto.getMediaType())
                .mediaCode(dto.getMediaCode())
                .mediaTitle(info.title())
                .targetField(safe(dto.getTargetField()))
                .currentValue(safe(dto.getCurrentValue()))
                .suggestedValue(safe(dto.getSuggestedValue()))
                .note(safe(dto.getNote()))
                .guestUserId(actor.getUserId())
                .guestUsername(actor.getUsername())
                .guestDisplayName(actor.getName())
                .status(CorrectionStatus.PENDING)
                .recordCreatedBy(info.createdBy())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        GuestCorrection saved = correctionRepository.save(correction);
        auditService.record(saved, GuestCorrectionAuditAction.SUBMIT, auth, request,
                "Submitted correction for " + dto.getMediaType() + "=" + dto.getMediaCode()
                        + " field='" + dto.getTargetField() + "'");
        return toDto(saved);
    }

    /** Returns the authenticated user's own correction submissions (paginated). */
    @Transactional(readOnly = true)
    public Page<GuestCorrectionResponseDTO> getMyCorrections(Authentication auth,
                                                              Integer page, Integer size) {
        User actor = requireActor(auth);
        Pageable pageable = PageRequest.of(clampPage(page), clampSize(size),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return correctionRepository
                .findAllByGuestUserIdAndRemovedAtIsNull(actor.getUserId(), pageable)
                .map(this::toDto);
    }

    /** Returns one of the authenticated user's own submissions by id. */
    @Transactional(readOnly = true)
    public GuestCorrectionResponseDTO getMyCorrection(Long id, Authentication auth,
                                                       HttpServletRequest request) {
        User actor = requireActor(auth);
        GuestCorrection correction = correctionRepository.findByIdAndRemovedAtIsNull(id)
                .orElseThrow(() -> new GuestCorrectionNotFoundException(
                        "Correction not found: id=" + id));

        if (!correction.getGuestUserId().equals(actor.getUserId())) {
            throw new IllegalAdminOperationException("CORRECTION_NOT_YOURS",
                    "You can only view your own correction submissions.");
        }
        auditService.record(correction, GuestCorrectionAuditAction.VIEW, auth, request, null);
        return toDto(correction);
    }

    // ─── Admin actions ──────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<GuestCorrectionResponseDTO> adminSearch(CorrectionMediaType mediaType,
                                                         CorrectionStatus status,
                                                         String mediaCode,
                                                         String recordCreatedBy,
                                                         Long guestUserId,
                                                         boolean includeRemoved,
                                                         Instant from,
                                                         Instant to,
                                                         Integer page,
                                                         Integer size,
                                                         Authentication auth,
                                                         HttpServletRequest request) {
        Pageable pageable = PageRequest.of(clampPage(page), clampSize(size),
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));
        Specification<GuestCorrection> spec = buildSpec(
                mediaType, status, mediaCode, recordCreatedBy, guestUserId, includeRemoved, from, to);

        Page<GuestCorrectionResponseDTO> result = correctionRepository.findAll(spec, pageable).map(this::toDto);
        auditService.record(null, GuestCorrectionAuditAction.LIST, auth, request,
                "Admin searched corrections page=" + clampPage(page));
        return result;
    }

    @Transactional(readOnly = true)
    public GuestCorrectionResponseDTO adminGetById(Long id, Authentication auth,
                                                    HttpServletRequest request) {
        GuestCorrection correction = correctionRepository.findById(id)
                .orElseThrow(() -> new GuestCorrectionNotFoundException(
                        "Correction not found: id=" + id));
        auditService.record(correction, GuestCorrectionAuditAction.VIEW, auth, request, null);
        return toDto(correction);
    }

    /**
     * Admin forwards the correction to the employee who created the media record.
     * Sends a UserWarning (INFO severity) to that employee and updates status to FORWARDED.
     */
    public GuestCorrectionResponseDTO adminForward(Long id,
                                                    AdminCorrectionForwardRequestDTO dto,
                                                    Authentication auth,
                                                    HttpServletRequest request) {
        GuestCorrection correction = correctionRepository.findByIdAndRemovedAtIsNull(id)
                .orElseThrow(() -> new GuestCorrectionNotFoundException(
                        "Correction not found or removed: id=" + id));

        if (correction.getStatus() == CorrectionStatus.RESOLVED
                || correction.getStatus() == CorrectionStatus.REJECTED) {
            throw new CorrectionAlreadyProcessedException(
                    "Correction id=" + id + " is already " + correction.getStatus()
                            + " and cannot be forwarded.");
        }

        User actor = resolveActor(auth);
        String actorUsername = actor != null ? actor.getUsername()
                : (auth != null ? auth.getName() : "admin");

        // Resolve the target employee: use explicit override if provided, else fall back to record creator
        User targetEmployee = null;
        if (dto != null && dto.getTargetEmployeeId() != null) {
            targetEmployee = userRepository.findById(dto.getTargetEmployeeId()).orElseThrow(() ->
                    new IllegalAdminOperationException("EMPLOYEE_NOT_FOUND",
                            "Target employee not found: id=" + dto.getTargetEmployeeId()));
        } else {
            String employeeUsername = correction.getRecordCreatedBy();
            if (employeeUsername != null && !employeeUsername.isBlank()) {
                targetEmployee = userRepository.findByUsername(employeeUsername).orElse(null);
            }
        }

        if (targetEmployee != null) {
            final User finalTarget = targetEmployee;
            UserWarningCreateRequestDTO warningDto = new UserWarningCreateRequestDTO();
            warningDto.setTargetUserId(finalTarget.getUserId());
            warningDto.setSeverity(WarningSeverity.INFO);
            String rawTitle = "Correction Suggestion: " + correction.getMediaType()
                    + " [" + correction.getMediaCode() + "] — field: " + correction.getTargetField();
            warningDto.setTitle(rawTitle.length() > 200 ? rawTitle.substring(0, 197) + "..." : rawTitle);
            warningDto.setMessage(buildForwardMessage(correction, dto));
            userWarningService.send(warningDto, auth, request);
        }

        correction.setStatus(CorrectionStatus.FORWARDED);
        correction.setForwardedBy(actorUsername);
        correction.setForwardedAt(Instant.now());
        correction.setForwardNote(safe(dto != null ? dto.getForwardNote() : null));
        correction.setUpdatedAt(Instant.now());

        GuestCorrection saved = correctionRepository.save(correction);
        String targetName = targetEmployee != null ? targetEmployee.getUsername() : correction.getRecordCreatedBy();
        auditService.record(saved, GuestCorrectionAuditAction.FORWARD, auth, request,
                "Forwarded to employee '" + targetName + "'"
                        + (dto != null && dto.getForwardNote() != null ? " note included" : ""));
        return toDto(saved);
    }

    /** Admin marks correction as resolved (employee applied the fix). */
    public GuestCorrectionResponseDTO adminResolve(Long id,
                                                    AdminCorrectionResolveRequestDTO dto,
                                                    Authentication auth,
                                                    HttpServletRequest request) {
        GuestCorrection correction = correctionRepository.findByIdAndRemovedAtIsNull(id)
                .orElseThrow(() -> new GuestCorrectionNotFoundException(
                        "Correction not found or removed: id=" + id));

        if (correction.getStatus() == CorrectionStatus.REJECTED) {
            throw new CorrectionAlreadyProcessedException(
                    "Correction id=" + id + " is already REJECTED and cannot be resolved.");
        }

        User actor = resolveActor(auth);
        String actorUsername = actor != null ? actor.getUsername()
                : (auth != null ? auth.getName() : "admin");

        correction.setStatus(CorrectionStatus.RESOLVED);
        correction.setResolvedBy(actorUsername);
        correction.setResolvedAt(Instant.now());
        correction.setResolveNote(safe(dto != null ? dto.getResolveNote() : null));
        correction.setUpdatedAt(Instant.now());

        GuestCorrection saved = correctionRepository.save(correction);
        auditService.record(saved, GuestCorrectionAuditAction.RESOLVE, auth, request,
                "Resolved by " + actorUsername);
        return toDto(saved);
    }

    /** Admin rejects a correction suggestion. */
    public GuestCorrectionResponseDTO adminReject(Long id,
                                                   AdminCorrectionRejectRequestDTO dto,
                                                   Authentication auth,
                                                   HttpServletRequest request) {
        GuestCorrection correction = correctionRepository.findByIdAndRemovedAtIsNull(id)
                .orElseThrow(() -> new GuestCorrectionNotFoundException(
                        "Correction not found or removed: id=" + id));

        if (correction.getStatus() == CorrectionStatus.RESOLVED) {
            throw new CorrectionAlreadyProcessedException(
                    "Correction id=" + id + " is already RESOLVED and cannot be rejected.");
        }
        if (correction.getStatus() == CorrectionStatus.REJECTED) {
            return toDto(correction);
        }

        User actor = resolveActor(auth);
        String actorUsername = actor != null ? actor.getUsername()
                : (auth != null ? auth.getName() : "admin");

        correction.setStatus(CorrectionStatus.REJECTED);
        correction.setResolvedBy(actorUsername);
        correction.setResolvedAt(Instant.now());
        correction.setResolveNote(safe(dto != null ? dto.getResolveNote() : null));
        correction.setUpdatedAt(Instant.now());

        GuestCorrection saved = correctionRepository.save(correction);
        auditService.record(saved, GuestCorrectionAuditAction.REJECT, auth, request,
                "Rejected by " + actorUsername);
        return toDto(saved);
    }

    /**
     * Admin directly applies the suggested value to the media record field,
     * then marks the correction as RESOLVED. Supports all simple string/text
     * public fields. List fields (tags, keywords, contributors, genres) must
     * be updated manually via the existing media update endpoints.
     */
    public GuestCorrectionResponseDTO adminApply(Long id,
                                                  AdminCorrectionApplyRequestDTO dto,
                                                  Authentication auth,
                                                  HttpServletRequest request) {
        GuestCorrection correction = correctionRepository.findByIdAndRemovedAtIsNull(id)
                .orElseThrow(() -> new GuestCorrectionNotFoundException(
                        "Correction not found or removed: id=" + id));

        if (correction.getStatus() == CorrectionStatus.REJECTED) {
            throw new CorrectionAlreadyProcessedException(
                    "Correction id=" + id + " is already REJECTED and cannot be applied.");
        }

        String field  = correction.getTargetField();
        String value  = correction.getSuggestedValue();

        applyFieldToMedia(correction.getMediaType(), correction.getMediaCode(), field, value);

        User actor = resolveActor(auth);
        String actorUsername = actor != null ? actor.getUsername()
                : (auth != null ? auth.getName() : "admin");

        correction.setStatus(CorrectionStatus.RESOLVED);
        correction.setResolvedBy(actorUsername);
        correction.setResolvedAt(Instant.now());
        correction.setResolveNote(safe(dto != null ? dto.getResolveNote()
                : "Admin applied correction directly to the record."));
        correction.setUpdatedAt(Instant.now());

        GuestCorrection saved = correctionRepository.save(correction);
        auditService.record(saved, GuestCorrectionAuditAction.RESOLVE, auth, request,
                "Applied field='" + field + "' on " + correction.getMediaType()
                        + "[" + correction.getMediaCode() + "] by " + actorUsername);
        return toDto(saved);
    }

    private void applyFieldToMedia(CorrectionMediaType type, String code, String field, String value) {
        switch (type) {
            case AUDIO -> {
                var audio = audioRepository.findByAudioCodeAndRemovedAtIsNull(code)
                        .orElseThrow(() -> new GuestCorrectionNotFoundException(
                                "Audio record not found: code=" + code));
                applyToAudio(audio, field, value);
                audio.setUpdatedAt(Instant.now());
                audioRepository.save(audio);
            }
            case VIDEO -> {
                var video = videoRepository.findByVideoCodeAndRemovedAtIsNull(code)
                        .orElseThrow(() -> new GuestCorrectionNotFoundException(
                                "Video record not found: code=" + code));
                applyToVideo(video, field, value);
                video.setUpdatedAt(Instant.now());
                videoRepository.save(video);
            }
            case IMAGE -> {
                var image = imageRepository.findByImageCodeAndRemovedAtIsNull(code)
                        .orElseThrow(() -> new GuestCorrectionNotFoundException(
                                "Image record not found: code=" + code));
                applyToImage(image, field, value);
                image.setUpdatedAt(Instant.now());
                imageRepository.save(image);
            }
            case TEXT -> {
                var text = textRepository.findByTextCodeAndRemovedAtIsNull(code)
                        .orElseThrow(() -> new GuestCorrectionNotFoundException(
                                "Text record not found: code=" + code));
                applyToText(text, field, value);
                text.setUpdatedAt(Instant.now());
                textRepository.save(text);
            }
        }
    }

    private void applyToAudio(ak.dev.khi_archive_platform.platform.model.audio.Audio a,
                               String field, String value) {
        switch (field) {
            case "originTitle"        -> a.setOriginTitle(value);
            case "alterTitle"         -> a.setAlterTitle(value);
            case "form"               -> a.setForm(value);
            case "abstractText"       -> a.setAbstractText(value);
            case "description"        -> a.setDescription(value);
            case "speaker"            -> a.setSpeaker(value);
            case "producer"           -> a.setProducer(value);
            case "composer"           -> a.setComposer(value);
            case "poet"               -> a.setPoet(value);
            case "language"           -> a.setLanguage(value);
            case "dialect"            -> a.setDialect(value);
            case "typeOfComposition"  -> a.setTypeOfComposition(value);
            case "typeOfPerformance"  -> a.setTypeOfPerformance(value);
            case "lyrics"             -> a.setLyrics(value);
            case "recordingVenue"     -> a.setRecording_venue(value);
            case "city"               -> a.setCity(value);
            case "region"             -> a.setRegion(value);
            case "audience"           -> a.setAudience(value);
            case "copyright"          -> a.setCopyright(value);
            case "rightOwner"         -> a.setRightOwner(value);
            case "licenseType"        -> a.setLicenseType(value);
            case "availability"       -> a.setAvailability(value);
            case "owner"              -> a.setOwner(value);
            case "publisher"          -> a.setPublisher(value);
            default -> throw new IllegalArgumentException(
                    "Field '" + field + "' is not a supported correctable field on AUDIO. " +
                    "List fields (tags, keywords, genres, contributors) must be updated " +
                    "via the Audio update endpoint.");
        }
    }

    private void applyToVideo(ak.dev.khi_archive_platform.platform.model.video.Video v,
                               String field, String value) {
        switch (field) {
            case "originalTitle"          -> v.setOriginalTitle(value);
            case "alternativeTitle"       -> v.setAlternativeTitle(value);
            case "description"            -> v.setDescription(value);
            case "language"               -> v.setLanguage(value);
            case "dialect"                -> v.setDialect(value);
            case "event"                  -> v.setEvent(value);
            case "location"               -> v.setLocation(value);
            case "creatorArtistDirector"  -> v.setCreatorArtistDirector(value);
            case "producer"               -> v.setProducer(value);
            case "contributor"            -> v.setContributor(value);
            case "personShownInVideo"     -> v.setPersonShownInVideo(value);
            case "subtitle"               -> v.setSubtitle(value);
            case "audience"               -> v.setAudience(value);
            case "provenance"             -> v.setProvenance(value);
            case "publisher"              -> v.setPublisher(value);
            case "copyright"              -> v.setCopyright(value);
            case "licenseType"            -> v.setLicenseType(value);
            default -> throw new IllegalArgumentException(
                    "Field '" + field + "' is not a supported correctable field on VIDEO.");
        }
    }

    private void applyToImage(ak.dev.khi_archive_platform.platform.model.image.Image i,
                               String field, String value) {
        switch (field) {
            case "originalTitle"             -> i.setOriginalTitle(value);
            case "alternativeTitle"          -> i.setAlternativeTitle(value);
            case "description"               -> i.setDescription(value);
            case "event"                     -> i.setEvent(value);
            case "location"                  -> i.setLocation(value);
            case "creatorArtistPhotographer" -> i.setCreatorArtistPhotographer(value);
            case "contributor"               -> i.setContributor(value);
            case "personShownInImage"        -> i.setPersonShownInImage(value);
            case "audience"                  -> i.setAudience(value);
            case "provenance"                -> i.setProvenance(value);
            case "photostory"                -> i.setPhotostory(value);
            case "imageStatus"               -> i.setImageStatus(value);
            case "copyright"                 -> i.setCopyright(value);
            case "licenseType"               -> i.setLicenseType(value);
            default -> throw new IllegalArgumentException(
                    "Field '" + field + "' is not a supported correctable field on IMAGE.");
        }
    }

    private void applyToText(ak.dev.khi_archive_platform.platform.model.text.Text t,
                              String field, String value) {
        switch (field) {
            case "originalTitle"    -> t.setOriginalTitle(value);
            case "alternativeTitle" -> t.setAlternativeTitle(value);
            case "description"      -> t.setDescription(value);
            case "language"         -> t.setLanguage(value);
            case "dialect"          -> t.setDialect(value);
            case "documentType"     -> t.setDocumentType(value);
            case "author"           -> t.setAuthor(value);
            case "contributors"     -> t.setContributors(value);
            case "script"           -> t.setScript(value);
            case "series"           -> t.setSeries(value);
            case "edition"          -> t.setEdition(value);
            case "volume"           -> t.setVolume(value);
            case "printingHouse"    -> t.setPrintingHouse(value);
            case "audience"         -> t.setAudience(value);
            case "provenance"       -> t.setProvenance(value);
            case "publisher"        -> t.setPublisher(value);
            case "copyright"        -> t.setCopyright(value);
            case "licenseType"      -> t.setLicenseType(value);
            default -> throw new IllegalArgumentException(
                    "Field '" + field + "' is not a supported correctable field on TEXT.");
        }
    }

    /** Admin soft-deletes a correction. */
    public void adminRemove(Long id, Authentication auth, HttpServletRequest request) {
        GuestCorrection correction = correctionRepository.findById(id)
                .orElseThrow(() -> new GuestCorrectionNotFoundException(
                        "Correction not found: id=" + id));
        if (correction.getRemovedAt() != null) return;

        User actor = resolveActor(auth);
        String actorUsername = actor != null ? actor.getUsername()
                : (auth != null ? auth.getName() : "admin");

        correction.setRemovedAt(Instant.now());
        correction.setRemovedBy(actorUsername);
        correction.setUpdatedAt(Instant.now());
        correctionRepository.save(correction);

        auditService.record(correction, GuestCorrectionAuditAction.REMOVE, auth, request,
                "Removed by " + actorUsername);
    }

    /** Returns the catalog of valid statuses. */
    @Transactional(readOnly = true)
    public List<String> statusCatalog() {
        return java.util.Arrays.stream(CorrectionStatus.values()).map(Enum::name).toList();
    }

    /** Returns the catalog of valid media types. */
    @Transactional(readOnly = true)
    public List<String> mediaTypeCatalog() {
        return java.util.Arrays.stream(CorrectionMediaType.values()).map(Enum::name).toList();
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    private record MediaInfo(String title, String createdBy) {}

    private MediaInfo resolveMediaInfo(CorrectionMediaType type, String code) {
        return switch (type) {
            case AUDIO -> audioRepository.findByAudioCodeAndRemovedAtIsNull(code)
                    .map(a -> new MediaInfo(a.getOriginTitle(), a.getCreatedBy()))
                    .orElseThrow(() -> new GuestCorrectionNotFoundException(
                            "Audio record not found: code=" + code));
            case VIDEO -> videoRepository.findByVideoCodeAndRemovedAtIsNull(code)
                    .map(v -> new MediaInfo(v.getOriginalTitle(), v.getCreatedBy()))
                    .orElseThrow(() -> new GuestCorrectionNotFoundException(
                            "Video record not found: code=" + code));
            case IMAGE -> imageRepository.findByImageCodeAndRemovedAtIsNull(code)
                    .map(i -> new MediaInfo(i.getOriginalTitle(), i.getCreatedBy()))
                    .orElseThrow(() -> new GuestCorrectionNotFoundException(
                            "Image record not found: code=" + code));
            case TEXT -> textRepository.findByTextCodeAndRemovedAtIsNull(code)
                    .map(t -> new MediaInfo(t.getOriginalTitle(), t.getCreatedBy()))
                    .orElseThrow(() -> new GuestCorrectionNotFoundException(
                            "Text record not found: code=" + code));
        };
    }

    private String buildForwardMessage(GuestCorrection c, AdminCorrectionForwardRequestDTO dto) {
        StringBuilder sb = new StringBuilder();
        sb.append("A guest user has suggested a correction for a record you created.\n\n");
        sb.append("Media: ").append(c.getMediaType()).append(" [").append(c.getMediaCode()).append("]");
        if (c.getMediaTitle() != null) sb.append(" — ").append(c.getMediaTitle());
        sb.append("\nField: ").append(c.getTargetField());
        if (c.getCurrentValue() != null) sb.append("\nCurrent value: ").append(c.getCurrentValue());
        sb.append("\nSuggested value: ").append(c.getSuggestedValue());
        if (c.getNote() != null && !c.getNote().isBlank()) {
            sb.append("\nGuest note: ").append(c.getNote());
        }
        sb.append("\nSubmitted by: ").append(c.getGuestDisplayName())
                .append(" (").append(c.getGuestUsername()).append(")");
        if (dto != null && dto.getForwardNote() != null && !dto.getForwardNote().isBlank()) {
            sb.append("\n\nAdmin note: ").append(dto.getForwardNote());
        }
        return HtmlUtils.htmlEscape(sb.toString());
    }

    private Specification<GuestCorrection> buildSpec(CorrectionMediaType mediaType,
                                                      CorrectionStatus status,
                                                      String mediaCode,
                                                      String recordCreatedBy,
                                                      Long guestUserId,
                                                      boolean includeRemoved,
                                                      Instant from,
                                                      Instant to) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (mediaType != null) {
                predicates.add(cb.equal(root.get("mediaType"), mediaType));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (mediaCode != null && !mediaCode.isBlank()) {
                predicates.add(cb.equal(root.get("mediaCode"), mediaCode.trim()));
            }
            if (recordCreatedBy != null && !recordCreatedBy.isBlank()) {
                predicates.add(cb.equal(root.get("recordCreatedBy"), recordCreatedBy.trim()));
            }
            if (guestUserId != null) {
                predicates.add(cb.equal(root.get("guestUserId"), guestUserId));
            }
            if (!includeRemoved) {
                predicates.add(cb.isNull(root.get("removedAt")));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), to));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static User resolveActor(Authentication auth) {
        if (auth == null) return null;
        Object principal = auth.getPrincipal();
        return principal instanceof User u ? u : null;
    }

    private static User requireActor(Authentication auth) {
        User actor = resolveActor(auth);
        if (actor == null || actor.getUserId() == null) {
            throw new IllegalAdminOperationException("UNAUTHENTICATED",
                    "You must be signed in to submit a correction.");
        }
        return actor;
    }

    private static String safe(String input) {
        return input == null ? null : HtmlUtils.htmlEscape(input.trim());
    }

    private static int clampPage(Integer page) {
        return page == null || page < 0 ? 0 : page;
    }

    private static int clampSize(Integer size) {
        if (size == null || size <= 0) return DEFAULT_PAGE_SIZE;
        return Math.min(size, MAX_PAGE_SIZE);
    }

    private GuestCorrectionResponseDTO toDto(GuestCorrection c) {
        return GuestCorrectionResponseDTO.builder()
                .id(c.getId())
                .mediaType(c.getMediaType())
                .mediaCode(c.getMediaCode())
                .mediaTitle(c.getMediaTitle())
                .targetField(c.getTargetField())
                .currentValue(c.getCurrentValue())
                .suggestedValue(c.getSuggestedValue())
                .note(c.getNote())
                .guestUserId(c.getGuestUserId())
                .guestUsername(c.getGuestUsername())
                .guestDisplayName(c.getGuestDisplayName())
                .status(c.getStatus())
                .recordCreatedBy(c.getRecordCreatedBy())
                .forwardedBy(c.getForwardedBy())
                .forwardedAt(c.getForwardedAt())
                .forwardNote(c.getForwardNote())
                .resolvedBy(c.getResolvedBy())
                .resolvedAt(c.getResolvedAt())
                .resolveNote(c.getResolveNote())
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .removedAt(c.getRemovedAt())
                .build();
    }
}
