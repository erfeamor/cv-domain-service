package com.erfeamor.cvdomain.common;

import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/**
 * A {@code PUT} refused under contract design rule 8 (T-113): the row is no longer at the version
 * the client read. Answered {@code 409} with a {@link ProblemDetail} body by
 * {@link StaleVersionAdvice}.
 *
 * <p>Two paths lead here. The usual one is {@link #requireCurrent}: the client sent a
 * {@code version} that differs from the stored one. That comparison has to be explicit, because
 * Hibernate's own {@code @Version} check compares the row against the version <em>this
 * transaction</em> read, never against the client's: a client that read version 0 and writes
 * after someone else's committed update would otherwise pass. The other path is a genuine race
 * between two in-flight transactions, surfaced by Hibernate's conditional UPDATE and resolved by
 * each controller's {@link ConcurrentUpdateException} handler.
 */
public class StaleVersionException extends RuntimeException {

    private static final String DETAIL =
            "The resource was modified since the supplied version; reload it and retry";

    public StaleVersionException() {
        super(DETAIL);
    }

    /**
     * Rule 8's comparison. A {@code null} {@code sent} is a client that omitted {@code version}:
     * the update applies unconditionally (last write wins, the v1 behaviour).
     *
     * @param sent the version bound from the request body, or {@code null} if omitted
     * @param stored the version of the row as read by this transaction
     * @throws StaleVersionException if {@code sent} is present and differs from {@code stored}
     */
    public static void requireCurrent(Long sent, Long stored) {
        if (sent != null && !Objects.equals(sent, stored)) {
            throw new StaleVersionException();
        }
    }

    /** The 409 response: Spring's {@code application/problem+json} body, status 409. */
    public static ResponseEntity<ProblemDetail> response() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, DETAIL));
    }
}
