package ak.dev.khi_archive_platform.user.service;

import ak.dev.khi_archive_platform.user.dto.admin.UserAuditLogDTO;
import ak.dev.khi_archive_platform.user.enums.UserAuditAction;
import ak.dev.khi_archive_platform.user.exceptions.UserNotFoundException;
import ak.dev.khi_archive_platform.user.model.UserAuditLog;
import ak.dev.khi_archive_platform.user.repo.UserAuditLogRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Read-side service for the {@code user_audit_logs} table — backs the
 * {@code /api/admin/users/audit-logs} endpoints. Audit-log reads are
 * intentionally <b>not</b> themselves audited (otherwise viewing the log
 * generates new rows in the same log on every page load).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserAuditLogService {

    /** Hard cap on page size so a malicious or misconfigured client can't
     *  pull the whole table in one request. */
    private static final int MAX_PAGE_SIZE = 200;
    private static final int DEFAULT_PAGE_SIZE = 50;

    private final UserAuditLogRepository auditLogRepository;

    /**
     * Page through the audit log with optional filters. All filters are AND-ed.
     * Sort defaults to most-recent-first.
     */
    public Page<UserAuditLogDTO> search(Filter filter, Integer page, Integer size, String sort) {
        int pageIndex = page == null || page < 0 ? 0 : page;
        int pageSize  = size == null || size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        Sort.Direction dir = parseDir(sort);
        Pageable pageable = PageRequest.of(pageIndex, pageSize, Sort.by(dir, "occurredAt").and(Sort.by(dir, "id")));
        return auditLogRepository.findAll(buildSpec(filter), pageable).map(this::toDto);
    }

    public UserAuditLogDTO getById(Long id) {
        UserAuditLog log = auditLogRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException("Audit log not found: id=" + id));
        return toDto(log);
    }

    /** All recognised actions — drives the action filter dropdown on the UI. */
    public List<String> listActions() {
        return Arrays.stream(UserAuditAction.values()).map(Enum::name).toList();
    }

    /** Distinct actor usernames seen in the table. Useful for filter UIs. */
    public List<String> listActors() {
        return auditLogRepository.findDistinctActorUsernames();
    }

    // ─── Spec ───────────────────────────────────────────────────────────────

    private Specification<UserAuditLog> buildSpec(Filter f) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (f.targetUserId() != null) {
                predicates.add(cb.equal(root.get("targetUserId"), f.targetUserId()));
            }
            if (f.targetUsername() != null && !f.targetUsername().isBlank()) {
                predicates.add(cb.equal(cb.lower(root.get("targetUsername")),
                        f.targetUsername().trim().toLowerCase(Locale.ROOT)));
            }
            if (f.actor() != null && !f.actor().isBlank()) {
                predicates.add(cb.equal(cb.lower(root.get("actorUsername")),
                        f.actor().trim().toLowerCase(Locale.ROOT)));
            }
            if (f.action() != null) {
                predicates.add(cb.equal(root.get("action"), f.action()));
            }
            if (f.from() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("occurredAt"), f.from()));
            }
            if (f.to() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("occurredAt"), f.to()));
            }
            if (f.q() != null && !f.q().isBlank()) {
                String like = "%" + f.q().trim().toLowerCase(Locale.ROOT) + "%";
                Predicate detailsLike  = cb.like(cb.lower(root.get("details")),  like);
                Predicate targetLike   = cb.like(cb.lower(root.get("targetUsername")), like);
                Predicate actorLike    = cb.like(cb.lower(root.get("actorUsername")),  like);
                Predicate emailLike    = cb.like(cb.lower(root.get("targetEmail")), like);
                Predicate permsLike    = cb.like(cb.lower(root.get("permissionsChanged")), like);
                predicates.add(cb.or(detailsLike, targetLike, actorLike, emailLike, permsLike));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private Sort.Direction parseDir(String sort) {
        if (sort == null) return Sort.Direction.DESC;
        String norm = sort.trim().toLowerCase(Locale.ROOT);
        if (norm.endsWith("asc"))  return Sort.Direction.ASC;
        if (norm.endsWith("desc")) return Sort.Direction.DESC;
        throw new IllegalArgumentException("Invalid sort value: '" + sort + "'. Allowed: asc | desc");
    }

    // ─── Mapping ────────────────────────────────────────────────────────────

    private UserAuditLogDTO toDto(UserAuditLog l) {
        return UserAuditLogDTO.builder()
                .id(l.getId())
                .action(l.getAction())
                .targetUserId(l.getTargetUserId())
                .targetUsername(l.getTargetUsername())
                .targetDisplayName(l.getTargetDisplayName())
                .targetEmail(l.getTargetEmail())
                .previousRole(l.getPreviousRole())
                .newRole(l.getNewRole())
                .permissionsChanged(l.getPermissionsChanged())
                .actorUserId(l.getActorUserId())
                .actorUsername(l.getActorUsername())
                .actorDisplayName(l.getActorDisplayName())
                .actorAuthorities(l.getActorAuthorities())
                .actorPermissions(l.getActorPermissions())
                .deviceInfo(l.getDeviceInfo())
                .ipAddress(l.getIpAddress())
                .sessionId(l.getSessionId())
                .sessionLoginTimestamp(l.getSessionLoginTimestamp())
                .sessionExpiresAt(l.getSessionExpiresAt())
                .sessionActive(l.getSessionActive())
                .requestMethod(l.getRequestMethod())
                .requestPath(l.getRequestPath())
                .details(l.getDetails())
                .occurredAt(l.getOccurredAt())
                .build();
    }

    /** Filter parameters; null = unconstrained. */
    public record Filter(Long targetUserId,
                         String targetUsername,
                         String actor,
                         UserAuditAction action,
                         Instant from,
                         Instant to,
                         String q) {}
}
