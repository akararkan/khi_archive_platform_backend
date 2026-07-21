package ak.dev.khi_archive_platform.platform.api.analytics;

import ak.dev.khi_archive_platform.platform.dto.analytics.InventoryStatsDTO;
import ak.dev.khi_archive_platform.platform.dto.analytics.VisibilityStatsDTO;
import ak.dev.khi_archive_platform.platform.enums.AnalyticsAuditAction;
import ak.dev.khi_archive_platform.platform.service.analytics.AnalyticsAuditService;
import ak.dev.khi_archive_platform.platform.service.analytics.InventoryAnalyticsService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Live-inventory and visibility statistics — a state snapshot of the archive,
 * distinct from the audit-activity reports on {@link AnalyticsAPI}. These count
 * rows in the operational tables right now and are therefore unaffected by any
 * date window. Admin-only; each view is written to {@code analytics_audit_logs}.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/analytics")
@PreAuthorize("hasRole('ADMIN')")
public class InventoryAnalyticsAPI {

    private final InventoryAnalyticsService inventoryService;
    private final AnalyticsAuditService auditService;

    /**
     * How many items exist right now, per type, split active vs trashed.
     * Answers "number of items and item types".
     */
    @GetMapping("/inventory")
    public ResponseEntity<InventoryStatsDTO> inventory(Authentication auth, HttpServletRequest request) {
        InventoryStatsDTO body = inventoryService.getInventory();
        auditService.record(AnalyticsAuditAction.VIEW_INVENTORY, "inventory", auth, request,
                "Inventory snapshot (grandTotal=" + body.getGrandTotal() + ")");
        return ResponseEntity.ok(body);
    }

    /**
     * Visible-vs-hidden split for projects and media, plus how many items live
     * inside hidden projects. Answers "visible and invisible projects and items
     * in projects".
     */
    @GetMapping("/visibility")
    public ResponseEntity<VisibilityStatsDTO> visibility(Authentication auth, HttpServletRequest request) {
        VisibilityStatsDTO body = inventoryService.getVisibility();
        auditService.record(AnalyticsAuditAction.VIEW_VISIBILITY, "visibility", auth, request,
                "Visibility snapshot (projectsVisible=" + body.getProjectsVisible()
                        + " projectsHidden=" + body.getProjectsHidden() + ")");
        return ResponseEntity.ok(body);
    }
}
