package ak.dev.khi_archive_platform.user.exceptions;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.http.converter.HttpMessageNotReadableException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice(basePackages = "ak.dev.khi_archive_platform.user")
public class GlobalExceptionHandler {

    @ExceptionHandler(UserAlreadyExistsException.class)
    public ResponseEntity<UserApiErrorResponse> handleUserAlreadyExists(
            UserAlreadyExistsException ex,
            HttpServletRequest request
    ) {
        return build(HttpStatus.CONFLICT, "USER_ALREADY_EXISTS", ex.getMessage(), request.getRequestURI(), null);
    }

    @ExceptionHandler({UserNotFoundException.class, UsernameNotFoundException.class})
    public ResponseEntity<UserApiErrorResponse> handleUserNotFound(
            RuntimeException ex,
            HttpServletRequest request
    ) {
        return build(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", ex.getMessage(), request.getRequestURI(), null);
    }

    @ExceptionHandler({BadCredentialsException.class, AuthenticationException.class})
    public ResponseEntity<UserApiErrorResponse> handleAuthenticationFailure(
            RuntimeException ex,
            HttpServletRequest request
    ) {
        return build(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_FAILED", ex.getMessage(), request.getRequestURI(), null);
    }

    @ExceptionHandler(org.springframework.security.authentication.LockedException.class)
    public ResponseEntity<UserApiErrorResponse> handleLocked(
            org.springframework.security.authentication.LockedException ex,
            HttpServletRequest request
    ) {
        return build(HttpStatus.LOCKED, "ACCOUNT_LOCKED", ex.getMessage(), request.getRequestURI(), null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<UserApiErrorResponse> handleAccessDenied(
            AccessDeniedException ex,
            HttpServletRequest request
    ) {
        return build(HttpStatus.FORBIDDEN, "ACCESS_DENIED", ex.getMessage(), request.getRequestURI(), null);
    }

    @ExceptionHandler({IllegalArgumentException.class, ConstraintViolationException.class,
            MethodArgumentNotValidException.class, BindException.class,
            HttpMessageNotReadableException.class})
    public ResponseEntity<UserApiErrorResponse> handleBadRequest(
            Exception ex,
            HttpServletRequest request
    ) {
        Map<String, Object> details = null;

        if (ex instanceof MethodArgumentNotValidException manv) {
            details = new LinkedHashMap<>();
            for (FieldError error : manv.getBindingResult().getFieldErrors()) {
                details.put(error.getField(), error.getDefaultMessage());
            }
        } else if (ex instanceof BindException bindException) {
            details = new LinkedHashMap<>();
            for (FieldError error : bindException.getBindingResult().getFieldErrors()) {
                details.put(error.getField(), error.getDefaultMessage());
            }
        }

        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", ex.getMessage(), request.getRequestURI(), details);
    }

    @ExceptionHandler({UserStorageException.class, java.io.IOException.class})
    public ResponseEntity<UserApiErrorResponse> handleStorageFailure(
            Exception ex,
            HttpServletRequest request
    ) {
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_ERROR", ex.getMessage(), request.getRequestURI(), null);
    }

    @ExceptionHandler({MultipartException.class, MaxUploadSizeExceededException.class})
    public ResponseEntity<UserApiErrorResponse> handleUploadTooLarge(
            Exception ex,
            HttpServletRequest request
    ) {
        return build(HttpStatus.PAYLOAD_TOO_LARGE, "UPLOAD_TOO_LARGE", ex.getMessage(), request.getRequestURI(), null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<UserApiErrorResponse> handleUnexpected(
            Exception ex,
            HttpServletRequest request
    ) {
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR", "An unexpected error occurred.", request.getRequestURI(), null);
    }

    private ResponseEntity<UserApiErrorResponse> build(HttpStatus status,
                                                       String error,
                                                       String message,
                                                       String path,
                                                       Map<String, Object> details) {
        return ResponseEntity.status(status).body(new UserApiErrorResponse(
                Instant.now(),
                status.value(),
                error,
                message,
                path,
                details
        ));
    }
}
