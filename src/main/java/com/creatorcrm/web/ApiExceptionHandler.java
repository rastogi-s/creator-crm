package com.creatorcrm.web;

import com.creatorcrm.llm.LlmException;
import com.creatorcrm.security.SecretStore.MissingCredentialException;
import com.creatorcrm.workflow.OutreachService.DuplicatePitchException;
import java.util.Map;
import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps exceptions to short JSON errors. Internals (stack traces, SQL) never reach the browser. */
@RestControllerAdvice(basePackages = "com.creatorcrm.web")
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(DuplicatePitchException.class)
    ResponseEntity<Map<String, Object>> duplicate(DuplicatePitchException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage(), "duplicate", e.duplicate));
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
    ResponseEntity<Map<String, String>> badRequest(Exception e) {
        String msg = e instanceof MethodArgumentNotValidException v && v.getBindingResult().getFieldError() != null
                ? v.getBindingResult().getFieldError().getField() + " " + v.getBindingResult().getFieldError().getDefaultMessage()
                : e.getMessage();
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }

    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<Map<String, String>> notFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Not found"));
    }

    @ExceptionHandler(MissingCredentialException.class)
    ResponseEntity<Map<String, String>> missing(MissingCredentialException e) {
        return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String, String>> conflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(LlmException.class)
    ResponseEntity<Map<String, String>> llm(LlmException e) {
        log.warn("AI call failed: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", e.getMessage()));
    }
}
