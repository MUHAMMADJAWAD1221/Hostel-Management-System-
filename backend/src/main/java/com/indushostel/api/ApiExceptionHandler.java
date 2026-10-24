package com.indushostel.api;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String,String>> badRequest(IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("message", safe(e.getMessage(), "Invalid request"))); }
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Map<String,String>> forbidden(AccessDeniedException e) { return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "You do not have permission to perform this action")); }
    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<Map<String,String>> unauthorized(AuthenticationException e) { return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Email or password is incorrect")); }
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String,String>> responseStatus(ResponseStatusException e) { return ResponseEntity.status(e.getStatusCode()).body(Map.of("message", safe(e.getReason(), "Request failed"))); }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String,String>> invalid(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream().findFirst().map(err -> err.getField()+" "+err.getDefaultMessage()).orElse("Invalid request");
        return ResponseEntity.badRequest().body(Map.of("message", message));
    }
    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<Map<String,String>> conflict(DuplicateKeyException e) { return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "That identifier is already in use")); }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Map<String,String>> integrityConflict(DataIntegrityViolationException e) { return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "This change conflicts with existing records")); }
    @ExceptionHandler(EmptyResultDataAccessException.class)
    ResponseEntity<Map<String,String>> notFound(EmptyResultDataAccessException e) { return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "The requested record was not found")); }
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<Map<String,String>> oversized(Exception e) { return ResponseEntity.badRequest().body(Map.of("message", "The uploaded file is too large")); }
    private String safe(String message, String fallback) { return message == null || message.isBlank() ? fallback : message; }
}
