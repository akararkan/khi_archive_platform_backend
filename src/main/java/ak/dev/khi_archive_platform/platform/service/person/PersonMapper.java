package ak.dev.khi_archive_platform.platform.service.person;

import ak.dev.khi_archive_platform.platform.dto.person.PersonResponseDTO;
import ak.dev.khi_archive_platform.platform.model.person.Person;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

final class PersonMapper {

    private PersonMapper() {}

    static PersonResponseDTO toResponse(Person p) {
        return PersonResponseDTO.builder()
                .id(p.getId())
                .personCode(p.getPersonCode())
                .mediaPortrait(p.getMediaPortrait())
                .fullName(p.getFullName())
                .nickname(p.getNickname())
                .romanizedName(p.getRomanizedName())
                .gender(p.getGender())
                // Wrap Hibernate-managed collections in plain ArrayList so the cached
                // DTO never holds a PersistentBag with a session reference (not serializable).
                .personType(p.getPersonType() != null ? new ArrayList<>(p.getPersonType()) : new ArrayList<>())
                .region(p.getRegion())
                .dateOfBirth(p.getDateOfBirth())
                .dateOfBirthPrecision(p.getDateOfBirthPrecision())
                .placeOfBirth(p.getPlaceOfBirth())
                .dateOfDeath(p.getDateOfDeath())
                .dateOfDeathPrecision(p.getDateOfDeathPrecision())
                .placeOfDeath(p.getPlaceOfDeath())
                .description(p.getDescription())
                .tag(splitToList(p.getTag()))
                .keywords(splitToList(p.getKeywords()))
                .note(p.getNote())
                .createdAt(p.getCreatedAt())
                .updatedAt(p.getUpdatedAt())
                .removedAt(p.getRemovedAt())
                .createdBy(p.getCreatedBy())
                .updatedBy(p.getUpdatedBy())
                .removedBy(p.getRemovedBy())
                .build();
    }

    static List<String> splitToList(String s) {
        if (s == null || s.isBlank()) return new ArrayList<>();
        return Arrays.stream(s.split(","))
                .map(String::trim)
                .filter(str -> !str.isEmpty())
                .collect(Collectors.toCollection(ArrayList::new));
    }
}
