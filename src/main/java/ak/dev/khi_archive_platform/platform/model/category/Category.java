package ak.dev.khi_archive_platform.platform.model.category;

import ak.dev.khi_archive_platform.platform.model.object.ObjectAttribute;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "categories",
        indexes = {
                @Index(name = "idx_category_code", columnList = "category_code"),
                @Index(name = "idx_category_deleted_at", columnList = "deleted_at")
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

    @JsonIgnore
    @OneToOne(mappedBy = "category", fetch = FetchType.LAZY)
    private ObjectAttribute object;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "created_by", length = 120)
    private String createdBy;

    @Column(name = "updated_by", length = 120)
    private String updatedBy;

    @Column(name = "deleted_by", length = 120)
    private String deletedBy;

    public void attachObject(ObjectAttribute object) {
        this.object = object;
        if (object != null && object.getCategory() != this) {
            object.setCategory(this);
        }
    }

    public void detachObject(ObjectAttribute object) {
        if (this.object == object) {
            this.object = null;
        }
        if (object != null && object.getCategory() == this) {
            object.setCategory(null);
        }
    }

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
