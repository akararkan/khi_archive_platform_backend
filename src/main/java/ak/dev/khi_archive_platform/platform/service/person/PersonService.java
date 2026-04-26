package ak.dev.khi_archive_platform.platform.service.person;

import ak.dev.khi_archive_platform.S3Service;
import ak.dev.khi_archive_platform.platform.dto.person.PersonCreateRequestDTO;
import ak.dev.khi_archive_platform.platform.dto.person.PersonResponseDTO;
import ak.dev.khi_archive_platform.platform.dto.person.PersonUpdateRequestDTO;
import ak.dev.khi_archive_platform.platform.enums.DatePrecision;
import ak.dev.khi_archive_platform.platform.enums.PersonAuditAction;
import ak.dev.khi_archive_platform.platform.exceptions.PersonAlreadyExistsException;
import ak.dev.khi_archive_platform.platform.exceptions.PersonNotFoundException;
import ak.dev.khi_archive_platform.platform.model.person.Person;
import ak.dev.khi_archive_platform.platform.repo.person.PersonRepository;
import ak.dev.khi_archive_platform.platform.repo.project.ProjectRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class PersonService {

    private final PersonRepository personRepository;
    private final ProjectRepository projectRepository;
    private final PersonAuditService personAuditService;
    private final S3Service s3Service;

    public PersonResponseDTO createPerson(PersonCreateRequestDTO dto,
                                          MultipartFile mediaPortrait,
                                          Authentication authentication,
                                          HttpServletRequest request) {
        String personCode = normalizePersonCode(dto.getPersonCode());

        if (personRepository.existsByPersonCodeAndRemovedAtIsNull(personCode)) {
            throw new PersonAlreadyExistsException("Person code already exists: " + personCode);
        }

        Person person = new Person();
        person.setPersonCode(personCode);
        person.setFullName(dto.getFullName());
        applyFields(person, dto.getNickname(), dto.getRomanizedName(), dto.getGender(), dto.getPersonType(), dto.getRegion(),
                dto.getDateOfBirthYear(), dto.getDateOfBirthMonth(), dto.getDateOfBirthDay(), dto.getPlaceOfBirth(),
                dto.getDateOfDeathYear(), dto.getDateOfDeathMonth(), dto.getDateOfDeathDay(), dto.getPlaceOfDeath(),
                dto.getDescription(), dto.getTag(), dto.getKeywords(), dto.getNote());

        person.setMediaPortrait(uploadPortrait(mediaPortrait, personCode, null));
        touchCreateAudit(person, authentication);

        Person saved = personRepository.save(person);
        personAuditService.record(saved, PersonAuditAction.CREATE, authentication, request,
                "Created person record with code=" + saved.getPersonCode());
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<PersonResponseDTO> getAll(Authentication authentication, HttpServletRequest request) {
        List<PersonResponseDTO> result = personRepository.findAllByRemovedAtIsNull().stream()
                .map(this::toResponse)
                .toList();
        personAuditService.record(null, PersonAuditAction.LIST, authentication, request,
                "Listed active person records");
        return result;
    }

    @Transactional(readOnly = true)
    public PersonResponseDTO getByPersonCode(String personCode,
                                             Authentication authentication,
                                             HttpServletRequest request) {
        String normalizedPersonCode = normalizePersonCode(personCode);
        Person person = personRepository.findByPersonCodeAndRemovedAtIsNull(normalizedPersonCode)
                .orElseThrow(() -> new PersonNotFoundException("Person not found: " + personCode));
        personAuditService.record(person, PersonAuditAction.READ, authentication, request,
                "Read person record");
        return toResponse(person);
    }

    public PersonResponseDTO updatePerson(String personCode,
                                          PersonUpdateRequestDTO dto,
                                          MultipartFile mediaPortrait,
                                          Authentication authentication,
                                          HttpServletRequest request) {
        String normalizedPersonCode = normalizePersonCode(personCode);
        Person person = personRepository.findByPersonCodeAndRemovedAtIsNull(normalizedPersonCode)
                .orElseThrow(() -> new PersonNotFoundException("Person not found: " + personCode));

        PersonSnapshot before = PersonSnapshot.from(person);

        if (dto.getFullName() != null) person.setFullName(dto.getFullName());
        if (dto.getNickname() != null) person.setNickname(dto.getNickname());
        if (dto.getRomanizedName() != null) person.setRomanizedName(dto.getRomanizedName());
        if (dto.getGender() != null) person.setGender(dto.getGender());
        if (dto.getPersonType() != null) person.setPersonType(dto.getPersonType());
        if (dto.getRegion() != null) person.setRegion(dto.getRegion());
        updateDate(person, dto.getDateOfBirthYear(), dto.getDateOfBirthMonth(), dto.getDateOfBirthDay(), true);
        updateDate(person, dto.getDateOfDeathYear(), dto.getDateOfDeathMonth(), dto.getDateOfDeathDay(), false);
        if (dto.getPlaceOfBirth() != null) person.setPlaceOfBirth(dto.getPlaceOfBirth());
        if (dto.getPlaceOfDeath() != null) person.setPlaceOfDeath(dto.getPlaceOfDeath());
        if (dto.getDescription() != null) person.setDescription(dto.getDescription());
        if (dto.getTag() != null) person.setTag(joinList(dto.getTag()));
        if (dto.getKeywords() != null) person.setKeywords(joinList(dto.getKeywords()));
        if (dto.getNote() != null) person.setNote(dto.getNote());

        String oldPortrait = person.getMediaPortrait();
        if (Boolean.TRUE.equals(dto.getRemoveMediaPortrait())) {
            deletePortrait(oldPortrait);
            person.setMediaPortrait(null);
        } else if (mediaPortrait != null && !mediaPortrait.isEmpty()) {
            person.setMediaPortrait(uploadPortrait(mediaPortrait, person.getPersonCode(), oldPortrait));
        }

        touchUpdateAudit(person, authentication);
        Person saved = personRepository.save(person);
        personAuditService.record(saved, PersonAuditAction.UPDATE, authentication, request,
                buildUpdateAuditDetails(before, saved, dto, mediaPortrait));
        return toResponse(saved);
    }

    /**
     * Soft remove — marks the person as removed but keeps data in the database.
     */
    public void removePerson(String personCode,
                             Authentication authentication,
                             HttpServletRequest request) {
        String normalizedPersonCode = normalizePersonCode(personCode);
        Person person = personRepository.findByPersonCodeAndRemovedAtIsNull(normalizedPersonCode)
                .orElseThrow(() -> new PersonNotFoundException("Person not found: " + personCode));

        if (projectRepository.existsByPersonAndRemovedAtIsNull(person)) {
            throw new IllegalStateException("Person has active projects and cannot be removed. Remove the projects first.");
        }

        person.setRemovedAt(Instant.now());
        person.setRemovedBy(resolveActorUsername(authentication));
        Person saved = personRepository.save(person);
        personAuditService.record(saved, PersonAuditAction.REMOVE, authentication, request,
                "Removed person record (soft delete)");
    }

    /**
     * Hard delete — permanently removes the row from the database.
     * Restricted to ADMIN and SUPER_ADMIN roles only.
     */
    public void deletePerson(String personCode,
                             Authentication authentication,
                             HttpServletRequest request) {
        requireAdminRole(authentication);
        String normalizedPersonCode = normalizePersonCode(personCode);
        Person person = personRepository.findByPersonCodeAndRemovedAtIsNull(normalizedPersonCode)
                .or(() -> personRepository.findAll().stream()
                        .filter(p -> p.getPersonCode().equals(normalizedPersonCode))
                        .findFirst())
                .orElseThrow(() -> new PersonNotFoundException("Person not found: " + personCode));

        if (projectRepository.existsByPersonAndRemovedAtIsNull(person)) {
            throw new IllegalStateException("Person has active projects and cannot be permanently deleted. Remove or delete the projects first.");
        }

        deletePortrait(person.getMediaPortrait());
        personAuditService.record(person, PersonAuditAction.DELETE, authentication, request,
                "Permanently deleted person record");
        personRepository.delete(person);
    }

    // ─── Field Helpers ───────────────────────────────────────────────────────────

    private void applyFields(Person person,
                             String nickname,
                             String romanizedName,
                             ak.dev.khi_archive_platform.platform.enums.Gender gender,
                             List<String> personType,
                             String region,
                             Integer birthYear,
                             Integer birthMonth,
                             Integer birthDay,
                             String placeOfBirth,
                             Integer deathYear,
                             Integer deathMonth,
                             Integer deathDay,
                             String placeOfDeath,
                             String description,
                             List<String> tag,
                             List<String> keywords,
                             String note) {
        if (nickname != null) person.setNickname(nickname);
        if (romanizedName != null) person.setRomanizedName(romanizedName);
        if (gender != null) person.setGender(gender);
        if (personType != null) person.setPersonType(personType);
        if (region != null) person.setRegion(region);
        setDate(person, birthYear, birthMonth, birthDay, true);
        if (placeOfBirth != null) person.setPlaceOfBirth(placeOfBirth);
        setDate(person, deathYear, deathMonth, deathDay, false);
        if (placeOfDeath != null) person.setPlaceOfDeath(placeOfDeath);
        if (description != null) person.setDescription(description);
        if (tag != null) person.setTag(joinList(tag));
        if (keywords != null) person.setKeywords(joinList(keywords));
        if (note != null) person.setNote(note);
    }

    private void setDate(Person person, Integer year, Integer month, Integer day, boolean birth) {
        if (year == null && month == null && day == null) {
            return;
        }
        if (year == null) {
            throw new IllegalArgumentException("Year is required when date parts are provided");
        }

        DateValue value = normalizeDate(year, month, day);
        if (birth) {
            person.setDateOfBirth(value.date());
            person.setDateOfBirthPrecision(value.precision());
        } else {
            person.setDateOfDeath(value.date());
            person.setDateOfDeathPrecision(value.precision());
        }
    }

    private void updateDate(Person person, Integer year, Integer month, Integer day, boolean birth) {
        if (year == null && month == null && day == null) {
            return;
        }
        setDate(person, year, month, day, birth);
    }

    private DateValue normalizeDate(Integer year, Integer month, Integer day) {
        if (year == null) {
            throw new IllegalArgumentException("Year is required");
        }
        if (month == null && day == null) {
            return new DateValue(LocalDate.of(year, 1, 1), DatePrecision.YEAR_ONLY);
        }
        if (month != null && day == null) {
            return new DateValue(YearMonth.of(year, month).atDay(1), DatePrecision.MONTH_ONLY);
        }
        if (month == null) {
            throw new IllegalArgumentException("Month is required when day is provided");
        }
        return new DateValue(LocalDate.of(year, month, day), DatePrecision.FULL);
    }

    private String uploadPortrait(MultipartFile file, String personCode, String oldPortrait) {
        if (file == null || file.isEmpty()) {
            return oldPortrait;
        }
        try {
            String originalFilename = StringUtils.cleanPath(file.getOriginalFilename() == null ? "portrait" : file.getOriginalFilename());
            String newPortrait = s3Service.uploadPersonPortrait(file.getBytes(), originalFilename, file.getContentType(), personCode);
            if (oldPortrait != null && !oldPortrait.isBlank() && !oldPortrait.equals(newPortrait)) {
                deletePortrait(oldPortrait);
            }
            return newPortrait;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to upload person portrait", e);
        }
    }

    private void deletePortrait(String url) {
        if (url != null && !url.isBlank() && s3Service.isOurS3Url(url)) {
            s3Service.deleteFile(url);
        }
    }

    private String joinList(List<String> items) {
        if (items == null) return null;
        List<String> cleaned = items.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(String::trim)
                .collect(Collectors.toList());
        if (cleaned.isEmpty()) return null;
        return String.join(",", cleaned);
    }

    private List<String> splitToList(String s) {
        if (s == null || s.isBlank()) return Collections.emptyList();
        return Arrays.stream(s.split(","))
                .map(String::trim)
                .filter(str -> !str.isEmpty())
                .collect(Collectors.toList());
    }

    private void touchCreateAudit(Person person, Authentication authentication) {
        Instant now = Instant.now();
        String actor = resolveActorUsername(authentication);
        person.setCreatedAt(now);
        person.setUpdatedAt(now);
        person.setCreatedBy(actor);
        person.setUpdatedBy(actor);
    }

    private void touchUpdateAudit(Person person, Authentication authentication) {
        person.setUpdatedAt(Instant.now());
        person.setUpdatedBy(resolveActorUsername(authentication));
    }

    private String resolveActorUsername(Authentication authentication) {
        return authentication == null ? "anonymous" : authentication.getName();
    }

    private String normalizePersonCode(String personCode) {
        if (personCode == null) {
            throw new IllegalArgumentException("Person code is required");
        }
        String trimmed = personCode.trim();
        if (trimmed.isBlank()) {
            throw new IllegalArgumentException("Person code is required");
        }
        return trimmed;
    }

    private void requireAdminRole(Authentication authentication) {
        if (authentication == null) {
            throw new AccessDeniedException("Authentication is required for this operation");
        }
        boolean isAdmin = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> "ROLE_ADMIN".equals(a) || "ROLE_SUPER_ADMIN".equals(a));
        if (!isAdmin) {
            throw new AccessDeniedException("Only ADMIN or SUPER_ADMIN can permanently delete records");
        }
    }

    private PersonResponseDTO toResponse(Person p) {
        return PersonResponseDTO.builder()
                .id(p.getId())
                .personCode(p.getPersonCode())
                .mediaPortrait(p.getMediaPortrait())
                .fullName(p.getFullName())
                .nickname(p.getNickname())
                .romanizedName(p.getRomanizedName())
                .gender(p.getGender())
                .personType(p.getPersonType())
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

    // ─── Audit Details ───────────────────────────────────────────────────────────

    private String buildUpdateAuditDetails(PersonSnapshot before,
                                           Person after,
                                           PersonUpdateRequestDTO dto,
                                           MultipartFile mediaPortrait) {
        List<String> changes = new java.util.ArrayList<>();

        addChange(changes, "fullName", before.fullName, after.getFullName());
        addChange(changes, "nickname", before.nickname, after.getNickname());
        addChange(changes, "romanizedName", before.romanizedName, after.getRomanizedName());
        addChange(changes, "gender", before.gender, after.getGender());
        addChange(changes, "personType", before.personType, after.getPersonType());
        addChange(changes, "region", before.region, after.getRegion());
        addChange(changes, "dateOfBirth", before.dateOfBirth, after.getDateOfBirth());
        addChange(changes, "dateOfBirthPrecision", before.dateOfBirthPrecision, after.getDateOfBirthPrecision());
        addChange(changes, "placeOfBirth", before.placeOfBirth, after.getPlaceOfBirth());
        addChange(changes, "dateOfDeath", before.dateOfDeath, after.getDateOfDeath());
        addChange(changes, "dateOfDeathPrecision", before.dateOfDeathPrecision, after.getDateOfDeathPrecision());
        addChange(changes, "placeOfDeath", before.placeOfDeath, after.getPlaceOfDeath());
        addChange(changes, "description", before.description, after.getDescription());
        addChange(changes, "tag", before.tag, after.getTag());
        addChange(changes, "keywords", before.keywords, after.getKeywords());
        addChange(changes, "note", before.note, after.getNote());

        if (Boolean.TRUE.equals(dto.getRemoveMediaPortrait())) {
            if (before.mediaPortrait != null && !before.mediaPortrait.isBlank()) {
                changes.add("mediaPortrait removed: " + before.mediaPortrait);
            }
        } else if (mediaPortrait != null && !mediaPortrait.isEmpty()) {
            if (!equalsSafe(before.mediaPortrait, after.getMediaPortrait())) {
                changes.add("mediaPortrait replaced: " + before.mediaPortrait + " -> " + after.getMediaPortrait());
            }
        }

        if (changes.isEmpty()) {
            return "Updated person record (no field changes detected)";
        }
        return "Updated person record: " + String.join(" | ", changes);
    }

    private void addChange(List<String> changes, String field, Object before, Object after) {
        if (!equalsSafe(before, after)) {
            changes.add(field + ": " + valueToString(before) + " -> " + valueToString(after));
        }
    }

    private boolean equalsSafe(Object a, Object b) {
        return java.util.Objects.equals(a, b);
    }

    private String valueToString(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof List<?> list) {
            return list.toString();
        }
        return String.valueOf(value);
    }

    private record DateValue(LocalDate date, DatePrecision precision) {}

    private record PersonSnapshot(
            String fullName,
            String nickname,
            String romanizedName,
            ak.dev.khi_archive_platform.platform.enums.Gender gender,
            List<String> personType,
            String region,
            LocalDate dateOfBirth,
            DatePrecision dateOfBirthPrecision,
            String placeOfBirth,
            LocalDate dateOfDeath,
            DatePrecision dateOfDeathPrecision,
            String placeOfDeath,
            String description,
            String tag,
            String keywords,
            String note,
            String mediaPortrait
    ) {
        static PersonSnapshot from(Person person) {
            return new PersonSnapshot(
                    person.getFullName(),
                    person.getNickname(),
                    person.getRomanizedName(),
                    person.getGender(),
                    person.getPersonType() == null ? null : List.copyOf(person.getPersonType()),
                    person.getRegion(),
                    person.getDateOfBirth(),
                    person.getDateOfBirthPrecision(),
                    person.getPlaceOfBirth(),
                    person.getDateOfDeath(),
                    person.getDateOfDeathPrecision(),
                    person.getPlaceOfDeath(),
                    person.getDescription(),
                    person.getTag(),
                    person.getKeywords(),
                    person.getNote(),
                    person.getMediaPortrait()
            );
        }
    }
}
