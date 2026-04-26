package ak.dev.khi_archive_platform.platform.dto.person;

import ak.dev.khi_archive_platform.user.consts.ValidationPatterns;
import ak.dev.khi_archive_platform.platform.enums.Gender;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PersonCreateRequestDTO {

    @NotBlank(message = "Person code is required")
    @Size(max = 50, message = "Person code must not exceed 50 characters")
    @Pattern(regexp = ValidationPatterns.PERSON_CODE, message = "Person code must contain only letters, numbers, underscores, or hyphens")
    private String personCode;

    @NotBlank(message = "Full name is required")
    private String fullName;

    private String nickname;
    private String romanizedName;
    private Gender gender;
    private List<String> personType;
    private String region;

    private Integer dateOfBirthYear;
    private Integer dateOfBirthMonth;
    private Integer dateOfBirthDay;

    private String placeOfBirth;

    private Integer dateOfDeathYear;
    private Integer dateOfDeathMonth;
    private Integer dateOfDeathDay;

    private String placeOfDeath;
    private String description;
    private List<String> tag;
    private List<String> keywords;
    private String note;
}

