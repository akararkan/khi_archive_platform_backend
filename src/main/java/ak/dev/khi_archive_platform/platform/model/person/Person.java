package ak.dev.khi_archive_platform.platform.model.person;

import ak.dev.khi_archive_platform.platform.enums.DatePrecision;
import ak.dev.khi_archive_platform.platform.enums.Gender;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Entity
@Table(name = "person",
        indexes = {
                @Index(name = "idx_person_code", columnList = "person_code"),
                @Index(name = "idx_person_region", columnList = "region"),
                @Index(name = "idx_person_removed_at", columnList = "removed_at")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Person {
    // ─── Primary key ────────────────────────────────────────────────────────────
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ─── Identification ──────────────────────────────────────────────────────────

    /** Business key (e.g. HZI, AMA). Used as FK in other tables. */
    @Column(name = "person_code", unique = true, nullable = false, length = 50)
    private String personCode;

    /** S3 public URL of the profile portrait image */
    @Column(name = "media_portrait", length = 255)
    private String mediaPortrait;

    /** Full legal / historical name */
    @Column(name = "full_name", nullable = false, columnDefinition = "TEXT")
    private String fullName;

    /** Well-known nickname / pen name */
    @Column(name = "nickname", columnDefinition = "TEXT")
    private String nickname;

    /** Name romanized in Latin script */
    @Column(name = "romanized_name", length = 255)
    private String romanizedName;

    @Column(name = "gender", length = 50)
    private Gender gender;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_person_type", joinColumns = @JoinColumn(name = "person_id"))
    @Column(name = "person_type", length = 255)
    private List<String> personType;

    @Column(name = "region", length = 255)
    private String region;

    // ─── Dates ────────────────────────────────────────────────────────────────────

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Enumerated(EnumType.STRING)
    @Column(name = "date_of_birth_precision", length = 20)
    private DatePrecision dateOfBirthPrecision;

    @Column(name = "place_of_birth", length = 255)
    private String placeOfBirth;

    @Column(name = "date_of_death")
    private LocalDate dateOfDeath;

    @Enumerated(EnumType.STRING)
    @Column(name = "date_of_death_precision", length = 20)
    private DatePrecision dateOfDeathPrecision;

    @Column(name = "place_of_death", length = 255)
    private String placeOfDeath;

    // ─── Description & discovery ──────────────────────────────────────────────────
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "tag", columnDefinition = "TEXT")
    private String tag;

    @Column(name = "keywords", columnDefinition = "TEXT")
    private String keywords;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

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
