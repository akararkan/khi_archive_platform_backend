package ak.dev.khi_archive_platform.platform.model.category;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "categories",
        indexes = {
                @Index(name = "idx_category_code", columnList = "category_code"),
                @Index(name = "idx_category_removed_at", columnList = "removed_at")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "category_code", unique = true, nullable = false, length = 120)
    private String categoryCode;

    @Column(name = "name", nullable = false, columnDefinition = "TEXT")
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /**
     * Keywords / alternative names for this category.
     * Used to prevent duplicate categories with similar meanings.
     */
    @Builder.Default
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "category_keywords", joinColumns = @JoinColumn(name = "category_id"))
    @Column(name = "keyword", columnDefinition = "TEXT")
    private List<String> keywords = new ArrayList<>();

    // ─── Audit ───────────────────────────────────────────────────────────────────

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "removed_at")
    private Instant removedAt;

    @Column(name = "created_by", length = 120)
    private String createdBy;

    @Column(name = "updated_by", length = 120)
    private String updatedBy;

    @Column(name = "removed_by", length = 120)
    private String removedBy;

    /**
     * Optimistic-locking version. JPA bumps it automatically on every save;
     * concurrent updates that read a stale version trip
     * {@code ObjectOptimisticLockingFailureException} → translated to HTTP 409.
     */
    @jakarta.persistence.Version
    @org.hibernate.annotations.ColumnDefault("0")
    @Column(name = "version", nullable = false)
    private Long version;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (version == null) version = 0L;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
