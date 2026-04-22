package ak.dev.khi_archive_platform.platform.dto.person;

import ak.dev.khi_archive_platform.platform.enums.DatePrecision;
import ak.dev.khi_archive_platform.platform.enums.Gender;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PersonResponseDTO {
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
    private List<String> tag;
    private List<String> keywords;
    private String note;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant deletedAt;
    private String createdBy;
    private String updatedBy;
    private String deletedBy;
}


