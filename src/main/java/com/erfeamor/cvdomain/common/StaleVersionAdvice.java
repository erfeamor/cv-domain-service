package com.erfeamor.cvdomain.common;

import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps {@link StaleVersionException} to rule 8's {@code 409}.
 *
 * <p>Global on purpose, and safe to be: it claims only this application's own exception, which
 * is thrown only by the update paths of the four versioned resources. It does <em>not</em>
 * claim Spring's {@code ObjectOptimisticLockingFailureException}; that one is ambiguous (a row
 * deleted under the write is a 404, not a 409) and is resolved per controller.
 */
@RestControllerAdvice
public class StaleVersionAdvice {

    @ExceptionHandler(StaleVersionException.class)
    public ResponseEntity<ProblemDetail> handleStaleVersion(StaleVersionException ex) {
        return StaleVersionException.response();
    }
}
