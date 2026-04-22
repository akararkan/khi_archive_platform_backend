package ak.dev.khi_archive_platform.platform.dto.person;

import ak.dev.khi_archive_platform.platform.enums.Gender;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class PersonUpdateRequestDTO {

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

    private Boolean removeMediaPortrait;
}

