package ak.dev.khi_archive_platform.platform.api.person;

import ak.dev.khi_archive_platform.platform.dto.person.PersonCreateRequestDTO;
import ak.dev.khi_archive_platform.platform.dto.person.PersonResponseDTO;
import ak.dev.khi_archive_platform.platform.dto.person.PersonUpdateRequestDTO;
import ak.dev.khi_archive_platform.platform.exceptions.PersonValidationException;
import ak.dev.khi_archive_platform.platform.service.person.PersonService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/person")
public class PersonAPI {

    private final PersonService personService;
    private final ObjectMapper objectMapper;
    private final Validator validator;

    @GetMapping
    public ResponseEntity<List<PersonResponseDTO>> getAll(Authentication auth, HttpServletRequest request) {
        return ResponseEntity.ok(personService.getAll(auth, request));
    }

    @GetMapping("/{personCode}")
    public ResponseEntity<PersonResponseDTO> getByPersonCode(
            @PathVariable String personCode,
            Authentication auth,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(personService.getByPersonCode(personCode, auth, request));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PersonResponseDTO> create(
            @RequestPart("data") String dataJson,
            @RequestPart("mediaPortrait") MultipartFile mediaPortrait,
            Authentication auth,
            HttpServletRequest request
    ) {
        PersonCreateRequestDTO dto = parseAndValidate(dataJson, PersonCreateRequestDTO.class);
        return ResponseEntity.ok(personService.createPerson(dto, mediaPortrait, auth, request));
    }

    @PatchMapping(value = "/{personCode}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PersonResponseDTO> update(
            @PathVariable String personCode,
            @RequestPart("data") String dataJson,
            @RequestPart(value = "mediaPortrait", required = false) MultipartFile mediaPortrait,
            Authentication auth,
            HttpServletRequest request
    ) {
        PersonUpdateRequestDTO dto = parseAndValidate(dataJson, PersonUpdateRequestDTO.class);
        return ResponseEntity.ok(personService.updatePerson(personCode, dto, mediaPortrait, auth, request));
    }

    /**
     * Soft remove — marks the person as removed but keeps data in the database.
     */
    @PatchMapping("/{personCode}/remove")
    public ResponseEntity<Void> remove(
            @PathVariable String personCode,
            Authentication auth,
            HttpServletRequest request
    ) {
        personService.removePerson(personCode, auth, request);
        return ResponseEntity.noContent().build();
    }

    /**
     * Hard delete — permanently removes the row from the database.
     * Restricted to ADMIN and SUPER_ADMIN only.
     */
    @DeleteMapping("/{personCode}")
    public ResponseEntity<Void> delete(
            @PathVariable String personCode,
            Authentication auth,
            HttpServletRequest request
    ) {
        personService.deletePerson(personCode, auth, request);
        return ResponseEntity.noContent().build();
    }

    private <T> T parseAndValidate(String dataJson, Class<T> clazz) {
        if (dataJson == null || dataJson.isBlank()) {
            throw new PersonValidationException("Missing 'data' part in the request.");
        }

        T dto;
        try {
            dto = objectMapper.readValue(dataJson, clazz);
        } catch (Exception e) {
            throw new PersonValidationException("Failed to parse person data: " + e.getMessage());
        }

        var violations = validator.validate(dto);
        if (!violations.isEmpty()) {
            Map<String, String> fieldErrors = new LinkedHashMap<>();
            for (ConstraintViolation<T> violation : violations) {
                fieldErrors.put(violation.getPropertyPath().toString(), violation.getMessage());
            }
            throw new PersonValidationException("Validation failed for person data.", fieldErrors);
        }

        return dto;
    }
}
