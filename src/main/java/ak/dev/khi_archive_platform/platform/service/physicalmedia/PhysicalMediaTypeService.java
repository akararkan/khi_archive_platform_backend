package ak.dev.khi_archive_platform.platform.service.physicalmedia;

import ak.dev.khi_archive_platform.platform.dto.physicalmedia.PhysicalMediaTypeCreateRequestDTO;
import ak.dev.khi_archive_platform.platform.dto.physicalmedia.PhysicalMediaTypeResponseDTO;
import ak.dev.khi_archive_platform.platform.dto.physicalmedia.PhysicalMediaTypeUpdateRequestDTO;
import ak.dev.khi_archive_platform.platform.enums.PhysicalMediaAuditAction;
import ak.dev.khi_archive_platform.platform.exceptions.PhysicalMediaNotFoundException;
import ak.dev.khi_archive_platform.platform.exceptions.PhysicalMediaValidationException;
import ak.dev.khi_archive_platform.platform.model.physicalmedia.PhysicalMediaType;
import ak.dev.khi_archive_platform.platform.repo.physicalmedia.PhysicalMediaRepository;
import ak.dev.khi_archive_platform.platform.repo.physicalmedia.PhysicalMediaTypeRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * CRUD for the {@code physical_media_types} catalog. Read paths are open
 * to any holder of {@code physical_media:read} (the frontend hits them
 * to populate the type dropdown + autofill values); mutations are gated
 * on {@code physical_media:type_manage} (admin-only by default).
 *
 * <p>Auto-create entry point ({@link #ensureExists}) is invoked by the
 * Excel importer when a sheet carries a type the catalog doesn't know
 * yet — the row gets added with blank defaults so the import doesn't
 * fail on an unfamiliar tape format.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class PhysicalMediaTypeService {

    private final PhysicalMediaTypeRepository typeRepository;
    private final PhysicalMediaRepository mediaRepository;
    private final PhysicalMediaAuditService auditService;

    // ─── Reads ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<PhysicalMediaTypeResponseDTO> listAll() {
        return typeRepository.findAllOrderedByName().stream()
                .map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public PhysicalMediaTypeResponseDTO getById(Long id) {
        return toResponse(typeRepository.findById(id)
                .orElseThrow(() -> new PhysicalMediaNotFoundException(
                        "Physical-media type not found: " + id)));
    }

    @Transactional(readOnly = true)
    public Optional<PhysicalMediaType> findByName(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        return typeRepository.findByName(name.trim());
    }

    @Transactional(readOnly = true)
    public boolean existsByName(String name) {
        if (name == null || name.isBlank()) return false;
        return typeRepository.existsByName(name.trim());
    }

    // ─── Mutations ───────────────────────────────────────────────────────────

    public PhysicalMediaTypeResponseDTO create(PhysicalMediaTypeCreateRequestDTO dto,
                                               Authentication auth,
                                               HttpServletRequest request) {
        String name = dto.getName() == null ? "" : dto.getName().trim();
        if (name.isEmpty()) throw new PhysicalMediaValidationException("Name is required");
        if (typeRepository.existsByName(name)) {
            throw new PhysicalMediaValidationException("Type already exists: " + name);
        }
        PhysicalMediaType entity = PhysicalMediaType.builder()
                .name(name)
                .description(trimOrNull(dto.getDescription()))
                .extension(trimOrNull(dto.getExtension()))
                .bitOrColorDepth(trimOrNull(dto.getBitOrColorDepth()))
                .sampleOrFrameRate(trimOrNull(dto.getSampleOrFrameRate()))
                .channelsOrResolution(trimOrNull(dto.getChannelsOrResolution()))
                .playbackModel(trimOrNull(dto.getPlaybackModel()))
                .captureInterface(trimOrNull(dto.getCaptureInterface()))
                .signalInterface(trimOrNull(dto.getSignalInterface()))
                .ingestSoftware(trimOrNull(dto.getIngestSoftware()))
                .formatCodec(trimOrNull(dto.getFormatCodec()))
                .createdBy(actor(auth))
                .updatedBy(actor(auth))
                .build();
        PhysicalMediaType saved = typeRepository.save(entity);
        auditService.recordTypeAction(saved.getId(), saved.getName(),
                PhysicalMediaAuditAction.TYPE_CREATE, auth, request,
                "added type '" + saved.getName() + "'");
        return toResponse(saved);
    }

    public PhysicalMediaTypeResponseDTO update(Long id,
                                               PhysicalMediaTypeUpdateRequestDTO dto,
                                               Authentication auth,
                                               HttpServletRequest request) {
        PhysicalMediaType entity = typeRepository.findById(id)
                .orElseThrow(() -> new PhysicalMediaNotFoundException(
                        "Physical-media type not found: " + id));
        // Track every field the PATCH actually touched so the audit details
        // read "fields=extension,formatCodec" instead of an opaque "PATCH".
        StringBuilder changed = new StringBuilder();
        if (dto.getName() != null) {
            String newName = dto.getName().trim();
            if (newName.isEmpty()) throw new PhysicalMediaValidationException("Name must not be blank");
            // Rejection on collision; an admin renaming "VHS Cassette" to an
            // existing "VHS Cassette" is almost certainly a mistake.
            if (!newName.equals(entity.getName()) && typeRepository.existsByName(newName)) {
                throw new PhysicalMediaValidationException("Type already exists: " + newName);
            }
            entity.setName(newName); changed.append("name,");
        }
        if (dto.getDescription() != null) { entity.setDescription(trimOrNull(dto.getDescription())); changed.append("description,"); }
        if (dto.getExtension() != null) { entity.setExtension(trimOrNull(dto.getExtension())); changed.append("extension,"); }
        if (dto.getBitOrColorDepth() != null) { entity.setBitOrColorDepth(trimOrNull(dto.getBitOrColorDepth())); changed.append("bitOrColorDepth,"); }
        if (dto.getSampleOrFrameRate() != null) { entity.setSampleOrFrameRate(trimOrNull(dto.getSampleOrFrameRate())); changed.append("sampleOrFrameRate,"); }
        if (dto.getChannelsOrResolution() != null) { entity.setChannelsOrResolution(trimOrNull(dto.getChannelsOrResolution())); changed.append("channelsOrResolution,"); }
        if (dto.getPlaybackModel() != null) { entity.setPlaybackModel(trimOrNull(dto.getPlaybackModel())); changed.append("playbackModel,"); }
        if (dto.getCaptureInterface() != null) { entity.setCaptureInterface(trimOrNull(dto.getCaptureInterface())); changed.append("captureInterface,"); }
        if (dto.getSignalInterface() != null) { entity.setSignalInterface(trimOrNull(dto.getSignalInterface())); changed.append("signalInterface,"); }
        if (dto.getIngestSoftware() != null) { entity.setIngestSoftware(trimOrNull(dto.getIngestSoftware())); changed.append("ingestSoftware,"); }
        if (dto.getFormatCodec() != null) { entity.setFormatCodec(trimOrNull(dto.getFormatCodec())); changed.append("formatCodec,"); }
        entity.setUpdatedBy(actor(auth));
        PhysicalMediaType saved = typeRepository.save(entity);
        auditService.recordTypeAction(saved.getId(), saved.getName(),
                PhysicalMediaAuditAction.TYPE_UPDATE, auth, request,
                "fields=" + (changed.length() == 0 ? "<none>" : changed.substring(0, changed.length() - 1)));
        return toResponse(saved);
    }

    public void delete(Long id, Authentication auth, HttpServletRequest request) {
        PhysicalMediaType entity = typeRepository.findById(id)
                .orElseThrow(() -> new PhysicalMediaNotFoundException(
                        "Physical-media type not found: " + id));
        // Refuse deletion when records still reference the type by name —
        // there's no FK so we'd silently orphan them. Admin can re-tag the
        // affected rows to another type and try again.
        long inUse = mediaRepository.findAll().stream()
                .filter(p -> entity.getName().equals(p.getPhysicalMediaType()))
                .count();
        if (inUse > 0) {
            throw new PhysicalMediaValidationException(
                    "Type '" + entity.getName() + "' is still used by " + inUse + " record(s)");
        }
        String name = entity.getName();
        Long typeId = entity.getId();
        typeRepository.delete(entity);
        auditService.recordTypeAction(typeId, name,
                PhysicalMediaAuditAction.TYPE_DELETE, auth, request,
                "deleted type '" + name + "'");
    }

    /**
     * Importer entry point: returns the catalog row for {@code name},
     * creating a blank-defaults row if none exists yet. Mirrors the
     * "lenient on import" stance of the rest of the importer.
     */
    public PhysicalMediaType ensureExists(String name, String actorUsername) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) return null;
        return typeRepository.findByName(n).orElseGet(() -> {
            PhysicalMediaType fresh = PhysicalMediaType.builder()
                    .name(n)
                    .description("Auto-created during Excel import.")
                    .createdBy(actorUsername == null ? "system-import" : actorUsername)
                    .updatedBy(actorUsername == null ? "system-import" : actorUsername)
                    .build();
            return typeRepository.save(fresh);
        });
    }

    // ─── Mapping ─────────────────────────────────────────────────────────────

    private PhysicalMediaTypeResponseDTO toResponse(PhysicalMediaType e) {
        return PhysicalMediaTypeResponseDTO.builder()
                .id(e.getId())
                .name(e.getName())
                .description(e.getDescription())
                .extension(e.getExtension())
                .bitOrColorDepth(e.getBitOrColorDepth())
                .sampleOrFrameRate(e.getSampleOrFrameRate())
                .channelsOrResolution(e.getChannelsOrResolution())
                .playbackModel(e.getPlaybackModel())
                .captureInterface(e.getCaptureInterface())
                .signalInterface(e.getSignalInterface())
                .ingestSoftware(e.getIngestSoftware())
                .formatCodec(e.getFormatCodec())
                .createdBy(e.getCreatedBy())
                .updatedBy(e.getUpdatedBy())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .version(e.getVersion())
                .build();
    }

    private static String trimOrNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String actor(Authentication auth) {
        return auth == null ? "system" : auth.getName();
    }
}
