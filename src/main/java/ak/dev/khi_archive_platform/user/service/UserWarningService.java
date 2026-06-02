package ak.dev.khi_archive_platform.user.service;

import ak.dev.khi_archive_platform.user.dto.admin.UnacknowledgedWarningCountDTO;
import ak.dev.khi_archive_platform.user.dto.admin.UserWarningCreateRequestDTO;
import ak.dev.khi_archive_platform.user.dto.admin.UserWarningResponseDTO;
import ak.dev.khi_archive_platform.user.dto.admin.UserWarningUpdateRequestDTO;
import ak.dev.khi_archive_platform.user.enums.UserAuditAction;
import ak.dev.khi_archive_platform.user.enums.WarningSeverity;
import ak.dev.khi_archive_platform.user.exceptions.IllegalAdminOperationException;
import ak.dev.khi_archive_platform.user.exceptions.UserNotFoundException;
import ak.dev.khi_archive_platform.user.exceptions.UserWarningNotFoundException;
import ak.dev.khi_archive_platform.user.model.User;
import ak.dev.khi_archive_platform.user.model.UserWarning;
import ak.dev.khi_archive_platform.user.repo.UserRepository;
import ak.dev.khi_archive_platform.user.repo.UserWarningRepository;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Issues, lists, edits and revokes admin warnings to employees, plus the
 * recipient-facing acknowledge flow.
 *
 * <p>Every mutation writes one row to {@code user_audit_logs} via
 * {@link UserAuditService}:
 * <ul>
 *   <li>{@code WARNING_SENT} — admin issued a new warning.</li>
 *   <li>{@code WARNING_REVOKED} — admin revoked an existing warning.</li>
 *   <li>{@code WARNING_ACKNOWLEDGED} — recipient confirmed they read it.</li>
 *   <li>{@code UPDATE} — admin edited an existing warning.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Transactional
public class UserWarningService {

    private static final int DEFAULT_PAGE_SIZE = 25;
    private static final int MAX_PAGE_SIZE = 200;

    private final UserWarningRepository warningRepository;
    private final UserRepository userRepository;
    private final UserAuditService auditService;

    // ─── Admin actions ──────────────────────────────────────────────────────

    /** Admin issues a new warning. */
    public UserWarningResponseDTO send(UserWarningCreateRequestDTO dto,
                                       Authentication auth,
                                       HttpServletRequest request) {
        User target = userRepository.findById(dto.getTargetUserId())
                .orElseThrow(() -> new UserNotFoundException(
                        "User not found: id=" + dto.getTargetUserId()));

        // Self-warn would be noise and breaks the "admin sends to employee"
        // mental model. Refuse it.
        User actor = resolveActor(auth);
        if (actor != null && actor.getUserId() != null
                && actor.getUserId().equals(target.getUserId())) {
            throw new IllegalAdminOperationException(
                    "SELF_WARNING",
                    "You cannot send a warning to yourself.");
        }

        WarningSeverity severity = dto.getSeverity() == null
                ? WarningSeverity.WARNING : dto.getSeverity();

        UserWarning warning = UserWarning.builder()
                .targetUserId(target.getUserId())
                .targetUsername(target.getUsername())
                .actorUserId(actor != null ? actor.getUserId() : null)
                .actorUsername(actor != null ? actor.getUsername()
                        : (auth != null ? auth.getName() : null))
                .actorDisplayName(actor != null ? actor.getName()
                        : (auth != null ? auth.getName() : null))
                .severity(severity)
                .title(safe(dto.getTitle()))
                .message(safe(dto.getMessage()))
                .acknowledged(false)
                .createdAt(Instant.now())
                .build();
        UserWarning saved = warningRepository.save(warning);

        auditService.record(target, UserAuditAction.WARNING_SENT,
                null, null, null, auth, request,
                "Sent warning id=" + saved.getId()
                        + " severity=" + severity
                        + " title='" + saved.getTitle() + "'");
        return toDto(saved);
    }

    /** Admin edits an existing (non-revoked) warning. No-op if nothing changes. */
    public UserWarningResponseDTO update(Long warningId,
                                         UserWarningUpdateRequestDTO dto,
                                         Authentication auth,
                                         HttpServletRequest request) {
        UserWarning warning = warningRepository.findByIdAndRemovedAtIsNull(warningId)
                .orElseThrow(() -> new UserWarningNotFoundException(
                        "Warning not found or already revoked: id=" + warningId));

        Map<String, String> diff = new LinkedHashMap<>();
        if (dto.getSeverity() != null && dto.getSeverity() != warning.getSeverity()) {
            diff.put("severity", warning.getSeverity() + " -> " + dto.getSeverity());
            warning.setSeverity(dto.getSeverity());
        }
        if (dto.getTitle() != null && !dto.getTitle().isBlank()
                && !dto.getTitle().equals(warning.getTitle())) {
            String safeTitle = safe(dto.getTitle());
            diff.put("title", "'" + warning.getTitle() + "' -> '" + safeTitle + "'");
            warning.setTitle(safeTitle);
        }
        if (dto.getMessage() != null && !dto.getMessage().isBlank()
                && !dto.getMessage().equals(warning.getMessage())) {
            warning.setMessage(safe(dto.getMessage()));
            diff.put("message", "(updated)");
        }
        if (diff.isEmpty()) return toDto(warning);

        UserWarning saved = warningRepository.save(warning);

        User target = userRepository.findById(saved.getTargetUserId()).orElse(null);
        auditService.record(target, UserAuditAction.UPDATE,
                null, null, null, auth, request,
                "Edited warning id=" + saved.getId() + ": "
                        + diff.entrySet().stream()
                                .map(e -> e.getKey() + "=" + e.getValue())
                                .collect(Collectors.joining("; ")));
        return toDto(saved);
    }

    /** Admin revokes a warning. Soft-delete: row is preserved, recipient stops
     *  seeing it. Idempotent — revoking an already-revoked warning is a no-op. */
    public void revoke(Long warningId, Authentication auth, HttpServletRequest request) {
        UserWarning warning = warningRepository.findById(warningId)
                .orElseThrow(() -> new UserWarningNotFoundException(
                        "Warning not found: id=" + warningId));
        if (warning.getRemovedAt() != null) return;

        warning.setRemovedAt(Instant.now());
        warningRepository.save(warning);

        User target = userRepository.findById(warning.getTargetUserId()).orElse(null);
        auditService.record(target, UserAuditAction.WARNING_REVOKED,
                null, null, null, auth, request,
                "Revoked warning id=" + warning.getId()
                        + " severity=" + warning.getSeverity()
                        + " title='" + warning.getTitle() + "'");
    }

    @Transactional(readOnly = true)
    public Page<UserWarningResponseDTO> search(Long targetUserId,
                                               Long actorUserId,
                                               WarningSeverity severity,
                                               Boolean acknowledged,
                                               boolean includeRevoked,
                                               Instant from,
                                               Instant to,
                                               Integer page,
                                               Integer size) {
        Pageable pageable = PageRequest.of(clampPage(page), clampSize(size),
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));
        Specification<UserWarning> spec = buildSearchSpec(targetUserId, actorUserId,
                severity, acknowledged, includeRevoked, from, to);
        return warningRepository.findAll(spec, pageable).map(this::toDto);
    }

    /** Build a {@link Specification} that only adds predicates for the filters
     *  actually supplied. Spec-driven dynamic SQL keeps Postgres from seeing
     *  untyped {@code (? IS NULL)} comparisons (which fail with SQLSTATE
     *  42P18 "could not determine data type of parameter") and lets the
     *  planner skip the predicate entirely when the filter isn't used. */
    private Specification<UserWarning> buildSearchSpec(Long targetUserId,
                                                       Long actorUserId,
                                                       WarningSeverity severity,
                                                       Boolean acknowledged,
                                                       boolean includeRevoked,
                                                       Instant from,
                                                       Instant to) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (targetUserId != null) {
                predicates.add(cb.equal(root.get("targetUserId"), targetUserId));
            }
            if (actorUserId != null) {
                predicates.add(cb.equal(root.get("actorUserId"), actorUserId));
            }
            if (severity != null) {
                predicates.add(cb.equal(root.get("severity"), severity));
            }
            if (acknowledged != null) {
                predicates.add(cb.equal(root.get("acknowledged"), acknowledged));
            }
            if (!includeRevoked) {
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

    @Transactional(readOnly = true)
    public UserWarningResponseDTO getByIdForAdmin(Long warningId) {
        UserWarning warning = warningRepository.findById(warningId)
                .orElseThrow(() -> new UserWarningNotFoundException(
                        "Warning not found: id=" + warningId));
        return toDto(warning);
    }

    // ─── Recipient (self-service) ───────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<UserWarningResponseDTO> getMyWarnings(Authentication auth,
                                                      Integer page, Integer size) {
        User me = requireActor(auth);
        Pageable pageable = PageRequest.of(clampPage(page), clampSize(size));
        return warningRepository.findActiveForUser(me.getUserId(), pageable)
                .map(this::toDto);
    }

    @Transactional(readOnly = true)
    public UnacknowledgedWarningCountDTO myUnacknowledgedCount(Authentication auth) {
        User me = requireActor(auth);
        long count = warningRepository
                .countByTargetUserIdAndAcknowledgedFalseAndRemovedAtIsNull(me.getUserId());
        return UnacknowledgedWarningCountDTO.builder().unacknowledged(count).build();
    }

    /** Recipient marks one warning as read. Only the warning's target user
     *  can acknowledge — anyone else gets 403 via {@code IllegalAdminOperationException}. */
    public UserWarningResponseDTO acknowledge(Long warningId,
                                              Authentication auth,
                                              HttpServletRequest request) {
        User me = requireActor(auth);
        UserWarning warning = warningRepository.findByIdAndRemovedAtIsNull(warningId)
                .orElseThrow(() -> new UserWarningNotFoundException(
                        "Warning not found or already revoked: id=" + warningId));

        if (!warning.getTargetUserId().equals(me.getUserId())) {
            throw new IllegalAdminOperationException(
                    "WARNING_NOT_FOR_YOU",
                    "You can only acknowledge warnings addressed to your own account.");
        }
        if (Boolean.TRUE.equals(warning.getAcknowledged())) return toDto(warning);

        warning.setAcknowledged(true);
        warning.setAcknowledgedAt(Instant.now());
        UserWarning saved = warningRepository.save(warning);

        auditService.record(me, UserAuditAction.WARNING_ACKNOWLEDGED,
                null, null, null, auth, request,
                "Acknowledged warning id=" + saved.getId()
                        + " severity=" + saved.getSeverity()
                        + " title='" + saved.getTitle() + "'");
        return toDto(saved);
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    private static User resolveActor(Authentication auth) {
        if (auth == null) return null;
        Object principal = auth.getPrincipal();
        return principal instanceof User u ? u : null;
    }

    private static User requireActor(Authentication auth) {
        User actor = resolveActor(auth);
        if (actor == null || actor.getUserId() == null) {
            throw new IllegalAdminOperationException(
                    "UNAUTHENTICATED",
                    "You must be signed in to manage your warnings.");
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

    private UserWarningResponseDTO toDto(UserWarning w) {
        return UserWarningResponseDTO.builder()
                .id(w.getId())
                .targetUserId(w.getTargetUserId())
                .targetUsername(w.getTargetUsername())
                .actorUserId(w.getActorUserId())
                .actorUsername(w.getActorUsername())
                .actorDisplayName(w.getActorDisplayName())
                .severity(w.getSeverity())
                .title(w.getTitle())
                .message(w.getMessage())
                .acknowledged(Boolean.TRUE.equals(w.getAcknowledged()))
                .acknowledgedAt(w.getAcknowledgedAt())
                .createdAt(w.getCreatedAt())
                .removedAt(w.getRemovedAt())
                .build();
    }
}
