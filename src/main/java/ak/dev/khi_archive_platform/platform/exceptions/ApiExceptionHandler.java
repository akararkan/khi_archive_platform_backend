package ak.dev.khi_archive_platform.platform.exceptions;

import ak.dev.khi_archive_platform.common.exceptions.ApiErrorResponse;
import ak.dev.khi_archive_platform.common.exceptions.ApiErrorResponses;
import ak.dev.khi_archive_platform.common.exceptions.ErrorCategory;
import ak.dev.khi_archive_platform.common.exceptions.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Centralised exception → {@link ApiErrorResponse} translation for everything
 * under the {@code platform} package.
 *
 * <p>Every handler converts a thrown exception into the same envelope shape so
 * the frontend can:
 * <ul>
 *   <li>switch on {@code error} for entity-specific UX (e.g. AUDIO_NOT_FOUND
 *       vs PROJECT_NOT_FOUND),</li>
 *   <li>switch on {@code category} for the broad family (validation vs
 *       authorisation vs storage),</li>
 *   <li>show {@code message} directly to the user, and</li>
 *   <li>show {@code hint} as a recovery suggestion.</li>
 * </ul>
 *
 * <p>Each handler is intentionally small and reads top-to-bottom — adding a
 * new domain exception means dropping a new {@code @ExceptionHandler} block in
 * the appropriate HTTP-status section.</p>
 */
@RestControllerAdvice(basePackages = "ak.dev.khi_archive_platform.platform")
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    // ─── 400 Bad Request — granular handlers ────────────────────────────────

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @SuppressWarnings("unused")
    public ResponseEntity<ApiErrorResponse> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                          HttpServletRequest request) {
        Map<String, Object> details = fieldErrors(ex.getBindingResult().getFieldErrors());
        return build(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, ErrorCategory.VALIDATION,
                "One or more fields failed validation. See 'details' for the per-field reason.",
                "Fix the highlighted fields and resubmit the request.",
                request, details);
    }

    @ExceptionHandler(BindException.class)
    @SuppressWarnings("unused")
    public ResponseEntity<ApiErrorResponse> handleBind(BindException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, ErrorCategory.VALIDATION,
                "Request binding failed. See 'details' for the per-field reason.",
                "Check the field names and types and resubmit.",
                request, fieldErrors(ex.getBindingResult().getFieldErrors()));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    @SuppressWarnings("unused")
    public ResponseEntity<ApiErrorResponse> handleConstraintViolation(ConstraintViolationException ex,
                                                                       HttpServletRequest request) {
        Map<String, Object> details = new LinkedHashMap<>();
        ex.getConstraintViolations().forEach(v ->
                details.put(v.getPropertyPath().toString(), v.getMessage()));
        return build(HttpStatus.BAD_REQUEST, ErrorCode.CONSTRAINT_VIOLATION, ErrorCategory.VALIDATION,
                "Request violated one or more constraints. See 'details'.",
                "Adjust the indicated parameters and retry.",
                request, details.isEmpty() ? null : details);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @SuppressWarnings("unused")
    public ResponseEntity<ApiErrorResponse> handleUnreadable(HttpMessageNotReadableException ex,
                                                              HttpServletRequest request) {
        Map<String, Object> details = new LinkedHashMap<>();
        Throwable cause = ex.getCause();
        if (cause instanceof JsonMappingException mappingException && !mappingException.getPath().isEmpty()) {
            String path = mappingException.getPath().stream()
                    .map(JsonMappingException.Reference::getFieldName)
                    .filter(field -> field != null && !field.isBlank())
                    .reduce((a, b) -> a + "." + b)
                    .orElse(null);
            if (path != null) details.put("field", path);
        }
        if (cause instanceof JsonProcessingException jsonProcessingException) {
            details.put("location", String.valueOf(jsonProcessingException.getLocation()));
        }
        return build(HttpStatus.BAD_REQUEST, ErrorCode.JSON_PARSE_ERROR, ErrorCategory.BAD_REQUEST,
                "Request body could not be parsed as JSON.",
                "Make sure the body is valid JSON and field types match the schema.",
                request, details.isEmpty() ? null : details);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    @SuppressWarnings("unused")
    public ResponseEntity<ApiErrorResponse> handleMissingParam(MissingServletRequestParameterException ex,
                                                                HttpServletRequest request) {
        Map<String, Object> details = Map.of(
                "parameter", ex.getParameterName(),
                "expectedType", ex.getParameterType()
        );
        return build(HttpStatus.BAD_REQUEST, ErrorCode.MISSING_PARAMETER, ErrorCategory.BAD_REQUEST,
                "Required parameter '" + ex.getParameterName() + "' is missing.",
                "Include '" + ex.getParameterName() + "' (" + ex.getParameterType() + ") in the request.",
                request, details);
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    @SuppressWarnings("unused")
    public ResponseEntity<ApiErrorResponse> handleMissingPart(MissingServletRequestPartException ex,
                                                               HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ErrorCode.MISSING_REQUEST_PART, ErrorCategory.BAD_REQUEST,
                "Multipart request is missing the '" + ex.getRequestPartName() + "' part.",
                "Send 'data' as application/json and any file part(s) as multipart/form-data.",
                request, Map.of("part", ex.getRequestPartName()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @SuppressWarnings("unused")
    public ResponseEntity<ApiErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                                HttpServletRequest request) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("parameter", ex.getName());
        details.put("rejectedValue", String.valueOf(ex.getValue()));
        if (ex.getRequiredType() != null) {
            details.put("expectedType", ex.getRequiredType().getSimpleName());
        }
        String hint = ex.getRequiredType() != null
                ? "Pass '" + ex.getName() + "' as " + ex.getRequiredType().getSimpleName() + "."
                : "Check the parameter type.";
        return build(HttpStatus.BAD_REQUEST, ErrorCode.TYPE_MISMATCH, ErrorCategory.BAD_REQUEST,
                "Parameter '" + ex.getName() + "' has the wrong type.",
                hint, request, details);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @SuppressWarnings("unused")
    public ResponseEntity<ApiErrorResponse> handleIllegalArgument(IllegalArgumentException ex,
                                                                   HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, ErrorCategory.BAD_REQUEST,
                ex.getMessage() == null ? "Bad request." : ex.getMessage(),
                null, request, null);
    }

    // ── Entity-specific validation (carry per-field details) ───────────────

    @ExceptionHandler(AudioValidationException.class)
    @SuppressWarnings("unused")
    public ResponseEntity<ApiErrorResponse> handleAudioValidation(AudioValidationException ex,
                                                                   HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ErrorCode.AUDIO_VALIDATION_ERROR, ErrorCategory.VALIDATION,
                ex.getMessage(),
                "Audio submission rejected — fix the indicated fields and resubmit.",
                request, toDetails(ex.getFieldErrors()));
    }

    @ExceptionHandler(ProjectValidationException.class)
    @SuppressWarnings("unused")
    public ResponseEntity<ApiErrorResponse> handleProjectValidation(ProjectValidationException ex,
                                                                     HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ErrorCode.PROJECT_VALIDATION_ERROR, ErrorCategory.VALIDATION,
                ex.getMessage(),
                "Project payload invalid — see field-level reasons in 'details'.",
                request, toDetails(ex.getFieldErrors()));
    }

    @ExceptionHandler(VideoValidationException.class)
    @SuppressWarnings("unused")
    public ResponseEntity<ApiErrorResponse> handleVideoValidation(VideoValidationException ex,
                                                                   HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ErrorCode.VIDEO_VALIDATION_ERROR, ErrorCategory.VALIDATION,
                ex.getMessage(),
                "Video submission rejected — fix the indicated fields and resubmit.",
                request, toDetails(ex.getFieldErrors()));
    }

    @ExceptionHandler(ImageValidationException.class)
    @SuppressWarnings("unused")
    public ResponseEntity<ApiErrorResponse> handleImageValidation(ImageValidationException ex,
                                                                   HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ErrorCode.IMAGE_VALIDATION_ERROR, ErrorCategory.VALIDATION,
                ex.getMessage(),
                "Image submission rejected — fix the indicated fields and resubmit.",
                request, toDetails(ex.getFieldErrors()));
    }

    @ExceptionHandler(TextValidationException.class)
    @SuppressWarnings("unused")
    public ResponseEntity<ApiErrorResponse> handleTextValidation(TextValidationException ex,
                                                                  HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ErrorCode.TEXT_VALIDATION_ERROR, ErrorCategory.VALIDATION,
                ex.getMessage(),
                "Text payload invalid — see field-level reasons in 'details'.",
                request, toDetails(ex.getFieldErrors()));
    }

    @ExceptionHandler(PersonValidationException.class)
    @SuppressWarnings("unused")
    public ResponseEntity<ApiErrorResponse> handlePersonValidation(PersonValidationException ex,
                                                                    HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ErrorCode.PERSON_VALIDATION_ERROR, ErrorCategory.VALIDATION,
                ex.getMessage(),
                "Person payload invalid — see field-level reasons in 'details'.",
                request, toDetails(ex.getFieldErrors()));
    }

    @ExceptionHandler(MaqamValidationException.class)
    @SuppressWarnings("unused")
    public ResponseEntity<ApiErrorResponse> handleMaqamValidation(MaqamValidationException ex,
                                                                   HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ErrorCode.MAQAM_VALIDATION_ERROR, ErrorCategory.VALIDATION,
                ex.getMessage(),
                "Maqam record invalid — see field-level reasons in 'details'.",
                request, toDetails(ex.getFieldErrors()));
    }

    @ExceptionHandler(PhysicalMediaValidationException.class)
    @SuppressWarnings("unused")
    public ResponseEntity<ApiErrorResponse> handlePhysicalMediaValidation(PhysicalMediaValidationException ex,
                                                                           HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ErrorCode.PHYSICAL_MEDIA_VALIDATION_ERROR, ErrorCategory.VALIDATION,
                ex.getMessage(),
                "Physical-media row invalid — see field-level reasons in 'details'.",
                request, toDetails(ex.getFieldErrors()));
    }

    // ─── 404 Not Found ──────────────────────────────────────────────────────

    @ExceptionHandler(VideoNotFoundException.class)
    @SuppressWarnings("unused")
    public ResponseEntity<ApiErrorResponse> handleVideoNotFound(VideoNotFoundException ex,
                                                                 HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, ErrorCode.VIDEO_NOT_FOUND, ErrorCategory.NOT_FOUND,
                ex.getMessage(),
                "Confirm the video id and that it has not been trashed.",
                request, null);
    }

    @ExceptionHandler(AudioNotFoundException.class)
    @SuppressWarnings("unused")
    public ResponseEntity<ApiErrorResponse> handleAudioNotFound(AudioNotFoundException ex,
                                                                 HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, ErrorCode.AUDIO_NOT_FOUND, ErrorCategory.NOT_FOUND,
                ex.getMessage(),
                "Confirm the audio id and that it has not been trashed.",
                request, null);
    }

    @ExceptionHandler(ImageNotFoundException.class)
    @SuppressWarnings("unused")
    public ResponseEntity<ApiErrorResponse> handleImageNotFound(ImageNotFoundException ex,
                                                                 HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, ErrorCode.IMAGE_NOT_FOUND, ErrorCategory.NOT_FOUND,
                ex.getMessage(),
                "Confirm the image id and that it has not been trashed.",
                request, null);
    }

    @ExceptionHandler(TextNotFoundException.class)
    @SuppressWarnings("unused")
    public ResponseEntity<ApiErrorResponse> handleTextNotFound(TextNotFoundException ex,
                                                                HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, ErrorCode.TEXT_NOT_FOUND, ErrorCategory.NOT_FOUND,
                ex.getMessage(),
                "Confirm the text id and that it has not been trashed.",
                request, null);
    }

    @ExceptionHandler(CategoryNotFoundException.class)
    @SuppressWarnings("unused")
    public ResponseEntity<ApiErrorResponse> handleCategoryNotFound(CategoryNotFoundException ex,
                                                                    HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, ErrorCode.CATEGORY_NOT_FOUND, ErrorCategory.NOT_FOUND,
                ex.getMessage(),
                "Pick an existing category from the catalog or create one first.",
                request, null);
    }

    @ExceptionHandler(ProjectNotFoundException.class)
    @SuppressWarnings("unused")
    public ResponseEntity<ApiErrorResponse> handleProjectNotFound(ProjectNotFoundException ex,
                                                                   HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, ErrorCode.PROJECT_NOT_FOUND, ErrorCategory.NOT_FOUND,
                ex.getMessage(),
                "Confirm the project id; trashed projects must be restored before use.",
                request, null);
    }

    @ExceptionHandler(PersonNotFoundException.class)
    @SuppressWarnings("unused")
    public ResponseEntity<ApiErrorResponse> handlePersonNotFound(PersonNotFoundException ex,
                                                                  HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, ErrorCode.PERSON_NOT_FOUND, ErrorCategory.NOT_FOUND,
                ex.getMessage(),
                "Confirm the person id or create a new person record.",
                request, null);
    }

    @ExceptionHandler(MaqamNotFoundException.class)
    @SuppressWarnings("unused")
    public ResponseEntity<ApiErrorResponse> handleMaqamNotFound(MaqamNotFoundException ex,
                                                                 HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, ErrorCode.MAQAM_NOT_FOUND, ErrorCategory.NOT_FOUND,
                ex.getMessage(),
                "Confirm the maqam record id.",
                request, null);
    }

    @ExceptionHandler(PhysicalMediaNotFoundException.class)
    @SuppressWarnings("unused")
    public ResponseEntity<ApiErrorResponse> handlePhysicalMediaNotFound(PhysicalMediaNotFoundException ex,
                                                                         HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, ErrorCode.PHYSICAL_MEDIA_NOT_FOUND, ErrorCategory.NOT_FOUND,
                ex.getMessage(),
                "Confirm the physical-media id, or re-import the spreadsheet row.",
                request, null);
    }

    @ExceptionHandler(MaqamAccessDeniedException.class)
    @SuppressWarnings("unused")
    public ResponseEntity<ApiErrorResponse> handleMaqamAccessDenied(MaqamAccessDeniedException ex,
                                                                     HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, ErrorCode.MAQAM_PANEL_ACCESS_DENIED, ErrorCategory.AUTHORIZATION,
                ex.getMessage(),
                "Only TEACHER or ADMIN accounts may access the maqam voting panel.",
                request, null);
    }

    @ExceptionHandler(GuestCorrectionNotFoundException.class)
    @SuppressWarnings("unused")
    public ResponseEntity<ApiErrorResponse> handleCorrectionNotFound(GuestCorrectionNotFoundException ex,
                                                                      HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, ErrorCode.CORRECTION_NOT_FOUND, ErrorCategory.NOT_FOUND,
                ex.getMessage(),
                "Confirm the correction id; processed corrections may have been archived.",
                request, null);
    }

    @ExceptionHandler(KhiLogoNotFoundException.class)
    @SuppressWarnings("unused")
    public ResponseEntity<ApiErrorResponse> handleKhiLogoNotFound(KhiLogoNotFoundException ex,
                                                                    HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, ErrorCode.KHI_LOGO_NOT_FOUND, ErrorCategory.NOT_FOUND,
                ex.getMessage(),
                "Confirm the khi logo id.",
                request, null);
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    @SuppressWarnings("unused")
    public ResponseEntity<ApiErrorResponse> handleNotFound(Exception ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, ErrorCategory.NOT_FOUND,
                "Endpoint not found: " + request.getMethod() + " " + request.getRequestURI(),
                "Check the URL, HTTP method and API version.",
                request, null);
    }

    // ─── 405 Method Not Allowed ─────────────────────────────────────────────

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @SuppressWarnings("unused")
    public ResponseEntity<ApiErrorResponse> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex,
                                                                    HttpServletRequest request) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("method", ex.getMethod());
        if (ex.getSupportedMethods() != null) {
            details.put("supportedMethods", List.of(ex.getSupportedMethods()));
        }
        return build(HttpStatus.METHOD_NOT_ALLOWED, ErrorCode.METHOD_NOT_ALLOWED, ErrorCategory.BAD_REQUEST,
                "HTTP method " + ex.getMethod() + " is not supported on this endpoint.",
                "Allowed methods are listed in 'details.supportedMethods'.",
                request, details);
    }

    // ─── 409 Conflict ────────────────────────────────────────────────────────

    @ExceptionHandler(DataIntegrityViolationException.class)
    @SuppressWarnings("unused")
    public ResponseEntity<ApiErrorResponse> handleIntegrityViolation(DataIntegrityViolationException ex,
                                                                      HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ErrorCode.CONFLICT, ErrorCategory.CONFLICT,
                ApiErrorResponses.rootMessage(ex),
                "A database constraint blocked this change (unique key, foreign key or NOT NULL).",
                request, null);
    }

    /**
     * Concurrent edit detected via {@code @Version}. Two users (or two tabs of
     * the same user) modified the same record at the same time — the second
     * save sees a stale version. Translate to a 409 with a clear retry hint
     * instead of leaking the framework exception.
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    @SuppressWarnings("unused")
    public ResponseEntity<ApiErrorResponse> handleOptimisticLock(ObjectOptimisticLockingFailureException ex,
                                                                  HttpServletRequest request) {
        String entity = ex.getPersistentClassName() != null
                ? ex.getPersistentClassName().substring(ex.getPersistentClassName().lastIndexOf('.') + 1)
                : "record";
        String message = "This " + entity + " was modified by someone else while you were editing it.";
        return build(HttpStatus.CONFLICT, ErrorCode.STALE_VERSION, ErrorCategory.CONFLICT,
                message,
                "Reload the latest version, re-apply your changes and try again.",
                request, Map.of("entity", entity));
    }

    @ExceptionHandler(AudioAlreadyExistsException.class)
    @SuppressWarnings("unused")
    public ResponseEntity<ApiErrorResponse> handleAudioAlreadyExists(AudioAlreadyExistsException ex,
                                                                      HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ErrorCode.AUDIO_ALREADY_EXISTS, ErrorCategory.CONFLICT,
                ex.getMessage(), "Pick a different title or update the existing audio record.", request, null);
    }

    @ExceptionHandler(VideoAlreadyExistsException.class)
    @SuppressWarnings("unused")
    public ResponseEntity<ApiErrorResponse> handleVideoAlreadyExists(VideoAlreadyExistsException ex,
                                                                      HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ErrorCode.VIDEO_ALREADY_EXISTS, ErrorCategory.CONFLICT,
                ex.getMessage(), "Pick a different title or update the existing video record.", request, null);
    }

    @ExceptionHandler(ImageAlreadyExistsException.class)
    @SuppressWarnings("unused")
    public ResponseEntity<ApiErrorResponse> handleImageAlreadyExists(ImageAlreadyExistsException ex,
                                                                      HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ErrorCode.IMAGE_ALREADY_EXISTS, ErrorCategory.CONFLICT,
                ex.getMessage(), "Pick a different title or update the existing image record.", request, null);
    }

    @ExceptionHandler(TextAlreadyExistsException.class)
    @SuppressWarnings("unused")
    public ResponseEntity<ApiErrorResponse> handleTextAlreadyExists(TextAlreadyExistsException ex,
                                                                     HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ErrorCode.TEXT_ALREADY_EXISTS, ErrorCategory.CONFLICT,
                ex.getMessage(), "Pick a different title or update the existing text record.", request, null);
    }

    @ExceptionHandler(CategoryAlreadyExistsException.class)
    @SuppressWarnings("unused")
    public ResponseEntity<ApiErrorResponse> handleCategoryAlreadyExists(CategoryAlreadyExistsException ex,
                                                                         HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ErrorCode.CATEGORY_ALREADY_EXISTS, ErrorCategory.CONFLICT,
                ex.getMessage(), "Reuse the existing category or pick a unique name.", request, null);
    }

    @ExceptionHandler(ProjectAlreadyExistsException.class)
    @SuppressWarnings("unused")
    public ResponseEntity<ApiErrorResponse> handleProjectAlreadyExists(ProjectAlreadyExistsException ex,
                                                                        HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ErrorCode.PROJECT_ALREADY_EXISTS, ErrorCategory.CONFLICT,
                ex.getMessage(), "Reuse the existing project or pick a unique name.", request, null);
    }

    @ExceptionHandler(PersonAlreadyExistsException.class)
    @SuppressWarnings("unused")
    public ResponseEntity<ApiErrorResponse> handlePersonAlreadyExists(PersonAlreadyExistsException ex,
                                                                       HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ErrorCode.PERSON_ALREADY_EXISTS, ErrorCategory.CONFLICT,
                ex.getMessage(), "Reuse the existing person record or adjust the identifying fields.", request, null);
    }

    @ExceptionHandler(CategoryInUseException.class)
    @SuppressWarnings("unused")
    public ResponseEntity<ApiErrorResponse> handleCategoryInUse(CategoryInUseException ex,
                                                                 HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ErrorCode.CATEGORY_IN_USE, ErrorCategory.CONFLICT,
                ex.getMessage(),
                "Reassign the linked media to another category before deleting this one.",
                request, null);
    }

    @ExceptionHandler(ProjectInUseException.class)
    @SuppressWarnings("unused")
    public ResponseEntity<ApiErrorResponse> handleProjectInUse(ProjectInUseException ex,
                                                                HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ErrorCode.PROJECT_IN_USE, ErrorCategory.CONFLICT,
                ex.getMessage(),
                "Move or trash the linked media before deleting this project.",
                request, null);
    }

    @ExceptionHandler(CorrectionAlreadyProcessedException.class)
    @SuppressWarnings("unused")
    public ResponseEntity<ApiErrorResponse> handleCorrectionAlreadyProcessed(CorrectionAlreadyProcessedException ex,
                                                                              HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ErrorCode.CORRECTION_ALREADY_PROCESSED, ErrorCategory.CONFLICT,
                ex.getMessage(),
                "This correction was already accepted or rejected — refresh the list to see its current state.",
                request, null);
    }

    // ─── 401 / 403 Auth ─────────────────────────────────────────────────────

    @ExceptionHandler(BadCredentialsException.class)
    @SuppressWarnings("unused")
    public ResponseEntity<ApiErrorResponse> handleBadCredentials(BadCredentialsException ex,
                                                                  HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, ErrorCode.BAD_CREDENTIALS, ErrorCategory.AUTHENTICATION,
                "Username or password is incorrect.",
                "Re-enter your credentials or reset your password.",
                request, null);
    }

    @ExceptionHandler(AuthenticationException.class)
    @SuppressWarnings("unused")
    public ResponseEntity<ApiErrorResponse> handleAuthenticationFailure(AuthenticationException ex,
                                                                         HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, ErrorCode.AUTHENTICATION_FAILED, ErrorCategory.AUTHENTICATION,
                ex.getMessage() == null ? "Authentication required." : ex.getMessage(),
                "Sign in and retry the request.",
                request, null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    @SuppressWarnings("unused")
    public ResponseEntity<ApiErrorResponse> handleAccessDenied(AccessDeniedException ex,
                                                                HandlerMethod handler,
                                                                HttpServletRequest request) {
        Map<String, Object> details = new LinkedHashMap<>();

        // Required authority — extract from the handler's @PreAuthorize when available.
        // Format we recognise: hasAuthority('audio:create') / hasRole('ADMIN').
        String required = extractRequiredAuthority(handler);
        if (required != null) details.put("requiredAuthority", required);

        // Actor info — useful for the frontend to show "you have X, you need Y".
        org.springframework.security.core.Authentication auth =
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth != null) {
            details.put("actor", auth.getName());
            details.put("actorAuthorities", auth.getAuthorities().stream()
                    .map(org.springframework.security.core.GrantedAuthority::getAuthority)
                    .filter(a -> a != null && !a.isBlank())
                    .distinct()
                    .sorted()
                    .toList());
        }
        details.put("requestMethod", request.getMethod());

        String message = required != null
                ? "You don't have permission to perform this action. Required authority: '" + required + "'."
                : "You don't have permission to perform this action.";

        String hint = required != null
                ? "Ask an administrator to grant '" + required + "' or to assign a role that includes it."
                : "Ask an administrator to grant the missing permission for this endpoint.";

        return build(HttpStatus.FORBIDDEN, ErrorCode.ACCESS_DENIED, ErrorCategory.AUTHORIZATION,
                message, hint, request, details);
    }

    /** Pulls the authority/role string out of a {@code @PreAuthorize("hasAuthority('xxx')")}
     *  or {@code hasRole('xxx')} expression on the resolved handler method. Returns null
     *  if the handler isn't annotated, the expression doesn't match, or the handler
     *  method couldn't be resolved (e.g. denial happened before routing). */
    private String extractRequiredAuthority(HandlerMethod handler) {
        if (handler == null) return null;
        org.springframework.security.access.prepost.PreAuthorize ann =
                handler.getMethodAnnotation(org.springframework.security.access.prepost.PreAuthorize.class);
        if (ann == null) {
            ann = handler.getBeanType().getAnnotation(
                    org.springframework.security.access.prepost.PreAuthorize.class);
        }
        if (ann == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("has(?:Authority|Role)\\s*\\(\\s*'([^']+)'\\s*\\)")
                .matcher(ann.value());
        return m.find() ? m.group(1) : null;
    }

    // ─── 415 / 413 / 406 Media ──────────────────────────────────────────────

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    @SuppressWarnings("unused")
    public ResponseEntity<ApiErrorResponse> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException ex,
                                                                        HttpServletRequest request) {
        String contentType = ex.getContentType() == null ? "unknown" : ex.getContentType().toString();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("received", contentType);
        if (ex.getSupportedMediaTypes() != null && !ex.getSupportedMediaTypes().isEmpty()) {
            details.put("supported", ex.getSupportedMediaTypes().stream().map(Object::toString).toList());
        }
        return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ErrorCode.UNSUPPORTED_MEDIA_TYPE, ErrorCategory.MEDIA,
                "Unsupported request content type (" + contentType + ").",
                "For multipart create/update, send the 'data' part as application/json and the file part as a file.",
                request, details);
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    @SuppressWarnings("unused")
    public ResponseEntity<ApiErrorResponse> handleNotAcceptable(HttpMediaTypeNotAcceptableException ex,
                                                                 HttpServletRequest request) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (ex.getSupportedMediaTypes() != null && !ex.getSupportedMediaTypes().isEmpty()) {
            details.put("supported", ex.getSupportedMediaTypes().stream().map(Object::toString).toList());
        }
        return build(HttpStatus.NOT_ACCEPTABLE, ErrorCode.NOT_ACCEPTABLE, ErrorCategory.MEDIA,
                "Server cannot produce a response acceptable to the client.",
                "Adjust the 'Accept' header — see 'details.supported' for what we can return.",
                request, details);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @SuppressWarnings("unused")
    public ResponseEntity<ApiErrorResponse> handleUploadTooLarge(MaxUploadSizeExceededException ex,
                                                                  HttpServletRequest request) {
        Map<String, Object> details = Map.of("maxBytes", ex.getMaxUploadSize());
        return build(HttpStatus.PAYLOAD_TOO_LARGE, ErrorCode.UPLOAD_TOO_LARGE, ErrorCategory.MEDIA,
                "Upload exceeds the configured size limit.",
                "Compress or split the file and retry — see 'details.maxBytes' for the cap.",
                request, details);
    }

    @ExceptionHandler(MultipartException.class)
    @SuppressWarnings("unused")
    public ResponseEntity<ApiErrorResponse> handleMultipart(MultipartException ex,
                                                             HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, ErrorCategory.MEDIA,
                "Multipart request could not be parsed.",
                "Ensure boundaries and part names match the API contract.",
                request, null);
    }

    // ─── 500 Server Error ────────────────────────────────────────────────────

    @ExceptionHandler(QueryTimeoutException.class)
    @SuppressWarnings("unused")
    public ResponseEntity<ApiErrorResponse> handleQueryTimeout(QueryTimeoutException ex,
                                                                HttpServletRequest request) {
        log.warn("Database query timed out", ex);
        return build(HttpStatus.GATEWAY_TIMEOUT, ErrorCode.TIMEOUT, ErrorCategory.DATABASE,
                "The database query took too long to complete.",
                "Retry the request, narrow the filter, or contact support if it persists.",
                request, null);
    }

    @ExceptionHandler(DataAccessException.class)
    @SuppressWarnings("unused")
    public ResponseEntity<ApiErrorResponse> handleDatabaseError(DataAccessException ex,
                                                                 HttpServletRequest request) {
        log.error("Database access error on {}", request.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.DATABASE_ERROR, ErrorCategory.DATABASE,
                "A database error prevented the request from completing.",
                "Retry shortly; if the problem persists, share the traceId with support.",
                request, null);
    }

    @ExceptionHandler(IOException.class)
    @SuppressWarnings("unused")
    public ResponseEntity<ApiErrorResponse> handleIO(IOException ex, HttpServletRequest request) {
        log.error("I/O error on {}", request.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.STORAGE_ERROR, ErrorCategory.STORAGE,
                "Storage I/O failure while handling the request.",
                "Retry shortly; if the problem persists, share the traceId with support.",
                request, null);
    }

    /**
     * Stream/proxy controllers (audio, video, image, text) signal deliberate
     * statuses — mostly 404 — by throwing {@link ResponseStatusException}.
     * Without this handler the {@code Exception.class} catch-all below would
     * rewrite every one of them into an opaque 500.
     */
    @ExceptionHandler(ResponseStatusException.class)
    @SuppressWarnings("unused")
    public ResponseEntity<ApiErrorResponse> handleResponseStatus(ResponseStatusException ex,
                                                                  HttpServletRequest request) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) status = HttpStatus.INTERNAL_SERVER_ERROR;

        String message = ex.getReason() != null && !ex.getReason().isBlank()
                ? ex.getReason()
                : status.getReasonPhrase();

        if (status == HttpStatus.NOT_FOUND) {
            return build(status, ErrorCode.NOT_FOUND, ErrorCategory.NOT_FOUND,
                    message, "Check the identifier and try again.", request, null);
        }
        if (status.is4xxClientError()) {
            return build(status, ErrorCode.BAD_REQUEST, ErrorCategory.BAD_REQUEST,
                    message, "Review the request and try again.", request, null);
        }
        log.error("ResponseStatusException ({}) on {}: {}",
                status.value(), request.getRequestURI(), message, ex);
        return build(status, ErrorCode.INTERNAL_SERVER_ERROR, ErrorCategory.SERVER_ERROR,
                message, "Retry shortly; if the problem persists, share the traceId with support.",
                request, null);
    }

    @ExceptionHandler(Exception.class)
    @SuppressWarnings("unused")
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        // Never leak internal messages to the client. Log with trace id for support correlation.
        log.error("Unhandled error on {}", request.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_SERVER_ERROR, ErrorCategory.SERVER_ERROR,
                "An unexpected error occurred.",
                "Retry shortly; if the problem persists, share the traceId with support.",
                request, null);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private ResponseEntity<ApiErrorResponse> build(HttpStatus status,
                                                    String error,
                                                    ErrorCategory category,
                                                    String message,
                                                    String hint,
                                                    HttpServletRequest request,
                                                    Map<String, Object> details) {
        return ApiErrorResponses.build(status, error, category, message, hint, request.getRequestURI(), details);
    }

    private Map<String, Object> fieldErrors(Iterable<FieldError> fieldErrors) {
        Map<String, Object> details = new LinkedHashMap<>();
        for (FieldError error : fieldErrors) {
            details.put(error.getField(), error.getDefaultMessage());
        }
        return details.isEmpty() ? null : details;
    }

    private Map<String, Object> toDetails(Map<String, String> fieldErrors) {
        if (fieldErrors == null || fieldErrors.isEmpty()) {
            return null;
        }
        return new LinkedHashMap<>(fieldErrors);
    }
}
