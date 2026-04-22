package ak.dev.khi_archive_platform.platform.api.person;

import ak.dev.khi_archive_platform.platform.dto.person.PersonCreateRequestDTO;
import ak.dev.khi_archive_platform.platform.dto.person.PersonResponseDTO;
import ak.dev.khi_archive_platform.platform.dto.person.PersonUpdateRequestDTO;
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

import java.util.List;

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
            @RequestPart(value = "image", required = false) MultipartFile mediaPortrait,
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
            @RequestPart(value = "image", required = false) MultipartFile mediaPortrait,
            Authentication auth,
            HttpServletRequest request
    ) {
        PersonUpdateRequestDTO dto = parseAndValidate(dataJson, PersonUpdateRequestDTO.class);
        return ResponseEntity.ok(personService.updatePerson(personCode, dto, mediaPortrait, auth, request));
    }

    private <T> T parseAndValidate(String dataJson, Class<T> clazz) {
        try {
            if (dataJson == null || dataJson.isBlank()) {
                throw new IllegalArgumentException("Missing 'data' part");
            }

            T dto = objectMapper.readValue(dataJson, clazz);

            var violations = validator.validate(dto);
            if (!violations.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (ConstraintViolation<T> violation : violations) {
                    sb.append(violation.getPropertyPath())
                            .append(": ")
                            .append(violation.getMessage())
                            .append("; ");
                }
                throw new IllegalArgumentException("Validation failed: " + sb);
            }

            return dto;
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse 'data' part: " + e.getMessage(), e);
        }
    }

    @DeleteMapping("/{personCode}")
    public ResponseEntity<Void> delete(
            @PathVariable String personCode,
            Authentication auth,
            HttpServletRequest request
    ) {
        personService.deletePerson(personCode, auth, request);
        return ResponseEntity.noContent().build();
    }
}
