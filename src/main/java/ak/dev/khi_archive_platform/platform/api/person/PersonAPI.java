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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
    @PreAuthorize("hasAuthority('person:read')")
    public ResponseEntity<Page<PersonResponseDTO>> getAll(
            @PageableDefault(size = 100) Pageable pageable,
            Authentication auth,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(personService.getAll(pageable, auth, request));
    }

    /**
     * Fuzzy, typo-tolerant search across full_name, nickname, romanized_name,
     * description, tag, keywords, region, places, code, and person_type.
     * Backed by PostgreSQL pg_trgm GIN indexes — multilingual and fast.
     */
    @GetMapping("/search")
    @PreAuthorize("hasAuthority('person:read')")
    public ResponseEntity<List<PersonResponseDTO>> search(
            @RequestParam("q") String q,
            @RequestParam(value = "limit", required = false) Integer limit,
            Authentication auth,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(personService.search(q, limit, auth, request));
    }

    @GetMapping("/{personCode}")
    @PreAuthorize("hasAuthority('person:read')")
    public ResponseEntity<PersonResponseDTO> getByPersonCode(
            @PathVariable String personCode,
            Authentication auth,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(personService.getByPersonCode(personCode, auth, request));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('person:create')")
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
    @PreAuthorize("hasAuthority('person:update')")
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
     * Soft delete — sends the person record to trash. Admin-only.
     * Cascades to every active project linked to the person (each linked
     * project also cascades to its audio/video/image/text). The response body
     * lists the project codes that were trashed alongside, so the UI can tell
     * the user exactly what moved to the trash.
     */
    @DeleteMapping("/{personCode}")
    @PreAuthorize("hasAuthority('person:delete')")
    public ResponseEntity<PersonService.DeleteResult> delete(
            @PathVariable String personCode,
            Authentication auth,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(personService.deletePerson(personCode, auth, request));
    }

    /**
     * Restore a person record from trash. Admin-only. Cascades to every
     * trashed project linked to the person (each restored project itself
     * cascades-restore to its audio/video/image/text). The response body
     * lists the restored project codes.
     */
    @PostMapping("/{personCode}/restore")
    @PreAuthorize("hasAuthority('person:delete')")
    public ResponseEntity<PersonService.RestoreResult> restore(
            @PathVariable String personCode,
            Authentication auth,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(personService.restorePerson(personCode, auth, request));
    }

    /**
     * List trashed person records. Admin-only.
     */
    @GetMapping("/trash")
    @PreAuthorize("hasAuthority('person:delete')")
    public ResponseEntity<Page<PersonResponseDTO>> getTrash(
            @PageableDefault(size = 100) Pageable pageable,
            Authentication auth,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(personService.getTrash(pageable, auth, request));
    }

    /**
     * Permanently delete a person from trash, including their portrait file.
     * Admin-only. The person must already be in trash, and no project (active
     * or trashed) may still reference them.
     */
    @DeleteMapping("/{personCode}/purge")
    @PreAuthorize("hasAuthority('person:delete')")
    public ResponseEntity<Void> purge(
            @PathVariable String personCode,
            Authentication auth,
            HttpServletRequest request
    ) {
        personService.purgePerson(personCode, auth, request);
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
