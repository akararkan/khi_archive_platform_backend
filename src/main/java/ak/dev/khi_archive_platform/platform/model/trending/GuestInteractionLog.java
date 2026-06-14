package ak.dev.khi_archive_platform.platform.model.trending;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Records every time a guest user views a specific public entity.
 * Used to compute trending scores via time-decay weighting.
 *
 * Index on (entity_type, entity_code, interacted_at) powers the
 * trending GROUP BY query. Index on interacted_at alone powers cleanup.
 */
@Entity
@Table(
    name = "guest_interaction_logs",
    indexes = {
        @Index(name = "idx_guest_interaction_entity",
               columnList = "entity_type, entity_code, interacted_at"),
        @Index(name = "idx_guest_interaction_time",
               columnList = "interacted_at")
    }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GuestInteractionLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // audio | video | text | image | project | person | category
    @Column(name = "entity_type", nullable = false, length = 20)
    private String entityType;

    @Column(name = "entity_code", nullable = false, length = 100)
    private String entityCode;

    @Column(name = "interacted_at", nullable = false)
    private Instant interactedAt;
}
