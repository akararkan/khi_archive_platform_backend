package ak.dev.khi_archive_platform.user.exceptions;

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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AccountExpiredException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
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
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Centralised exception → {@link ApiErrorResponse} translation for the
 * {@code user} package (auth, profile, admin, warnings).
 *
 * <p>Mirrors {@code ApiExceptionHandler} so the wire shape is identical
 * across packages — anything new added here should usually have a sibling
 * entry there too.</p>
 */
@RestControllerAdvice(basePackages = "ak.dev.khi_archive_platform.user")
@SuppressWarnings("unused")
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // ─── 409 — domain-rule conflicts ────────────────────────────────────────

    @ExceptionHandler(UserAlreadyExistsException.class)
    public ResponseEntity<ApiErrorResponse> handleUserAlreadyExists(UserAlreadyExistsException ex,
                                                                     HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ErrorCode.USER_ALREADY_EXISTS, ErrorCategory.CONFLICT,
                ex.getMessage(),
                "Use a different username/email or recover the existing account via password reset.",
                request, null);
    }

    @ExceptionHandler(IllegalAdminOperationException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalAdminOperation(IllegalAdminOperationException ex,
                                                                         HttpServletRequest request) {
        Map<String, Object> details = ex.getDetails().isEmpty() ? null : new LinkedHashMap<>(ex.getDetails());
        return build(HttpStatus.CONFLICT, ex.getErrorCode(), ErrorCategory.CONFLICT,
                ex.getMessage(),
                "This operation is structurally forbidden by the admin rules — pick a different target or change scope.",
                request, details);
    }

    @ExceptionHandler(UnknownPermissionException.class)
    public ResponseEntity<ApiErrorResponse> handleUnknownPermission(UnknownPermissionException ex,
                                                                     HttpServletRequest request) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("unknown", ex.getUnknown());
        details.put("catalog", "/api/admin/users/catalog/permissions");
        return build(HttpStatus.BAD_REQUEST, ErrorCode.UNKNOWN_PERMISSION, ErrorCategory.VALIDATION,
                ex.getMessage(),
                "Use the catalog endpoint to discover valid permission codes.",
                request, details);
    }

    // ─── 404 — entity lookups ───────────────────────────────────────────────

    @ExceptionHandler({UserNotFoundException.class, UsernameNotFoundException.class})
    public ResponseEntity<ApiErrorResponse> handleUserNotFound(RuntimeException ex,
                                                                HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, ErrorCode.USER_NOT_FOUND, ErrorCategory.NOT_FOUND,
                ex.getMessage(),
                "Confirm the username/id; the account may have been deleted.",
                request, null);
    }

    @ExceptionHandler(UserWarningNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleWarningNotFound(UserWarningNotFoundException ex,
                                                                   HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, ErrorCode.WARNING_NOT_FOUND, ErrorCategory.NOT_FOUND,
                ex.getMessage(),
                "Confirm the warning id; revoked warnings are soft-deleted.",
                request, null);
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ApiErrorResponse> handleNotFound(Exception ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, ErrorCategory.NOT_FOUND,
                "Endpoint not found: " + request.getMethod() + " " + request.getRequestURI(),
                "Check the URL, HTTP method and API version.",
                request, null);
    }

    // ─── 401 / 403 — authentication & authorisation ─────────────────────────

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiErrorResponse> handleBadCredentials(BadCredentialsException ex,
                                                                  HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, ErrorCode.BAD_CREDENTIALS, ErrorCategory.AUTHENTICATION,
                "Username or password is incorrect.",
                "Re-enter your credentials or reset your password.",
                request, null);
    }

    @ExceptionHandler(DisabledException.class)
    public ResponseEntity<ApiErrorResponse> handleDisabled(DisabledException ex, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, ErrorCode.ACCOUNT_DISABLED, ErrorCategory.ACCOUNT_STATE,
                "This account is disabled.",
                "Contact an administrator to re-enable the account.",
                request, null);
    }

    @ExceptionHandler(LockedException.class)
    public ResponseEntity<ApiErrorResponse> handleLocked(LockedException ex, HttpServletRequest request) {
        return build(HttpStatus.LOCKED, ErrorCode.ACCOUNT_LOCKED, ErrorCategory.ACCOUNT_STATE,
                ex.getMessage() == null ? "This account is locked." : ex.getMessage(),
                "Wait until the lock expires, or contact an administrator to unlock it.",
                request, null);
    }

    @ExceptionHandler(CredentialsExpiredException.class)
    public ResponseEntity<ApiErrorResponse> handleCredentialsExpired(CredentialsExpiredException ex,
                                                                      HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, ErrorCode.CREDENTIALS_EXPIRED, ErrorCategory.ACCOUNT_STATE,
                "Your credentials have expired.",
                "Reset your password to continue.",
                request, null);
    }

    @ExceptionHandler(AccountExpiredException.class)
    public ResponseEntity<ApiErrorResponse> handleAccountExpired(AccountExpiredException ex,
                                                                  HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, ErrorCode.ACCOUNT_DISABLED, ErrorCategory.ACCOUNT_STATE,
                "This account has expired.",
                "Contact an administrator to renew the account.",
                request, null);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiErrorResponse> handleAuthentication(AuthenticationException ex,
                                                                  HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, ErrorCode.AUTHENTICATION_FAILED, ErrorCategory.AUTHENTICATION,
                ex.getMessage() == null ? "Authentication required." : ex.getMessage(),
                "Sign in and retry the request.",
                request, null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccessDenied(AccessDeniedException ex,
                                                                HandlerMethod handler,
                                                                HttpServletRequest request) {
        Map<String, Object> details = new LinkedHashMap<>();
        String required = extractRequiredAuthority(handler);
        if (required != null) details.put("requiredAuthority", required);

        org.springframework.security.core.Authentication auth =
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth != null) {
            details.put("actor", auth.getName());
            details.put("actorAuthorities", auth.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
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

    // ─── 400 — granular bad-request handlers ────────────────────────────────

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                          HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, ErrorCategory.VALIDATION,
                "One or more fields failed validation. See 'details' for the per-field reason.",
                "Fix the highlighted fields and resubmit the request.",
                request, fieldErrors(ex.getBindingResult().getFieldErrors()));
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiErrorResponse> handleBind(BindException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, ErrorCategory.VALIDATION,
                "Request binding failed. See 'details' for the per-field reason.",
                "Check the field names and types and resubmit.",
                request, fieldErrors(ex.getBindingResult().getFieldErrors()));
    }

    @ExceptionHandler(ConstraintViolationException.class)
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
    public ResponseEntity<ApiErrorResponse> handleMissingPart(MissingServletRequestPartException ex,
                                                               HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ErrorCode.MISSING_REQUEST_PART, ErrorCategory.BAD_REQUEST,
                "Multipart request is missing the '" + ex.getRequestPartName() + "' part.",
                "Send 'data' as application/json and any file part(s) as multipart/form-data.",
                request, Map.of("part", ex.getRequestPartName()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
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
    public ResponseEntity<ApiErrorResponse> handleIllegalArgument(IllegalArgumentException ex,
                                                                   HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, ErrorCategory.BAD_REQUEST,
                ex.getMessage() == null ? "Bad request." : ex.getMessage(),
                null, request, null);
    }

    // ─── 405 / 413 / 415 ────────────────────────────────────────────────────

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
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

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
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
                "For multipart requests, send 'data' as application/json and file part(s) as multipart/form-data.",
                request, details);
    }

    @ExceptionHandler({MultipartException.class, MaxUploadSizeExceededException.class})
    public ResponseEntity<ApiErrorResponse> handleUploadTooLarge(Exception ex, HttpServletRequest request) {
        Map<String, Object> details = null;
        if (ex instanceof MaxUploadSizeExceededException mused) {
            details = Map.of("maxBytes", mused.getMaxUploadSize());
        }
        return build(HttpStatus.PAYLOAD_TOO_LARGE, ErrorCode.UPLOAD_TOO_LARGE, ErrorCategory.MEDIA,
                "Upload exceeds the configured size limit.",
                "Compress or split the file and retry — see 'details.maxBytes' for the cap.",
                request, details);
    }

    // ─── 409 — generic DB conflict ──────────────────────────────────────────

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConflict(DataIntegrityViolationException ex,
                                                            HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ErrorCode.CONFLICT, ErrorCategory.CONFLICT,
                ApiErrorResponses.rootMessage(ex),
                "A database constraint blocked this change (unique key, foreign key or NOT NULL).",
                request, null);
    }

    // ─── 5xx ────────────────────────────────────────────────────────────────

    @ExceptionHandler(UserStorageException.class)
    public ResponseEntity<ApiErrorResponse> handleUserStorage(UserStorageException ex,
                                                               HttpServletRequest request) {
        log.error("User storage error on {}", request.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.STORAGE_ERROR, ErrorCategory.STORAGE,
                "Profile-image storage failure.",
                "Retry shortly; if the problem persists, share the traceId with support.",
                request, null);
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<ApiErrorResponse> handleIO(IOException ex, HttpServletRequest request) {
        log.error("I/O error on {}", request.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.STORAGE_ERROR, ErrorCategory.STORAGE,
                "Storage I/O failure while handling the request.",
                "Retry shortly; if the problem persists, share the traceId with support.",
                request, null);
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiErrorResponse> handleDatabaseError(DataAccessException ex,
                                                                 HttpServletRequest request) {
        log.error("Database access error on {}", request.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.DATABASE_ERROR, ErrorCategory.DATABASE,
                "A database error prevented the request from completing.",
                "Retry shortly; if the problem persists, share the traceId with support.",
                request, null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
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
}
