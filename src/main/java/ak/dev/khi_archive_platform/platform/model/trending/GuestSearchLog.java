package ak.dev.khi_archive_platform.platform.model.trending;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Records every search query made by a guest user.
 * Used to compute the "Top Searches" list on the trending endpoint.
 */
@Entity
@Table(
    name = "guest_search_logs",
    indexes = {
        @Index(name = "idx_guest_search_time",  columnList = "searched_at"),
        @Index(name = "idx_guest_search_query", columnList = "query")
    }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GuestSearchLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "query", nullable = false, length = 500)
    private String query;

    @Column(name = "searched_at", nullable = false)
    private Instant searchedAt;
}
