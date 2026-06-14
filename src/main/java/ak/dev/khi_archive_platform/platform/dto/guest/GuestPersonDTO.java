package ak.dev.khi_archive_platform.platform.dto.guest;

import ak.dev.khi_archive_platform.platform.enums.DatePrecision;
import ak.dev.khi_archive_platform.platform.enums.Gender;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GuestPersonDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private String personCode;
    private String mediaPortrait;
    private String fullName;
    private String nickname;
    private String romanizedName;
    private Gender gender;
    private List<String> personType;
    private String region;
    private LocalDate dateOfBirth;
    private DatePrecision dateOfBirthPrecision;
    private String placeOfBirth;
    private LocalDate dateOfDeath;
    private DatePrecision dateOfDeathPrecision;
    private String placeOfDeath;
    private String description;
    private long projectCount;

    // ── Trending metadata ──────────────────────────────────────────────────
    private boolean isTrending;
    private Integer trendingRank;
    private Double  trendingScore;
}
