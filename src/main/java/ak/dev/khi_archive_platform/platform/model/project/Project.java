package ak.dev.khi_archive_platform.platform.model.project;

import ak.dev.khi_archive_platform.platform.model.category.Category;
import ak.dev.khi_archive_platform.platform.model.person.Person;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "projects",
        indexes = {
                @Index(name = "idx_project_code", columnList = "project_code"),
                @Index(name = "idx_project_person_id", columnList = "person_id"),
                @Index(name = "idx_project_removed_at", columnList = "removed_at")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Unique project code, auto-generated: PERSONCODE_CATEGORYCODE or UNTITLED_CATEGORYCODE */
    @Column(name = "project_code", unique = true, nullable = false, length = 200)
    private String projectCode;

    @Column(name = "project_name", nullable = false, columnDefinition = "TEXT")
    private String projectName;

    /**
     * Optional person link. If null, this is an "Untitled Project" (not tied to any person).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "person_id")
    private Person person;

    /** A project can belong to multiple categories. At least one is required on creation. */
    @Builder.Default
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "project_categories",
            joinColumns = @JoinColumn(name = "project_id"),
            inverseJoinColumns = @JoinColumn(name = "category_id")
    )
    private List<Category> categories = new ArrayList<>();

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Builder.Default
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "project_tags", joinColumns = @JoinColumn(name = "project_id"))
    @Column(name = "tag", columnDefinition = "TEXT")
    private List<String> tags = new ArrayList<>();

    @Builder.Default
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "project_keywords", joinColumns = @JoinColumn(name = "project_id"))
    @Column(name = "keyword", columnDefinition = "TEXT")
    private List<String> keywords = new ArrayList<>();

    // ─── Audit ───────────────────��───────────────────────────────────────────────

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

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
