package com.shortener.exception;

import com.shortener.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(UrlNotFoundException.class)
    ResponseEntity<ErrorResponse> handleNotFound(
            UrlNotFoundException exception,
            HttpServletRequest request
    ) {
        return response(HttpStatus.NOT_FOUND, "URL_NOT_FOUND", exception.getMessage(), request, Map.of());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> handleUnreadableBody(
            HttpMessageNotReadableException exception,
            HttpServletRequest request
    ) {
        return response(
                HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST",
                "A valid JSON request body is required",
                request,
                Map.of()
        );
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ErrorResponse> handleNoResource(
            NoResourceFoundException exception,
            HttpServletRequest request
    ) {
        return response(
                HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND",
                "The requested resource was not found",
                request,
                Map.of()
        );
    }

    @ExceptionHandler(InvalidUrlException.class)
    ResponseEntity<ErrorResponse> handleBadRequest(
            InvalidUrlException exception,
            HttpServletRequest request
    ) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.getMessage(), request, Map.of());
    }

    @ExceptionHandler(ShortCodeAlreadyExistsException.class)
    ResponseEntity<ErrorResponse> handleShortCodeConflict(
            ShortCodeAlreadyExistsException exception,
            HttpServletRequest request
    ) {
        return response(
                HttpStatus.CONFLICT,
                "SHORT_CODE_ALREADY_EXISTS",
                exception.getMessage(),
                request,
                Map.of()
        );
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ErrorResponse> handleUnsupportedMediaType(
            HttpMediaTypeNotSupportedException exception,
            HttpServletRequest request
    ) {
        return response(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "UNSUPPORTED_MEDIA_TYPE",
                "Content-Type application/json is required",
                request,
                Map.of()
        );
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    ResponseEntity<ErrorResponse> handleUnacceptableMediaType(
            HttpMediaTypeNotAcceptableException exception,
            HttpServletRequest request
    ) {
        return response(
                HttpStatus.NOT_ACCEPTABLE,
                "NOT_ACCEPTABLE",
                "The requested response media type is not supported",
                request,
                Map.of()
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> handleValidation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request
    ) {
        Map<String, String> details = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors()
                .forEach(error -> details.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return response(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "Request validation failed",
                request,
                details
        );
    }

    @ExceptionHandler(ShortCodeGenerationException.class)
    ResponseEntity<ErrorResponse> handleGenerationFailure(
            ShortCodeGenerationException exception,
            HttpServletRequest request
    ) {
        LOGGER.error("Short-code generation failed", exception);
        return response(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "SHORT_CODE_GENERATION_FAILED",
                "Unable to create a short URL",
                request,
                Map.of()
        );
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> handleUnexpected(
            Exception exception,
            HttpServletRequest request
    ) {
        LOGGER.error("Unexpected request failure", exception);
        return response(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "An unexpected error occurred",
                request,
                Map.of()
        );
    }

    private ResponseEntity<ErrorResponse> response(
            HttpStatus status,
            String errorCode,
            String message,
            HttpServletRequest request,
            Map<String, String> details
    ) {
        return ResponseEntity.status(status).body(new ErrorResponse(
                Instant.now(),
                status.value(),
                errorCode,
                message,
                request.getRequestURI(),
                details
        ));
    }
}
