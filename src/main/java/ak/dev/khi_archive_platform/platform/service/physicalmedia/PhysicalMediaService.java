package ak.dev.khi_archive_platform.platform.service.physicalmedia;

import ak.dev.khi_archive_platform.platform.dto.physicalmedia.PhysicalMediaCreateRequestDTO;
import ak.dev.khi_archive_platform.platform.dto.physicalmedia.PhysicalMediaFilterParams;
import ak.dev.khi_archive_platform.platform.dto.physicalmedia.PhysicalMediaResponseDTO;
import ak.dev.khi_archive_platform.platform.dto.physicalmedia.PhysicalMediaUpdateRequestDTO;
import ak.dev.khi_archive_platform.platform.enums.PhysicalMediaAuditAction;
import ak.dev.khi_archive_platform.platform.exceptions.PhysicalMediaNotFoundException;
import ak.dev.khi_archive_platform.platform.exceptions.PhysicalMediaValidationException;
import ak.dev.khi_archive_platform.platform.model.physicalmedia.PhysicalMedia;
import ak.dev.khi_archive_platform.platform.repo.physicalmedia.PhysicalMediaRepository;
import ak.dev.khi_archive_platform.platform.service.common.CodeGenLock;
import ak.dev.khi_archive_platform.platform.service.common.PaginationSupport;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * CRUD + trash workflow for the physical-media inventory.
 *
 * <p>The {@code pmCode} business key is generated atomically through
 * {@link CodeGenLock} so concurrent creates never collide. Code format:
 * {@code PM_000001}, zero-padded on the row count at the time of insert.
 *
 * <p>Reads are open to anyone with {@code physical_media:read}; mutations
 * gate on the matching {@code create / update / remove / delete}
 * authorities. The Excel importer ({@link PhysicalMediaExcelImportService})
 * calls into this service for the actual persistence so audit + dedupe
 * stay consistent between hand-entry and bulk import paths.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class PhysicalMediaService {

    private static final String CODE_PREFIX = "PM";
    private static final String CODE_LOCK_NAMESPACE = "physical-media-code-gen";
    /** Per-type lock namespace prefix; concurrent inserts on different media
     *  types proceed in parallel, two inserts on the same type serialise. */
    private static final String INVENTORY_NUM_LOCK_NAMESPACE = "physical-media-inv-num:";

    private final PhysicalMediaRepository repository;
    private final PhysicalMediaMapper mapper;
    private final PhysicalMediaAuditService auditService;
    private final CodeGenLock codeGenLock;
    private final PhysicalMediaTypeService typeService;

    // ─── Create / Update / Delete ────────────────────────────────────────────

    public PhysicalMediaResponseDTO create(PhysicalMediaCreateRequestDTO dto,
                                           Authentication auth,
                                           HttpServletRequest request) {
        validateRequiredOnCreate(dto);

        PhysicalMedia entity = new PhysicalMedia();
        entity.setPmCode(generateCode());
        entity.setSource("MANUAL");
        entity.setCreatedBy(actorName(auth));
        entity.setUpdatedBy(actorName(auth));
        mapper.applyCreate(entity, dto);
        // Per-type sequence: the inventory Number is ALWAYS server-assigned on
        // manual create — max(Number)+1 for this media type — so the frontend
        // never sends it and two artefacts of the same type can't collide.
        // Any value the client happened to include is deliberately ignored
        // here; only the Excel importer round-trips the sheet's own Number.
        // physicalMediaType is guaranteed non-blank by validateRequiredOnCreate
        // above; the lock is keyed by type so concurrent inserts on the same
        // type serialise while different types stay parallel.
        entity.setInventoryNumber(nextInventoryNumber(entity.getPhysicalMediaType()));

        PhysicalMedia saved = repository.save(entity);
        auditService.record(saved, PhysicalMediaAuditAction.CREATE, auth, request,
                "type=" + saved.getPhysicalMediaType()
                        + " label=" + saved.getPhysicalLabel());
        return mapper.toResponse(saved);
    }

    /**
     * Inserts a row coming from the Excel importer. Distinct from
     * {@link #create} so the importer can:
     * <ul>
     *   <li>Mark {@code source = IMPORT} for traceability.</li>
     *   <li>Skip per-row audit (the importer writes one batch
     *       {@link PhysicalMediaAuditAction#IMPORT} entry).</li>
     *   <li>Auto-create unknown types in the catalog instead of rejecting
     *       the row.</li>
     * </ul>
     *
     * <p><b>Every row becomes a new record</b>. {@code pmCode} is the
     * unique business key; the sheet's
     * {@code (physical_media_type, physical_label)} pair is <em>not</em>
     * unique — two artefacts that share a label are still two artefacts.
     * The importer used to upsert on that pair, which silently merged
     * distinct physical tapes; that behaviour has been removed.
     *
     * <p><b>Runs in its own transaction</b> (REQUIRES_NEW). Without this, a
     * single bad row throws from inside the importer's per-row loop; even
     * though the loop catches it, Spring has already marked the outer
     * transaction rollback-only, and the whole 4400-row batch dies at commit
     * with {@code UnexpectedRollbackException}. REQUIRES_NEW isolates each
     * row's success or failure so the importer's "skip and continue"
     * semantics actually work.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PhysicalMedia insertFromImport(PhysicalMediaCreateRequestDTO dto, String actor) {
        PhysicalMedia entity = new PhysicalMedia();
        entity.setPmCode(generateCode());
        entity.setSource("IMPORT");
        entity.setCreatedBy(actor);
        entity.setUpdatedBy(actor);
        mapper.applyCreate(entity, dto);
        // Lenient on import: if the sheet carries an unknown media type,
        // auto-register it in the catalog (blank defaults). Manual creates
        // are strict; the importer must not refuse an entire sheet just
        // because somebody coined a new tape format last week.
        if (entity.getPhysicalMediaType() != null
                && !entity.getPhysicalMediaType().isBlank()) {
            typeService.ensureExists(entity.getPhysicalMediaType(), actor);
        }
        // Sheet-row Number wins when present (round-trips the sheet). When
        // the row didn't carry one (hand-added without filling it, or a
        // blank cell), fall through to the per-type sequence.
        if (entity.getInventoryNumber() == null
                && entity.getPhysicalMediaType() != null) {
            entity.setInventoryNumber(nextInventoryNumber(entity.getPhysicalMediaType()));
        }
        return repository.save(entity);
    }

    public PhysicalMediaResponseDTO update(String pmCode,
                                           PhysicalMediaUpdateRequestDTO dto,
                                           Authentication auth,
                                           HttpServletRequest request) {
        PhysicalMedia entity = mustFindActive(pmCode);
        List<String> touched = mapper.applyUpdate(entity, dto);
        entity.setUpdatedBy(actorName(auth));

        PhysicalMedia saved = repository.save(entity);
        auditService.record(saved, PhysicalMediaAuditAction.UPDATE, auth, request,
                "fields=" + (touched.isEmpty() ? "<none>" : String.join(",", touched)));
        return mapper.toResponse(saved);
    }

    public void softDelete(String pmCode, Authentication auth, HttpServletRequest request) {
        PhysicalMedia entity = mustFindActive(pmCode);
        entity.setRemovedAt(Instant.now());
        entity.setRemovedBy(actorName(auth));
        repository.save(entity);
        auditService.record(entity, PhysicalMediaAuditAction.REMOVE, auth, request, "soft-trashed");
    }

    public PhysicalMediaResponseDTO restore(String pmCode, Authentication auth, HttpServletRequest request) {
        PhysicalMedia entity = repository.findByPmCode(pmCode)
                .orElseThrow(() -> new PhysicalMediaNotFoundException("Physical media not found: " + pmCode));
        if (entity.getRemovedAt() == null) {
            throw new PhysicalMediaValidationException("Physical media is not in trash: " + pmCode);
        }
        entity.setRemovedAt(null);
        entity.setRemovedBy(null);
        entity.setUpdatedBy(actorName(auth));
        PhysicalMedia saved = repository.save(entity);
        auditService.record(saved, PhysicalMediaAuditAction.RESTORE, auth, request, "restored from trash");
        return mapper.toResponse(saved);
    }

    public void purge(String pmCode, Authentication auth, HttpServletRequest request) {
        PhysicalMedia entity = repository.findByPmCode(pmCode)
                .orElseThrow(() -> new PhysicalMediaNotFoundException("Physical media not found: " + pmCode));
        if (entity.getRemovedAt() == null) {
            throw new PhysicalMediaValidationException("Physical media must be trashed before purge: " + pmCode);
        }
        auditService.record(entity, PhysicalMediaAuditAction.PURGE, auth, request, "permanent deletion");
        repository.delete(entity);
    }

    // ─── Reads ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PhysicalMediaResponseDTO getByCode(String pmCode, Authentication auth, HttpServletRequest request) {
        PhysicalMedia entity = mustFindActive(pmCode);
        auditService.record(entity, PhysicalMediaAuditAction.READ, auth, request, null);
        return mapper.toResponse(entity);
    }

    /**
     * Active rows with optional filter + sort. Three cases, cheapest first:
     * <ul>
     *   <li><b>No params</b> — the original fast DB-paged query with the
     *       default order; one page loaded. Byte-for-byte the pre-filter
     *       behaviour.</li>
     *   <li><b>Sort only</b> (a {@code sortBy} on a DB-mappable key, no
     *       filters) — still the fast DB-paged query, but with the order pushed
     *       into the database via {@link #effectivePageable}; one page loaded,
     *       no full-set scan.</li>
     *   <li><b>Filters present, or a derived-key sort</b> ({@code digitization})
     *       — load the full active set, map to DTOs, filter+sort in memory via
     *       {@link PhysicalMediaFilterSupport}, then slice with
     *       {@link PaginationSupport}. This entity is DB-paged (no read-cache),
     *       so the full-set load happens only here; the inventory is a few
     *       thousand rows, microseconds to scan.</li>
     * </ul>
     * The DB and in-memory orderings are built to agree (see {@code SortSupport}),
     * so a row lands in the same place whichever path runs.
     */
    @Transactional(readOnly = true)
    public Page<PhysicalMediaResponseDTO> listActive(Pageable pageable,
                                                     PhysicalMediaFilterParams filter,
                                                     Authentication auth,
                                                     HttpServletRequest request) {
        PhysicalMediaFilterParams params = filter == null ? new PhysicalMediaFilterParams() : filter;

        Page<PhysicalMediaResponseDTO> page;
        if (needsInMemory(params)) {
            List<PhysicalMediaResponseDTO> all = repository.findAllByRemovedAtIsNullOrderByIdAsc()
                    .stream().map(mapper::toResponse).toList();
            List<PhysicalMediaResponseDTO> view = PhysicalMediaFilterSupport.applyFiltersAndSort(all, params);
            page = PaginationSupport.sliceList(view, pageable);
        } else {
            page = repository.findAllByRemovedAtIsNull(effectivePageable(pageable, params))
                    .map(mapper::toResponse);
        }

        auditService.record(null, PhysicalMediaAuditAction.LIST, auth, request,
                "size=" + page.getNumberOfElements()
                        + " total=" + page.getTotalElements()
                        + auditSuffix(params));
        return page;
    }

    /**
     * Trashed rows with the same optional filter + sort as {@link #listActive}.
     * Same two-path shape: unfiltered → fast DB-paged trash query; filtered →
     * load the full trashed set, filter+sort in memory, slice.
     */
    @Transactional(readOnly = true)
    public Page<PhysicalMediaResponseDTO> listTrash(Pageable pageable,
                                                    PhysicalMediaFilterParams filter,
                                                    Authentication auth,
                                                    HttpServletRequest request) {
        PhysicalMediaFilterParams params = filter == null ? new PhysicalMediaFilterParams() : filter;

        Page<PhysicalMediaResponseDTO> page;
        if (needsInMemory(params)) {
            List<PhysicalMediaResponseDTO> all = repository.findAllByRemovedAtIsNotNullOrderByIdAsc()
                    .stream().map(mapper::toResponse).toList();
            List<PhysicalMediaResponseDTO> view = PhysicalMediaFilterSupport.applyFiltersAndSort(all, params);
            page = PaginationSupport.sliceList(view, pageable);
        } else {
            page = repository.findAllByRemovedAtIsNotNull(effectivePageable(pageable, params))
                    .map(mapper::toResponse);
        }

        auditService.record(null, PhysicalMediaAuditAction.LIST, auth, request,
                "trash size=" + page.getNumberOfElements()
                        + " total=" + page.getTotalElements()
                        + auditSuffix(params));
        return page;
    }

    @Transactional(readOnly = true)
    public List<PhysicalMediaResponseDTO> search(String q, int limit, Authentication auth, HttpServletRequest request) {
        if (q == null || q.isBlank()) return List.of();
        int safeLimit = Math.max(1, Math.min(limit, 100));
        List<PhysicalMedia> rows = repository.searchByText(q.trim(), safeLimit);
        auditService.record(null, PhysicalMediaAuditAction.SEARCH, auth, request, "q=" + q + " hits=" + rows.size());
        return rows.stream().map(mapper::toResponse).toList();
    }

    /** Used by the importer to log one IMPORT entry per batch. */
    public void recordImport(int inserted, int skipped,
                             Authentication auth, HttpServletRequest request) {
        auditService.record(null, PhysicalMediaAuditAction.IMPORT, auth, request,
                "inserted=" + inserted + " skipped=" + skipped);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /**
     * A request must take the in-memory path when it carries real filters, or
     * when it sorts by a key the DB can't express (physical media:
     * {@code digitization}, ordered by a derived code). A pure sort-only
     * request on a DB-mappable key stays on the fast DB-paged path.
     */
    private static boolean needsInMemory(PhysicalMediaFilterParams p) {
        return p.hasActiveFilters()
                || PhysicalMediaFilterSupport.requiresInMemorySort(p.getSortBy(), p.getSortDirection());
    }

    /**
     * The pageable to hand the DB fast path: the incoming page/size, but with
     * the sort replaced by the {@code sortBy}-resolved order when present.
     * Falls back to the incoming pageable (its {@code @PageableDefault}) when
     * no {@code sortBy} was supplied.
     */
    private static Pageable effectivePageable(Pageable pageable, PhysicalMediaFilterParams p) {
        Sort dbSort = PhysicalMediaFilterSupport.resolveDbSort(p.getSortBy(), p.getSortDirection());
        return dbSort.isSorted()
                ? PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), dbSort)
                : pageable;
    }

    /** Audit suffix: nothing when empty, {@code filtered=true} on the in-memory
     *  path, {@code sorted=true} on the DB fast-sort path. */
    private static String auditSuffix(PhysicalMediaFilterParams p) {
        if (p.isEmpty()) return "";
        return needsInMemory(p) ? " filtered=true" : " sorted=true";
    }

    private PhysicalMedia mustFindActive(String pmCode) {
        return repository.findByPmCodeAndRemovedAtIsNull(pmCode)
                .orElseThrow(() -> new PhysicalMediaNotFoundException("Physical media not found: " + pmCode));
    }

    private void validateRequiredOnCreate(PhysicalMediaCreateRequestDTO dto) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (isBlank(dto.getPhysicalMediaType())) errors.put("physicalMediaType", "must not be blank");
        if (isBlank(dto.getTitle()) && isBlank(dto.getPhysicalLabel())) {
            errors.put("title", "title or physicalLabel must be provided");
        }
        // Strict on manual create: the frontend dropdown is driven by the
        // catalog, so a value it doesn't contain is almost always a typo.
        // The Excel importer takes a different path that auto-creates
        // unknown types instead of rejecting.
        if (errors.isEmpty()
                && !isBlank(dto.getPhysicalMediaType())
                && !typeService.existsByName(dto.getPhysicalMediaType())) {
            errors.put("physicalMediaType",
                    "not in the type catalog — add it via /api/physical-media/types first");
        }
        if (!errors.isEmpty()) {
            throw new PhysicalMediaValidationException("Validation failed", errors);
        }
    }

    private String generateCode() {
        codeGenLock.lock(CODE_LOCK_NAMESPACE);
        long sequence = repository.count() + 1;
        return String.format(Locale.ROOT, "%s_%06d", CODE_PREFIX, sequence);
    }

    /**
     * Mints the next {@code Number} for one media type. The Excel sheet
     * uses a per-type contiguous counter (Audio Cassette: 1..1893, VHS:
     * 1..55, …) and the team expects the same shape going forward — the
     * 56th VHS gets Number=56. Looks at {@code MAX(inventoryNumber)} for
     * the type and adds 1; first-of-type starts at 1.
     *
     * <p>Concurrency: serialised on a per-type advisory lock so two
     * employees creating new VHS rows simultaneously don't collide on
     * the same number. Different types stay parallel.
     */
    private int nextInventoryNumber(String physicalMediaType) {
        codeGenLock.lock(INVENTORY_NUM_LOCK_NAMESPACE + physicalMediaType.trim());
        Integer max = repository.findMaxInventoryNumberByPhysicalMediaType(physicalMediaType.trim());
        return (max == null ? 0 : max) + 1;
    }

    /**
     * Best-effort preview of the next {@code Number} for a type without
     * acquiring the advisory lock. Drives the frontend's "the new row
     * will be Number 56" hint so the user can see the value before they
     * submit and doesn't have to type it again. The actual create path
     * re-mints under the lock, so a stale preview can't collide.
     */
    @Transactional(readOnly = true)
    public int peekNextInventoryNumber(String physicalMediaType) {
        if (physicalMediaType == null || physicalMediaType.isBlank()) {
            throw new PhysicalMediaValidationException("type query parameter is required");
        }
        Integer max = repository.findMaxInventoryNumberByPhysicalMediaType(physicalMediaType.trim());
        return (max == null ? 0 : max) + 1;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String actorName(Authentication auth) {
        return auth == null ? "system" : auth.getName();
    }
}
