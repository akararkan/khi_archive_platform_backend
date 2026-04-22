package ak.dev.khi_archive_platform.platform.exceptions;

import ak.dev.khi_archive_platform.common.exceptions.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice(basePackages = "ak.dev.khi_archive_platform.platform")
public class ApiExceptionHandler {

    @ExceptionHandler({
            IllegalArgumentException.class,
            HttpMessageNotReadableException.class,
            MethodArgumentNotValidException.class,
            BindException.class,
            ConstraintViolationException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            MissingServletRequestPartException.class
    })
    @SuppressWarnings("unused")
    public ResponseEntity<ApiErrorResponse> handleBadRequest(Exception ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", ex.getMessage(), request.getRequestURI(), validationDetails(ex));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    @SuppressWarnings("unused")
    public ResponseEntity<ApiErrorResponse> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException ex,
                                                                       HttpServletRequest request) {
        String contentType = ex.getContentType() == null ? "unknown" : ex.getContentType().toString();
        return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "UNSUPPORTED_MEDIA_TYPE",
                "Unsupported request content type (" + contentType + "). For multipart person create/update, send the 'data' part as application/json and the 'image' part as a file.",
                request.getRequestURI(),
                null);
    }

    @ExceptionHandler({DataIntegrityViolationException.class})
    @SuppressWarnings("unused")
    public ResponseEntity<ApiErrorResponse> handleConflict(DataIntegrityViolationException ex,
                                                           HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "CONFLICT", rootMessage(ex), request.getRequestURI(), null);
    }

    @ExceptionHandler(ObjectAttributeAlreadyExistsException.class)
    @SuppressWarnings("unused")
    public ResponseEntity<ApiErrorResponse> handleObjectAlreadyExists(ObjectAttributeAlreadyExistsException ex,
                                                                      HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "OBJECT_ALREADY_EXISTS", ex.getMessage(), request.getRequestURI(), null);
    }

    @ExceptionHandler(ObjectAttributeNotFoundException.class)
    @SuppressWarnings("unused")
    public ResponseEntity<ApiErrorResponse> handleObjectNotFound(ObjectAttributeNotFoundException ex,
                                                                 HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "OBJECT_NOT_FOUND", ex.getMessage(), request.getRequestURI(), null);
    }

    @ExceptionHandler(AudioAlreadyExistsException.class)
    @SuppressWarnings("unused")
    public ResponseEntity<ApiErrorResponse> handleAudioAlreadyExists(AudioAlreadyExistsException ex,
                                                                     HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "AUDIO_ALREADY_EXISTS", ex.getMessage(), request.getRequestURI(), null);
    }

    @ExceptionHandler(AudioNotFoundException.class)
    @SuppressWarnings("unused")
    public ResponseEntity<ApiErrorResponse> handleAudioNotFound(AudioNotFoundException ex,
                                                                HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "AUDIO_NOT_FOUND", ex.getMessage(), request.getRequestURI(), null);
    }

    @ExceptionHandler(AudioValidationException.class)
    @SuppressWarnings("unused")
    public ResponseEntity<ApiErrorResponse> handleAudioValidation(AudioValidationException ex,
                                                                  HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "AUDIO_VALIDATION_ERROR", ex.getMessage(), request.getRequestURI(), null);
    }


    @ExceptionHandler(CategoryAlreadyExistsException.class)
    @SuppressWarnings("unused")
    public ResponseEntity<ApiErrorResponse> handleCategoryAlreadyExists(CategoryAlreadyExistsException ex,
                                                                        HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "CATEGORY_ALREADY_EXISTS", ex.getMessage(), request.getRequestURI(), null);
    }

    @ExceptionHandler(CategoryNotFoundException.class)
    @SuppressWarnings("unused")
    public ResponseEntity<ApiErrorResponse> handleCategoryNotFound(CategoryNotFoundException ex,
                                                                   HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "CATEGORY_NOT_FOUND", ex.getMessage(), request.getRequestURI(), null);
    }

    @ExceptionHandler(CategoryInUseException.class)
    @SuppressWarnings("unused")
    public ResponseEntity<ApiErrorResponse> handleCategoryInUse(CategoryInUseException ex,
                                                                HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "CATEGORY_IN_USE", ex.getMessage(), request.getRequestURI(), null);
    }

    @ExceptionHandler({DataAccessException.class})
    @SuppressWarnings("unused")
    public ResponseEntity<ApiErrorResponse> handleDatabaseError(DataAccessException ex,
                                                             HttpServletRequest request) {
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "DATABASE_ERROR", rootMessage(ex), request.getRequestURI(), null);
    }

    @ExceptionHandler({AuthenticationException.class, BadCredentialsException.class})
    @SuppressWarnings("unused")
    public ResponseEntity<ApiErrorResponse> handleAuthenticationFailure(Exception ex,
                                                                         HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_FAILED", ex.getMessage(), request.getRequestURI(), null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    @SuppressWarnings("unused")
    public ResponseEntity<ApiErrorResponse> handleAccessDenied(AccessDeniedException ex,
                                                               HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, "ACCESS_DENIED", ex.getMessage(), request.getRequestURI(), null);
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    @SuppressWarnings("unused")
    public ResponseEntity<ApiErrorResponse> handleNotFound(Exception ex,
                                                           HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage(), request.getRequestURI(), null);
    }

    @ExceptionHandler({MultipartException.class, MaxUploadSizeExceededException.class})
    @SuppressWarnings("unused")
    public ResponseEntity<ApiErrorResponse> handleUploadTooLarge(Exception ex,
                                                                 HttpServletRequest request) {
        return build(HttpStatus.valueOf(413), "UPLOAD_TOO_LARGE", ex.getMessage(), request.getRequestURI(), null);
    }

    @ExceptionHandler(Exception.class)
    @SuppressWarnings("unused")
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex,
                                                             HttpServletRequest request) {
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR", ex.getMessage() == null ? "An unexpected error occurred." : ex.getMessage(), request.getRequestURI(), null);
    }

    private ResponseEntity<ApiErrorResponse> build(HttpStatus status,
                                                    String error,
                                                    String message,
                                                    String path,
                                                    Map<String, Object> details) {
        return ResponseEntity.status(status).body(new ApiErrorResponse(
                Instant.now(),
                status.value(),
                error,
                message,
                path,
                details
        ));
    }

    private Map<String, Object> validationDetails(Exception ex) {
        if (ex instanceof MethodArgumentNotValidException manv) {
            return fieldErrors(manv.getBindingResult().getFieldErrors());
        }
        if (ex instanceof BindException bindException) {
            return fieldErrors(bindException.getBindingResult().getFieldErrors());
        }
        if (ex instanceof ConstraintViolationException violationException) {
            Map<String, Object> details = new LinkedHashMap<>();
            violationException.getConstraintViolations().forEach(v ->
                    details.put(v.getPropertyPath().toString(), v.getMessage()));
            return details.isEmpty() ? null : details;
        }
        return null;
    }

    private Map<String, Object> fieldErrors(Iterable<FieldError> fieldErrors) {
        Map<String, Object> details = new LinkedHashMap<>();
        for (FieldError error : fieldErrors) {
            details.put(error.getField(), error.getDefaultMessage());
        }
        return details.isEmpty() ? null : details;
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() != null ? current.getMessage() : throwable.getMessage();
    }
}

